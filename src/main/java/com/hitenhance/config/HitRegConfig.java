package com.hitenhance.config;

import net.minecraftforge.common.config.Configuration;

public class HitRegConfig {

    final Configuration config;

    // ── 主开关 ──
    public boolean enabled = true;

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

    // ── 包优先级队列 ──
    public boolean packetPriorityEnabled = true;

    public HitRegConfig(Configuration config) {
        this.config = config;
        config.load();
        readFieldsFromConfig();
        if (config.hasChanged()) config.save();
    }

    private void readFieldsFromConfig() {
        String cat;

        cat = "general";
        enabled = config.getBoolean("enabled", cat, true, "总开关");
        packetPriorityEnabled = config.getBoolean("packetPriorityEnabled", cat, true,
                "包优先级队列：攻击/移动包优先发送，低优先级包延迟合并");

        cat = "cps_buffer";
        cpsBufferEnabled = config.getBoolean("cpsBufferEnabled", cat, true,
                "左键输入缓冲，防止因 GC/渲染卡顿导致丢刀");
        cpsBufferMaxPerTick = config.getInt("cpsBufferMaxPerTick", cat, 1, 0, 5,
                "每 tick 最多补的点击次数（0=禁用补偿）");

        cat = "air_swing";
        airSwingEnabled = config.getBoolean("airSwingEnabled", cat, true,
                "左键对空时挥动画");

        cat = "keepalive";
        keepAliveBoost = config.getBoolean("keepAliveBoost", cat, true,
                "收到 KeepAlive 包后立即回复，不等 tick 循环");

        cat = "hit_predict";
        localHitPrediction = config.getBoolean("localHitPrediction", cat, true,
                "本地命中预测：攻击时本地先判定是否命中并显示标识");
        hitPredictRange = config.getFloat("hitPredictRange", cat, 4.5F, 1.0F, 6.0F,
                "命中预测的判定范围（方块）");

        cat = "hud";
        pingTpsHudEnabled = config.getBoolean("pingTpsHudEnabled", cat, true,
                "显示 Ping / TPS HUD");
        pingTpsHudX = config.getInt("pingTpsHudX", cat, 2, 0, 1920, "HUD X 坐标");
        pingTpsHudY = config.getInt("pingTpsHudY", cat, 2, 0, 1080, "HUD Y 坐标");

        cat = "reach_indicator";
        reachIndicatorEnabled = config.getBoolean("reachIndicatorEnabled", cat, true,
                "显示目标距离指示器");
        reachIndicatorRange = config.getFloat("reachIndicatorRange", cat, 4.5F, 1.0F, 6.0F,
                "近战可达范围");
    }

    /**
     * 从磁盘重载配置，使 GUI 修改实时生效。
     * 在 GuiConfig.onGuiClosed() 中调用。
     */
    public void reload() {
        config.load();
        readFieldsFromConfig();
        if (config.hasChanged()) config.save();
    }
}
