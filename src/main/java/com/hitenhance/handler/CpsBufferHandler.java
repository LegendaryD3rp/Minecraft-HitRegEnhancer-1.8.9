package com.hitenhance.handler;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

/**
 * CPS 防丢帧 + C02 滑动窗口限流。
 *
 * ——背景——
 * 当 GC/渲染卡顿导致 tick 被跳过时，鼠标「按下-释放」事件可能被吞掉。
 * 本 handler 检测左键上升沿（按下瞬间），如果准星对准实体且在 reach 范围内，
 * 在后续 tick 补偿一次 attackEntity() + swingItem()。
 *
 * ——补偿的包序（基于 MCP 源码验证）——
 *   1. playerController.attackEntity() → 发 C02PacketUseEntity(target, ATTACK)
 *   2. 显式调用 thePlayer.swingItem() → 发 C0APacketAnimation
 *   与原版 clickMouse() 包序一致：C02 在前，C0A 在后。
 *
 * ——为什么安全——                                          ←
 *   C02 + C0A 是合法的攻击包对，反作弊不会标记。                ←
 *   补偿仅在 mouseDown 且准星对准实体时触发，不会凭空发请求。    ←
 *   原版 1.8.9 服务端无攻击冷却，C02 随到随处理。
 *
 * ——滑动窗口限流——                                          ←
 *   任意 1000ms 窗口内最多 20 次 C02 输出。
 *   防止极短时间 cps 冲高被部分反作弊标记为异常统计。
 */
@SideOnly(Side.CLIENT)
public class CpsBufferHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private boolean wasLeftDown = false;
    private int skippedClicks = 0;

    // 补偿限频：至少间隔 100ms
    private long lastCompensationTime = 0;
    private static final long COMPENSATION_INTERVAL_MS = 100L;

    // ── 滑动窗口限流：任意 1000ms 窗口内最多 20 次 C02 ──
    private final long[] c02Timestamps = new long[20];
    private int c02Index = 0;

    /** @return true = 允许发送，false = 窗口满了，限流 */
    private boolean canSendC02() {
        long now = System.currentTimeMillis();
        int slot = c02Index % 20;
        if (c02Timestamps[slot] > 0 && now - c02Timestamps[slot] < 1000) {
            return false;
        }
        c02Timestamps[slot] = now;
        c02Index++;
        return true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.cpsBufferEnabled) {
            wasLeftDown = Mouse.isButtonDown(0);
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            wasLeftDown = Mouse.isButtonDown(0);
            return;
        }

        boolean leftDown = Mouse.isButtonDown(0);

        // 上升沿：左键刚按下，缓冲 +1
        if (leftDown && !wasLeftDown) {
            skippedClicks++;
        }

        // 下降沿：左键释放，清空缓冲（不残存）
        if (!leftDown && wasLeftDown) {
            skippedClicks = 0;
        }

        // ── 补偿执行 ──
        if (skippedClicks > 0 && mc.currentScreen == null) {

            // 限频 1：补偿间隔 100ms
            long now = System.currentTimeMillis();
            if (now - lastCompensationTime < COMPENSATION_INTERVAL_MS) {
                wasLeftDown = leftDown;
                return;
            }

            // 限频 2：滑动窗口 20 C02/s
            if (!canSendC02()) {
                wasLeftDown = leftDown;
                return;
            }

            if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && mc.objectMouseOver.entityHit != null) {

                Entity target = mc.objectMouseOver.entityHit;

                // 到达距离校验（防补偿穿墙）
                double reach = mc.playerController.getBlockReachDistance();
                if (mc.thePlayer.getDistanceToEntity(target) <= reach) {

                    // 包序：C02 先发，C0A 紧随 —— 与原版 clickMouse() 一致
                    mc.playerController.attackEntity(mc.thePlayer, target);
                    mc.thePlayer.swingItem();

                    lastCompensationTime = now;
                    skippedClicks--;
                }
            } else {
                // 没有实体目标 → 鼠标松开就清空，按着就保留
                if (!leftDown) {
                    skippedClicks = 0;
                }
            }
        }

        wasLeftDown = leftDown;
    }
}
