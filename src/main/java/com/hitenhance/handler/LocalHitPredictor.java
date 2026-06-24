package com.hitenhance.handler;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

/**
 * 本地命中预测。
 *
 * 左击瞬间本地先判定目标是否在近战范围内。
 * 如果在范围内，立即触发命中标识（声音/粒子/标记），
 * 不等服务端确认（约 0~100ms 提前）。
 *
 * 实现：通过 ClientTickEvent 监听左击上升沿，
 * 检查 objectMouseOver.entityHit 的距离。
 * 如果 ≤ 配置范围，触发本地效果。
 */
@SideOnly(Side.CLIENT)
public class LocalHitPredictor {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasLeftDown = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.localHitPrediction) {
            wasLeftDown = Mouse.isButtonDown(0);
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            wasLeftDown = Mouse.isButtonDown(0);
            return;
        }

        boolean leftDown = Mouse.isButtonDown(0);

        if (leftDown && !wasLeftDown) {
            // 左键刚按下 — 检查是否有实体目标
            if (mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit != null
                && mc.objectMouseOver.typeOfHit == net.minecraft.util.MovingObjectPosition.MovingObjectType.ENTITY) {

                Entity target = mc.objectMouseOver.entityHit;
                double dist = mc.thePlayer.getDistanceToEntity(target);

                // 判断距离（剑 ≈ 4.5 格，斧 ≈ 5.0 格，默认 4.5）
                double range = HitRegEnhancer.config.hitPredictRange;
                if (dist <= range && target instanceof EntityLivingBase) {
                    // ── 本地命中效果 ──
                    // 1. 播放击中音效（本地）
                    mc.theWorld.playSound(
                        target.posX, target.posY, target.posZ,
                        "game.player.hurt", 1.0F, 1.0F, false
                    );

                    // 2. 命中粒子（红心粒子）
                    for (int i = 0; i < 4; i++) {
                        mc.theWorld.spawnParticle(
                            net.minecraft.util.EnumParticleTypes.CRIT,
                            target.posX + (Math.random() - 0.5) * 0.6,
                            target.posY + target.getEyeHeight() * 0.5 + (Math.random() - 0.5) * 0.4,
                            target.posZ + (Math.random() - 0.5) * 0.6,
                            0, 0, 0
                        );
                    }

                    // 3. 震动效果（屏幕震动 - 如果有配置）
                    // 4. 命中标记（如果安装了 Hit Marker Mod，它会在服务端确认时触发，
                    //    这里只做本地补强，不冲突）
                }
            }
        }

        wasLeftDown = leftDown;
    }
}
