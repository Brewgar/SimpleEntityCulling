package com.example.simpleentityculling.client.command;

import com.example.simpleentityculling.client.SimpleEntityCullingClient;
import com.example.simpleentityculling.client.config.PerformanceProfile;
import com.example.simpleentityculling.client.config.SimpleEntityCullingConfig;
import com.example.simpleentityculling.client.visibility.VisibilityManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * Client-side {@code /sec} command: profile switching, overlay and
 * visualization toggles, config reload. Intended for benchmarking and
 * troubleshooting; all of this is also editable in the JSON config.
 */
public final class SecCommands {

    private SecCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("sec")
                        .then(ClientCommandManager.literal("profile")
                                .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (PerformanceProfile p : PerformanceProfile.values()) {
                                                builder.suggest(p.name());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
                                            PerformanceProfile p = PerformanceProfile.byName(name);
                                            if (!p.name().equalsIgnoreCase(name)) {
                                                ctx.getSource().sendError(Text.literal(
                                                        "Unknown profile '" + name + "'. Options: safe, balanced, aggressive"));
                                                return 0;
                                            }
                                            SimpleEntityCullingClient.config.applyProfile(p.name());
                                            SimpleEntityCullingClient.config.save();
                                            reloadManager();
                                            ctx.getSource().sendFeedback(Text.literal(
                                                    "SimpleEntityCulling profile set to " + p.name()));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("overlay").executes(ctx -> {
                            var cfg = SimpleEntityCullingClient.config;
                            cfg.debugOverlay = !cfg.debugOverlay;
                            cfg.save();
                            ctx.getSource().sendFeedback(Text.literal(
                                    "Debug overlay: " + (cfg.debugOverlay ? "ON" : "OFF")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("visualize").executes(ctx -> {
                            var cfg = SimpleEntityCullingClient.config;
                            cfg.debugVisualization = !cfg.debugVisualization;
                            cfg.save();
                            ctx.getSource().sendFeedback(Text.literal(
                                    "Debug visualization: " + (cfg.debugVisualization ? "ON" : "OFF")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            SimpleEntityCullingClient.config = SimpleEntityCullingConfig.load();
                            reloadManager();
                            ctx.getSource().sendFeedback(Text.literal("SimpleEntityCulling config reloaded."));
                            return 1;
                        }))
        ));
    }

    private static void reloadManager() {
        VisibilityManager mgr = VisibilityManager.get();
        if (mgr != null) {
            mgr.reload(SimpleEntityCullingClient.config);
        }
    }
}
