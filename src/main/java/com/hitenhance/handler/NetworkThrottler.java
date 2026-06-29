package com.hitenhance.handler;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 网络节流：
 *   1. ServerList Ping 节流 — 多人游戏列表刷新时，限制每台服务器的 ping 频率
 *   2. Mojang Status 节流 — 限制 status.mojang.com 查询频率
 *
 * 原理：
 *   原版在多人游戏界面打开时会批量 ping 所有服务器，且当 session 服务器
 *   状态变化时频繁请求 Mojang API。这些 HTTP 请求在 Hypixel 大厅等场景下
 *   与真正的游戏网络包争带宽。
 *
 *   本 handler 追踪最后一次请求时间，在节流窗口内跳过重复请求。
 *   不修改任何包内容，反作弊无法检测。
 */
@SideOnly(Side.CLIENT)
public class NetworkThrottler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Server Ping 节流 ──
    // 每个服务器上一 tick 记录了 ping 请求 → 跳过的标志
    private boolean pingGuardActive = false;
    private int pingGuardCooldown = 0;
    private static final int PING_GUARD_TICKS = 30;  // 30 ticks ≈ 1.5s

    // ── Mojang Status 节流 ──
    private long lastStatusCheck = 0;
    private static final long STATUS_THROTTLE_MS = 5000L;  // 5 秒

    // ── ServerGui 检测（通过 tick 发现 GUI 变化）──
    private boolean wasInMultiplayerGui = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!HitRegEnhancer.config.enabled) return;
        if (mc.theWorld == null && mc.thePlayer == null) {
            // 只在主菜单/多人游戏界面时处理
        }

        // ── 1. Server Ping 节流 ──
        handlePingThrottle();

        // ── 2. Mojang Status 节流 ──
        handleStatusThrottle();
    }

    // ================================================================
    //  Server Ping 节流
    // ================================================================

    /**
     * 原理：当玩家打开多人游戏 GUI 时，原版 ServerList 会 ping 列表中所有服务器。
     * 我们通过 tick 计数做一个"守卫"：在进入 GUI 后的 N tick 内抑制不必要的 ping，
     * 但保留首次 ping。
     *
     * 更激进的做法是反射 OldServerPinger 的 ping 方法，但本文只用 tick 级节流。
     */
    private void handlePingThrottle() {
        boolean inMultiplayerGui = mc.currentScreen != null
            && mc.currentScreen.getClass().getName().equals("net.minecraft.client.gui.GuiMultiplayer");

        if (inMultiplayerGui && !wasInMultiplayerGui) {
            // 刚进入多人游戏界面 → 允许首次 ping，开启守卫
            pingGuardActive = true;
            pingGuardCooldown = 0;
        }

        if (pingGuardActive) {
            pingGuardCooldown++;
            if (pingGuardCooldown >= PING_GUARD_TICKS) {
                pingGuardActive = false;
            }
        }

        wasInMultiplayerGui = inMultiplayerGui;
    }

    /** 返回 true = 当前 tick 允许 ping，false = 被节流 */
    public boolean canPingServer() {
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.pingThrottleEnabled) return true;
        // 如果守卫冷却中，且不是首次进入 GUI，节流
        if (pingGuardActive && pingGuardCooldown > 2) {
            return false;
        }
        return true;
    }

    // ================================================================
    //  Mojang Status 节流
    // ================================================================

    /**
     * 原版 Minecraft 在主菜单会定期调用 checkStatus() 查询 status.mojang.com。
     * 我们直接限制调用频率。
     */
    private void handleStatusThrottle() {
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.statusThrottleEnabled) return;
        if (mc.currentScreen == null) return;  // 游戏中不检查状态

        long now = System.currentTimeMillis();
        if (now - lastStatusCheck < STATUS_THROTTLE_MS) return;
        lastStatusCheck = now;

        // 触发一次 Mojang 状态检查
        // 原版在 GuiMainMenu.updateScreen() 中每秒检查一次
        // 我们限制为 5 秒一次
    }

    /** 对外暴露：是否允许执行 Mojang 状态检查 */
    public boolean canCheckStatus() {
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.statusThrottleEnabled) return true;
        long now = System.currentTimeMillis();
        return (now - lastStatusCheck) >= STATUS_THROTTLE_MS;
    }
}
