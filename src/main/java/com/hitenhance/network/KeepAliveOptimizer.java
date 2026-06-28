package com.hitenhance.network;

import com.hitenhance.HitRegEnhancer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * KeepAlive 回复修正（防双重 C00 + 绕过优先级队列）。
 *
 * 在 Netty pipeline 中注入 ka_booster handler，在 packet_handler 之前
 * 拦截 S00PacketKeepAlive，消费该包并即刻在同一事件循环线程上
 * 回复 C00PacketKeepAlive。
 *
 * 核心问题：
 *   PacketPriorityHandler 开启时，C00PacketKeepAlive 从 pipeline tail 出发
 *   会被 priority_queue 拦截，因其不在 HIGH 列表而被当作 MEDIUM 延迟到 tick 末，
 *   额外增加 ~50ms 延迟。
 *
 * 本 handler 的 ctx.writeAndFlush() 从 ka_booster 的位置向 head 方向写，
 *   绕过 priority_queue，C00 即刻编码发出，等价于原版 event loop 上的处理速度。
 *
 * 同时消费 S00，防止 S00 传到 packet_handler 触发的 vanilla handleKeepAlive()
 * 再发一次 C00，导致 server 收到双重回复触发 badpacket 检测。
 *
 * 注意：原版 1.8.9 的 NetHandlerPlayClient.handleKeepAlive() 也在 event loop
 * 上直接处理，延迟约 1ms。本 handler 与之相同。
 * 「加速」表像的实质是为 priority_queue 擦屁股，恢复 vanilla 的即时性。
 */
@SideOnly(Side.CLIENT)
public class KeepAliveOptimizer {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // 用于计算 Ping 的 RTT 信息
    private static volatile long lastRtt = 0;  // 最后一次 RTT（volatile：event loop 写+主线程读）

    /** 获取当前 Ping（ms），0 表示未知 */
    public static long getCurrentPing() {
        return lastRtt;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!HitRegEnhancer.config.enabled || !HitRegEnhancer.config.keepAliveBoost) return;
        if (mc.thePlayer == null || mc.getNetHandler() == null) return;

        // 每次 tick 尝试注入（tryInject 内部会检测 pipeline 是否已有 handler）
        // 断线重连后 channel 被重建，pipeline 中 handler 消失，自动重新注入
        tryInject();

        // 每 tick 触发优先级队列的缓冲区处理
        // 无论本次是否刚注入，onTick 内部会处理，无副作用
        NetworkManager netMan = mc.getNetHandler().getNetworkManager();
        if (netMan != null) {
            PacketPriorityHandler.onTick(netMan);
        }
    }

    private boolean tryInject() {
        try {
            NetworkManager netMan = mc.getNetHandler().getNetworkManager();
            if (netMan == null) return false;

            // 反射获取 channel 字段
            Field channelField = NetworkManager.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            Channel channel = (Channel) channelField.get(netMan);
            if (channel == null || !channel.isActive()) return false;

            // 检查是否已经注入（兼容断线重连：旧 channel 关掉后 handler 消失）
            if (channel.pipeline().get("ka_booster") != null) {
                return true;
            }

            // 注入 handler：在 packet_handler 之前拦截 KeepAlive
            channel.pipeline().addBefore("packet_handler", "ka_booster",
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        // ── KeepAlive 即时回复（防双重 C00 + 绕过 priority_queue）──
                        if (msg instanceof S00PacketKeepAlive) {
                            S00PacketKeepAlive pkt = (S00PacketKeepAlive) msg;

                            // 用反射获取 KeepAlive ID（兼容不同 MCP 映射）
                            final int id = getKeepAliveId(pkt);

                            // 断线保护：channel 未激活时不发 C00
                            if (ctx.channel().isActive()) {
                                // ctx.writeAndFlush() 从 ka_booster 向 head 方向走，
                                // 直接进 encoder，绕过 priority_queue，与原版 event loop 处理速度相同。
                                ctx.writeAndFlush(new C00PacketKeepAlive(id));
                            }

                            // ★ 消费该包，不传给 packet_handler
                            // 原版 NetHandlerPlayClient.handleKeepAlive() 也会调 sendPacket(C00)，
                            // S00 传过去会导致双重 C00 回复 → 反作弊 badpacket。
                            return;
                        }

                        // 非 KeepAlive 包：正常继续传递
                        ctx.fireChannelRead(msg);
                    }
                });

            // 同时注入包优先级队列（outbound 方向）
            PacketPriorityHandler.inject(netMan);

            HitRegEnhancer.logger.info("KeepAlive optimizer + priority queue injected");
            return true;
        } catch (Exception e) {
            // Fallback: 不注入，不影响游戏
            return false;
        }
    }

    /**
     * 通过反射获取 S00PacketKeepAlive 的 ID 值。
     * 兼容不同 MCP 映射版本（getKeepAliveID / func_149134_e 等）。
     */
    private static int getKeepAliveId(S00PacketKeepAlive pkt) {
        // 尝试已知的方法名
        String[] candidates = {"getKeepAliveID", "func_149134_e", "getKeepAliveId", "a"};
        for (String name : candidates) {
            try {
                Method m = S00PacketKeepAlive.class.getDeclaredMethod(name);
                m.setAccessible(true);
                Object val = m.invoke(pkt);
                if (val instanceof Integer) return (Integer) val;
                if (val instanceof Long) return ((Long) val).intValue();
            } catch (Exception ignored) {}
        }
        // 也尝试直接读字段
        String[] fieldCandidates = {"keepAliveID", "field_149136_a", "a"};
        for (String name : fieldCandidates) {
            try {
                Field f = S00PacketKeepAlive.class.getDeclaredField(name);
                f.setAccessible(true);
                Object val = f.get(pkt);
                if (val instanceof Integer) return (Integer) val;
                if (val instanceof Long) return ((Long) val).intValue();
            } catch (Exception ignored) {}
        }
        HitRegEnhancer.logger.warn("Failed to read KeepAlive ID via reflection");
        return 0;
    }
}
