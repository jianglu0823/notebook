package io.llmnote.note;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "note")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", nullable = false)
    private Long notebookId;

    @Column(nullable = false)
    private String title;

    /** 笔记类型:RICHTEXT(富文本 HTML)/ MARKDOWN(Markdown 源码) */
    @Column(nullable = false)
    private String type;

    /** 正文:RICHTEXT 存 HTML,MARKDOWN 存 Markdown 源码 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
