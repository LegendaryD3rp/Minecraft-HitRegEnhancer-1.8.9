package com.hitenhance.config;

import com.hitenhance.HitRegEnhancer;
import net.minecraftforge.common.config.Configuration;

/**
 * 配置管理，跟 Compass Mod 一样用单类别 + readFields / save 分离。
 */
public class HitRegConfig {

    final Configuration config;

    // ── 主开关 ──
    public boolean enabled = true;
    public boolean packetPriorityEnabled = true;

    // ── CPS 防丢帧 ──
    public boolean cpsBufferEnabled = true;
    public int cpsBufferMaxPerTick = 1;

    // ── 对空挥动 ──
    public boolean airSwingEnabled = true;

    // ── KeepAlive 即时回复 ──
    public boolean keepAliveBoost = true;

    // ── 本地命中预测 ──
    public boolean localHitPrediction = false;
    public double hitPredictRange = 4.5;

    // ── Ping/TPS HUD ──
    public boolean pingTpsHudEnabled = true;
    public int pingTpsHudX = 4;   // 右边缘间距
    public int pingTpsHudY = 4;   // 顶部间距

    // ── 攻击范围指示器 ──
    public boolean reachIndicatorEnabled = true;
    public double reachIndicatorRange = 4.5;

    public HitRegConfig(Configuration config) {
        this.config = config;
        load();
    }

    /**
     * 从磁盘读入，再读到 Java 字段。
     */
    public void load() {
        config.load();
        readFields();
        if (config.hasChanged()) config.save();
    }

    /**
     * 从内存 Property 读到 Java 字段，然后写盘。
     * GUI 保存时用这个（Compass Mod 做法）。
     */
    public void reload() {
        HitRegEnhancer.logger.info("[HitRegConfig] reload() called");
        // 读之前先看 Property 当前值
        HitRegEnhancer.logger.info("[HitRegConfig]  before: enabled=" + config.get("general", "enabled", true).getString()
            + " cpsBufferEnabled=" + config.get("general", "cpsBufferEnabled", true).getString());
        readFields();
        HitRegEnhancer.logger.info("[HitRegConfig]  after readFields: enabled=" + enabled + " cpsBufferEnabled=" + cpsBufferEnabled);
        config.save();
        HitRegEnhancer.logger.info("[HitRegConfig] config.save() done, file=" + config.toString());
    }

    // ================================================================
    //  以下两个方法跟 Compass Mod 结构一致
    // ================================================================

    /** 从 Property 读到 Java 字段 */
    private void readFields() {
        String cat = Configuration.CATEGORY_GENERAL;

        enabled              = config.getBoolean("enabled",               cat, true,  "总开关");
        packetPriorityEnabled= config.getBoolean("packetPriorityEnabled", cat, true,  "包优先级队列");
        cpsBufferEnabled     = config.getBoolean("cpsBufferEnabled",      cat, true,  "CPS 防丢帧");
        cpsBufferMaxPerTick  = config.getInt   ("cpsBufferMaxPerTick",   cat, 1, 0, 5,"每 tick 补刀数");
        airSwingEnabled      = config.getBoolean("airSwingEnabled",       cat, true,  "对空挥动");
        keepAliveBoost       = config.getBoolean("keepAliveBoost",        cat, true,  "KeepAlive 加速");
        localHitPrediction   = config.getBoolean("localHitPrediction",    cat, false, "本地命中预测（自欺欺人模式）");
        hitPredictRange      = config.getFloat  ("hitPredictRange",      cat, 4.5F, 1, 6, "预测范围");
        pingTpsHudEnabled    = config.getBoolean("pingTpsHudEnabled",     cat, true,  "Ping/TPS HUD");
        pingTpsHudX          = config.getInt    ("pingTpsHudX",           cat, 4, 0, 1920, "距右边缘的间距");
        pingTpsHudY          = config.getInt    ("pingTpsHudY",           cat, 4, 0, 1080, "距顶部边缘的间距");
        reachIndicatorEnabled= config.getBoolean("reachIndicatorEnabled", cat, true,  "范围指示器");
        reachIndicatorRange  = config.getFloat  ("reachIndicatorRange",   cat, 4.5F, 1, 6, "指示器范围");
    }
}
