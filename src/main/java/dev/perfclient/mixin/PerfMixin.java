package dev.perfclient.mixin;

import dev.perfclient.PerfClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ParticleManager.class, EntityRenderDispatcher.class})
public class PerfMixin {

    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void perfclient$limit(Particle particle, CallbackInfo ci) {
        if (!PerfClient.ParticleLimit.allow()) ci.cancel();
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private <E extends Entity> void perfclient$cull(E entity, Frustum frustum, double x, double y, double z,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (PerfClient.EntityCull.shouldCull(entity, x, y, z)) cir.setReturnValue(false);
    }
}
