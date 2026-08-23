package com.example.simpleentityculling.client.debug;

import com.example.simpleentityculling.client.SimpleEntityCullingClient;
import com.example.simpleentityculling.client.visibility.VisibilityManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Optional HUD text overlay (disabled by default). Reads only counters; it
 * never participates in the render decision.
 */
public final class DebugOverlay {
    private static final StringBuilder SB = new StringBuilder(128);

    private DebugOverlay() {
    }

    public static void render(DrawContext context, float tickDelta) {
        VisibilityManager mgr = VisibilityManager.get();
        if (mgr == null || !SimpleEntityCullingClient.config.debugOverlay) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.world == null) {
            return;
        }

        var s = mgr.stats;
        var cfg = SimpleEntityCullingClient.config;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.scale(0.85f, 0.85f, 0.85f);

        int y = 4;
        int color = 0xFFE0E0E0;
        int accent = 0xFF7FDB7F;

        y = 4;
        y = text(context, new StringBuilder("SimpleEntityCulling [").append(cfg.profile().name())
                .append("] gen=").append(0), y, 0xFF9FD4FF);
        y = kv(context, "Entities considered: ", s.consideredTotal, y);
        y = kv(context, "Candidates (vanilla-rendered): ", s.candidates, y);
        y = kv(context, "Rendered: ", s.rendered, y);
        y = kv(context, "Occlusion culled: ", s.occlusionCulled + s.cellCulled, y);
        y = kv(context, "  via cells: ", s.cellCulled, y);
        y = kv(context, "  grace window: ", s.graceHidden, y);
        y = kv(context, "Forced visible: ", s.forcedRendered, y);
        y = kv(context, "Close-range skip: ", s.closeSkipped, y);
        y = kv(context, "Beyond range skip: ", s.rangeSkipped, y);

        y = kv(context, "Cache hits/tick: ", s.cacheHits, y);
        SB.setLength(0);
        SB.append("Cache entries: ").append(s.cacheEntries)
                .append("  hit rate: ").append(String.format("%.1f", s.hitRatePercent())).append('%');
        y = text(context, SB, y, color);

        y = kv(context, "Tests requested/tick: ", s.testsRequested, y);
        y = kv(context, "Tests dropped/tick: ", s.testsDropped, y);
        SB.setLength(0);
        SB.append("Queue: ").append(s.queueSize)
                .append("  results pending: ").append(s.resultsPending)
                .append("  avg test: ").append(String.format("%.1f", s.avgTestMicros)).append(" us");
        y = text(context, SB, y, color);

        y = kv(context, "Dense cells: ", s.denseCells, y);
        SB.setLength(0);
        SB.append("Async worker: ").append(mgr.engine().isAsync() ? "on" : "off");
        y = text(context, SB, y, cfg.asyncCulling ? accent : 0xFFFFB266);

        matrices.pop();
    }

    private static int line(DrawContext ctx, StringBuilder sb, String... parts) {
        sb.setLength(0);
        for (String p : parts) {
            sb.append(p);
        }
        return text(ctx, sb, 0, 0xFF9FD4FF) + 2;
    }

    private static int kv(DrawContext ctx, String key, long value, int y) {
        SB.setLength(0);
        SB.append(key).append(value);
        return text(ctx, SB, y, 0xFFE0E0E0);
    }

    private static int text(DrawContext ctx, StringBuilder sb, int y, int color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (y == 0) {
            y = 4;
        }
        ctx.drawTextWithShadow(client.textRenderer, sb.toString(), 5, y, color);
        return y + 10;
    }
}
