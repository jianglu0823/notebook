package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.auth.Principal;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙盒快进服务:预估 token/花费 → 一次性 gate → 落 {@link SandboxRun} 并交 {@link SandboxRunner} 异步跑。
 * 隔离于真实世界,只读取选定居民当前属性做种子快照。
 *
 * <p>一次性限制:非管理员按 {@code ownerId} 查已有任务则拒绝;管理员({@code jianglu})不限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    /** 每次运行固定 1 次报告 LLM 调用;里程碑数 ≈ years。经验 token 常数(qwen-turbo)。 */
    private static final long TOK_IN_PER_CALL = 600;
    private static final long TOK_OUT_PER_CALL = 400;

    private final SandboxRunRepository runRepo;
    private final SandboxEventRepository eventRepo;
    private final AgentEmployeeRepository employeeRepo;
    private final AgentEmployeeService employeeService;
    private final SandboxRunner runner;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    /** 预估结果。 */
    public record Estimate(int years, int seeds, int estLlmCalls,
                           long estInputTokens, long estOutputTokens, double estCostRmb) {}

    /** 预估某次快进的 LLM 调用/ token / 花费。 */
    public Estimate estimate(int years, List<Long> memberIds) {
        int y = clampYears(years);
        int seeds = memberIds == null ? 0 : memberIds.size();
        int llmCalls = 1; // 目前仅最终报告 1 次(规则引擎推进,不逐年调 LLM)
        long tin = llmCalls * TOK_IN_PER_CALL + (long) y * 40; // 年数带来的上下文增量
        long tout = llmCalls * TOK_OUT_PER_CALL;
        double cost = modelFactory.costRmb(modelFactory.defaultTextModel(), tin, tout);
        return new Estimate(y, seeds, llmCalls, tin, tout, cost);
    }

    /** 沙盒统一用默认免费模型,所有人无限体验,故永不判定「已用过」。 */
    public boolean alreadyUsed(Principal principal) {
        return false;
    }

    /** 发起一次沙盒运行:默认免费模型,所有人无限次 → 落库 → 异步跑。返回创建的 run(PENDING/RUNNING)。 */
    public SandboxRun start(Principal principal, String title, int years, List<Long> memberIds) {
        if (principal == null) throw new IllegalArgumentException("未鉴权");
        if (memberIds == null || memberIds.isEmpty()) throw new IllegalArgumentException("请至少选择一名居民");
        int y = clampYears(years);

        List<AgentEmployee> members = new ArrayList<>();
        for (Long id : memberIds) {
            AgentEmployee e = employeeRepo.findById(id).orElse(null);
            if (e != null) members.add(e);
        }
        if (members.isEmpty()) throw new IllegalArgumentException("选中的居民不存在");

        Estimate est = estimate(y, memberIds);
        SandboxRun run = new SandboxRun();
        run.setOwnerId(principal.ownerId());
        run.setTitle(title == null || title.isBlank() ? (y + " 年推演") : title.trim());
        run.setYears(y);
        run.setMemberIds(toJson(memberIds));
        run.setStatus("PENDING");
        run.setEstCostRmb(java.math.BigDecimal.valueOf(est.estCostRmb()));
        SandboxRun saved = runRepo.save(run);

        runner.run(saved.getId(), members, y, LocalDate.now());
        return saved;
    }

    public List<SandboxRun> list(Principal principal) {
        if (isAdmin(principal)) return runRepo.findTop50ByOrderByIdDesc();
        return principal == null ? List.of() : runRepo.findByOwnerIdOrderByIdDesc(principal.ownerId());
    }

    public SandboxRun get(Long id) {
        return runRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("沙盒任务不存在"));
    }

    public List<SandboxEvent> events(Long runId) {
        return eventRepo.findByRunIdOrderBySeqAsc(runId);
    }

    private boolean isAdmin(Principal p) { return employeeService.isAdmin(p); }

    private static int clampYears(int y) { return Math.max(1, Math.min(50, y)); }

    private String toJson(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception ex) { return "[]"; }
    }

    /**
     * 启动时回收孤儿任务:上次进程若在推演途中被重启/崩溃,状态会永远卡在 PENDING/RUNNING
     * (异步线程随进程消亡,来不及写终态),前端便会无限轮询「推演中…」。此处一次性置为 FAILED。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileStaleRuns() {
        List<SandboxRun> stale = runRepo.findByStatusIn(List.of("PENDING", "RUNNING"));
        for (SandboxRun r : stale) {
            r.setStatus("FAILED");
            r.setErrorMsg("服务重启中断,推演已终止,请重新发起");
            runRepo.save(r);
        }
        if (!stale.isEmpty()) log.info("启动回收沙盒孤儿任务 {} 个 → FAILED", stale.size());
    }
}
