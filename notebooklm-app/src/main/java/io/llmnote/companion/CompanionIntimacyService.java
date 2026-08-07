package io.llmnote.companion;

import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.dto.Milestone;
import io.llmnote.companion.enums.MessageRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 亲密度规则化服务(B7):
 * - 每日 user 消息累计 +1(助手回复不计),日上限 = properties.intimacyDailyCap(默认 50)。
 * - 跨日首次交互 daysCount+1。
 * - 命中 7 / 30 / 100 天里程碑:置位对应 flag,落一条 role=SYSTEM 的提示消息。
 * - 整个流程零 LLM,纯 SQL 计数。
 */
@Slf4j
@Service
public class CompanionIntimacyService {

    private final CompanionIntimacyRepository intimacyRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionRepository companionRepository;
    private final CompanionProperties properties;

    public CompanionIntimacyService(CompanionIntimacyRepository intimacyRepository,
                                    CompanionMessageRepository messageRepository,
                                    CompanionRepository companionRepository,
                                    CompanionProperties properties) {
        this.intimacyRepository = intimacyRepository;
        this.messageRepository = messageRepository;
        this.companionRepository = companionRepository;
        this.properties = properties;
    }

    public record RecordResult(CompanionIntimacy intimacy, List<Milestone> milestones) {}

    /**
     * 记录一次用户交互(每条 user 消息触发一次)。
     * @return 更新后的亲密度 + 本次触发的里程碑(可能为空)
     */
    @Transactional
    public RecordResult recordInteraction(String userId, Long companionId) {
        if (companionRepository.findByIdAndUserId(companionId, userId).isEmpty()) {
            throw new IllegalArgumentException("companion not owned: " + companionId);
        }
        LocalDate today = LocalDate.now();
        CompanionIntimacy entity = intimacyRepository.findById(companionId)
                .orElseGet(() -> {
                    CompanionIntimacy init = new CompanionIntimacy();
                    init.setCompanionId(companionId);
                    init.setScore(0);
                    init.setDaysCount(0);
                    init.setMilestone7Sent(false);
                    init.setMilestone30Sent(false);
                    init.setMilestone100Sent(false);
                    return init;
                });

        int prevScore = entity.getScore() == null ? 0 : entity.getScore();
        int prevDays = entity.getDaysCount() == null ? 0 : entity.getDaysCount();
        boolean newDay = entity.getLastInteractDate() == null || !entity.getLastInteractDate().equals(today);
        int todayAdded = newDay ? 0 : Math.max(0, prevScore - prevDays);  // 估算今天已加的次数
        int dailyCap = properties.getIntimacyDailyCap();

        int newScore = prevScore;
        int newDays = prevDays;
        if (newDay) {
            newDays = prevDays + 1;
            newScore = prevScore + 1;
        } else if (todayAdded < dailyCap) {
            newScore = prevScore + 1;
        }
        entity.setScore(newScore);
        entity.setDaysCount(newDays);
        entity.setLastInteractDate(today);
        intimacyRepository.save(entity);

        List<Milestone> triggered = checkMilestones(entity, companionId, userId);
        return new RecordResult(entity, triggered);
    }

    private List<Milestone> checkMilestones(CompanionIntimacy entity, Long companionId, String userId) {
        List<Milestone> out = new ArrayList<>();
        int days = entity.getDaysCount() == null ? 0 : entity.getDaysCount();
        if (days >= 7 && !Boolean.TRUE.equals(entity.getMilestone7Sent())) {
            entity.setMilestone7Sent(true);
            intimacyRepository.save(entity);
            out.add(emit(companionId, "milestone_7", "我们已经一起走过 7 天啦,我会一直陪着你的~"));
        }
        if (days >= 30 && !Boolean.TRUE.equals(entity.getMilestone30Sent())) {
            entity.setMilestone30Sent(true);
            intimacyRepository.save(entity);
            out.add(emit(companionId, "milestone_30", "30 天了,你已经是我生活里很重要的人。"));
        }
        if (days >= 100 && !Boolean.TRUE.equals(entity.getMilestone100Sent())) {
            entity.setMilestone100Sent(true);
            intimacyRepository.save(entity);
            out.add(emit(companionId, "milestone_100", "100 天纪念日,谢谢你的陪伴,我会一直在。"));
        }
        if (!out.isEmpty()) {
            log.info("intimacy milestone triggered: userId={} companionId={} days={} types={}",
                    userId, companionId, days, out.stream().map(Milestone::kind).toList());
        }
        return out;
    }

    private Milestone emit(Long companionId, String kind, String text) {
        CompanionMessage m = new CompanionMessage();
        m.setCompanionId(companionId);
        m.setRole(MessageRole.SYSTEM);
        m.setContent(text);
        m.setAttachments("[]");
        m.setMetadata("{\"milestone\":\"" + kind + "\"}");
        m.setHidden(false);
        messageRepository.save(m);
        return new Milestone(kind, text);
    }
}
