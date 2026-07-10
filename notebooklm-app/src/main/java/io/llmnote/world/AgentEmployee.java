package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 智能体小世界里的一名「居民」:一个可配置人设、带长期记忆的 HarnessAgent。
 * 人设(persona)是该员工的 system prompt 基底;office_x/y 是家/基点,pos_x/y 是漫游当前坐标。
 * 斯坦福小镇模式下全局共享:owner_id 统一为 "world"。删除改为软删除(status=jailed 关小黑屋)。
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

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
