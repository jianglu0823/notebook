-- ============================================================
-- drama-multidraft DDL Migration
-- Date: 2026-08-19
-- Target: MySQL 8.0+ / nblm 库
-- 目的:短剧生成前先生成 3 版候选(口播 + 6 段分镜 + 主角/风格锁定),
--      用户挑 1 版并可编辑,再启动 CogVideoX 渲染。新增 xhs_drama_draft 表
--      存候选版(1 行/版),xhs_project 加 5 字段(4 个可选输入 + chosen_draft_id)。
-- 由前端表单 + XhsService.genDraftsAsync 在 App 启动后第一次调用时落数据。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE xhs_drama_draft (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  project_id        BIGINT        NOT NULL COMMENT '所属 xhs_project.id',
  version_no        TINYINT       NOT NULL COMMENT '版本号 1/2/3',
  character_profile TEXT          NULL     COMMENT '主角固定外貌/身份描述,逐字注入每段 scene 保持一致',
  style_lock        TEXT          NULL     COMMENT '画面风格锁定(色调/光线/镜头/画风关键词)',
  narration         MEDIUMTEXT    NULL     COMMENT '旁白口播稿(简体中文)',
  scenes            MEDIUMTEXT    NULL     COMMENT '6 段分镜画面 prompt 的 JSON 数组',
  error_msg         VARCHAR(2048) NULL     COMMENT '该版本生成失败原因(可空)',
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_draft_proj_ver (project_id, version_no),
  KEY idx_draft_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短剧 3 版候选脚本';

ALTER TABLE xhs_project
  ADD COLUMN genre           VARCHAR(64)  NULL COMMENT '短剧类型(可选,如 都市/悬疑/治愈)' AFTER drama_status,
  ADD COLUMN main_character  VARCHAR(255) NULL COMMENT '主角设定(可选)' AFTER genre,
  ADD COLUMN story_arc       VARCHAR(255) NULL COMMENT '故事弧线/走向(可选)' AFTER main_character,
  ADD COLUMN tone            VARCHAR(64)  NULL COMMENT '基调(可选,如 温暖治愈/黑色幽默)' AFTER story_arc,
  ADD COLUMN chosen_draft_id BIGINT       NULL COMMENT '用户选中的 xhs_drama_draft.id(渲染前必填)' AFTER tone;
