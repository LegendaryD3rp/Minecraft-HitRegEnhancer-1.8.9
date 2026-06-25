package com.hitenhance.config;

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
    public boolean localHitPrediction = true;
    public double hitPredictRange = 4.5;

    // ── Ping/TPS HUD ──
    public boolean pingTpsHudEnabled = true;
    public int pingTpsHudX = 2;
    public int pingTpsHudY = 2;

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
        readFields();
        config.save();
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
        localHitPrediction   = config.getBoolean("localHitPrediction",    cat, true,  "本地命中预测");
        hitPredictRange      = config.getFloat  ("hitPredictRange",      cat, 4.5F, 1, 6, "预测范围");
        pingTpsHudEnabled    = config.getBoolean("pingTpsHudEnabled",     cat, true,  "Ping/TPS HUD");
        pingTpsHudX          = config.getInt    ("pingTpsHudX",           cat, 2, 0, 1920, "HUD X");
        pingTpsHudY          = config.getInt    ("pingTpsHudY",           cat, 2, 0, 1080, "HUD Y");
        reachIndicatorEnabled= config.getBoolean("reachIndicatorEnabled", cat, true,  "范围指示器");
        reachIndicatorRange  = config.getFloat  ("reachIndicatorRange",   cat, 4.5F, 1, 6, "指示器范围");
    }
}
