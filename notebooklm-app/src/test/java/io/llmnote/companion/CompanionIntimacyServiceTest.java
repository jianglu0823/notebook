package io.llmnote.companion;

import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.dto.Milestone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanionIntimacyServiceTest {

    @Mock CompanionIntimacyRepository intimacyRepository;
    @Mock CompanionMessageRepository messageRepository;
    @Mock CompanionRepository companionRepository;
    CompanionProperties properties;
    CompanionIntimacyService service;

    @BeforeEach
    void setup() {
        properties = new CompanionProperties();
        properties.setIntimacyDailyCap(50);
        service = new CompanionIntimacyService(intimacyRepository, messageRepository,
                companionRepository, properties);
        when(companionRepository.findByIdAndUserId(anyLong(), any())).thenReturn(Optional.of(new Companion()));
    }

    @Test
    void firstInteraction_initializesScoreAndDays() {
        when(intimacyRepository.findById(7L)).thenReturn(Optional.empty());
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.intimacy().getDaysCount()).isEqualTo(1);
        assertThat(res.intimacy().getScore()).isEqualTo(1);
        assertThat(res.intimacy().getLastInteractDate()).isEqualTo(LocalDate.now());
        assertThat(res.milestones()).isEmpty();
    }

    @Test
    void sameDayIncrementsScoreWithCap() {
        CompanionIntimacy existing = new CompanionIntimacy();
        existing.setCompanionId(7L);
        existing.setScore(3);
        existing.setDaysCount(1);
        existing.setLastInteractDate(LocalDate.now());
        when(intimacyRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.intimacy().getDaysCount()).isEqualTo(1);
        assertThat(res.intimacy().getScore()).isEqualTo(4);
    }

    @Test
    void sameDayAtCap_doesNotIncrement() {
        CompanionIntimacy existing = new CompanionIntimacy();
        existing.setCompanionId(7L);
        // daysCount=1, score=51 表示今天已加 50 次
        existing.setScore(51);
        existing.setDaysCount(1);
        existing.setLastInteractDate(LocalDate.now());
        when(intimacyRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.intimacy().getScore()).isEqualTo(51);  // 不变
    }

    @Test
    void newDayIncrementsDaysCount() {
        CompanionIntimacy existing = new CompanionIntimacy();
        existing.setCompanionId(7L);
        existing.setScore(10);
        existing.setDaysCount(6);
        existing.setLastInteractDate(LocalDate.now().minusDays(1));
        when(intimacyRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.intimacy().getDaysCount()).isEqualTo(7);
        assertThat(res.intimacy().getScore()).isEqualTo(11);
    }

    @Test
    void milestone7_emitsSystemMessage() {
        CompanionIntimacy existing = new CompanionIntimacy();
        existing.setCompanionId(7L);
        existing.setScore(6);
        existing.setDaysCount(6);
        existing.setLastInteractDate(LocalDate.now().minusDays(1));
        when(intimacyRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.milestones()).extracting(Milestone::kind).containsExactly("milestone_7");
        assertThat(res.intimacy().getMilestone7Sent()).isTrue();

        ArgumentCaptor<CompanionMessage> msgCap = ArgumentCaptor.forClass(CompanionMessage.class);
        verify(messageRepository).save(msgCap.capture());
        assertThat(msgCap.getValue().getRole()).isEqualTo(io.llmnote.companion.enums.MessageRole.SYSTEM);
        assertThat(msgCap.getValue().getContent()).contains("7 天");
    }

    @Test
    void milestone_alreadySent_doesNotEmitAgain() {
        CompanionIntimacy existing = new CompanionIntimacy();
        existing.setCompanionId(7L);
        existing.setScore(8);
        existing.setDaysCount(8);
        existing.setLastInteractDate(LocalDate.now().minusDays(1));
        existing.setMilestone7Sent(true);
        when(intimacyRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.milestones()).isEmpty();
        verify(messageRepository, never()).save(any());
    }

    @Test
    void milestone30And100InSameTurn_bothFire() {
        CompanionIntimacy existing = new CompanionIntimacy();
        existing.setCompanionId(7L);
        existing.setScore(99);
        existing.setDaysCount(99);
        existing.setLastInteractDate(LocalDate.now().minusDays(1));
        when(intimacyRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(intimacyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var res = service.recordInteraction("u:1", 7L);

        assertThat(res.intimacy().getDaysCount()).isEqualTo(100);
        assertThat(res.milestones()).extracting(Milestone::kind)
                .containsExactly("milestone_7", "milestone_30", "milestone_100");
        verify(messageRepository, times(3)).save(any());
    }

    @Test
    void notOwned_throwsIllegalArgument() {
        when(companionRepository.findByIdAndUserId(7L, "u:1")).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recordInteraction("u:1", 7L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
