package io.llmnote.notebook;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "source")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", nullable = false)
    private Long notebookId;

    @Column(name = "note_id")
    private Long noteId;

    @Column(nullable = false)
    private String name;

    /** PDF/DOCX/WEB/IMAGE/AUDIO/TEXT/NOTE_BODY */
    @Column(nullable = false)
    private String type;

    @Column(name = "storage_path")
    private String storagePath;

    /** PENDING/PARSING/EMBEDDING/DONE/FAILED */
    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "error_msg")
    private String errorMsg;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
