package io.llmnote.world;

import io.llmnote.llm.ZhipuMediaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 短片(CogVideoX)异步生成器。独立 bean 让 {@code @Async} 生效(同 bean 内自调用会失效)。
 * 每日结算时先落一条占位的视频产物(kind=video, content 空),再交给本 runner 后台
 * submit→poll→download→回填 content 相对路径。视频分钟级,故必须异步,避免阻塞结算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldMediaRunner {

    /** 轮询上限与间隔:最多约 10 分钟(50 次 × 12s)。 */
    private static final int MAX_POLLS = 50;
    private static final long POLL_INTERVAL_MS = 12_000;

    private final ZhipuMediaClient mediaClient;
    private final AgentProductRepository productRepo;

    /** 后台生成短片并回填产物 content。失败则保留占位(前端显示「生成失败」)。 */
    @Async
    public void generateVideo(Long productId, Long agentId, LocalDate date, int seq, String prompt) {
        try {
            String taskId = mediaClient.submitVideo(prompt);
            if (taskId == null) { log.warn("短片提交失败 productId={}", productId); return; }
            String url = mediaClient.pollVideo(taskId, MAX_POLLS, POLL_INTERVAL_MS);
            if (url == null) { log.warn("短片生成失败/超时 productId={} taskId={}", productId, taskId); return; }
            String rel = mediaClient.downloadToWorld(url, agentId, date, seq, "mp4");
            if (rel == null) { log.warn("短片落盘失败 productId={}", productId); return; }
            AgentProduct p = productRepo.findById(productId).orElse(null);
            if (p == null) { log.warn("短片回填时产物已不存在 productId={}", productId); return; }
            p.setContent(rel);
            productRepo.save(p);
            log.info("短片完成 productId={} agentId={} path={}", productId, agentId, rel);
        } catch (Exception e) {
            log.warn("短片生成异常 productId={}: {}", productId, e.getMessage());
        }
    }
}
