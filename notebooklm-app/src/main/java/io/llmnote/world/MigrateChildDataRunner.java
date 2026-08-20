package io.llmnote.world;

import io.llmnote.world.config.WorldChildrenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * town-children-rules W3:启动时一次性迁移历史孩子数据。
 *
 * <p>两段式:
 * <ol>
 *   <li>把所有 parentIds 非空的居民的 avatar 用 props.pickAvatar(name) 重写(确定性,幂等)。</li>
 *   <li>对 parentIds 行数 &gt; maxPerCouple 的夫妇,清掉 partners 的 pregnantSince / dueDate。</li>
 * </ol>
 *
 * <p>设计要点:
 * <ul>
 *   <li>在 EMF 准备好之后、HTTP 监听器启动之前执行(标准 Spring Boot CommandLineRunner 时机)。</li>
 *   <li>每行 try/catch:单行失败 WARN 继续,不阻塞启动(R-4 "partial failure does not crash startup")。</li>
 *   <li>pickAvatar 确定性 → 重启不重写(同 name 同池同 hash → 同 emoji),自然幂等。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MigrateChildDataRunner implements CommandLineRunner {

    private final AgentEmployeeRepository repo;
    private final WorldChildrenProperties props;

    @Override
    public void run(String... args) {
        List<AgentEmployee> children = repo.findByParentIdsIsNotNull();
        int reavatar = 0;
        Map<String, Integer> coupleChildCount = new HashMap<>();

        // 阶段①:重写所有 child 的 avatar,顺手按 parent_ids 聚合孩子数
        for (AgentEmployee child : children) {
            try {
                String newAvatar = props.pickAvatar(child.getName());
                child.setAvatar(newAvatar);
                repo.save(child);
                reavatar++;
                if (child.getParentIds() != null) {
                    coupleChildCount.merge(child.getParentIds(), 1, Integer::sum);
                }
            } catch (Exception e) {
                log.warn("migrate child {} avatar failed: {}", child.getId(), e.getClass().getSimpleName());
            }
        }

        // 阶段②:对过 cap 的夫妇清 partners 的 pregnantSince / dueDate
        int couplesCleared = 0;
        for (Map.Entry<String, Integer> e : coupleChildCount.entrySet()) {
            if (e.getValue() <= props.getMaxPerCouple()) continue;
            String parentIdsRaw = e.getKey();
            for (String pid : parentIdsRaw.split(",")) {
                String trimmed = pid == null ? "" : pid.trim();
                if (trimmed.isEmpty()) continue;
                Long parentId;
                try {
                    parentId = Long.parseLong(trimmed);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                repo.findById(parentId).ifPresent(parent -> {
                    if (parent.getPregnantSince() != null || parent.getDueDate() != null) {
                        parent.setPregnantSince(null);
                        parent.setDueDate(null);
                        try {
                            repo.save(parent);
                        } catch (Exception ex) {
                            log.warn("migrate parent {} clear pregnant failed: {}",
                                    parentId, ex.getClass().getSimpleName());
                        }
                    }
                });
            }
            couplesCleared++;
        }

        log.info("migrate children: reavatar={} couplesCleared={}", reavatar, couplesCleared);
    }
}
