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
 * CPS 防丢帧 + 滑动窗口限流。
 *
 * 当 GC/渲染卡顿导致 tick 被跳过时，鼠标「按下-释放」可能被吞掉。
 * 本 handler 检测左键上升沿（按下瞬间），如果该次点击未被正常处理，
 * 在后续 tick 补偿一次攻击。
 *
 * 补偿方式：
 *   直接调用 playerController.attackEntity()，内部发 C02 + C0A（与原版包序一致），
 *   不额外追加 swingItem()，避免重复 C0A 触发反作弊 badpacket 检测。
 *
 * 安全防护：
 *   1. 最小补偿间隔 100ms（防连发）
 *   2. 滑动窗口 20 C02/s 限流（防短时 cps 冲高被反作弊标记）
 *   3. 到达距离校验（防穿墙）
 *   4. 鼠标释放后清空缓冲（不残存）
 */
@SideOnly(Side.CLIENT)
public class CpsBufferHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private boolean wasLeftDown = false;
    private int skippedClicks = 0;

    // 补偿限频：至少间隔 100ms，防止补偿连发被反作弊判定为 badpacket
    private long lastCompensationTime = 0;
    private static final long COMPENSATION_INTERVAL_MS = 100L;

    // ── 滑动窗口限流：任何 1000ms 窗口内最多 20 次 C02 ──
    // 循环数组，记录最近 20 次 C02 的发送时间戳
    private final long[] c02Timestamps = new long[20];
    private int c02Index = 0;

    /**
     * 检查并记录一次 C02 发送。
     * @return true = 允许发送，false = 已到上限，不发
     */
    private boolean canSendC02() {
        long now = System.currentTimeMillis();
        int slot = c02Index % 20;
        // 如果最旧的记录还在 1000ms 以内 → 窗口满了，限流
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

        // 下降沿：左键释放，清空缓冲
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

            // 限频 2：滑动窗口 20 C02/s（保护反作弊侧）
            if (!canSendC02()) {
                // 达到 20cps 上限，跳过本次补偿
                // skippedClicks 保留，下次 tick 再试
                wasLeftDown = leftDown;
                return;
            }

            // 当前十字准星对准了实体
            if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && mc.objectMouseOver.entityHit != null) {

                Entity target = mc.objectMouseOver.entityHit;

                // 到达距离校验（防止补偿穿墙或远距离投掷物误判）
                double reach = mc.playerController.getBlockReachDistance();
                if (mc.thePlayer.getDistanceToEntity(target) <= reach) {

                    // attackEntity() 内部发 C02PacketUseEntity + swingItem → C0APacketAnimation
                    // 包序与原版 clickMouse 一致，不追加额外 swingItem()
                    mc.playerController.attackEntity(mc.thePlayer, target);

                    lastCompensationTime = now;
                    skippedClicks--;
                }
            } else {
                // 当前没有实体目标
                // 鼠标还按着：保留缓冲，等下一 tick
                // 鼠标已松开：清空
                if (!leftDown) {
                    skippedClicks = 0;
                }
            }
        }

        wasLeftDown = leftDown;
    }
}
