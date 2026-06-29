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
 * 配置 GUI。
 */
public class HitRegGuiConfig extends GuiConfig {

    public HitRegGuiConfig(GuiScreen parent) {
        super(parent, getConfigElements(),
              HitRegEnhancer.MODID, false, false,
              "HitRegEnhancer — PvP 输入优化");
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> list = new ArrayList<>();
        Configuration cfg = HitRegEnhancer.config.config;
        String cat = Configuration.CATEGORY_GENERAL;

        String[] keys = {
            "enabled",
            "leftClickBypassEnabled",
            "cpsBufferEnabled",
            "skinCacheEnabled",
            "pingThrottleEnabled",
            "statusThrottleEnabled"
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
