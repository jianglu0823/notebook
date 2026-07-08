package io.llmnote.news;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新闻收集任务:用户选方向 → 大模型联网搜索最新动态 → 整理成一条笔记。
 * 异步执行,状态 PENDING → GENERATING → DONE/FAILED;完成后 notebookId/noteId 指向生成的笔记。
 */
@Data
@Entity
@Table(name = "news_job")
public class NewsJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    /** 新闻方向:预设分类中文名或用户自定义关键词 */
    @Column(nullable = false, length = 255)
    private String topic;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "notebook_id")
    private Long notebookId;

    @Column(name = "note_id")
    private Long noteId;

    @Column(name = "error_msg", length = 2048)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
