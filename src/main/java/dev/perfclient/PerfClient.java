package dev.perfclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PerfClient implements ClientModInitializer {

    public static Config CONFIG;
    public static final List<Module> MODULES = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();
        System.out.println("[PerfClient] Mobile launcher: " + Mobile.is());

        // ---- Register modules here ----
        MODULES.add(new EntityCull());
        MODULES.add(new ParticleLimit());
        MODULES.add(new AutoViewDistance());
        MODULES.add(new FpsHud());
        // -------------------------------

        ClientTickEvents.END_CLIENT_TICK.register(c -> MODULES.forEach(m -> m.onTick(c)));

        try { HudHook.register(); }
        catch (Throwable t) { System.err.println("[PerfClient] HUD hook unavailable, HUD disabled: " + t); }

        try { GuiHooks.registerButtons(); }
        catch (Throwable t) { System.err.println("[PerfClient] Menu button unavailable: " + t); }
    }

    // Isolated so a missing HUD API can't break the rest of the mod
    static final class HudHook {
        static void register() {
            HudRenderCallback.EVENT.register((ctx, tick) -> {
                MinecraftClient c = MinecraftClient.getInstance();
                MODULES.forEach(m -> m.onHud(ctx, c));
            });
        }
    }

    // "PerfClient" button on the title screen and pause menu
    static final class GuiHooks {
        static void registerButtons() {
            ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
                if (screen instanceof GameMenuScreen || screen instanceof TitleScreen) {
                    Screens.getButtons(screen).add(
                        ButtonWidget.builder(Text.literal("PerfClient"),
                                b -> client.setScreen(new PerfScreen(screen)))
                            .dimensions(4, h - 24, 70, 20).build());
                }
            });
        }
    }

    // ================= Module interface =================
    public interface Module {
        default void onTick(MinecraftClient c) {}
        default void onHud(DrawContext ctx, MinecraftClient c) {}
    }

    // ================= Mobile detection =================
    public static final class Mobile {
        private static final boolean MOBILE;
        static {
            boolean m = false;
            try {
                for (String k : System.getenv().keySet()) {
                    String u = k.toUpperCase();
                    if (u.startsWith("POJAV") || u.startsWith("FCL_") || u.startsWith("ZALITH")
                            || u.startsWith("MOD_ANDROID") || u.startsWith("MOBILEGLUES")) { m = true; break; }
                }
                String dir = FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
                if (dir.contains("/Android/data/") || dir.startsWith("/storage/emulated")
                        || dir.startsWith("/data/user/") || dir.startsWith("/data/data/")) m = true;
                String os = System.getProperty("os.name", "").toLowerCase();
                String vendor = System.getProperty("java.vendor", "").toLowerCase();
                if (os.contains("android") || vendor.contains("android")) m = true;
            } catch (Throwable ignored) {}
            MOBILE = m;
        }
        public static boolean is() { return MOBILE; }
    }

    // ================= Config =================
    public static class Config {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("perfclient.json");

        public boolean entityCulling = true;
        public boolean particleLimiter = true;
        public boolean autoViewDistance = true;
        public boolean fpsHud = true;

        public int entityCullDistance = 64;
        public int maxParticlesPerTick = 600;
        public int targetFps = 60;
        public int minViewDistance = 4;
        public int maxViewDistance = 12;

        static Config load() {
            try {
                if (Files.exists(FILE)) return GSON.fromJson(Files.readString(FILE), Config.class);
            } catch (Exception e) {
                System.err.println("[PerfClient] Bad config, using defaults: " + e);
            }
            Config c = fresh();
            c.save();
            return c;
        }

        static Config fresh() {
            Config c = new Config();
            if (Mobile.is()) {
                c.entityCullDistance = 40;
                c.maxParticlesPerTick = 150;
                c.targetFps = 40;
                c.maxViewDistance = 8;
                c.minViewDistance = 3;
            }
            return c;
        }

        void copyFrom(Config o) {
            entityCulling = o.entityCulling;
            particleLimiter = o.particleLimiter;
            autoViewDistance = o.autoViewDistance;
            fpsHud = o.fpsHud;
            entityCullDistance = o.entityCullDistance;
            maxParticlesPerTick = o.maxParticlesPerTick;
            targetFps = o.targetFps;
            minViewDistance = o.minViewDistance;
            maxViewDistance = o.maxViewDistance;
        }

        void save() {
            try { Files.writeString(FILE, GSON.toJson(this)); }
            catch (Exception e) { System.err.println("[PerfClient] Could not save config: " + e); }
        }
    }

    // ================= Module: Entity culling =================
    public static class EntityCull implements Module {
        public static boolean shouldCull(Entity e, double cx, double cy, double cz) {
            if (CONFIG == null || !CONFIG.entityCulling) return false;
            if (e instanceof PlayerEntity || e.hasPassengers() || e.hasVehicle()) return false;
            double max = CONFIG.entityCullDistance;
            double dx = e.getX() - cx, dy = e.getY() - cy, dz = e.getZ() - cz;
            return dx * dx + dy * dy + dz * dz > max * max;
        }
    }

    // ================= Module: Particle limiter =================
    public static class ParticleLimit implements Module {
        private static int spawned = 0;
        @Override public void onTick(MinecraftClient c) { spawned = 0; }

        public static boolean allow() {
            if (CONFIG == null || !CONFIG.particleLimiter) return true;
            return ++spawned <= CONFIG.maxParticlesPerTick;
        }
    }

    // ================= Module: Auto view distance =================
    public static class AutoViewDistance implements Module {
        private int ticks = 0, cooldown = 0;

        @Override
        public void onTick(MinecraftClient c) {
            if (!CONFIG.autoViewDistance || c.world == null || c.player == null) return;
            if (++ticks < 100) return;
            ticks = 0;
            if (cooldown > 0) { cooldown--; return; }

            int fps = c.getCurrentFps();
            int vd = c.options.getViewDistance().getValue();
            if (fps < CONFIG.targetFps - 10 && vd > CONFIG.minViewDistance) {
                c.options.getViewDistance().setValue(vd - 1);
                cooldown = 2;
            } else if (fps > CONFIG.targetFps + 30 && vd < CONFIG.maxViewDistance) {
                c.options.getViewDistance().setValue(vd + 1);
                cooldown = 4;
            }
        }
    }

    // ================= Module: FPS HUD =================
    // Uses TextWidget because DrawContext.drawText changed return type in newer versions
    public static class FpsHud implements Module {
        private TextWidget w;

        @Override
        public void onHud(DrawContext ctx, MinecraftClient c) {
            if (!CONFIG.fpsHud || c.options.hudHidden) return;
            if (w == null) w = new TextWidget(Text.literal(""), c.textRenderer);
            Text t = Text.literal(c.getCurrentFps() + " FPS");
            w.setMessage(t);
            w.setWidth(c.textRenderer.getWidth(t));
            w.setPosition(4, 4);
            w.render(ctx, 0, 0, 0f);
        }
    }

    // ================= Settings GUI =================
    public static class PerfScreen extends Screen {
        private final Screen parent;
        private int left, top, pw, panelH, rowH;

        public PerfScreen(Screen parent) {
            super(Text.literal("PerfClient"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            Config c = CONFIG;
            boolean small = height < 200;
            rowH = small ? 18 : 20;
            int gap = small ? 2 : 5;
            int header = small ? 22 : 28;
            int rows = 5;

            pw = Math.min(280, width - 8);
            panelH = header + rows * (rowH + gap) + rowH + 12;
            left = (width - pw) / 2;
            top = Math.max(2, (height - panelH) / 2);

            int pad = 8;
            int inner = pw - pad * 2;
            int half = (inner - 4) / 2;
            int x1 = left + pad;
            int x2 = x1 + half + 4;
            int y = top + header;
            int step = rowH + gap;

            TextWidget title = new TextWidget(Text.literal("PerfClient").formatted(Formatting.BOLD), textRenderer);
            title.setPosition(left + 8, top + 8);
            addDrawableChild(title);

            String mode = Mobile.is() ? "Mobile mode" : "Desktop mode";
            TextWidget modeW = new TextWidget(Text.literal(mode).formatted(Formatting.GRAY), textRenderer);
            modeW.setPosition(left + pw - 8 - textRenderer.getWidth(mode), top + 8);
            addDrawableChild(modeW);

            toggle(x1, y, half, "Entity Culling", "Hide entities far from you",
                    () -> c.entityCulling, v -> c.entityCulling = v);
            addDrawableChild(new IntSlider(x2, y, inner - half - 4, rowH, "Distance", 16, 128,
                    c.entityCullDistance, v -> c.entityCullDistance = v));
            y += step;

            toggle(x1, y, half, "Particle Limit", "Cap particles spawned per tick",
                    () -> c.particleLimiter, v -> c.particleLimiter = v);
            addDrawableChild(new IntSlider(x2, y, inner - half - 4, rowH, "Max/tick", 20, 1000,
                    c.maxParticlesPerTick, v -> c.maxParticlesPerTick = v));
            y += step;

            toggle(x1, y, half, "Auto Render Dist", "Adjusts render distance to hold your FPS target",
                    () -> c.autoViewDistance, v -> c.autoViewDistance = v);
            addDrawableChild(new IntSlider(x2, y, inner - half - 4, rowH, "Target FPS", 20, 120,
                    c.targetFps, v -> c.targetFps = v));
            y += step;

            addDrawableChild(new IntSlider(x1, y, half, rowH, "Min chunks", 2, 16,
                    c.minViewDistance, v -> c.minViewDistance = v));
            addDrawableChild(new IntSlider(x2, y, inner - half - 4, rowH, "Max chunks", 2, 32,
                    c.maxViewDistance, v -> c.maxViewDistance = v));
            y += step;

            toggle(x1, y, inner, "FPS Counter", "Show FPS in the top-left corner",
                    () -> c.fpsHud, v -> c.fpsHud = v);
            y += step;

            addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> {
                CONFIG.copyFrom(Config.fresh());
                client.setScreen(new PerfScreen(parent));
            }).dimensions(x1, y + 2, half, rowH)
              .tooltip(Tooltip.of(Text.literal("Restore defaults"))).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                    .dimensions(x2, y + 2, inner - half - 4, rowH).build());
        }

        private void toggle(int x, int y, int w, String name, String tip,
                            BooleanSupplier get, Consumer<Boolean> set) {
            addDrawableChild(ButtonWidget.builder(label(name, get.getAsBoolean()), b -> {
                set.accept(!get.getAsBoolean());
                b.setMessage(label(name, get.getAsBoolean()));
            }).dimensions(x, y, w, rowH).tooltip(Tooltip.of(Text.literal(tip))).build());
        }

        private static Text label(String name, boolean on) {
            return Text.literal(name + ": ")
                    .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
        }

        @Override
        public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
            super.renderBackground(ctx, mx, my, delta);
            ctx.fill(left, top, left + pw, top + panelH, 0xDD0D1117);
            ctx.fill(left, top, left + pw, top + 2, 0xFF3FB950);
        }

        @Override
        public void close() {
            client.setScreen(parent);
        }

        @Override
        public void removed() {
            CONFIG.save();
        }

        private static class IntSlider extends SliderWidget {
            private final String label;
            private final int min, max;
            private final IntConsumer setter;

            IntSlider(int x, int y, int w, int h, String label, int min, int max, int value, IntConsumer setter) {
                super(x, y, w, h, Text.empty(), (Math.max(min, Math.min(max, value)) - min) / (double) (max - min));
                this.label = label;
                this.min = min;
                this.max = max;
                this.setter = setter;
                updateMessage();
            }

            private int get() { return min + (int) Math.round(value * (max - min)); }

            @Override
            protected void updateMessage() {
                if (label == null) return;
                setMessage(Text.literal(label + ": " + get()));
            }

            @Override
            protected void applyValue() { setter.accept(get()); }
        }
    }
}
