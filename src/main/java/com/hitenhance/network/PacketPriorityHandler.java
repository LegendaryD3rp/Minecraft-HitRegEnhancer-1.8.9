package com.hitenhance.network;

import com.hitenhance.HitRegEnhancer;
import io.netty.channel.*;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 包优先级队列。
 *
 * 在 Netty pipeline 的 outbound 方向插入 handler，
 * 根据包类型分配优先级：
 *
 *   HIGH   → 立即 write + flush，不等 tick 末
 *   MEDIUM → 正常排队（tick 末批量 flush）
 *   LOW    → 延迟 1~2 tick 再发（合并低优先级包）
 */
@SideOnly(Side.CLIENT)
public class PacketPriorityHandler extends ChannelOutboundHandlerAdapter {

    // ── 低优先级缓冲（线程安全）──
    private static final Queue<PacketEntry> lowBuffer = new ConcurrentLinkedQueue<>();
    private static volatile int lowAge = 0;
    private static final int LOW_DELAY_TICKS = 1;
    private static final int LOW_BUFFER_MAX = 1024;  // 防溢出上限

    // ── 中优先级标记（tick 末 flush）──
    private static volatile boolean tickFlushNeeded = false;

    // ═══════════════════════════════════════
    //  包优先级判定
    // ═══════════════════════════════════════

    private static boolean isHighPriority(Packet<?> pkt) {
        return pkt instanceof C02PacketUseEntity           // 攻击 / 交互实体
            || pkt instanceof C0APacketAnimation           // 挥动包（MCP 名 C0APacketAnimation，非 C0APacketSwing）
            || pkt instanceof C03PacketPlayer              // 位置 / 旋转（所有变体）
            || pkt instanceof C08PacketPlayerBlockPlacement // 右键放置 / 使用物品
            || pkt instanceof C09PacketHeldItemChange       // 切换快捷栏
            || pkt instanceof C0BPacketEntityAction         // 疾跑 / 潜行
            || pkt instanceof C0CPacketInput                // 按键输入
            || pkt instanceof C07PacketPlayerDigging;       // 挖掘（开始/停止）
    }

    private static boolean isLowPriority(Packet<?> pkt) {
        return pkt instanceof C17PacketCustomPayload        // 自定义负载（模组通道）
            || pkt instanceof C12PacketUpdateSign           // 编辑告示牌
            || pkt instanceof C10PacketCreativeInventoryAction; // 创造模式物品栏（PVP 不涉及）
    }

    // ═══════════════════════════════════════
    //  Netty outbound 拦截
    // ═══════════════════════════════════════

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (HitRegEnhancer.config == null
            || !HitRegEnhancer.config.enabled
            || !HitRegEnhancer.config.packetPriorityEnabled) {
            // 功能关闭：直通
            super.write(ctx, msg, promise);
            return;
        }

        if (!(msg instanceof Packet)) {
            // 非 MC 包（如 ByteBuf），直通
            super.write(ctx, msg, promise);
            return;
        }

        Packet<?> pkt = (Packet<?>) msg;

        if (isHighPriority(pkt)) {
            // ██ 高优先级：立即发送 + flush ██
            super.write(ctx, msg, promise);
            ctx.flush();
            return;
        }

        if (isLowPriority(pkt)) {
            // ██ 低优先级：缓冲延迟（保存 ctx 以防无限递归）██
            if (lowBuffer.size() >= LOW_BUFFER_MAX) {
                // 缓冲区满：降级为直通发送，不丢包
                super.write(ctx, msg, promise);
            } else {
                lowBuffer.add(new PacketEntry(ctx, msg, promise));
            }
            return;
        }

        // ██ 中优先级：正常排队，tick 末会 flush ██
        super.write(ctx, msg, promise);
        tickFlushNeeded = true;
    }

    // ═══════════════════════════════════════
    //  Tick 事件：flush 缓冲区
    // ═══════════════════════════════════════

    public static void onTick(NetworkManager netMan) {
        if (netMan == null) return;
        if (HitRegEnhancer.config == null || !HitRegEnhancer.config.enabled
            || !HitRegEnhancer.config.packetPriorityEnabled) return;

        // 1. 低优先级缓冲 → 到期后发出
        if (!lowBuffer.isEmpty()) {
            lowAge++;
            if (lowAge >= LOW_DELAY_TICKS) {
                flushLowBuffer();
                lowAge = 0;
            }
        }

        // 2. 中优先级 → flush 缓冲区
        if (tickFlushNeeded) {
            try {
                Channel channel = getChannel(netMan);
                if (channel != null) {
                    channel.flush();
                }
            } catch (Exception ignored) {}
            tickFlushNeeded = false;
        }
    }

    private static void flushLowBuffer() {
        if (lowBuffer.isEmpty()) return;
        PacketEntry entry;
        while ((entry = lowBuffer.poll()) != null) {
            final ChannelHandlerContext ectx = entry.ctx;
            final Object emsg = entry.msg;
            final ChannelPromise epromise = entry.promise;
            try {
                ectx.executor().execute(() -> {
                    try {
                        ectx.write(emsg, epromise);
                    } catch (Exception ignored) {
                        // event loop 关闭或 ctx 失效时静默丢弃
                    }
                });
            } catch (Exception ignored) {
                // executor 已 shutdown，直接丢弃
            }
        }
    }

    // ═══════════════════════════════════════
    //  Pipeline 注入
    // ═══════════════════════════════════════

    public static void inject(NetworkManager netMan) {
        if (HitRegEnhancer.config != null && !HitRegEnhancer.config.packetPriorityEnabled) return;
        try {
            Channel channel = getChannel(netMan);
            if (channel == null || !channel.isActive()) return;

            // 每次注入检查 pipeline 中是否已有该 handler（兼容断线重连）
            if (channel.pipeline().get("priority_queue") != null) {
                return;
            }

            // ██ 关键：addAfter 而非 addBefore ██
            // packet_handler 在 pipeline 中的位置在 encoder 之后（更靠近 tail）。
            // addAfter("packet_handler", ...) 将 priority_queue 放在
            // packet_handler 更靠后（更接近 tail）的位置。
            // 出站方向（tail→head）：priority_queue 先于 encoder 收到 Packet 对象。
            channel.pipeline().addAfter("packet_handler", "priority_queue",
                new PacketPriorityHandler());

            HitRegEnhancer.logger.info("Packet priority queue injected");
        } catch (Exception e) {
            HitRegEnhancer.logger.error("Failed to inject packet priority queue", e);
        }
    }

    private static Channel getChannel(NetworkManager netMan) {
        try {
            Field f = NetworkManager.class.getDeclaredField("channel");
            f.setAccessible(true);
            return (Channel) f.get(netMan);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════
    //  内部数据结构
    // ═══════════════════════════════════════

    private static class PacketEntry {
        final ChannelHandlerContext ctx;
        final Object msg;
        final ChannelPromise promise;
        PacketEntry(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            this.ctx = ctx;
            this.msg = msg;
            this.promise = promise;
        }
    }
}
