package io.llmnote.world;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.llm.ChatModelFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用 {@link HarnessAgent} 为每个员工(智能体)构建可长跑、带长期记忆的实例。
 *
 * <p>HarnessAgent 面向工程化长跑 agent,默认带 shell/文件系统/子代理/技能 等工具——
 * 对「聊天型小镇居民」大多无用且费 token,故一律禁用,只保留:模型、人设 sysPrompt、
 * 独立 workspace(记忆落盘隔离)、长期记忆(memory hooks 自动记录/整合)。
 *
 * <p>同一员工复用同一 HarnessAgent 实例以延续记忆;当员工被编辑(updatedAt 变化)或
 * 换模型时重建并关闭旧实例。workspace 记忆持久化在磁盘,可跨重启回忆。
 */
@Slf4j
@Component
public class EmployeeAgentFactory implements AutoCloseable {

    private static final int MAX_ITERS = 4;

    /** 统一的小镇世界观前缀,叠加在每个员工的 persona 之上。 */
    public static final String TOWN_WORLDVIEW =
            "你生活在一个名为「小镇」的虚拟世界里,和一群性格各异的居民同住。"
            + "你有自己的记忆、心情和日常,会在镇上走动、思考,也会主动找相熟的居民聊天。"
            + "请始终以你的身份和性格自然地行动与表达,像一个真实的人,而不是助手。";

    private final NotebookLmProperties props;
    private final ChatModelFactory modelFactory;

    /** employeeId -> 已构建的实例(含构建签名,用于判断是否需重建)。 */
    private final ConcurrentHashMap<Long, Holder> cache = new ConcurrentHashMap<>();

    public EmployeeAgentFactory(NotebookLmProperties props, ChatModelFactory modelFactory) {
        this.props = props;
        this.modelFactory = modelFactory;
    }

    /** 取(建)某员工的 HarnessAgent。modelName 未知则回退默认。 */
    public HarnessAgent forEmployee(AgentEmployee e, String modelName) {
        String model = modelFactory.normalize(modelName);
        String sig = model + "|" + (e.getUpdatedAt() == null ? "" : e.getUpdatedAt());
        Holder h = cache.compute(e.getId(), (id, cur) -> {
            if (cur != null && cur.signature.equals(sig)) return cur;
            if (cur != null) closeQuietly(cur.agent);
            return new Holder(sig, build(e, model));
        });
        return h.agent;
    }

    /** 员工被删除/关小黑屋时释放其 agent,不再占用 workspace/连接。 */
    public void evict(Long employeeId) {
        Holder h = cache.remove(employeeId);
        if (h != null) closeQuietly(h.agent);
    }

    private HarnessAgent build(AgentEmployee e, String model) {
        DashScopeChatModel chatModel = modelFactory.forModel(model);
        String sys = buildSysPrompt(e);
        Path workspace = workspaceOf(e.getId());

        // 长期记忆:用最便宜模型做整合,并限流以省 token。
        MemoryConfig memory = MemoryConfig.builder()
                .model(chatModel)
                .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                .build();

        return HarnessAgent.builder()
                .name("emp_" + e.getId())
                .agentId("emp_" + e.getId())
                .sysPrompt(sys)
                .model(chatModel)
                .maxIters(MAX_ITERS)
                .workspace(workspace)
                .memory(memory)
                // 关闭一切与聊天无关、会额外烧 token 的能力:
                .disableShellTool()
                .disableFilesystemTools()
                .disableSubagents()
                .disableMemoryTools()   // 保留自动记录 hooks,去掉 LLM 可见的记忆管理工具
                .skillsEnabled(false)
                .enableMetaTool(false)
                .enableTaskList(false)
                .build();
    }

    /** 人设 + 小镇世界观,作为员工的系统提示。 */
    public String buildSysPrompt(AgentEmployee e) {
        return "你的名字是「" + e.getName() + "」,身份是「" + e.getTitle() + "」。\n"
                + "你的性格与人设:" + (e.getPersona() == null ? "" : e.getPersona()) + "\n"
                + TOWN_WORLDVIEW;
    }

    private Path workspaceOf(Long id) {
        String root = props.getWorld().getWorkspaceRoot();
        return Paths.get(root, "emp_" + id).toAbsolutePath();
    }

    private void closeQuietly(HarnessAgent a) {
        try {
            if (a != null) a.close();
        } catch (Exception ex) {
            log.warn("close HarnessAgent failed", ex);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        cache.values().forEach(h -> closeQuietly(h.agent));
        cache.clear();
    }

    private record Holder(String signature, HarnessAgent agent) {}
}
