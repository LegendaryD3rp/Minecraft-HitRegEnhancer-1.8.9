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

    // ── HTTP 缓存 ──
    public boolean skinCacheEnabled = true;

    // ── 网络节流 ──
    public boolean pingThrottleEnabled = true;
    public boolean statusThrottleEnabled = true;

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
        skinCacheEnabled      = config.getBoolean("skinCacheEnabled",        cat, true, "皮肤 HTTP 缓存（本地持久化，减少重复下载）");
        pingThrottleEnabled   = config.getBoolean("pingThrottleEnabled",     cat, true, "服务器 Ping 节流（多人大厅场景降低网络占用）");
        statusThrottleEnabled = config.getBoolean("statusThrottleEnabled",   cat, true, "Mojang 状态查询节流（减少 status.mojang.com 访问频率）");
    }
}
