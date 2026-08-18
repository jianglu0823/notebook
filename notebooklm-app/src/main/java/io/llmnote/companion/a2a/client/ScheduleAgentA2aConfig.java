package io.llmnote.companion.a2a.client;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.llmnote.companion.a2a.ScheduleAgentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * nblm-app 端的 A2A 客户端装配:用 NacosAgentCardResolver 从 Nacos 3.x AI Registry
 * 拉 schedule-agent 的 AgentCard,然后构造 A2aAgent 调它。
 * <p>
 * 由 {@code agentscope.a2a.client.enabled=true} 守护 — 默认关,
 * 避免本机没有 Nacos 3.x 时 A2aAgent 启动失败。B5 切到真用时设 env 开启。
 * <p>
 * 设计:
 * <ul>
 *   <li>{@code scheduleAgentCardResolver} — NacosAgentCardResolver,单例
 *   <li>{@code scheduleAgent} — A2aAgent,持有 resolver,name=schedule-agent-v1
 *   <li>{@code scheduleAgentClient} — ScheduleAgentClient,把 A2aAgent 包成 @Tool,
 *       供 CompanionChatService ReAct 循环调用。与上面两个 bean 同配置类装配,
 *       避免此前 ScheduleAgentClient 标 @Component + @ConditionalOnBean 时跨配置类
 *       评估顺序问题导致永远不创建。
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "agentscope.a2a.client", name = "enabled", havingValue = "true")
public class ScheduleAgentA2aConfig {

    private static final Logger log = LoggerFactory.getLogger(ScheduleAgentA2aConfig.class);

    @Bean(name = "scheduleAgentCardResolver", destroyMethod = "")
    public AgentCardResolver scheduleAgentCardResolver(
            @Value("${agentscope.a2a.client.nacos.server-addr:127.0.0.1:8848}") String serverAddr,
            @Value("${agentscope.a2a.client.nacos.username:nacos}") String username,
            @Value("${agentscope.a2a.client.nacos.password:nacos}") String password,
            @Value("${agentscope.a2a.client.nacos.namespace:public}") String namespace) throws NacosException {
        Properties props = new Properties();
        props.put("serverAddr", serverAddr);
        props.put("username", username);
        props.put("password", password);
        props.put("namespace", namespace);
        log.info("ScheduleAgentA2aConfig: NacosAgentCardResolver init serverAddr={} namespace={}", serverAddr, namespace);
        return new NacosAgentCardResolver(props);
    }

    @Bean(name = "scheduleAgent")
    public A2aAgent scheduleAgent(
            @Value("${agentscope.a2a.client.agent-name:schedule-agent-v1}") String agentName,
            AgentCardResolver scheduleAgentCardResolver) {
        log.info("ScheduleAgentA2aConfig: A2aAgent(name={}) created with NacosAgentCardResolver", agentName);
        return A2aAgent.builder()
                .name(agentName)
                .agentCardResolver(scheduleAgentCardResolver)
                .build();
    }

    @Bean(name = "scheduleAgentClient")
    public ScheduleAgentClient scheduleAgentClient(@org.springframework.beans.factory.annotation.Qualifier("scheduleAgent") A2aAgent scheduleAgent) {
        log.info("ScheduleAgentA2aConfig: ScheduleAgentClient wired to A2aAgent(name={})", scheduleAgent.getName());
        return new ScheduleAgentClient(scheduleAgent);
    }
}
