package io.llmnote.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 长耗时编排的线程池隔离。
 *
 * <p>Spring Boot 默认所有 {@code @Async} 共用一个 {@code applicationTaskExecutor}
 * (核心 8 线程 + 无界队列):队列无界意味着线程数永远不会超过 8,新任务只会排队等空闲核心线程。
 * 当每日结算 / 会议 / 媒体生成 / 播客等长耗时 LLM 编排占满这 8 个线程后,新发起的沙盒任务会被
 * 无限期挂在队列里迟迟不执行,前端便一直卡在「推演中…」。
 *
 * <p>此处给沙盒单独一个专属线程池,使其永远不会被其它世界任务饿死;同时显式声明一个
 * {@code @Primary} 默认池,兜底其余不指名的 {@code @Async}(否则一旦出现自定义 Executor bean,
 * Spring Boot 的 {@code applicationTaskExecutor} 自动配置会退避,造成「多个 TaskExecutor」告警)。
 */
@Configuration
public class AsyncConfig {

    /** 默认线程池:所有未指名的 {@code @Async} 走这里。 */
    @Bean("taskExecutor")
    @Primary
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(16);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("async-");
        exec.initialize();
        return exec;
    }

    /** 沙盒快进专属线程池:{@link io.llmnote.world.SandboxRunner#run} 通过 {@code @Async("sandboxExecutor")} 使用。 */
    @Bean("sandboxExecutor")
    public Executor sandboxExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(32);
        exec.setThreadNamePrefix("sandbox-");
        exec.initialize();
        return exec;
    }
}
