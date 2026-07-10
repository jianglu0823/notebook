package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 全局世界设置(单行 id=1):自主行动总开关、间隔、模型。 */
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
    private String model = "qwen-turbo";

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
