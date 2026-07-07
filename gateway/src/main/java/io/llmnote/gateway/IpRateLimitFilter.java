package io.llmnote.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 基于 IP 的网关限流:防止 demo 开放后被无限调用产生 DashScope 成本。
 *
 * <p>策略:
 * <ul>
 *   <li>只对触发计费的写操作(POST)计数:问答 / 文档生成 / 播客 / 上传;GET 轮询不消耗配额。</li>
 *   <li>每类端点各有独立的「每 IP 每日配额」。</li>
 *   <li>另有一个跨端点的「每 IP 每分钟突发上限」,防瞬时刷。</li>
 *   <li>上传请求先按 Content-Length 挡掉超大文件。</li>
 * </ul>
 * 计数用 Redis 原子脚本(INCR + 首次 EXPIRE),按窗口 key 隔离。
 */
@Slf4j
@Component
public class IpRateLimitFilter implements GlobalFilter, Ordered {

    // INCR 当前计数;若为首次(==1)则设置过期。返回自增后的值。
    private static final RedisScript<Long> INCR_SCRIPT = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private static final long SECONDS_PER_DAY = 86_400L;
    private static final long SECONDS_PER_MINUTE = 60L;

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties props;

    public IpRateLimitFilter(ReactiveStringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public int getOrder() {
        // 在路由转发之前执行(高优先级)。
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest req = exchange.getRequest();
        Endpoint ep = classify(req);
        if (ep == null) {
            // 非受限端点(如 GET 轮询、静态资源、健康检查),直接放行。
            return chain.filter(exchange);
        }

        String ip = clientIp(req);

        // 上传:先按 Content-Length 挡超大文件,避免把大文件读进来才拒绝。
        if (ep == Endpoint.UPLOAD) {
            long len = req.getHeaders().getContentLength();
            if (len > props.getMaxUploadBytes()) {
                return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE,
                        "文件超过大小上限(" + (props.getMaxUploadBytes() / 1024 / 1024) + "MB)");
            }
        }

        String dayKey = "rl:day:" + ep.name() + ":" + ip + ":" + (System.currentTimeMillis() / 1000 / SECONDS_PER_DAY);
        String minKey = "rl:min:" + ip + ":" + (System.currentTimeMillis() / 1000 / SECONDS_PER_MINUTE);
        int dayLimit = dailyLimit(ep);

        return incr(minKey, SECONDS_PER_MINUTE)
                .flatMap(minCount -> {
                    if (minCount > props.getBurstPerMinute()) {
                        return reject(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                "请求过于频繁,请稍后再试(每分钟上限 " + props.getBurstPerMinute() + " 次)");
                    }
                    return incr(dayKey, SECONDS_PER_DAY).flatMap(dayCount -> {
                        if (dayCount > dayLimit) {
                            return reject(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                    ep.label + "今日调用已达上限(每日 " + dayLimit + " 次),请明日再试");
                        }
                        return chain.filter(exchange);
                    });
                })
                // Redis 故障时不阻断业务(fail-open),仅记录告警。
                .onErrorResume(e -> {
                    log.warn("rate limit check failed, fail-open: ip={} ep={} err={}", ip, ep, e.toString());
                    return chain.filter(exchange);
                });
    }

    private Mono<Long> incr(String key, long ttlSeconds) {
        return redis.execute(INCR_SCRIPT, List.of(key), List.of(String.valueOf(ttlSeconds))).next();
    }

    private int dailyLimit(Endpoint ep) {
        return switch (ep) {
            case QA -> props.getQaPerDay();
            case DOC -> props.getDocPerDay();
            case PODCAST -> props.getPodcastPerDay();
            case UPLOAD -> props.getUploadPerDay();
        };
    }

    /** 判定受限端点;非计费写操作返回 null 放行。 */
    private Endpoint classify(ServerHttpRequest req) {
        if (req.getMethod() != HttpMethod.POST) {
            return null;
        }
        String path = req.getURI().getPath();
        // /api/notebooks/{id}/qa
        if (path.matches("/api/notebooks/\\d+/qa/?")) {
            return Endpoint.QA;
        }
        // /api/notebooks/{id}/docs/{kind}
        if (path.matches("/api/notebooks/\\d+/docs/[A-Za-z_]+/?")) {
            return Endpoint.DOC;
        }
        // /api/notebooks/{id}/podcasts
        if (path.matches("/api/notebooks/\\d+/podcasts/?")) {
            return Endpoint.PODCAST;
        }
        // /api/notebooks/{id}/sources  (文件上传)
        if (path.matches("/api/notebooks/\\d+/sources/?")) {
            return Endpoint.UPLOAD;
        }
        return null;
    }

    /** 取真实客户端 IP:优先 X-Forwarded-For 首段(部署前可能有反代/LB)。 */
    private String clientIp(ServerHttpRequest req) {
        String xff = req.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeaders().getFirst("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddress() != null && req.getRemoteAddress().getAddress() != null
                ? req.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(status);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"message\":\"" + message + "\"}";
        DataBuffer buf = resp.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Mono.just(buf));
    }

    private enum Endpoint {
        QA("问答"), DOC("文档生成"), PODCAST("播客生成"), UPLOAD("文件上传");
        final String label;
        Endpoint(String label) { this.label = label; }
    }
}
