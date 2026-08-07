package io.llmnote.companion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 伴侣亲密度(每伴侣一行,companion_id 为主键)。
 * 规则化计数(零 LLM):每日用户消息累计 +1,日上限 50;跨日 daysCount+1;命中 7/30/100 天 milestone 追加 system 消息。
 */
@Data
@Entity
@Table(name = "companion_intimacy")
public class CompanionIntimacy {

    @Id
    @Column(name = "companion_id")
    private Long companionId;

    @Column(nullable = false)
    private Integer score = 0;

    @Column(name = "days_count", nullable = false)
    private Integer daysCount = 0;

    @Column(name = "last_interact_date")
    private LocalDate lastInteractDate;

    @Column(name = "milestone_7_sent", nullable = false)
    private Boolean milestone7Sent = false;

    @Column(name = "milestone_30_sent", nullable = false)
    private Boolean milestone30Sent = false;

    @Column(name = "milestone_100_sent", nullable = false)
    private Boolean milestone100Sent = false;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
