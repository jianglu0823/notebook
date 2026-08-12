package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 全局世界设置(单行 id=1)的读写:自主行动总开关/间隔/模型。 */
@Service
@RequiredArgsConstructor
public class WorldSettingsService {

    private final WorldSettingsRepository repo;

    public WorldSettings get() {
        return repo.findById(1L).orElseGet(() -> {
            WorldSettings s = new WorldSettings();
            s.setId(1L);
            return repo.save(s);
        });
    }

    public WorldSettings update(Boolean autonomousEnabled, Integer intervalSeconds, String model,
                                Boolean mediaEnabled, Boolean forumEnabled,
                                String forumCron, Integer forumMaxPostsPerAgentPerDay,
                                boolean isAdmin) {
        WorldSettings s = get();
        if (autonomousEnabled != null) s.setAutonomousEnabled(autonomousEnabled);
        if (intervalSeconds != null) s.setIntervalSeconds(Math.max(1, Math.min(3600, intervalSeconds)));
        if (model != null && !model.isBlank()) {
            if (!isAdmin) throw new IllegalArgumentException("仅管理员可切换模型");
            s.setModel(model.trim());
        }
        if (mediaEnabled != null) s.setMediaEnabled(mediaEnabled);
        if (forumEnabled != null) s.setForumEnabled(forumEnabled);
        if (forumCron != null && !forumCron.isBlank()) s.setForumCron(forumCron.trim());
        if (forumMaxPostsPerAgentPerDay != null) {
            s.setForumMaxPostsPerAgentPerDay(Math.max(1, Math.min(10, forumMaxPostsPerAgentPerDay)));
        }
        return repo.save(s);
    }

    /** 持久化整体设置(供世界时钟推进后保存)。 */
    public WorldSettings save(WorldSettings s) {
        return repo.save(s);
    }
}
