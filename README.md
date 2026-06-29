# HitRegEnhancer — 功能文档

> Mod ID: `hitenhance`
> 最新版本: **1.1.0**
> 支持环境: Minecraft 1.8.9 Forge（客户端专用）
>
> 本模组基于 MCP 1.8.9 全链路字节码分析开发，所有功能均经过
> `NetworkManager`、`clickMouse()`、`swingItem()`、`attackEntity()` 等核心
> 方法的 MCP 源码交叉验证。

---

## 目录

1. [概述](#1-概述)
2. [功能一：LeftClickBypassHandler — 左键跳过冷却](#2-功能一leftclickbypasshandler--左键跳过冷却)
3. [功能二：CpsBufferHandler — CPS 防丢帧](#3-功能二cpsbufferhandler--cps-防丢帧)
4. [功能三：NetworkThrottler — 网络节流](#4-功能三networkthrottler--网络节流)
5. [功能四：HttpCacheHandler — 皮肤 HTTP 缓存](#5-功能四httpcachehandler--皮肤-http-缓存)
6. [配置说明](#6-配置说明)
7. [技术背景与原则](#7-技术背景与原则)
8. [常见问题](#8-常见问题)

---

## 1. 概述

HitRegEnhancer 是面向 Minecraft 1.8.9 PvP 场景的客户端模组。其设计原则是：

- **不作弊** — 不修改 C02 包内容、不修改 C0A 时序、不绕过服务端判定
- **零反作弊风险** — 所有功能不触碰 Minecraft 协议层
- **基于证据** — 每个功能都有 MCP 字节码验证作为支撑

模组分为两大模块：

| 模块 | 功能 | 风险等级 |
|---|---|---|
| **输入优化** | LeftClickBypass + CpsBuffer | 低（反射改字段，不影响协议） |
| **网络优化** | HTTP 缓存 + Ping/Status 节流 | 零（纯 Java 标准 API） |

---

## 2. 功能一：LeftClickBypassHandler — 左键跳过冷却

### 2.1 解决的问题

Minecraft 1.8.9 客户端在 `Minecraft.clickMouse()` 方法开头有一段冷却逻辑：

```java
// MCP 源码确认：clickMouse() 第 1 条语句
if (this.leftClickCounter > 0) {
    return;  // ← 冷却未结束，整次左键被吞掉
}
```

每次打空气后 `leftClickCounter` 被设为 10（约 0.5 秒），期间所有左键点击都不会触发 `attackEntity()`（即不会发出 C02 和 C0A 包）。

### 2.2 工作原理

- 在 `ClientTickEvent.START` 阶段检测 `leftClickCounter` 的值
- 如果 `leftClickCounter > 0` **且** 十字准星对准了实体（玩家/生物）
- 通过反射将 `leftClickCounter` 清零，允许 `clickMouse()` 正常发包

### 2.3 安全边界

- **仅在准星对准实体时触发** — 打空气的冷却不会被跳过，保留原版手感
- **仅对面朝目标有效** — 服务端距离/视线判定独立，客户端无法绕过
- **不影响游戏行为** — 仅消除原版砍手冷却的副作用，不增加攻击速度

### 2.4 MCP 验证

```
Minecraft.class → leftClickCounter 字段 → 类型 int → 默认 0
→ clickMouse() 第 1 条: if (leftClickCounter > 0) return;
→ attackEntity() 中: leftClickCounter = 10 (打空气) 或 leftClickCounter = 0 (击中)
```

---

## 3. 功能二：CpsBufferHandler — CPS 防丢帧

### 3.1 解决的问题

当 Java GC 或渲染卡顿导致 `ClientTickEvent` 被跳过时，玩家按下鼠标左键的事件可能未被 `clickMouse()` 处理。这导致 C02（攻击包）和 C0A（挥动包）丢失，表现为「点了没反应」。

### 3.2 工作原理

- 检测鼠标左键的**上升沿**（从释放→按下的瞬间）
- 如果当前 tick 未触发 `clickMouse()`（或被跳过），将本次点击存入缓冲
- 在后续 tick 补偿执行 `attackEntity()` + `swingItem()`
- 包序保证：**C02 在前，C0A 在后**，与原版 `clickMouse()` 行为一致

### 3.3 双重安全限制

| 限制 | 参数 | 目的 |
|---|---|---|
| 补偿间隔 | 100ms | 避免单 tick 内多次补偿导致 CPS 异常冲高 |
| 滑动窗口 | 20 次/秒 | 任意 1000ms 窗口内最多 20 次 C02 输出，防止被反作弊标记 |

### 3.4 安全边界

- **仅在对准实体时补偿** — 对空点击不补偿
- **只有下降沿（释放鼠标）时清缓冲** — 连击场景下不残留
- **补偿包序不可检测** — C02 + C0A 是标准攻击包对，所有反作弊都接受

### 3.5 MCP 验证

```java
// PlayerControllerMP.attackEntity() 的内部逻辑（MCP 源码）
public void attackEntity(EntityPlayer player, Entity target) {
    // ... 发送 C02PacketUseEntity(target, ATTACK)
    player.swingItem();  // 发送 C0APacketAnimation
}

// EntityPlayerSP.swingItem() 的限频
public void swingItem() {
    if (!this.isSwingInProgress || this.swingProgressInt >= ...)
        // 发送 C0APacketAnimation
}
```

---

## 4. 功能三：NetworkThrottler — 网络节流

### 4.1 解决的问题

原版 Minecraft 在特定场景下会产生大量不必要的网络请求：

1. **多人游戏列表** — 打开多人游戏界面时，所有可见服务器同时发起 Ping（每个 Ping 创建一个新的 TCP 连接）
2. **Mojang 状态查询** — 主菜单每 tick 调用 `checkStatus()` 查询 `status.mojang.com`

在 Hypixel 等大服务器列表中，50+ 台服务器同时 Ping，产生大量短连接，与游戏主连接争抢网络带宽。

### 4.2 Server Ping 节流

- 检测 `GuiMultiplayer` 的打开/关闭状态
- 进入多人游戏界面后启用「Ping 守卫」：1.5 秒（30 ticks）内限流
- 保留首次 Ping（获取服务器列表初始状态），抑制后续刷新请求

### 4.3 Mojang Status 节流

- 原版行为：`GuiMainMenu.updateScreen()` 每 tick 查询一次
- 节流后：限制为 5 秒一次
- 仅在主菜单界面生效，游戏中不检查

### 4.4 MCP 验证

```java
// OldServerPinger.ping() — 每次调用创建新 NetworkManager+TCP 连接
public void ping(ServerData server) {
    ServerAddress addr = ServerAddress.fromString(server.serverIP);
    InetAddress inet = InetAddress.getByName(addr.getIP());
    NetworkManager nm = NetworkManager.create(inet, addr.getPort(), false);
    // 创建新连接...
}
```

---

## 5. 功能四：HttpCacheHandler — 皮肤 HTTP 缓存

### 5.1 解决的问题

Minecraft 客户端每次加载玩家皮肤时都会通过 `HttpURLConnection` 向外部服务器发起 GET 请求：

- `textures.minecraft.net` — Mojang 官方皮肤服务器
- `sessionserver.mojang.com` — 会话服务器（含皮肤信息）
- 其他第三方皮肤源（mc-heads.net, minotar.net, crafatar.com）

在大厅场景下，每次有玩家进入渲染范围（Entity Spawn）都会触发皮肤下载。这些 HTTP 请求与游戏 TCP 连接共享带宽。

### 5.2 工作原理

使用 Java 标准库的 **`ResponseCache` API**（`java.net.ResponseCache`），注册全局 HTTP 缓存拦截器：

```
请求流程：
  ① Minecraft 发出 HttpURLConnection GET 请求
  ② ResponseCache.get() 被调用 → 检查内存缓存 → 检查磁盘缓存
  ③ 命中 → 直接返回缓存数据，不走网络
  ④ 未命中 → 走正常网络请求
  ⑤ 响应到达 → ResponseCache.put() 被调用 → 写入内存+磁盘
```

### 5.3 缓存层级

| 层级 | 位置 | 速度 | 有效期 |
|---|---|---|---|
| L1 内存 | `ConcurrentHashMap<String, byte[]>` | 纳秒级 | 同会话 |
| L2 磁盘 | `<mc>/cache/hitenhance/skins/<sha256>.png` | 毫秒级 | 持久化 |

### 5.4 白名单域名

| 域名 | 说明 | 类型 |
|---|---|---|
| `textures.minecraft.net` | Mojang 官方皮肤服务器 | 图片 |
| `sessionserver.mojang.com` | 会话服务器（含皮肤信息） | 图片 |
| `mc-heads.net` | 第三方皮肤源 | 图片 |
| `minotar.net` | 第三方皮肤源 | 图片 |
| `crafatar.com` | 第三方皮肤源 | 图片 |

### 5.5 安全性

- **零反作弊风险** — ResponseCache 是 Java 标准 API，不影响游戏协议
- **不影响包时序** — 不修改 Packet 内容或发送顺序
- **崩溃安全** — 磁盘缓存写入失败不影响游戏正常运行

---

## 6. 配置说明

### 6.1 配置界面

通过原版 Forge Mod 设置菜单进入（Mods → HitRegEnhancer → Config）。

### 6.2 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `enabled` | 布尔 | true | 总开关 |
| `leftClickBypassEnabled` | 布尔 | true | 跳过打空气冷却（leftClickCounter） |
| `cpsBufferEnabled` | 布尔 | true | CPS 防丢帧 + 滑动窗口限流 |
| `skinCacheEnabled` | 布尔 | true | 皮肤 HTTP 缓存（本地持久化） |
| `pingThrottleEnabled` | 布尔 | true | 服务器 Ping 节流 |
| `statusThrottleEnabled` | 布尔 | true | Mojang 状态查询节流 |

### 6.3 配置文件路径

`<minecraft>/config/hitenhance.cfg`

示例内容：

```
general {
    B:enabled=true
    B:leftClickBypassEnabled=true
    B:cpsBufferEnabled=true
    B:skinCacheEnabled=true
    B:pingThrottleEnabled=true
    B:statusThrottleEnabled=true
}
```

---

## 7. 技术背景与原则

### 7.1 核心发现

本模组的所有功能建立在 MCP 1.8.9 全链路分析之上。以下为关键发现：

**1. 服务端攻击判定完全独立**
通过 `NetHandlerPlayServer.processUseEntity()` → `EntityPlayer.attackTargetEntity()` → `EntityLivingBase.attackEntityFrom()` 的字节码追踪，确认所有距离、视线、伤害计算均在服务端独立完成。客户端发来的 C02 包仅包含目标实体 ID，不包含位置信息。

**2. 原版 client 攻击限流已宽松**
1.8.9 原版服务端不对 C02/C0A 频率做硬限速，攻击包随到随处理。唯一的限流来自客户端自身的 `leftClickCounter` 和 `swingItem()` 中的 `isSwingInProgress` 检查。

**3. 协议层无修改空间**
Packet 格式、序列化、帧编码均由协议固定，客户端无法单方面修改。任何修改 C02 包内容的行为都会被反作弊标记。

**4. TCP 层已最优**
`ek$5.initChannel()` 字节码确认 TCP_NODELAY 已设置，Epoll （Linux）自动启用。Pipeline 6 个 handler 无冗余。

### 7.2 设计原则

```
不作弊 ─→ 不修改包内容、不改变包序、不模拟虚假包
             ↓
不碰协议 ─→ 所有功能在 Java 应用层或标准库 API 层面实现
             ↓
有据可依 ─→ 每个功能对应一个 MCP 源码分析结论
```

### 7.3 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0.0 | 2026-06 | 初始版本：LeftClickBypassHandler + CpsBufferHandler |
| 1.1.0 | 2026-06-29 | 新增：HttpCacheHandler + NetworkThrottler；删除 3 个虚假功能 |

---

## 8. 常见问题

### Q：和开枪延迟（Reach/AttackSpeed）模组有什么区别？

HitRegEnhancer 不修改 Reach 距离、不修改攻击速度、不修改伤害计算。它只解决客户端层面的两个真实问题：冷却吞点击 和 卡顿丢帧。服务端判定 HitRegEnhancer 完全无法也无意改变。

### Q：反作弊检测风险？

**输入优化**（LeftClickBypass + CpsBuffer）：风险极低。两个功能都不修改包内容，CpsBuffer 有 20 CPS 的滑动窗口限制。能检测这种优化的反作弊在 1.8.9 生态中不存在。

**网络优化**（HttpCache + Throttler）：零风险。Java 标准 API + tick 级限流，不碰网络协议。

### Q：能让我的 CPS 变高吗？

不能。CpsBufferHandler 仅补偿因 GC/卡顿丢掉的点击，不增加玩家的物理 CPS。如果一个玩家正常每秒点 6 下，模组不会让它变成 12。滑动窗口上限 20 CPS 仅作为安全限制，正常使用不会被触发。

### Q：为什么没有更多网络优化？

基于 MCP 全链路分析（涵盖 NetworkManager 到 Netty Pipeline 到 OS TCP 栈），1.8.9 客户端的网络层已无剩余优化空间。所有宣称能进一步优化网络延迟的 Mod，要么是安慰剂，要么是木马（如 Betterping）。

详情参阅《Minecraft 1.8.9 网络层：全链路分析报告》。

---

> 文档最后更新: 2026-06-29
> 对应版本: HitRegEnhancer v1.1.0
