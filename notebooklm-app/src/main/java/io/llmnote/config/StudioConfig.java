package io.llmnote.config;

import io.agentscope.core.studio.StudioManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Studio 可视化追踪:启动时注册到 Studio server,所有 agent/model/tool 调用
 * 自动通过 TracerRegistry hook 推送到 http://localhost:3000。关掉 agentscope.studio.enabled
 * 或停掉 Studio server 都不影响 app 启动(失败只 warn 日志)。
 */
@Configuration
@Slf4j
public class StudioConfig {

    @Value("${agentscope.studio.url:http://localhost:3000}")
    private String studioUrl;

    @Value("${spring.application.name:notebooklm-app}")
    private String project;

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.studio", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationRunner studioInitializer() {
        return args -> StudioManager.init()
                .studioUrl(studioUrl)
                .project(project)
                .runName(project + "-" + System.currentTimeMillis())
                .reconnectAttempts(5)
                .initialize()
                .doOnSuccess(v -> log.info("[studio] connected to {} (project={})", studioUrl, project))
                .doOnError(e -> log.warn("[studio] connect failed (continuing without studio): {}", e.toString()))
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .block();
    }

    @PreDestroy
    public void shutdown() {
        try {
            StudioManager.shutdown();
        } catch (Exception e) {
            log.debug("[studio] shutdown skipped: {}", e.toString());
        }
    }
}
