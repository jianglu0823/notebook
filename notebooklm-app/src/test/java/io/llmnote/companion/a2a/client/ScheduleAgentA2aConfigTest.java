package io.llmnote.companion.a2a.client;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * c5 — ScheduleAgentA2aConfig 装配单测。
 * <p>
 * 真实端到端(nblm-app 起 + 调 schedule-agent 收事件)在 {@code CompanionA2AEndToEndTest}。
 * 本测试只验证配置类的 bean 装配、@ConditionalOnProperty 守护、A2aAgent 注入 Nacos 解析器。
 */
class ScheduleAgentA2aConfigTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    @Test
    @DisplayName("默认 enabled=false → 不装配 A2aAgent / NacosAgentCardResolver(避免本机无 Nacos 时启动失败)")
    void disabledByDefault_noBeansLoaded() {
        context = new AnnotationConfigApplicationContext();
        // 模拟 application.yml 默认:enabled 不设 → false
        context.register(ScheduleAgentA2aConfig.class);
        context.refresh();

        assertFalse(context.containsBean("scheduleAgent"), "A2aAgent should NOT be loaded when enabled=false");
        assertFalse(context.containsBean("scheduleAgentCardResolver"),
                "NacosAgentCardResolver should NOT be loaded when enabled=false");
    }

    @Test
    @DisplayName("enabled=true → A2aAgent + NacosAgentCardResolver 装配,且 A2aAgent.name = 配置值")
    void enabled_loadsBeans_andAgentNameMatchesConfig() throws NacosException {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test",
                        java.util.Map.of(
                                "agentscope.a2a.client.enabled", "true",
                                "agentscope.a2a.client.agent-name", "schedule-agent-v1",
                                "agentscope.a2a.client.nacos.server-addr", "127.0.0.1:8848",
                                "agentscope.a2a.client.nacos.username", "nacos",
                                "agentscope.a2a.client.nacos.password", "nacos",
                                "agentscope.a2a.client.nacos.namespace", "public")));
        context.register(ScheduleAgentA2aConfig.class);
        context.refresh();

        assertTrue(context.containsBean("scheduleAgent"), "A2aAgent should be loaded when enabled=true");
        assertTrue(context.containsBean("scheduleAgentCardResolver"),
                "NacosAgentCardResolver should be loaded when enabled=true");

        A2aAgent agent = context.getBean("scheduleAgent", A2aAgent.class);
        assertNotNull(agent);
        // A2aAgent extends AgentBase,name() 来自 builder.name(...)
        assertEquals("schedule-agent-v1", agent.getName(),
                "A2aAgent.name should match agentscope.a2a.client.agent-name");
    }

    @Test
    @DisplayName("A2aAgent 持有 NacosAgentCardResolver 实例(R-c5.1:客户端用 Nacos 解析,不是固定 URL)")
    void a2aAgent_usesNacosResolver() throws NacosException {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test",
                        java.util.Map.of(
                                "agentscope.a2a.client.enabled", "true",
                                "agentscope.a2a.client.nacos.server-addr", "127.0.0.1:8848",
                                "agentscope.a2a.client.nacos.username", "nacos",
                                "agentscope.a2a.client.nacos.password", "nacos",
                                "agentscope.a2a.client.nacos.namespace", "public")));
        context.register(ScheduleAgentA2aConfig.class);
        context.refresh();

        AgentCardResolver resolver = context.getBean("scheduleAgentCardResolver", AgentCardResolver.class);
        assertNotNull(resolver);
        assertTrue(resolver instanceof NacosAgentCardResolver,
                "scheduleAgentCardResolver must be NacosAgentCardResolver, got " + resolver.getClass().getName());

        A2aAgent agent = context.getBean("scheduleAgent", A2aAgent.class);
        // 用反射读 AgentBase 内部的 resolver 字段,确认 A2aAgent 持有我们注入的实例
        Field f = findResolverField(agent.getClass());
        assertNotNull(f, "A2aAgent should have a resolver field");
        f.setAccessible(true);
        Object heldResolver;
        try {
            heldResolver = f.get(agent);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        assertNotNull(heldResolver, "A2aAgent.resolver should be set by builder");
        assertTrue(heldResolver instanceof NacosAgentCardResolver,
                "A2aAgent.resolver should be NacosAgentCardResolver, got " + heldResolver.getClass().getName());
        // 引用同一对象
        assertTrue(heldResolver == resolver,
                "A2aAgent should hold the same NacosAgentCardResolver instance as the bean (singleton)");
    }

    @Test
    @DisplayName("scheduleAgentCardResolver Bean 是单例(@Bean 默认单例)")
    void resolverIsSingleton() throws NacosException {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test",
                        java.util.Map.of(
                                "agentscope.a2a.client.enabled", "true",
                                "agentscope.a2a.client.nacos.server-addr", "127.0.0.1:8848",
                                "agentscope.a2a.client.nacos.username", "nacos",
                                "agentscope.a2a.client.nacos.password", "nacos",
                                "agentscope.a2a.client.nacos.namespace", "public")));
        context.register(ScheduleAgentA2aConfig.class);
        context.refresh();

        AgentCardResolver r1 = context.getBean("scheduleAgentCardResolver", AgentCardResolver.class);
        AgentCardResolver r2 = context.getBean("scheduleAgentCardResolver", AgentCardResolver.class);
        assertTrue(r1 == r2, "NacosAgentCardResolver should be singleton");
    }

    /**
     * 反射查找 A2aAgent 内部的 resolver 字段(可能是 agentCardResolver / resolver / 类似命名)。
     * 不假设具体字段名,遍历父类。
     */
    private static Field findResolverField(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (AgentCardResolver.class.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
