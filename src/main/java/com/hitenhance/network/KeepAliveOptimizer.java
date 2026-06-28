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
import java.util.concurrent.TimeUnit;

/**
 * KeepAlive 回复加速 + 随机抖动伪装。
 *
 * 在 Netty pipeline 中注入 handler，在 packet_handler 之前
 * 拦截 S00PacketKeepAlive，在同一事件循环线程中延迟
 * 30-100ms 随机后再回复 C00PacketKeepAlive。
 *
 * 这样 KeepAlive 回复不经过主线程 tick 循环（原版路径减少 ~50ms），
 * 同时 30-100ms 随机抖动模拟真实网络延迟，
 * 防止反作弊因 RTT < 5ms 识别出 KeepAlive 加速。
 */
@SideOnly(Side.CLIENT)
public class KeepAliveOptimizer {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // 用于计算 Ping 的 RTT 信息
    private static long lastKeepAliveReceiveTime = 0;
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
                        // ── KeepAlive 伪装回复（带随机抖动）──
                        if (msg instanceof S00PacketKeepAlive) {
                            S00PacketKeepAlive pkt = (S00PacketKeepAlive) msg;

                            final long s00ReceiveTime = System.currentTimeMillis();
                            lastKeepAliveReceiveTime = s00ReceiveTime;

                            // 用反射获取 KeepAlive ID（兼容不同 MCP 映射）
                            final int id = getKeepAliveId(pkt);

                            // ★ 随机抖动 30-100ms，模拟真实网络延迟
                            // 反作弊识别 KeepAlive 加速的手段就是看 RTT 是否 < 10ms
                            // 30-100ms 的随机抖动会落在正常玩家的 ping 范围内，
                            // 且每次抖动值不同，不会被统计为固定延迟特征。
                            final long jitter = 30 + (long)(Math.random() * 70);

                            // 在 Netty 事件循环线程内调度回复，不阻塞主线程
                            ctx.executor().schedule(() -> {
                                // 断线保护：player 在 jitter 时间内断开，丢弃回复
                                if (!ctx.channel().isActive()) return;

                                // ctx.writeAndFlush() 从 ka_booster 往 head 方向走，
                                // 直接进 encoder，绕过 priority_queue，真正做到即时回复。
                                ctx.writeAndFlush(new C00PacketKeepAlive(id));

                                // RTT 估算 = S00 收到 → C00 发出的实际耗时
                                // 这近似等于我们故意加的抖动延迟，
                                // 对 Ping 显示和反作弊来说都自然合理。
                                long rtt = System.currentTimeMillis() - s00ReceiveTime;
                                if (lastRtt == 0) {
                                    lastRtt = rtt;
                                } else {
                                    lastRtt = (lastRtt * 3 + rtt) / 4;
                                }
                            }, jitter, TimeUnit.MILLISECONDS);

                            // ★ 消费该包，不传给 packet_handler
                            // vanilla NetHandlerPlayClient.handleKeepAlive()
                            // 在 1.8.9 中只做一件事件：sendPacket(new C00PacketKeepAlive(id))
                            // 我们在上面已经发了，再传给 vanilla 会导致双重 C00 回复，
                            // 触发反作弊 badpacket/keepalive sequence 检测。
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
