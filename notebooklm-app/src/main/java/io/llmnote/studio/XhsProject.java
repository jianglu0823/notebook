package io.llmnote.studio;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 小红书文案生成工作流项目:一行贯穿 方向→标题→素材→风格文案→配图 全流程。
 * status 表示工作流进度:NEW → TITLES_DONE → RESEARCH_DONE → COPY_DONE → IMAGES_DONE;任一异步阶段可 FAILED。
 * 各步为独立异步任务,前端逐段轮询。publish_status 为发布管理状态(本地草稿/待发/已发)。
 */
@Data
@Entity
@Table(name = "xhs_project")
public class XhsProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 32)
    private String status = "NEW";

    /** 用户输入/选择的方向 */
    @Column(nullable = false, length = 255)
    private String direction;

    /** 候选标题 JSON 数组 */
    @Column(name = "title_options", columnDefinition = "TEXT")
    private String titleOptions;

    /** 用户选定的标题 */
    @Column(name = "chosen_title", length = 512)
    private String chosenTitle;

    /** 联网搜集的长文素材 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String research;

    /** 风格:ZHONGCAO/DUSHE/GANHUO/ZHIYU */
    @Column(length = 32)
    private String style;

    /** 生成的小红书文案 */
    @Column(name = "copy_text", columnDefinition = "MEDIUMTEXT")
    private String copyText;

    /** 配图本地路径 JSON 数组 */
    @Column(name = "image_paths", columnDefinition = "TEXT")
    private String imagePaths;

    /** 成片 mp4 路径(imageDir 下 <owner>/<id>/video.mp4) */
    @Column(name = "video_path", length = 512)
    private String videoPath;

    /** 视频阶段细分状态:PENDING/RENDERING/DONE/FAILED(可空) */
    @Column(name = "video_status", length = 32)
    private String videoStatus;

    /** 发布管理:DRAFT/READY/PUBLISHED */
    @Column(name = "publish_status", nullable = false, length = 32)
    private String publishStatus = "DRAFT";

    @Column(name = "error_msg", length = 2048)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
