package com.hitenhance.render;

import com.hitenhance.HitRegEnhancer;
import com.hitenhance.network.KeepAliveOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Ping / TPS HUD。
 *
 * Ping: 从 KeepAliveOptimizer 获取 RTT 估算值。
 * TPS:  通过 Netty pipeline 拦截 S03PacketTimeUpdate 计算。
 *
 * 屏幕左上角显示：
 *   Ping: 45ms
 *   TPS:  19.8
 */
@SideOnly(Side.CLIENT)
public class PingTpsHud {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── TPS 计算 ──
    private static long lastTimeUpdatePacket = 0;
    private static double smoothedTps = 20.0;
    private static int tpsSampleCount = 0;

    /** 由 Netty handler 调用，记录收到 S03PacketTimeUpdate 的时间 */
    public static void onTimeUpdatePacket() {
        long now = System.currentTimeMillis();

        if (lastTimeUpdatePacket > 0) {
            long delta = now - lastTimeUpdatePacket;
            // 服务器通常每 20~40 tick 发送一次 TimeUpdate。
            // 如果 delta 在合理范围内（500ms ~ 5000ms）
            if (delta > 200 && delta < 10000) {
                // 期望每 20 tick 一次 ≈ 1000ms
                // TPS = (期望 tick 数) / (实际时间/50ms)
                // 假设服务器每 20 tick 发一次
                double expectedMs = 1000.0; // 20 ticks
                double tps = 20.0 * (expectedMs / delta);
                tps = Math.min(20.0, Math.max(0.0, tps));

                // 平滑
                if (tpsSampleCount < 10) {
                    smoothedTps = (smoothedTps * tpsSampleCount + tps) / (tpsSampleCount + 1);
                    tpsSampleCount++;
                } else {
                    smoothedTps = smoothedTps * 0.8 + tps * 0.2;
                }
            }
        }

        lastTimeUpdatePacket = now;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.pingTpsHudEnabled) return;
        if (mc.thePlayer == null) return;

        ScaledResolution sr = new ScaledResolution(mc);
        FontRenderer fr = mc.fontRendererObj;

        // Ping & TPS 文字
        long ping = KeepAliveOptimizer.getCurrentPing();

        String pingColor = "§a";
        if (ping > 100) pingColor = "§e";
        if (ping > 200) pingColor = "§c";

        String pingStr = pingColor + "Ping: " + (ping > 0 ? ping + "ms" : "§7---");

        // TPS
        String tpsColor = "§a";
        if (smoothedTps < 19.0) tpsColor = "§e";
        if (smoothedTps < 17.0) tpsColor = "§c";

        String tpsStr = tpsColor + String.format("TPS: %.1f", smoothedTps);

        // ── 右上角对齐 ──
        int rightMargin = HitRegEnhancer.config.pingTpsHudX;
        int y           = HitRegEnhancer.config.pingTpsHudY;

        // Background
        int textWidth = Math.max(fr.getStringWidth(pingStr), fr.getStringWidth(tpsStr));
        int x = sr.getScaledWidth() - textWidth - rightMargin - 2;
        int bgColor = 0x66000000;

        Gui.drawRect(x - 2, y - 2, x + textWidth + 2, y + fr.FONT_HEIGHT * 2 + 2, bgColor);

        // 绘制文字（带阴影）
        fr.drawString(pingStr, x, y, 0xFFFFFF);
        fr.drawString(tpsStr, x, y + fr.FONT_HEIGHT, 0xFFFFFF);
    }
}
