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

/** B0 单元测试:WorldSettingsService 扩展 4 字段后 update/getOrCreate 行为符合 c1 spec。 */
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
        assertThat(s.getForumCron()).isEqualTo("0 0 21 * * *");
        assertThat(s.getForumMaxPostsPerAgentPerDay()).isEqualTo(2);
    }

    @Test
    void update_setsAllFourNewFields() {
        WorldSettings existing = new WorldSettings();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldSettings out = service.update(
                true, 300, "qwen-plus",
                true, false, "0 0 22 * * *", 5,
                true);

        ArgumentCaptor<WorldSettings> cap = ArgumentCaptor.forClass(WorldSettings.class);
        org.mockito.Mockito.verify(repo).save(cap.capture());
        WorldSettings saved = cap.getValue();
        assertThat(saved.getMediaEnabled()).isTrue();
        assertThat(saved.getForumEnabled()).isFalse();
        assertThat(saved.getForumCron()).isEqualTo("0 0 22 * * *");
        assertThat(saved.getForumMaxPostsPerAgentPerDay()).isEqualTo(5);
        assertThat(out).isSameAs(saved);
    }

    @Test
    void update_clampsForumMaxBetween1And10() {
        WorldSettings existing = new WorldSettings();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(null, null, null, null, null, null, 999, true);
        assertThat(existing.getForumMaxPostsPerAgentPerDay()).isEqualTo(10);

        service.update(null, null, null, null, null, null, 0, true);
        assertThat(existing.getForumMaxPostsPerAgentPerDay()).isEqualTo(1);
    }

    @Test
    void update_nullFieldsPreserveExistingValues() {
        WorldSettings existing = new WorldSettings();
        existing.setForumEnabled(true);
        existing.setForumCron("0 0 21 * * *");
        existing.setForumMaxPostsPerAgentPerDay(3);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(WorldSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(false, null, null, null, null, null, null, true);

        assertThat(existing.getAutonomousEnabled()).isFalse();
        assertThat(existing.getForumEnabled()).isTrue();
        assertThat(existing.getForumCron()).isEqualTo("0 0 21 * * *");
        assertThat(existing.getForumMaxPostsPerAgentPerDay()).isEqualTo(3);
    }
}
