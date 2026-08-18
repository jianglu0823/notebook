package io.llmnote.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** B0 单元测试:WorldSettingsService 扩展字段后 update/getOrCreate 行为符合 c1 spec。
 *  注意:forumCron 已废弃(2026-08-17 起,论坛帖子改由 AutonomousRunner 行动触发而非 cron 定时)。 */
@ExtendWith(MockitoExtension.class)
class WorldSettingsServiceTest {

    @Mock WorldSettingsRepository repo;
    @InjectMocks WorldSettingsService service;

    @Test
    void getOrCreate_seedsMediaEnabledFalseByDefault() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldSettings s = service.get();

        assertThat(s.getMediaEnabled()).isFalse();
        assertThat(s.getForumEnabled()).isTrue();
        // 2026-08-17:ForumGenerationService 调优,默认 cap 2 → 3
        assertThat(s.getForumMaxPostsPerAgentPerDay()).isEqualTo(3);
    }

    @Test
    void update_setsAllConfiguredFields() {
        WorldSettings existing = new WorldSettings();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldSettings out = service.update(
                true, 300, "qwen-plus",
                true, false, 5,
                true);

        ArgumentCaptor<WorldSettings> cap = ArgumentCaptor.forClass(WorldSettings.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        WorldSettings saved = cap.getValue();
        assertThat(saved.getMediaEnabled()).isTrue();
        assertThat(saved.getForumEnabled()).isFalse();
        assertThat(saved.getForumMaxPostsPerAgentPerDay()).isEqualTo(5);
        assertThat(out).isSameAs(saved);
    }

    @Test
    void update_clampsForumMaxBetween1And10() {
        WorldSettings existing = new WorldSettings();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(null, null, null, null, null, 999, true);
        assertThat(existing.getForumMaxPostsPerAgentPerDay()).isEqualTo(10);

        service.update(null, null, null, null, null, 0, true);
        assertThat(existing.getForumMaxPostsPerAgentPerDay()).isEqualTo(1);
    }

    @Test
    void update_nullFieldsPreserveExistingValues() {
        WorldSettings existing = new WorldSettings();
        existing.setForumEnabled(true);
        existing.setForumMaxPostsPerAgentPerDay(3);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(false, null, null, null, null, null, true);

        assertThat(existing.getAutonomousEnabled()).isFalse();
        assertThat(existing.getForumEnabled()).isTrue();
        assertThat(existing.getForumMaxPostsPerAgentPerDay()).isEqualTo(3);
    }
}
