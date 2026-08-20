package io.llmnote.studio;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短剧 3 版候选脚本:用户从 3 行里挑 1 版,可在线编辑 narration/scenes/characterProfile/styleLock,
 * 选中的版(draft.id == xhs_project.chosen_draft_id)被 genDramaAsync 读出渲染成片。
 * 1 行 = 1 版;同一 project 3 行由 uk_draft_proj_ver 保证不重。
 */
@Data
@Entity
@Table(name = "xhs_drama_draft")
public class XhsDramaDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 主角固定外貌/身份/穿着的描述;被逐字注入每段 scene 提升 CogVideoX 跨镜头角色一致性。 */
    @Column(name = "character_profile", columnDefinition = "TEXT")
    private String characterProfile;

    /** 全片画面风格关键词(色调/光线/镜头/画风),注入每段 scene。 */
    @Column(name = "style_lock", columnDefinition = "TEXT")
    private String styleLock;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String narration;

    /** 6 段分镜画面 prompt 的 JSON 数组(String 形式存,前端用 JSON.parse 还原)。 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String scenes;

    @Column(name = "error_msg", length = 2048)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
