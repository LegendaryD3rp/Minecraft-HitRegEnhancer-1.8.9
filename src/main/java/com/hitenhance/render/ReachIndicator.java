package com.hitenhance.render;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 攻击范围指示器。
 *
 * 当准星对准实体时，在屏幕下方显示距离：
 *   [目标名]  4.2m ✓
 *
 * ✓ = 在攻击范围内（≤ 4.5 格）
 * ✗ = 超出范围
 */
@SideOnly(Side.CLIENT)
public class ReachIndicator {

    private static final Minecraft mc = Minecraft.getMinecraft();

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.reachIndicatorEnabled) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // 检查是否有实体目标
        if (mc.objectMouseOver == null
            || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
            || mc.objectMouseOver.entityHit == null) {
            return;
        }

        Entity target = mc.objectMouseOver.entityHit;
        if (!(target instanceof EntityLivingBase)) return;

        double dist = mc.thePlayer.getDistanceToEntity(target);
        double range = HitRegEnhancer.config.reachIndicatorRange;

        // 名字
        String name = target.getDisplayName().getFormattedText();

        // 距离 + 状态
        String distStr = String.format("%.1f", dist);
        boolean inRange = dist <= range;
        String status = inRange ? "§a✓" : "§c✗";

        // 血量信息（如果是玩家）
        String healthStr = "";
        if (target instanceof net.minecraft.entity.player.EntityPlayer) {
            EntityLivingBase living = (EntityLivingBase) target;
            float hp = living.getHealth();
            float maxHp = living.getMaxHealth();
            int hpPercent = Math.round(hp / maxHp * 100);
            healthStr = " §7[" + hpPercent + "%]";
        }

        String fullText = name + " §7" + distStr + "m " + status + healthStr;

        // 绘制在屏幕下方中间
        ScaledResolution sr = new ScaledResolution(mc);
        FontRenderer fr = mc.fontRendererObj;

        int textWidth = fr.getStringWidth(fullText);
        int screenW = sr.getScaledWidth();
        int x = (screenW - textWidth) / 2;
        int y = sr.getScaledHeight() - 40;

        // 背景
        Gui.drawRect(x - 3, y - 2, x + textWidth + 3, y + fr.FONT_HEIGHT + 2, 0x66000000);

        fr.drawString(fullText, x, y, 0xFFFFFF);
    }
}
