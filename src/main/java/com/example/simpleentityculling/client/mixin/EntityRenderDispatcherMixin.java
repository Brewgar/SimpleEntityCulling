package com.example.simpleentityculling.client.mixin;

import com.example.simpleentityculling.client.render.RenderHooks;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single, focused hook into the centralized entity render decision.
 *
 * <ul>
 *   <li>HEAD: counts every entity vanilla considers (debug statistics).</li>
 *   <li>RETURN: only entities that vanilla itself would render are offered to
 *       SimpleEntityCulling. Vanilla therefore remains the baseline authority
 *       (frustum culling, rider rules, etc. all happen before us), and the mod
 *       can only ever add rejections - never force renders.</li>
 * </ul>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
            method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
            at = @At("HEAD")
    )
    private <E extends Entity> void simpleEntityCulling$countConsidered(E entity, Frustum frustum,
                                                                        double x, double y, double z,
                                                                        CallbackInfoReturnable<Boolean> cir) {
        RenderHooks.onVanillaCheck();
    }

    @Inject(
            method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
            at = @At("RETURN"),
            cancellable = true
    )
    private <E extends Entity> void simpleEntityCulling$decide(E entity, Frustum frustum,
                                                               double x, double y, double z,
                                                               CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && RenderHooks.shouldSkipRender(entity)) {
            cir.setReturnValue(false);
        }
    }
}
