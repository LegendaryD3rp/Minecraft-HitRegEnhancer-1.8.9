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
 * CPS 防丢帧。
 *
 * 当 GC/渲染卡顿导致 tick 被跳过时，鼠标「按下-释放」可能被吞掉。
 * 本 handler 检测左键状态变化，如果上一 tick 左键是按下状态
 * 且未被正常处理（ObjectMouseOver 状态异常），在本 tick 补偿一次攻击。
 *
 * 限制：每 tick 最多补偿 1 次，防止变相增加 CPS。
 */
@SideOnly(Side.CLIENT)
public class CpsBufferHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasLeftDown = false;
    private int skippedClicks = 0;

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

        // 上升沿：左键刚按下
        if (leftDown && !wasLeftDown) {
            skippedClicks++;
        }

        // 下降沿：左键刚释放
        if (!leftDown && wasLeftDown) {
            // 正常释放，清空缓冲
            skippedClicks = 0;
        }

        // 如果有积压的点击，且当前没有在攻击
        if (skippedClicks > 0) {
            // 检查是否真的需要补偿
            // 如果 mc.objectMouseOver 显示有实体目标，说明点击已被正常处理
            boolean clickWasProcessed = (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY);

            if (!clickWasProcessed && mc.currentScreen == null) {
                // 补偿：执行一次攻击
                if (mc.objectMouseOver != null
                    && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
                    mc.playerController.attackEntity(mc.thePlayer, mc.objectMouseOver.entityHit);
                    mc.thePlayer.swingItem();
                }
                // 消耗一次补偿
                skippedClicks--;
            } else {
                // 点击已被正常处理，清空缓冲
                skippedClicks = 0;
            }
        }

        wasLeftDown = leftDown;
    }
}
