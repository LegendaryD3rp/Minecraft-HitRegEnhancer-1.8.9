package com.hitenhance.network;

import com.hitenhance.HitRegEnhancer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 请求缓存 —— 皮肤纹理本地持久化。
 *
 * 原理：
 *   利用 Java 标准库的 ResponseCache API，注册全局缓存。
 *   当 Minecraft 通过 HttpURLConnection 下载皮肤纹理时，
 *   缓存拦截器自动生效，无需 Mixin 或 ASM。
 *
 *   缓存的 URL 白名单：
 *     - textures.minecraft.net    （Mojang 皮肤服务器）
 *     - sessionserver.mojang.com  （会话服务器）
 *     - mc-heads.net              （备用皮肤源）
 *     - minotar.net               （备用皮肤源）
 *     - crafatar.com              （备用皮肤源）
 *
 *   缓存目录：<minecraft>/cache/hitenhance/skins/<url-sha256>.png
 *   内存缓存：ConcurrentHashMap<String, byte[]>，同会话内零延迟。
 *
 *   优势：
 *     - Java 标准 API，不碰 Minecraft 协议层，不改任何包
 *     - 零反作弊风险
 *     - 在 Hypixel 大厅等多人场景下节省大量 HTTP 请求
 */
@SideOnly(Side.CLIENT)
public class HttpCacheHandler {

    private static Path cacheDir;
    private static boolean cacheInstalled = false;

    // 内存缓存：加速同会话内的重复请求
    private static final ConcurrentHashMap<String, byte[]> memCache = new ConcurrentHashMap<>();

    // 白名单域名（后缀匹配）
    private static final Set<String> CACHED_HOSTS = new HashSet<>(Arrays.asList(
        "textures.minecraft.net",
        "sessionserver.mojang.com",
        "mc-heads.net",
        "minotar.net",
        "crafatar.com"
    ));

    /** 安装缓存（FMLPreInitialization 调用） */
    public static void install() {
        if (cacheInstalled) return;
        try {
            File mcDir = Minecraft.getMinecraft().mcDataDir;
            cacheDir = Paths.get(mcDir.getAbsolutePath(), "cache", "hitenhance", "skins");
            Files.createDirectories(cacheDir);
            HitRegEnhancer.logger.info("[HttpCache] dir: " + cacheDir);

            ResponseCache.setDefault(new SkinResponseCache());
            cacheInstalled = true;
            HitRegEnhancer.logger.info("[HttpCache] installed");
        } catch (Exception e) {
            HitRegEnhancer.logger.error("[HttpCache] install failed: " + e.getMessage());
        }
    }

    // ── 工具方法 ──

    private static String urlToFilename(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString() + ".png";
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode()) + ".png";
        }
    }

    private static boolean shouldCache(String url) {
        if (url == null) return false;
        try {
            String host = new URI(url).getHost();
            if (host == null) return false;
            for (String cachedHost : CACHED_HOSTS) {
                if (host.endsWith(cachedHost)) return true;
            }
        } catch (URISyntaxException ignored) {}
        return false;
    }

    // ═══════════════════════════════════════════════
    //  ResponseCache 实现
    // ═══════════════════════════════════════════════

    private static class SkinResponseCache extends ResponseCache {

        @Override
        public CacheResponse get(URI uri, String rqstMethod, Map<String, List<String>> headers) {
            if (!"GET".equalsIgnoreCase(rqstMethod)) return null;
            String url = uri.toString();
            if (!shouldCache(url)) return null;

            // ① 内存缓存
            byte[] data = memCache.get(url);
            if (data != null) {
                HitRegEnhancer.logger.debug("[HttpCache] mem: " + url.substring(0, Math.min(60, url.length())));
                return new ByteArrayCacheResponse(data, headers);
            }

            // ② 磁盘缓存
            Path file = cacheDir.resolve(urlToFilename(url));
            if (Files.exists(file)) {
                try {
                    if (Files.size(file) == 0) { Files.delete(file); return null; }
                    data = Files.readAllBytes(file);
                    memCache.put(url, data);
                    HitRegEnhancer.logger.debug("[HttpCache] disk: " + url.substring(0, Math.min(60, url.length())));
                    return new ByteArrayCacheResponse(data, headers);
                } catch (IOException e) {
                    // 文件损坏，重下
                }
            }

            return null;  // 未命中 → 走网络
        }

        @Override
        public CacheRequest put(URI uri, URLConnection conn) {
            String url = uri.toString();
            if (!shouldCache(url)) return null;
            return new SkinCacheRequest(url);
        }
    }

    // ── CacheResponse 实现：从内存字节数组返回 ──

    private static class ByteArrayCacheResponse extends CacheResponse {
        private final byte[] data;
        private final Map<String, List<String>> headers;

        ByteArrayCacheResponse(byte[] data, Map<String, List<String>> origHeaders) {
            this.data = data;
            this.headers = new HashMap<>();
            // 保留原响应头中的 Content-Type
            if (origHeaders != null) {
                List<String> ct = origHeaders.get("Content-Type");
                if (ct != null) this.headers.put("Content-Type", ct);
            }
            if (!this.headers.containsKey("Content-Type")) {
                this.headers.put("Content-Type", Arrays.asList("image/png"));
            }
        }

        @Override
        public Map<String, List<String>> getHeaders() { return headers; }

        @Override
        public InputStream getBody() { return new ByteArrayInputStream(data); }
    }

    // ── CacheRequest 实现：将下载内容写入磁盘+内存 ──

    private static class SkinCacheRequest extends CacheRequest {
        private final String url;
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
        private boolean aborted = false;

        SkinCacheRequest(String url) { this.url = url; }

        @Override
        public OutputStream getBody() {
            return new FilterOutputStream(baos) {
                @Override
                public void close() throws IOException {
                    super.close();
                    if (!aborted && baos.size() > 0) {
                        commitToCache();
                    }
                }
            };
        }

        @Override
        public void abort() { aborted = true; }

        private void commitToCache() {
            byte[] data = baos.toByteArray();
            // 写入内存缓存
            memCache.put(url, data);
            // 写入磁盘缓存
            try {
                Path file = cacheDir.resolve(urlToFilename(url));
                Files.write(file, data);
                HitRegEnhancer.logger.debug("[HttpCache] saved: " + url.substring(0, Math.min(60, url.length())));
            } catch (IOException e) {
                HitRegEnhancer.logger.warn("[HttpCache] save failed: " + e.getMessage());
            }
        }
    }
}
