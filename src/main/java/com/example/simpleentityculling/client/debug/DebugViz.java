package com.example.simpleentityculling.client.debug;

import com.example.simpleentityculling.client.SimpleEntityCullingClient;
import com.example.simpleentityculling.client.visibility.VisibilityManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;

/**
 * Optional wireframe visualization of per-entity visibility decisions.
 * Disabled by default and capped so it can never dominate frame time.
 */
public final class DebugViz {
    private static final int MAX_BOXES = 512;

    // state -> RGBA
    private static final float[][] COLORS = {
            {0.6f, 0.6f, 0.7f, 0.7f},   // UNKNOWN - gray
            {0.2f, 1.0f, 0.2f, 0.8f},   // VISIBLE - green
            {1.0f, 1.0f, 0.2f, 0.8f},   // PROBABLY_VISIBLE - yellow
            {1.0f, 0.6f, 0.1f, 0.8f},   // PROBABLY_HIDDEN - orange
            {1.0f, 0.15f, 0.15f, 0.9f}, // HIDDEN - red
    };

    private DebugViz() {
    }

    public static void afterEntities(WorldRenderContext context) {
        if (!SimpleEntityCullingClient.config.debugVisualization) {
            return;
        }
        try {
            draw(context);
        } catch (Throwable ignored) {
            // Visualization must never break rendering.
        }
    }

    private static void draw(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        VisibilityManager mgr = VisibilityManager.get();
        if (world == null || mgr == null || context.consumers() == null) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }

        VertexConsumer vc = context.consumers().getBuffer(RenderLayer.getLines());

        int drawn = 0;
        for (Entity e : world.getEntities()) {
            if (drawn >= MAX_BOXES) {
                break;
            }
            byte state = mgr.debugStateFor(e.getId(), e);
            if (state < 0) {
                continue; // not tracked this tick
            }
            float[] c = COLORS[Math.min(COLORS.length - 1, state)];
            double distSq = e.squaredDistanceTo(context.camera().getPos());
            if (distSq > 64 * 64) {
                continue;
            }
            Box box = e.getBoundingBox().expand(SimpleEntityCullingClient.config.boundingBoxMargin);
            WorldRenderer.drawBox(matrices, vc, box, c[0], c[1], c[2], c[3]);
            drawn++;
        }
    }
}
