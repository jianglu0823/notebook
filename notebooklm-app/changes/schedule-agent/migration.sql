-- ============================================================
-- schedule-agent DDL Migration
-- Version: V1__create_schedule_agent_schema.sql
-- Target: MySQL 8.0+
-- Schema: schedule_agent (独立于 nblm 库)
-- 字符集: utf8mb4 / utf8mb4_unicode_ci
-- 引擎: InnoDB
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `schedule_agent`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `schedule_agent`;

-- ------------------------------------------------------------
-- 1. schedule_event — 日程事件主表（加密存储）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_event`;
CREATE TABLE `schedule_event` (
  `id`                    CHAR(36)        NOT NULL                COMMENT 'UUID 主键',
  `user_id`               BIGINT          NOT NULL                COMMENT '所属用户 ID',
  `title_cipher`          VARBINARY(1024) NOT NULL                COMMENT 'AES-256-GCM 密文',
  `title_iv`              VARBINARY(12)   NOT NULL                COMMENT '12 字节 IV',
  `description_cipher`    VARBINARY(2048)     NULL                COMMENT 'AES-256-GCM 密文（可空）',
  `description_iv`        VARBINARY(12)       NULL                COMMENT '12 字节 IV',
  `location_cipher`       VARBINARY(512)      NULL                COMMENT 'AES-256-GCM 密文（可空）',
  `location_iv`           VARBINARY(12)       NULL                COMMENT '12 字节 IV',
  `start_time`            DATETIME(3)     NOT NULL                COMMENT '开始时间（本地时区无关，存 UTC）',
  `end_time`              DATETIME(3)     NOT NULL                COMMENT '结束时间',
  `priority`              ENUM('P0','P1','P2') NOT NULL DEFAULT 'P2' COMMENT '优先级',
  `attendees`             JSON                NULL                COMMENT '参与人 JSON 数组',
  `recurrence`            VARCHAR(500)        NULL                COMMENT 'RFC 5545 RRULE',
  `emoji`                 VARCHAR(16)         NULL                COMMENT '主题 emoji',
  `idempotency_key`       VARCHAR(128)        NULL                COMMENT '幂等键',
  `sync_status`           ENUM('PENDING','SYNCED','FAILED','DELETED') NOT NULL DEFAULT 'PENDING',
  `last_sync_error`       VARCHAR(1000)       NULL                COMMENT '最近一次同步错误',
  `sync_retry_count`      TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '已重试次数',
  `status`                ENUM('active','deleted') NOT NULL DEFAULT 'active',
  `created_at`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at`            DATETIME(3)         NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_start`     (`user_id`, `start_time`),
  KEY `idx_user_status`    (`user_id`, `status`),
  KEY `idx_priority`       (`priority`),
  KEY `idx_idempotency`    (`user_id`, `idempotency_key`),
  KEY `idx_created`        (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='日程事件主表（title/description/location 加密）';

-- ------------------------------------------------------------
-- 2. schedule_event_occurrence — 重复事件物化（90 天）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_event_occurrence`;
CREATE TABLE `schedule_event_occurrence` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_id`        CHAR(36)        NOT NULL                COMMENT '主事件 ID',
  `user_id`         BIGINT          NOT NULL,
  `occurrence_start` DATETIME(3)    NOT NULL,
  `occurrence_end`   DATETIME(3)    NOT NULL,
  `is_cancelled`    BOOLEAN         NOT NULL DEFAULT FALSE,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_event`    (`event_id`),
  KEY `idx_user_time` (`user_id`, `occurrence_start`),
  CONSTRAINT `fk_occurrence_event`
    FOREIGN KEY (`event_id`) REFERENCES `schedule_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='重复事件物化实例（90 天滚动）';

-- ------------------------------------------------------------
-- 3. schedule_attendee — 参与人
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_attendee`;
CREATE TABLE `schedule_attendee` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_id`    CHAR(36)        NOT NULL,
  `user_id`     BIGINT          NOT NULL,
  `email`       VARCHAR(200)        NULL,
  `display_name` VARCHAR(100)       NULL,
  `rsvp_status` ENUM('pending','accepted','declined','tentative') NOT NULL DEFAULT 'pending',
  `hidden`      BOOLEAN         NOT NULL DEFAULT FALSE,
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_email` (`event_id`, `email`),
  KEY `idx_user` (`user_id`),
  CONSTRAINT `fk_attendee_event`
    FOREIGN KEY (`event_id`) REFERENCES `schedule_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 4. schedule_focus_session — 专注会话
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_focus_session`;
CREATE TABLE `schedule_focus_session` (
  `id`                       CHAR(36)        NOT NULL,
  `event_id`                 CHAR(36)        NOT NULL,
  `user_id`                  BIGINT          NOT NULL,
  `status`                   ENUM('RUNNING','COMPLETED','ABANDONED') NOT NULL DEFAULT 'RUNNING',
  `started_at`               DATETIME(3)     NOT NULL,
  `last_heartbeat_at`        DATETIME(3)         NULL,
  `completed_at`             DATETIME(3)         NULL,
  `abandoned_at`             DATETIME(3)         NULL,
  `planned_duration_minutes` INT                 NULL,
  `actual_duration_minutes`  INT                 NULL,
  `reflection`               VARCHAR(500)        NULL,
  `hidden`                   BOOLEAN         NOT NULL DEFAULT FALSE,
  `created_at`               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_running` (`event_id`, `status`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_heartbeat` (`status`, `last_heartbeat_at`),
  CONSTRAINT `fk_focus_event`
    FOREIGN KEY (`event_id`) REFERENCES `schedule_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='专注计时会话';

-- ------------------------------------------------------------
-- 5. schedule_checkin — 打卡记录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_checkin`;
CREATE TABLE `schedule_checkin` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT          NOT NULL,
  `event_id`     CHAR(36)        NOT NULL,
  `session_id`   CHAR(36)            NULL,
  `checkin_date` DATE            NOT NULL,
  `count`        TINYINT UNSIGNED NOT NULL DEFAULT 1,
  `streak_days`  SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  `milestone_reached` TINYINT UNSIGNED     NULL COMMENT '触发的 milestone 天数（7/30/100/365）',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_event_date` (`user_id`, `event_id`, `checkin_date`),
  KEY `idx_user_date` (`user_id`, `checkin_date`),
  CONSTRAINT `fk_checkin_event`
    FOREIGN KEY (`event_id`) REFERENCES `schedule_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='打卡记录 + 连续天数';

-- ------------------------------------------------------------
-- 6. schedule_user_pref — 用户偏好
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_user_pref`;
CREATE TABLE `schedule_user_pref` (
  `user_id`              BIGINT      NOT NULL,
  `no_morning_meetings`  BOOLEAN     NOT NULL DEFAULT FALSE,
  `no_evening_work`      BOOLEAN     NOT NULL DEFAULT FALSE,
  `workday_start`        TIME        NOT NULL DEFAULT '09:00:00',
  `workday_end`          TIME        NOT NULL DEFAULT '19:00:00',
  `weekend_start`        TIME        NOT NULL DEFAULT '10:00:00',
  `weekend_end`          TIME        NOT NULL DEFAULT '22:00:00',
  `min_slot_minutes`     INT         NOT NULL DEFAULT 30,
  `created_at`           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 7. schedule_emoji_dict — Emoji 字典
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_emoji_dict`;
CREATE TABLE `schedule_emoji_dict` (
  `id`         INT UNSIGNED    NOT NULL AUTO_INCREMENT,
  `keyword`    VARCHAR(100)    NOT NULL                COMMENT '关键词（单个，可批量通过 keywords JSON）',
  `keywords`   JSON                NULL                COMMENT '关键词 JSON 数组',
  `emoji`      VARCHAR(16)     NOT NULL,
  `category`   VARCHAR(50)         NULL,
  `weight`     INT             NOT NULL DEFAULT 1,
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_keyword` (`keyword`),
  KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='主题 Emoji 字典';

-- ------------------------------------------------------------
-- 8. schedule_agent_card — Agent Card 元数据
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_agent_card`;
CREATE TABLE `schedule_agent_card` (
  `id`            INT UNSIGNED    NOT NULL AUTO_INCREMENT,
  `agent_name`    VARCHAR(100)    NOT NULL,
  `version`       VARCHAR(20)     NOT NULL                COMMENT 'semver MAJOR.MINOR.PATCH',
  `url`           VARCHAR(200)    NOT NULL,
  `capabilities`  JSON            NOT NULL,
  `skills`        JSON            NOT NULL,
  `is_active`     BOOLEAN         NOT NULL DEFAULT TRUE,
  `registered_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_heartbeat_at` DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name_version` (`agent_name`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 9. schedule_task_artifact — A2A 任务 artifact
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_task_artifact`;
CREATE TABLE `schedule_task_artifact` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`      CHAR(36)        NOT NULL,
  `artifact_type` VARCHAR(50)    NOT NULL,
  `name`         VARCHAR(200)        NULL,
  `data`         LONGTEXT        NOT NULL                COMMENT 'JSON 序列化',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_task` (`task_id`),
  KEY `idx_type` (`artifact_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='A2A 任务输出工件';

-- ------------------------------------------------------------
-- 10. schedule_idempotency — 幂等键缓存（24h）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_idempotency`;
CREATE TABLE `schedule_idempotency` (
  `idempotency_key` VARCHAR(128)    NOT NULL,
  `user_id`         BIGINT          NOT NULL,
  `task_id`         CHAR(36)        NOT NULL,
  `response_body`   LONGTEXT            NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at`      DATETIME(3)     NOT NULL,
  PRIMARY KEY (`idempotency_key`, `user_id`),
  KEY `idx_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='24h 幂等键缓存';

-- ------------------------------------------------------------
-- 11. schedule_external_sync — 外部同步状态
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_external_sync`;
CREATE TABLE `schedule_external_sync` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_id`        CHAR(36)        NOT NULL,
  `provider`        VARCHAR(50)     NOT NULL                COMMENT 'mock / lark / google',
  `external_id`     VARCHAR(200)        NULL,
  `sync_status`     ENUM('PENDING','SYNCED','FAILED','DELETED') NOT NULL DEFAULT 'PENDING',
  `last_error`      VARCHAR(1000)       NULL,
  `retry_count`     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `last_attempt_at` DATETIME(3)         NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_provider` (`event_id`, `provider`),
  KEY `idx_status` (`sync_status`),
  CONSTRAINT `fk_sync_event`
    FOREIGN KEY (`event_id`) REFERENCES `schedule_event` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 12. schedule_metric_event — 埋点事件
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_metric_event`;
CREATE TABLE `schedule_metric_event` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(50)     NOT NULL                COMMENT 'DEEP_SCHEDULE_FALLBACK / CHECKIN_MILESTONE / REGISTRY_RE_REGISTER / LLM_CACHE_HIT / ...',
  `user_id`    BIGINT              NULL,
  `target_id`  VARCHAR(100)        NULL,
  `payload`    JSON                NULL,
  `ts`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_type_ts` (`event_type`, `ts`),
  KEY `idx_user_ts` (`user_id`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 13. schedule_audit_log — 审计日志（append-only）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_audit_log`;
CREATE TABLE `schedule_audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `actor`       VARCHAR(100)    NOT NULL                COMMENT 'userId 或 system',
  `action`      VARCHAR(50)     NOT NULL                COMMENT 'EVENT_CREATE / EVENT_UPDATE / EVENT_DELETE / FOCUS_START / FOCUS_COMPLETE / EXTERNAL_SYNC_* / CHECKIN_MILESTONE / LLM_CACHE_HIT / SECURITY_DECRYPT_FAIL / REGISTRY_*',
  `target_type` VARCHAR(50)         NULL                COMMENT 'event / focus / checkin / registry',
  `target_id`   VARCHAR(100)        NULL,
  `before`      JSON                NULL,
  `after`       JSON                NULL,
  `ip`          VARCHAR(64)         NULL,
  `ts`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_actor_ts` (`actor`, `ts`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_action_ts` (`action`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='审计日志（append-only，靠触发器禁止删除）';

-- append-only 触发器
DROP TRIGGER IF EXISTS `trg_audit_log_no_delete`;
DELIMITER //
CREATE TRIGGER `trg_audit_log_no_delete`
BEFORE DELETE ON `schedule_audit_log`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'schedule_audit_log is append-only';
END//
DELIMITER ;

DROP TRIGGER IF EXISTS `trg_audit_log_no_update`;
DELIMITER //
CREATE TRIGGER `trg_audit_log_no_update`
BEFORE UPDATE ON `schedule_audit_log`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'schedule_audit_log is append-only';
END//
DELIMITER ;

-- ------------------------------------------------------------
-- 14. schedule_audit_log_archive — 归档表（同结构）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_audit_log_archive`;
CREATE TABLE `schedule_audit_log_archive` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `actor`       VARCHAR(100)    NOT NULL,
  `action`      VARCHAR(50)     NOT NULL,
  `target_type` VARCHAR(50)         NULL,
  `target_id`   VARCHAR(100)        NULL,
  `before`      JSON                NULL,
  `after`       JSON                NULL,
  `ip`          VARCHAR(64)         NULL,
  `ts`          DATETIME(3)     NOT NULL,
  `archived_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_actor_ts` (`actor`, `ts`),
  KEY `idx_action_ts` (`action`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='审计日志归档（>365 天）';

-- ------------------------------------------------------------
-- 15. schedule_access_log — AccessLog（高频，单独表）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_access_log`;
CREATE TABLE `schedule_access_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `method`     VARCHAR(10)     NOT NULL,
  `path`       VARCHAR(500)    NOT NULL,
  `status`     SMALLINT UNSIGNED NOT NULL,
  `duration_ms` INT UNSIGNED   NOT NULL,
  `user_id`    BIGINT              NULL,
  `actor`      VARCHAR(100)        NULL                COMMENT 'userId 或 anonymous',
  `ip`         VARCHAR(64)         NULL,
  `user_agent` VARCHAR(500)        NULL,
  `ts`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_ts` (`user_id`, `ts`),
  KEY `idx_path_ts` (`path`(191), `ts`),
  KEY `idx_status_ts` (`status`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AccessLog（批量写入）';

-- ------------------------------------------------------------
-- 16. schedule_deep_schedule_cache — 深度排期缓存（24h）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `schedule_deep_schedule_cache`;
CREATE TABLE `schedule_deep_schedule_cache` (
  `cache_key`     VARCHAR(128)    NOT NULL                COMMENT 'userId + tasksHash + dateRangeHash',
  `user_id`       BIGINT          NOT NULL,
  `tasks_hash`    CHAR(64)        NOT NULL                COMMENT 'SHA-256 of tasks JSON',
  `date_range_hash` CHAR(64)      NOT NULL                COMMENT 'SHA-256 of dateRange JSON',
  `response_body` LONGTEXT        NOT NULL                COMMENT 'JSON 序列化',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at`    DATETIME(3)     NOT NULL,
  PRIMARY KEY (`cache_key`),
  KEY `idx_expires` (`expires_at`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='深度排期 24h 缓存';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 同步执行的追加 ALTER（建表基线补充到 infra/mysql/init.sql）
-- ============================================================
