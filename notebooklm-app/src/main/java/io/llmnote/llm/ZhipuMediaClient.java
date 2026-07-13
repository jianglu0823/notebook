package io.llmnote.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.llmnote.config.NotebookLmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 智谱 CogView(文生图)/ CogVideoX(文生视频)直连客户端。AgentScope 只封装 chat 类模型,
 * 这两个是智谱专有的非 chat 接口,故用 JDK 内置 {@link HttpClient} 直连 REST。
 *
 * <p>免费模型有速率限制,遇限流/瞬时错误按指数退避重试(仅重试,不回退 qwen)。产物落盘到
 * {@code storage.imageDir/world/<agentId>/<date>-<seq>.(png|mp4)},返回相对 imageDir 的相对路径,
 * 供 {@code /api/world/products/{id}/media} 直接以 FileSystemResource 服务。
 */
@Slf4j
@Component
public class ZhipuMediaClient {

    private static final int MAX_RETRIES = 4;
    private static final long BACKOFF_BASE_MS = 1000;

    private final NotebookLmProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public ZhipuMediaClient(NotebookLmProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ---- 图片(同步,CogView Flash 秒级) ----

    /** 文生图:调 CogView,返回临时图片 URL(带限流重试)。失败返回 null。 */
    public String generateImage(String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", props.getZhipu().getImageModel());
            body.put("prompt", prompt);
            body.put("watermark_enabled", false);
            JsonNode resp = postWithRetry("/images/generations", body);
            if (resp == null) return null;
            JsonNode data = resp.path("data");
            if (data.isArray() && data.size() > 0) {
                String url = data.get(0).path("url").asText(null);
                if (url != null && !url.isBlank()) return url;
            }
            log.warn("CogView 无 url 返回: {}", resp);
            return null;
        } catch (Exception e) {
            log.warn("CogView 文生图失败: {}", e.getMessage());
            return null;
        }
    }

    // ---- 视频(异步,CogVideoX Flash 分钟级) ----

    /** 提交文生视频任务,返回任务 id。失败返回 null。 */
    public String submitVideo(String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", props.getZhipu().getVideoModel());
            body.put("prompt", prompt);
            body.put("watermark_enabled", false);
            JsonNode resp = postWithRetry("/videos/generations", body);
            if (resp == null) return null;
            String id = resp.path("id").asText(null);
            if (id != null && !id.isBlank()) return id;
            log.warn("CogVideoX 无任务 id 返回: {}", resp);
            return null;
        } catch (Exception e) {
            log.warn("CogVideoX 提交失败: {}", e.getMessage());
            return null;
        }
    }

    /** 轮询视频任务直到 SUCCESS 取视频 url;FAIL/超时返回 null。 */
    public String pollVideo(String taskId, int maxPolls, long intervalMs) {
        for (int i = 0; i < maxPolls; i++) {
            try {
                JsonNode resp = getWithRetry("/async-result/" + taskId);
                if (resp != null) {
                    String status = resp.path("task_status").asText("");
                    if ("SUCCESS".equalsIgnoreCase(status)) {
                        JsonNode results = resp.path("video_result");
                        if (results.isArray() && results.size() > 0) {
                            String url = results.get(0).path("url").asText(null);
                            if (url != null && !url.isBlank()) return url;
                        }
                        log.warn("CogVideoX SUCCESS 但无 url: {}", resp);
                        return null;
                    }
                    if ("FAIL".equalsIgnoreCase(status)) {
                        log.warn("CogVideoX 任务失败 id={} resp={}", taskId, resp);
                        return null;
                    }
                }
            } catch (Exception e) {
                log.warn("CogVideoX 轮询异常 id={}: {}", taskId, e.getMessage());
            }
            try { Thread.sleep(intervalMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warn("CogVideoX 轮询超时 id={}", taskId);
        return null;
    }

    // ---- 落盘 ----

    /**
     * 下载媒体 URL 到 {@code imageDir/world/<agentId>/<date>-<seq>.<ext>},返回相对 imageDir 的相对路径
     * (如 {@code world/157/2026-07-10-1.png})。失败返回 null。
     */
    public String downloadToWorld(String url, Long agentId, LocalDate date, int seq, String ext) {
        try {
            Path root = Paths.get(props.getStorage().getImageDir()).toAbsolutePath().normalize();
            Path dir = root.resolve("world").resolve(String.valueOf(agentId));
            Files.createDirectories(dir);
            String fileName = date + "-" + seq + "." + ext;
            Path out = dir.resolve(fileName);
            try (var in = URI.create(url).toURL().openStream()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            return "world/" + agentId + "/" + fileName;
        } catch (Exception e) {
            log.warn("媒体下载落盘失败 url={}: {}", url, e.getMessage());
            return null;
        }
    }

    /** 把相对路径解析为 imageDir 下的绝对路径(供 controller 服务文件)。 */
    public Path resolveMedia(String relativePath) {
        return Paths.get(props.getStorage().getImageDir()).toAbsolutePath()
                .normalize().resolve(relativePath).normalize();
    }

    // ---- HTTP 底层(带限流重试) ----

    private JsonNode postWithRetry(String path, ObjectNode body) throws Exception {
        String url = props.getZhipu().getBaseUrl() + path;
        String payload = objectMapper.writeValueAsString(body);
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + props.getZhipu().getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return objectMapper.readTree(resp.body());
                if (!isTransientStatus(resp.statusCode()) || attempt >= MAX_RETRIES) {
                    throw new IllegalStateException("智谱 API " + resp.statusCode() + ": " + resp.body());
                }
                last = new IllegalStateException("HTTP " + resp.statusCode());
            } catch (Exception e) {
                last = e;
                if (attempt >= MAX_RETRIES) throw e;
            }
            backoff(attempt);
        }
        throw last;
    }

    private JsonNode getWithRetry(String path) throws Exception {
        String url = props.getZhipu().getBaseUrl() + path;
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + props.getZhipu().getApiKey())
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return objectMapper.readTree(resp.body());
                if (!isTransientStatus(resp.statusCode()) || attempt >= MAX_RETRIES) {
                    throw new IllegalStateException("智谱 API " + resp.statusCode() + ": " + resp.body());
                }
                last = new IllegalStateException("HTTP " + resp.statusCode());
            } catch (Exception e) {
                last = e;
                if (attempt >= MAX_RETRIES) throw e;
            }
            backoff(attempt);
        }
        throw last;
    }

    private boolean isTransientStatus(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
    }

    private void backoff(int attempt) {
        long sleep = (BACKOFF_BASE_MS << attempt) + ThreadLocalRandom.current().nextInt(500);
        log.warn("智谱媒体接口限流/瞬时错误,第 {} 次重试,退避 {}ms", attempt + 1, sleep);
        try { Thread.sleep(sleep); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
