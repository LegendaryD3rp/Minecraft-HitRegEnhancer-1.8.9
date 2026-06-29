package com.hitenhance.handler;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;

/**
 * leftClickCounter 跳过（实体目标时）。
 *
 * MCP 验证：clickMouse() 第一条就是 if (leftClickCounter > 0) return;
 * 打空气后 leftClickCounter = 10（0.5s），期间任何左键攻击都被吞掉。
 *
 * 本 handler 在 ClientTick 开始时检测：
 *   如果 leftClickCounter > 0 且十字准星对准了实体，
 *   则清零 leftClickCounter，允许 clickMouse() 正常发包（C02 + C0A）。
 *
 * 这是基于 MCP 全链路分析后确认的【唯一可优化方向】。
 * 服务端攻击判定完全独立，客户端发请求的速度已是原版极限。
 */
@SideOnly(Side.CLIENT)
public class LeftClickBypassHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // 反射缓存
    private static Field leftClickCounterField;
    private static boolean reflectOk = false;

    static {
        try {
            leftClickCounterField = Minecraft.class.getDeclaredField("leftClickCounter");
            leftClickCounterField.setAccessible(true);
            reflectOk = true;
        } catch (NoSuchFieldException e) {
            HitRegEnhancer.logger.error("[LeftClickBypass] leftClickCounter field not found: " + e.getMessage());
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.leftClickBypassEnabled) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!reflectOk) return;

        try {
            int leftClickCounter = leftClickCounterField.getInt(mc);
            if (leftClickCounter <= 0) return;

            // 只有在十字准星对准实体时才跳过，不滥清
            if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && mc.objectMouseOver.entityHit != null
                && mc.objectMouseOver.entityHit.isEntityAlive()) {

                leftClickCounterField.setInt(mc, 0);
            }
        } catch (IllegalAccessException e) {
            // 不会发生，setAccessible 已调用
        }
    }
}
