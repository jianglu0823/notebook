package io.llmnote.audio;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "podcast")
public class Podcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", nullable = false)
    private Long notebookId;

    @Column(length = 512)
    private String title;

    /** 双主持人对话脚本(JSON) */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String script;

    @Column(name = "audio_path", length = 1024)
    private String audioPath;

    /** PENDING/SCRIPTING/SYNTHESIZING/DONE/FAILED */
    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "error_msg", length = 2048)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
