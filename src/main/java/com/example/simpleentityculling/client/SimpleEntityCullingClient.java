package com.example.simpleentityculling.client;

import com.example.simpleentityculling.SimpleEntityCulling;
import com.example.simpleentityculling.client.command.SecCommands;
import com.example.simpleentityculling.client.config.SimpleEntityCullingConfig;
import com.example.simpleentityculling.client.debug.DebugOverlay;
import com.example.simpleentityculling.client.debug.DebugViz;
import com.example.simpleentityculling.client.visibility.VisibilityManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;

public class SimpleEntityCullingClient implements ClientModInitializer {
    public static SimpleEntityCullingConfig config;

    @Override
    public void onInitializeClient() {
        config = SimpleEntityCullingConfig.load();

        MinecraftClient client = MinecraftClient.getInstance();
        VisibilityManager.init(config, client);

        // Per-frame camera generation tracking.
        WorldRenderEvents.START.register(context -> {
            VisibilityManager mgr = VisibilityManager.get();
            if (mgr != null) {
                Camera camera = context.camera();
                if (camera != null) {
                    mgr.beginFrame(camera);
                }
            }
        });

        ClientTickEvents.START_CLIENT_TICK.register(c -> {
            VisibilityManager mgr = VisibilityManager.get();
            if (mgr != null) {
                mgr.startTick();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            VisibilityManager mgr = VisibilityManager.get();
            if (mgr != null) {
                mgr.endTick(c.world);
            }
        });

        // Dimension change / disconnect / world reload -> drop stale state.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, c) -> {
            VisibilityManager mgr = VisibilityManager.get();
            if (mgr != null) {
                mgr.reset();
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, c) -> {
            VisibilityManager mgr = VisibilityManager.get();
            if (mgr != null) {
                mgr.reset();
            }
        });

        HudRenderCallback.EVENT.register(DebugOverlay::render);
        WorldRenderEvents.AFTER_ENTITIES.register(DebugViz::afterEntities);

        SecCommands.register();

        SimpleEntityCulling.LOGGER.info("[SimpleEntityCulling] client initialized (profile: {}, async: {})",
                config.profile(), config.asyncCulling);
    }
}
