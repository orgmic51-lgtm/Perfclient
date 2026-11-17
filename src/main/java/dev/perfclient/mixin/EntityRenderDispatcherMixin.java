package dev.perfclient.mixin;

import dev.perfclient.PerfClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void perfclient$cull(Entity entity, Frustum frustum, double x, double y, double z,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (PerfClient.EntityCull.shouldCull(entity, x, y, z)) cir.setReturnValue(false);
    }
}
