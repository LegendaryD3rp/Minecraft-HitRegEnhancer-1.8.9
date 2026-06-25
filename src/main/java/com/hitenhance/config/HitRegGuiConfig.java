package com.hitenhance.config;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置 GUI — 跟 Compass Mod 一样的用法。
 * 所有元素平铺，不嵌套子菜单。
 */
public class HitRegGuiConfig extends GuiConfig {

    public HitRegGuiConfig(GuiScreen parent) {
        super(parent, getConfigElements(),
              HitRegEnhancer.MODID, false, false,
              "HitRegEnhancer — PvP 延迟优化");
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> list = new ArrayList<>();
        Configuration cfg = HitRegEnhancer.config.config;
        String cat = Configuration.CATEGORY_GENERAL;

        // 从 Configuration 里取每个 Property 包成 ConfigElement
        // 这些 Property 和 readFields() 里用的是同一批对象
        String[] keys = {
            "enabled", "packetPriorityEnabled",
            "cpsBufferEnabled", "cpsBufferMaxPerTick",
            "airSwingEnabled",
            "keepAliveBoost"
        };
        for (String key : keys) {
            list.add(new ConfigElement(cfg.getCategory(cat).get(key)));
        }

        return list;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        HitRegEnhancer.config.reload();
    }
}
