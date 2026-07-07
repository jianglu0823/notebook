-- NotebookLM 类应用 —— 元数据库结构
-- 向量本体存 Milvus,这里存资料/切块/会话等元数据与出处定位信息

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS notebooklm DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE notebooklm;

-- 用户(注册账号;游客不入库,身份靠浏览器 cookie 里的 uuid)
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(64)   NOT NULL,
    password_hash VARCHAR(100)  NOT NULL COMMENT 'BCrypt',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 笔记本(一个 notebook = 一组资料 + 其问答/生成物)
CREATE TABLE IF NOT EXISTS notebook (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    name         VARCHAR(255)  NOT NULL,
    description  VARCHAR(1024) NULL,
    owner_id     VARCHAR(64)   NULL COMMENT '预留:用户体系',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_notebook_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 笔记(一个 notebook 下有多条 note;每条 note 内含手写正文 + 上传文件)
CREATE TABLE IF NOT EXISTS note (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    notebook_id  BIGINT        NOT NULL,
    title        VARCHAR(255)  NOT NULL,
    content      MEDIUMTEXT    NULL COMMENT '富文本正文(HTML)',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_note_notebook (notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 资料源(上传的 PDF/Word/网页/图片/音频,或笔记正文 NOTE_BODY)
CREATE TABLE IF NOT EXISTS source (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    notebook_id   BIGINT        NOT NULL,
    note_id       BIGINT        NULL COMMENT '所属笔记',
    name          VARCHAR(512)  NOT NULL COMMENT '原始文件名/URL',
    type          VARCHAR(32)   NOT NULL COMMENT 'PDF/DOCX/WEB/IMAGE/AUDIO/TEXT/NOTE_BODY',
    storage_path  VARCHAR(1024) NULL COMMENT '本地卷/对象存储路径',
    status        VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSING/EMBEDDING/DONE/FAILED',
    error_msg     VARCHAR(2048) NULL,
    char_count    INT           NULL,
    chunk_count   INT           NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_source_notebook (notebook_id),
    INDEX idx_source_note (note_id),
    INDEX idx_source_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 切块(用于出处引用定位;向量本体在 Milvus,这里存映射与原文)
CREATE TABLE IF NOT EXISTS chunk (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_id     BIGINT        NOT NULL,
    notebook_id   BIGINT        NOT NULL,
    note_id       BIGINT        NULL COMMENT '所属笔记',
    seq           INT           NOT NULL COMMENT '在源内的顺序',
    vector_id     VARCHAR(128)  NULL COMMENT 'Milvus 主键',
    content       MEDIUMTEXT    NOT NULL COMMENT '切块原文,用于引用展示',
    locator       VARCHAR(255)  NULL COMMENT '出处定位:如 page=3 或 offset=1024',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chunk_source (source_id),
    INDEX idx_chunk_notebook (notebook_id),
    INDEX idx_chunk_note (note_id),
    INDEX idx_chunk_vector (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 问答历史
CREATE TABLE IF NOT EXISTS qa_history (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    notebook_id   BIGINT        NOT NULL,
    session_id    VARCHAR(64)   NOT NULL,
    question      TEXT          NOT NULL,
    answer        MEDIUMTEXT    NULL,
    citations     JSON          NULL COMMENT '引用的 chunk 列表',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qa_notebook (notebook_id),
    INDEX idx_qa_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 生成物(摘要/学习指南/FAQ)
CREATE TABLE IF NOT EXISTS generated_doc (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    notebook_id   BIGINT        NOT NULL,
    kind          VARCHAR(32)   NOT NULL COMMENT 'SUMMARY/STUDY_GUIDE/FAQ',
    content       MEDIUMTEXT    NULL,
    status        VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_gen_notebook (notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 音频概览播客
CREATE TABLE IF NOT EXISTS podcast (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    notebook_id   BIGINT        NOT NULL,
    title         VARCHAR(512)  NULL,
    script        MEDIUMTEXT    NULL COMMENT '双主持人对话脚本(JSON)',
    audio_path    VARCHAR(1024) NULL COMMENT '合成音频路径',
    status        VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SCRIPTING/SYNTHESIZING/DONE/FAILED',
    error_msg     VARCHAR(2048) NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_podcast_notebook (notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
