package com.hitenhance;

import com.hitenhance.config.HitRegConfig;
import com.hitenhance.handler.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = HitRegEnhancer.MODID, version = HitRegEnhancer.VERSION,
     clientSideOnly = true,
     guiFactory = "com.hitenhance.config.HitRegGuiFactory")
public class HitRegEnhancer {

    public static final String MODID = "hitenhance";
    public static final String VERSION = "1.0.0";

    public static Logger logger;
    public static HitRegConfig config;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        config = new HitRegConfig(new Configuration(event.getSuggestedConfigurationFile()));
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // ── 配置变更监听 ──
        MinecraftForge.EVENT_BUS.register(new ConfigChangeHandler());

        // ── 输入优化 ──
        MinecraftForge.EVENT_BUS.register(new LeftClickBypassHandler());
        MinecraftForge.EVENT_BUS.register(new CpsBufferHandler());

        logger.info("HitRegEnhancer initialized");
    }

    // ── 配置变更监听器 ──
    public static class ConfigChangeHandler {
        @SubscribeEvent
        public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (!event.modID.equals(MODID)) return;
            config.reload();
            logger.info("Configuration saved and reloaded");
        }
    }
}
