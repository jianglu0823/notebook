package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 智能体小世界里的一名「居民」:一个可配置人设、带长期记忆的 HarnessAgent。
 * 人设(persona)是该员工的 system prompt 基底;office_x/y 是家/基点,pos_x/y 是漫游当前坐标。
 * 智能体小镇模式下全局共享:owner_id 统一为 "world"。删除改为软删除(status=jailed 关小黑屋)。
 */
@Data
@Entity
@Table(name = "agent_employee")
public class AgentEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String avatar;

    @Column(length = 64)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String persona;

    @Column(length = 16)
    private String color;

    @Column(name = "office_x", nullable = false)
    private Integer officeX = 0;

    @Column(name = "office_y", nullable = false)
    private Integer officeY = 0;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 64)
    private String creator;

    @Column(length = 32)
    private String mood;

    @Column(name = "mood_emoji", length = 16)
    private String moodEmoji;

    @Column(length = 16)
    private String status = "active";

    @Column(name = "autonomous_active")
    private Boolean autonomousActive = true;

    @Column(name = "pos_x")
    private Double posX;

    @Column(name = "pos_y")
    private Double posY;

    /** 当前所在具名地点 key(见 {@link TownMap})。 */
    @Column(length = 32)
    private String location;

    /** 进小黑屋(软删除)时生成的第三人称「一生回顾」。 */
    @Column(name = "life_summary", columnDefinition = "TEXT")
    private String lifeSummary;

    // ---- 活世界:经济 / 职业 / 作息 / 家园 / 关系 / 生命 ----

    /** 货币余额。 */
    @Column(nullable = false)
    private Long coins = 0L;

    /** 职业类型 key(writer/singer/... 驱动产物);title 仍为展示名。 */
    @Column(length = 32)
    private String occupation;

    /** 作息模板 JSON:{"wake":7,"work":[9,12,14,18],"leisure":[19,22],"sleep":23}。 */
    @Column(name = "schedule_json", columnDefinition = "TEXT")
    private String scheduleJson;

    /** 家所在建筑 key。 */
    @Column(name = "home_place", length = 32)
    private String homePlace;

    /** 家园装饰 JSON:已购装饰 id 列表 + 等级。 */
    @Column(name = "home_decor_json", columnDefinition = "TEXT")
    private String homeDecorJson;

    /** 配偶居民 id。 */
    @Column(name = "spouse_id")
    private Long spouseId;

    /** 恋爱对象 id(未婚时)。 */
    @Column(name = "partner_id")
    private Long partnerId;

    /** 父母 id,逗号分隔(出生的孩子有值)。 */
    @Column(name = "parent_ids", length = 64)
    private String parentIds;

    /** 死亡日期(死亡即 jailed + lifeSummary)。 */
    @Column(name = "death_date")
    private LocalDate deathDate;

    /** 死因。 */
    @Column(name = "death_cause", length = 64)
    private String deathCause;

    /** 精力/健康,影响死亡概率与作息。 */
    @Column(nullable = false)
    private Integer energy = 100;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
