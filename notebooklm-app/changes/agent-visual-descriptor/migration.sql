-- ============================================================
-- agent-visual-descriptor DDL Migration
-- Date: 2026-08-18
-- Target: MySQL 8.0+ / nblm 库
-- 目的:给 agent_employee 加 visual_descriptor 列,存英文视觉描述,
--      论坛帖子配图/视频都拼到 prompt,提升同一 agent 多次出图角色一致性。
-- 由 AgentVisualDescriptorService 在 App 启动时 backfill。
-- ============================================================

SET NAMES utf8mb4;

ALTER TABLE agent_employee
    ADD COLUMN visual_descriptor TEXT NULL
    COMMENT '英文视觉描述(LLM 由 persona 一次性生成,论坛配图/视频拼到 prompt 提升角色一致性)'
    AFTER schedule_json;
