package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 货币流水(精简:只记较大额的收支)。 */
@Data
@Entity
@Table(name = "agent_transaction")
public class AgentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "sim_date")
    private LocalDate simDate;

    /** 变动额(正收入/负支出)。 */
    @Column(nullable = false)
    private Long delta = 0L;

    /** 变动后余额。 */
    @Column(nullable = false)
    private Long balance = 0L;

    @Column(length = 64)
    private String reason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
