package com.hitenhance.config;

import com.hitenhance.HitRegEnhancer;
import net.minecraftforge.common.config.Configuration;

/**
 * 配置管理。
 */
public class HitRegConfig {

    final Configuration config;

    // ── 总开关 ──
    public boolean enabled = true;

    // ── leftClickCounter 跳过 ──
    public boolean leftClickBypassEnabled = true;

    // ── CPS 防丢帧 ──
    public boolean cpsBufferEnabled = true;

    public HitRegConfig(Configuration config) {
        this.config = config;
        load();
    }

    public void load() {
        config.load();
        readFields();
        if (config.hasChanged()) config.save();
    }

    public void reload() {
        readFields();
        config.save();
    }

    private void readFields() {
        String cat = Configuration.CATEGORY_GENERAL;

        enabled               = config.getBoolean("enabled",                 cat, true, "总开关");
        leftClickBypassEnabled= config.getBoolean("leftClickBypassEnabled",  cat, true, "跳过打空气冷却（leftClickCounter）");
        cpsBufferEnabled      = config.getBoolean("cpsBufferEnabled",        cat, true, "CPS 防丢帧+限流");
    }
}
