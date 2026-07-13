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

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
