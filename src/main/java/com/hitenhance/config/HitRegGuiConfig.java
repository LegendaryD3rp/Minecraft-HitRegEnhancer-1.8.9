package com.hitenhance.config;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.DummyConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod 配置 GUI 页面。
 * 可从 Mods 列表 → HitRegEnhancer → Config 进入。
 * 保存后实时更新 {@link HitRegConfig} 中的字段值。
 */
public class HitRegGuiConfig extends GuiConfig {

    public HitRegGuiConfig(GuiScreen parent) {
        super(parent, buildConfigElements(),
              HitRegEnhancer.MODID, false, false,
              "HitRegEnhancer — PvP 延迟优化");
    }

    private static List<IConfigElement> buildConfigElements() {
        List<IConfigElement> list = new ArrayList<>();

        // ── General（展开显示，不加子菜单）──
        list.addAll(new ConfigElement(
                HitRegEnhancer.config.config.getCategory("general"))
                .getChildElements());

        // ── 子模块（折叠式）──
        addSubMenu(list, "cps_buffer",      "CPS 防丢帧");
        addSubMenu(list, "air_swing",       "对空挥动");
        addSubMenu(list, "keepalive",       "KeepAlive 优化");
        addSubMenu(list, "hit_predict",     "命中预测");
        addSubMenu(list, "hud",             "Ping/TPS HUD");
        addSubMenu(list, "reach_indicator", "攻击范围指示器");

        return list;
    }

    /**
     * 将一个配置类别包装为可折叠的子菜单。
     * 空类别不显示（如 attack_packet 已被删除）。
     */
    private static void addSubMenu(List<IConfigElement> list,
                                   String catName, String displayName) {
        ConfigCategory cat = HitRegEnhancer.config.config.getCategory(catName);
        if (cat.isEmpty()) return;

        list.add(new DummyConfigElement.DummyCategoryElement(
                displayName,
                "hitenhance.config." + catName,
                new ConfigElement(cat).getChildElements()));
    }

    /**
     * GUI 关闭时先保存配置到磁盘，再重载到内存字段。
     * GuiConfig.saveConfigElements() 只更新 Property 内存值，
     * 不写文件，必须手动调 config.save()。
     */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        HitRegEnhancer.config.save();
        HitRegEnhancer.config.reload();
    }
}
