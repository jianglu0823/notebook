package io.llmnote.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置。按端点类别设置每日配额,另有一个跨端点的每分钟突发上限。
 * 只对触发 DashScope 计费的写操作(POST)计数,GET 轮询不消耗配额。
 */
@Data
@ConfigurationProperties(prefix = "gateway.ratelimit")
public class RateLimitProperties {

    /** 总开关。 */
    private boolean enabled = true;

    /** 单 IP 每分钟总突发上限(跨所有受限端点),防瞬时刷。 */
    private int burstPerMinute = 20;

    /** 上传文件大小上限(字节)。默认 10MB。 */
    private long maxUploadBytes = 10L * 1024 * 1024;

    /** 各端点每日配额(每 IP)。 */
    private int qaPerDay = 50;
    private int docPerDay = 20;
    private int podcastPerDay = 5;
    private int uploadPerDay = 30;
}
