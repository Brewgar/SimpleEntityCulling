package com.example.simpleentityculling.client.render;

import com.example.simpleentityculling.SimpleEntityCulling;
import com.example.simpleentityculling.client.visibility.VisibilityManager;
import net.minecraft.entity.Entity;

/**
 * Tiny static bridge between the mixin and the visibility manager. Keeps the
 * mixin free of logic and guarantees fail-open behavior: any throwable in the
 * optimization path results in "render normally".
 */
public final class RenderHooks {
    private static int errorsLogged;

    private RenderHooks() {
    }

    /** Cheap counter executed for every entity the dispatcher considers. */
    public static void onVanillaCheck() {
        VisibilityManager m = VisibilityManager.get();
        if (m != null) {
            m.stats.consideredTotal++;
        }
    }

    /**
     * @return true when SimpleEntityCulling has strong evidence that the
     *         entity cannot be seen and its renderer should be skipped.
     */
    public static boolean shouldSkipRender(Entity entity) {
        try {
            VisibilityManager m = VisibilityManager.get();
            return m != null && !m.shouldRender(entity);
        } catch (Throwable t) {
            if (errorsLogged++ < 5) {
                SimpleEntityCulling.LOGGER.error(
                        "[SimpleEntityCulling] render decision failed; rendering normally", t);
            }
            return false;
        }
    }
}
