package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 全局世界设置(单行 id=1):自主行动总开关、间隔、模型 + 小镇内在时钟。 */
@Data
@Entity
@Table(name = "agent_world_settings")
public class WorldSettings {

    @Id
    private Long id = 1L;

    @Column(name = "autonomous_enabled", nullable = false)
    private Boolean autonomousEnabled = false;

    @Column(name = "interval_seconds", nullable = false)
    private Integer intervalSeconds = 600;

    @Column(nullable = false, length = 32)
    private String model = "glm-4.7-flash";

    /** 小镇内在日期。 */
    @Column(name = "sim_date")
    private LocalDate simDate;

    /** 当日内在分钟数(0-1439,默认 480=08:00)。 */
    @Column(name = "sim_minute", nullable = false)
    private Integer simMinute = 480;

    /** 每个 autonomous tick 推进的内在分钟。 */
    @Column(name = "minutes_per_tick", nullable = false)
    private Integer minutesPerTick = 120;

    /** 当前季节(结算时更新)。 */
    @Column(length = 8)
    private String season;

    /** 当前天气(结算时更新)。 */
    @Column(length = 16)
    private String weather;

    /** 当前温度(℃,可空=未知,兼容旧行)。 */
    @Column(name = "temperature")
    private Integer temperature;

    /** 短片/画作等老创作调度开关(默认 false,2026-08-12 起暂停,代码保留)。 */
    @Column(name = "media_enabled", nullable = false)
    private Boolean mediaEnabled = false;

    /** 论坛总开关(默认 true)。 */
    @Column(name = "forum_enabled", nullable = false)
    private Boolean forumEnabled = true;

    /** 每个 agent 每天最多生成帖子数(LLM 决定实际 1~N,默认 3)。
     *  帖子<b>不再</b>用 cron 定时批量生成,改由 {@link io.llmnote.world.AutonomousRunner}
     *  在每次行动落库后调用 {@code ForumGenerationService.generateFromAction} 触发。
     *  2026-08-17:从 2 提到 3,配合类目硬推荐 + 每 tick 2 agent 把日产量从 ~5 提到 ~135。 */
    @Column(name = "forum_max_posts_per_agent_per_day", nullable = false)
    private Integer forumMaxPostsPerAgentPerDay = 3;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
