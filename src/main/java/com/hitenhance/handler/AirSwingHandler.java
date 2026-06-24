package com.hitenhance.handler;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

/**
 * 对空挥动。
 *
 * 原版左击对空时没有任何反馈（swingItem 只在有实体目标时调用）。
 * 本 handler 在左击对空/对空气时也调用 swingItem，让动画不丢帧。
 */
@SideOnly(Side.CLIENT)
public class AirSwingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasLeftDown = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.airSwingEnabled) {
            wasLeftDown = Mouse.isButtonDown(0);
            return;
        }
        if (mc.thePlayer == null) {
            wasLeftDown = Mouse.isButtonDown(0);
            return;
        }

        boolean leftDown = Mouse.isButtonDown(0);

        if (leftDown && !wasLeftDown) {
            // 左键刚按下
            // 如果没有实体/方块目标，或者目标是 MISS（打空气）
            if (mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit == net.minecraft.util.MovingObjectPosition.MovingObjectType.MISS) {
                mc.thePlayer.swingItem();
            }
        }

        wasLeftDown = leftDown;
    }
}
