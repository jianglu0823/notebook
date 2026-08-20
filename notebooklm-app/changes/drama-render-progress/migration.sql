-- ============================================================
-- drama-render-progress DDL Migration
-- Date: 2026-08-19
-- Target: MySQL 8.0+ / nblm 库
-- 目的:短剧渲染时实时显示进度(已生成 N/6 段),失败时停在当前数字让用户知道卡在哪段。
-- 增量列 drama_segments_done(0~6),默认 0。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE xhs_project
  ADD COLUMN drama_segments_done TINYINT NOT NULL DEFAULT 0
    COMMENT '短剧渲染时已完成的段数(0~6);FAILED 时停在当前数字' AFTER chosen_draft_id;
