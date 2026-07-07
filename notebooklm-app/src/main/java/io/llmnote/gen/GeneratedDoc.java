package io.llmnote.gen;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "generated_doc")
public class GeneratedDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", nullable = false)
    private Long notebookId;

    /** SUMMARY / STUDY_GUIDE / FAQ */
    @Column(nullable = false, length = 32)
    private String kind;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
