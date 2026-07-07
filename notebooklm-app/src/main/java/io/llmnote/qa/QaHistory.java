package io.llmnote.qa;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "qa_history")
public class QaHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", nullable = false)
    private Long notebookId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String answer;

    /** 引用的 chunk 列表(JSON 字符串) */
    @Column(columnDefinition = "JSON")
    private String citations;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
