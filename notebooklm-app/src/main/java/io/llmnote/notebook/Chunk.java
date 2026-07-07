package io.llmnote.notebook;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chunk")
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "notebook_id", nullable = false)
    private Long notebookId;

    @Column(name = "note_id")
    private Long noteId;

    @Column(nullable = false)
    private Integer seq;

    /** Milvus 主键 */
    @Column(name = "vector_id")
    private String vectorId;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** 出处定位:如 page=3 / offset=1024 */
    private String locator;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
