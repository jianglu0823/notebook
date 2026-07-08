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
    type         VARCHAR(16)   NOT NULL DEFAULT 'RICHTEXT' COMMENT 'RICHTEXT/MARKDOWN',
    content      MEDIUMTEXT    NULL COMMENT '正文:RICHTEXT 存 HTML,MARKDOWN 存 Markdown 源码',
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

-- 新闻收集任务(选方向 → 联网搜索最新动态 → 整理成笔记)
CREATE TABLE IF NOT EXISTS news_job (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id      VARCHAR(64)   NOT NULL COMMENT '主体:u:<id> / g:<uuid>',
    topic         VARCHAR(255)  NOT NULL COMMENT '新闻方向:预设分类或自定义关键词',
    status        VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/GENERATING/DONE/FAILED',
    notebook_id   BIGINT        NULL COMMENT '生成笔记所在笔记本',
    note_id       BIGINT        NULL COMMENT '生成的笔记',
    error_msg     VARCHAR(2048) NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_news_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 小红书文案生成工作流:一条项目贯穿 方向→标题→素材→风格文案→配图 全流程
CREATE TABLE IF NOT EXISTS xhs_project (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id       VARCHAR(64)   NOT NULL COMMENT '主体:u:<id> / g:<uuid>',
    status         VARCHAR(32)   NOT NULL DEFAULT 'NEW' COMMENT 'NEW/TITLES_DONE/RESEARCH_DONE/COPY_DONE/IMAGES_DONE/FAILED',
    direction      VARCHAR(255)  NOT NULL COMMENT '用户输入/选择的方向',
    title_options  TEXT          NULL COMMENT '候选标题 JSON 数组',
    chosen_title   VARCHAR(512)  NULL COMMENT '用户选定的标题',
    research       MEDIUMTEXT    NULL COMMENT '联网搜集的长文素材',
    style          VARCHAR(32)   NULL COMMENT '风格:ZHONGCAO/DUSHE/GANHUO/ZHIYU',
    copy_text      MEDIUMTEXT    NULL COMMENT '生成的小红书文案',
    image_paths    TEXT          NULL COMMENT '配图本地路径 JSON 数组',
    publish_status VARCHAR(32)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/READY/PUBLISHED',
    error_msg      VARCHAR(2048) NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_xhs_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 多智能体协同写作:作者⇄编辑⇄核查 三个 ReActAgent 迭代收敛,rounds 记录每轮协作
CREATE TABLE IF NOT EXISTS writing_project (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id       VARCHAR(64)   NOT NULL COMMENT '主体:u:<id> / g:<uuid>',
    status         VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED',
    topic          VARCHAR(512)  NOT NULL COMMENT '写作主题/需求',
    genre          VARCHAR(32)   NULL COMMENT '体裁:ARTICLE/STORY/REVIEW/SCRIPT',
    max_rounds     INT           NOT NULL DEFAULT 3 COMMENT '最大迭代轮数',
    rounds         MEDIUMTEXT    NULL COMMENT '每轮协作过程 JSON 数组(draft/review/factcheck/verdict)',
    events         MEDIUMTEXT    NULL COMMENT '协作事件时间线 JSON 数组(节点/思考/工具调用实时追加)',
    final_text     MEDIUMTEXT    NULL COMMENT '收敛后的终稿',
    approved       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '编辑是否 APPROVE 收敛',
    error_msg      VARCHAR(2048) NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_writing_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 访问/操作日志(登录、注册及各类写操作;记录 IP、设备、动作)
CREATE TABLE IF NOT EXISTS access_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id      VARCHAR(64)   NULL COMMENT '主体:u:<id> / g:<uuid>,未鉴权为空',
    user_id       BIGINT        NULL COMMENT '注册用户 id',
    username      VARCHAR(64)   NULL COMMENT '登录/注册时的用户名',
    guest         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否游客',
    action        VARCHAR(64)   NOT NULL COMMENT 'LOGIN/LOGIN_FAIL/REGISTER/操作名',
    method        VARCHAR(8)    NULL COMMENT 'HTTP 方法',
    path          VARCHAR(512)  NULL COMMENT '请求路径',
    status        INT           NULL COMMENT 'HTTP 状态码',
    ip            VARCHAR(64)   NULL COMMENT '客户端 IP(取 X-Forwarded-For 首段)',
    device_type   VARCHAR(32)   NULL COMMENT 'Mobile/Tablet/Desktop/Bot/Unknown',
    user_agent    VARCHAR(512)  NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_alog_owner (owner_id),
    INDEX idx_alog_action (action),
    INDEX idx_alog_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
