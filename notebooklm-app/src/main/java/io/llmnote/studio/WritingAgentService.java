package io.llmnote.studio;

import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 方案7:多智能体协同迭代写作的入口(CRUD + 校验)。作者⇄核查⇄主编三个 ReActAgent
 * 迭代收敛的编排本体拆到独立 bean {@link WritingRunner} —— 是为了让 {@code @Async} 生效
 * (Spring @Async 走代理,同类自调用会失效,退化成同步,阻塞发起协作的 HTTP 请求)。
 * 全部按 ownerId 隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritingAgentService {

    private final ChatModelFactory modelFactory;
    private final WritingProjectRepository repo;
    private final WritingRunner runner;

    public WritingProject create(String ownerId, String topic, String genre, Integer maxRounds, String model) {
        WritingProject p = new WritingProject();
        p.setOwnerId(ownerId);
        p.setTopic(topic == null || topic.isBlank() ? "自由创作" : topic.trim());
        p.setGenre(normalizeGenre(genre));
        int rounds = maxRounds == null ? 3 : Math.max(1, Math.min(5, maxRounds));
        p.setMaxRounds(rounds);
        p.setModel(modelFactory.normalize(model));
        p.setStatus("PENDING");
        p = repo.save(p);
        runner.run(p.getId());
        return p;
    }

    public List<WritingProject> list(String ownerId) {
        return repo.findByOwnerIdOrderByIdDesc(ownerId);
    }

    public WritingProject get(Long id, String ownerId) {
        return owned(id, ownerId);
    }

    public void delete(Long id, String ownerId) {
        repo.delete(owned(id, ownerId));
    }

    private String normalizeGenre(String s) {
        String u = s == null ? "" : s.trim().toUpperCase();
        return switch (u) {
            case "ARTICLE", "STORY", "REVIEW", "SCRIPT" -> u;
            default -> "ARTICLE";
        };
    }

    private WritingProject owned(Long id, String ownerId) {
        return repo.findById(id)
                .filter(p -> p.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("writing project not found: " + id));
    }
}
