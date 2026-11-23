package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                                Integer forumMaxPostsPerAgentPerDay,
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
        if (forumMaxPostsPerAgentPerDay != null) {
            s.setForumMaxPostsPerAgentPerDay(Math.max(1, Math.min(10, forumMaxPostsPerAgentPerDay)));
        }
        return repo.save(s);
    }

    /** 只更新世界时钟字段，避免日结期间覆盖并发修改的开关和模型。 */
    @Transactional
    public void saveClock(WorldSettings s) {
        repo.updateClock(s.getId(), s.getSimDate(), s.getSimMinute(),
                s.getSeason(), s.getWeather(), s.getTemperature());
    }
}
