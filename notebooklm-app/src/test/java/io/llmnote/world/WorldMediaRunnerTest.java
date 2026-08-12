package io.llmnote.world;

import io.llmnote.llm.ZhipuMediaClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B0 单元测试:WorldMediaRunner 暂停机制(WorldSettings.mediaEnabled=false → 不调 ZhipuMediaClient)。 */
@ExtendWith(MockitoExtension.class)
class WorldMediaRunnerTest {

    @Mock ZhipuMediaClient mediaClient;
    @Mock AgentProductRepository productRepo;
    @Mock WorldSettingsRepository settingsRepo;
    @Mock WorldSettingsService settingsService;

    @Test
    void generateVideo_returnsImmediatelyWhenMediaDisabled() {
        WorldSettings s = new WorldSettings();
        s.setMediaEnabled(false);
        when(settingsService.get()).thenReturn(s);

        WorldMediaRunner runner = new WorldMediaRunner(mediaClient, productRepo, settingsService);

        runner.generateVideo(99L, 1L, LocalDate.of(2026, 8, 12), 0, "test prompt");

        verify(mediaClient, never()).submitVideo(anyString());
        verify(mediaClient, never()).pollVideo(anyString(), anyInt(), anyLong());
        verify(mediaClient, never()).downloadToWorld(anyString(), anyLong(), any(), anyInt(), anyString());
        verify(productRepo, never()).save(any());
    }

    @Test
    void generateVideo_proceedsWhenMediaEnabled() {
        WorldSettings s = new WorldSettings();
        s.setMediaEnabled(true);
        when(settingsService.get()).thenReturn(s);
        when(mediaClient.submitVideo(anyString())).thenReturn("task-1");
        when(mediaClient.pollVideo(anyString(), anyInt(), anyLong())).thenReturn("http://video.url/1.mp4");
        when(mediaClient.downloadToWorld(anyString(), anyLong(), any(), anyInt(), anyString())).thenReturn("world/1/2026-08-12/0.mp4");
        AgentProduct p = new AgentProduct();
        p.setId(99L);
        when(productRepo.findById(99L)).thenReturn(Optional.of(p));
        when(productRepo.save(any(AgentProduct.class))).thenAnswer(inv -> inv.getArgument(0));

        WorldMediaRunner runner = new WorldMediaRunner(mediaClient, productRepo, settingsService);

        runner.generateVideo(99L, 1L, LocalDate.of(2026, 8, 12), 0, "test prompt");

        verify(mediaClient).submitVideo("test prompt");
        verify(mediaClient).pollVideo("task-1", 50, 12_000L);
        verify(mediaClient).downloadToWorld("http://video.url/1.mp4", 1L, LocalDate.of(2026, 8, 12), 0, "mp4");
        assertThat(p.getContent()).isEqualTo("world/1/2026-08-12/0.mp4");
    }
}
