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
    model          VARCHAR(32)   NULL COMMENT '本次协作使用的模型',
    input_tokens   BIGINT        NOT NULL DEFAULT 0 COMMENT '累计输入 token',
    output_tokens  BIGINT        NOT NULL DEFAULT 0 COMMENT '累计输出 token',
    cost_rmb       DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '按模型单价换算的费用(元)',
    error_msg      VARCHAR(2048) NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_writing_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:员工(可配置人设的 ReActAgent)
CREATE TABLE IF NOT EXISTS agent_employee (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id       VARCHAR(64)   NOT NULL COMMENT '主体:鹿匠小镇模式下统一为 world(全局共享)',
    name           VARCHAR(64)   NOT NULL COMMENT '员工名字',
    avatar         VARCHAR(16)   NULL COMMENT '头像 emoji',
    title          VARCHAR(64)   NULL COMMENT '职位/角色',
    persona        TEXT          NULL COMMENT '性格人设(作为 system prompt)',
    color          VARCHAR(16)   NULL COMMENT '主题色 hex',
    office_x       INT           NOT NULL DEFAULT 0 COMMENT '地图/网格横向工位(家/基点)',
    office_y       INT           NOT NULL DEFAULT 0 COMMENT '地图/网格纵向工位(家/基点)',
    birth_date     DATE          NULL COMMENT '出生日期',
    creator        VARCHAR(64)   NULL COMMENT '创造者',
    mood           VARCHAR(32)   NULL COMMENT '心情状态文字',
    mood_emoji     VARCHAR(16)   NULL COMMENT '心情 emoji',
    status         VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT 'active / jailed(小黑屋,软删除)',
    autonomous_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否参与自主行动',
    pos_x          DOUBLE        NULL COMMENT '地图漫游当前 x',
    pos_y          DOUBLE        NULL COMMENT '地图漫游当前 y',
    location       VARCHAR(32)   NULL COMMENT '当前所在具名地点 key(见 TownMap)',
    life_summary   TEXT          NULL COMMENT '进小黑屋(软删除)时生成的第三人称一生回顾',
    coins          BIGINT        NOT NULL DEFAULT 0 COMMENT '货币余额',
    occupation     VARCHAR(32)   NULL COMMENT '职业类型 key(writer/singer/painter...)驱动每日产物',
    skills_json    TEXT          NULL COMMENT '创作技能 JSON {novel/image/video/music:{lv,style}} 每日自学+触发创作',
    schedule_json  TEXT          NULL COMMENT '作息模板 JSON(起床/工作/休闲/睡觉时段)',
    home_place     VARCHAR(32)   NULL COMMENT '家所在建筑 key',
    home_decor_json TEXT         NULL COMMENT '家园装饰 JSON(已购装饰 id + 等级)',
    spouse_id      BIGINT        NULL COMMENT '配偶居民 id',
    partner_id     BIGINT        NULL COMMENT '恋爱对象 id(未婚)',
    parent_ids     VARCHAR(64)   NULL COMMENT '父母 id 逗号分隔(出生的孩子有值)',
    death_date     DATE          NULL COMMENT '死亡日期',
    death_cause    VARCHAR(64)   NULL COMMENT '死因',
    energy         INT           NOT NULL DEFAULT 100 COMMENT '精力/健康,影响死亡概率与作息',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_owner (owner_id),
    INDEX idx_agent_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:话题会议/群聊(多员工轮流发言,events 记录发言时间线)
CREATE TABLE IF NOT EXISTS agent_meeting (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id         VARCHAR(64)   NOT NULL COMMENT '主体:u:<id> / g:<uuid>',
    status           VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED',
    topic            VARCHAR(512)  NOT NULL COMMENT '会议议题',
    participant_ids  TEXT          NULL COMMENT '参会员工 id JSON 数组',
    max_rounds       INT           NOT NULL DEFAULT 3 COMMENT '发言轮数',
    events           MEDIUMTEXT    NULL COMMENT '发言时间线 JSON 数组(实时追加,前端围观)',
    summary          MEDIUMTEXT    NULL COMMENT '主持人总结',
    model            VARCHAR(32)   NULL COMMENT '本场会议使用的模型',
    input_tokens     BIGINT        NOT NULL DEFAULT 0 COMMENT '累计输入 token',
    output_tokens    BIGINT        NOT NULL DEFAULT 0 COMMENT '累计输出 token',
    cost_rmb         DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '按模型单价换算的费用(元)',
    error_msg        VARCHAR(2048) NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_meeting_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:员工长期记忆/事件流(可观测,供前端时间线 + 跨员工引用)
CREATE TABLE IF NOT EXISTS agent_memory (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id         BIGINT        NOT NULL COMMENT '所属员工 id',
    kind             VARCHAR(16)   NOT NULL COMMENT 'observation/dialogue/meeting/reflection/action',
    content          TEXT          NOT NULL COMMENT '记忆内容',
    importance       INT           NOT NULL DEFAULT 5 COMMENT '重要度 1~10',
    related_agent_id BIGINT        NULL COMMENT '相关员工(如对话/找人)',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_mem_agent (agent_id),
    INDEX idx_mem_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:1:1 对话消息(用户 ↔ 员工)
CREATE TABLE IF NOT EXISTS agent_chat_msg (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id      BIGINT        NOT NULL COMMENT '对话的员工 id',
    owner_id      VARCHAR(64)   NOT NULL COMMENT '发起对话的用户主体',
    role          VARCHAR(8)    NOT NULL COMMENT 'user / agent',
    content       TEXT          NOT NULL COMMENT '消息内容',
    input_tokens  BIGINT        NOT NULL DEFAULT 0,
    output_tokens BIGINT        NOT NULL DEFAULT 0,
    cost_rmb      DECIMAL(12,6) NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_agent_owner (agent_id, owner_id),
    INDEX idx_chat_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:群聊会话(用户 + 多名居民,交互式多轮,支持中途增减成员)
CREATE TABLE IF NOT EXISTS agent_group_chat (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id       VARCHAR(64)   NOT NULL COMMENT '主体:u:<id> / g:<uuid>',
    title          VARCHAR(255)  NOT NULL COMMENT '群聊标题',
    member_ids     TEXT          NULL COMMENT '当前在场成员 id JSON 数组',
    model          VARCHAR(32)   NULL COMMENT '本群使用模型',
    input_tokens   BIGINT        NOT NULL DEFAULT 0,
    output_tokens  BIGINT        NOT NULL DEFAULT 0,
    cost_rmb       DECIMAL(12,6) NOT NULL DEFAULT 0,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_group_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:群聊消息(role=user/agent/system)
CREATE TABLE IF NOT EXISTS agent_group_msg (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    chat_id       BIGINT        NOT NULL COMMENT '所属群聊 id',
    agent_id      BIGINT        NULL COMMENT '发言居民 id(user/system 为空)',
    role          VARCHAR(8)    NOT NULL COMMENT 'user / agent / system',
    content       TEXT          NOT NULL COMMENT '消息内容',
    input_tokens  BIGINT        NOT NULL DEFAULT 0,
    output_tokens BIGINT        NOT NULL DEFAULT 0,
    cost_rmb      DECIMAL(12,6) NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_gmsg_chat (chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:自主行动记录(移动/思考/找人说话/反思)
CREATE TABLE IF NOT EXISTS agent_action (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id         BIGINT        NOT NULL COMMENT '行动的员工 id',
    type             VARCHAR(16)   NOT NULL COMMENT 'move/think/talk/reflect/goto',
    content          TEXT          NULL COMMENT '行动描述/说的话',
    target_agent_id  BIGINT        NULL COMMENT '找人说话的对象员工 id',
    place            VARCHAR(32)   NULL COMMENT '行动发生地点 key(见 TownMap)',
    scene            VARCHAR(64)   NULL COMMENT '场景短标题(可空)',
    input_tokens     BIGINT        NOT NULL DEFAULT 0,
    output_tokens    BIGINT        NOT NULL DEFAULT 0,
    cost_rmb         DECIMAL(12,6) NOT NULL DEFAULT 0,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_action_agent (agent_id),
    INDEX idx_action_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:全局世界设置(单行 id=1)
CREATE TABLE IF NOT EXISTS agent_world_settings (
    id                 BIGINT PRIMARY KEY COMMENT '固定为 1',
    autonomous_enabled TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '自主行动总开关(默认关)',
    interval_seconds   INT         NOT NULL DEFAULT 600 COMMENT '自主行动间隔秒(默认 10 分钟)',
    model              VARCHAR(32)  NOT NULL DEFAULT 'qwen-turbo' COMMENT '自主行动使用模型',
    sim_date           DATE        NULL COMMENT '小镇内在日期',
    sim_minute         INT         NOT NULL DEFAULT 480 COMMENT '当日分钟数 0-1439(默认 08:00)',
    minutes_per_tick   INT         NOT NULL DEFAULT 120 COMMENT '每个 tick 推进的内在分钟',
    season             VARCHAR(8)  NULL COMMENT '当前季节(春/夏/秋/冬)',
    weather            VARCHAR(16) NULL COMMENT '当前天气',
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO agent_world_settings (id, autonomous_enabled, interval_seconds, model)
VALUES (1, 0, 600, 'qwen-turbo');

-- 智能体小世界:职业产物(作家连载章节/歌手新歌/画师画作等)
CREATE TABLE IF NOT EXISTS agent_product (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id       BIGINT        NOT NULL COMMENT '产出居民 id',
    sim_date       DATE          NULL COMMENT '产出的小镇日期',
    occupation     VARCHAR(32)   NULL COMMENT '职业类型 key',
    kind           VARCHAR(32)   NULL COMMENT '产物类型(chapter/song/artwork...)',
    seq            INT           NULL COMMENT '第几章/第几首',
    title          VARCHAR(255)  NULL COMMENT '标题',
    content        TEXT          NULL COMMENT '内容/片段',
    quality        INT           NOT NULL DEFAULT 5 COMMENT '质量 1~10',
    input_tokens   BIGINT        NOT NULL DEFAULT 0,
    output_tokens  BIGINT        NOT NULL DEFAULT 0,
    cost_rmb       DECIMAL(12,6) NOT NULL DEFAULT 0,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_product_agent (agent_id),
    INDEX idx_product_date (sim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:世界日报(一天一行,含当日全世界 token/花费,仅管理者可见)
CREATE TABLE IF NOT EXISTS world_daily_report (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    sim_date            DATE          NOT NULL UNIQUE COMMENT '小镇日期',
    season              VARCHAR(8)    NULL COMMENT '季节',
    weather             VARCHAR(16)   NULL COMMENT '天气',
    highlights_json     TEXT          NULL COMMENT '杰出成就 JSON',
    stats_json          TEXT          NULL COMMENT '统计 JSON(新章/新歌/出生/死亡/结婚数)',
    news_json           TEXT          NULL COMMENT '突发新闻 JSON',
    narrative           TEXT          NULL COMMENT 'LLM 写的当日叙事',
    total_input_tokens  BIGINT        NULL COMMENT '当日全世界输入 token',
    total_output_tokens BIGINT        NULL COMMENT '当日全世界输出 token',
    total_cost_rmb      DECIMAL(12,6) NULL COMMENT '当日全世界花费(元)',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_report_date (sim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:货币流水(仅记大额)
CREATE TABLE IF NOT EXISTS agent_transaction (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id    BIGINT        NOT NULL COMMENT '居民 id',
    sim_date    DATE          NULL COMMENT '小镇日期',
    delta       BIGINT        NOT NULL DEFAULT 0 COMMENT '变动额(正=收入/负=支出)',
    balance     BIGINT        NOT NULL DEFAULT 0 COMMENT '变动后余额',
    reason      VARCHAR(64)   NULL COMMENT '事由',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_txn_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:居民两两关系(亲密度,归一化 a_id<b_id)
CREATE TABLE IF NOT EXISTS agent_relationship (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    a_id         BIGINT        NOT NULL COMMENT '较小的居民 id',
    b_id         BIGINT        NOT NULL COMMENT '较大的居民 id',
    intimacy     INT           NOT NULL DEFAULT 0 COMMENT '亲密度',
    status       VARCHAR(16)   NOT NULL DEFAULT 'stranger' COMMENT 'stranger/friend/close/dating/married',
    interactions INT           NOT NULL DEFAULT 0 COMMENT '累计互动次数',
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rel_pair (a_id, b_id),
    INDEX idx_rel_a (a_id),
    INDEX idx_rel_b (b_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 沙盒快进任务(隔离模拟,不影响真实世界;每人限一次,管理员不限)
CREATE TABLE IF NOT EXISTS sandbox_run (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_id            VARCHAR(64)   NOT NULL COMMENT '发起人:u:<id> / g:<uuid>',
    title               VARCHAR(255)  NULL COMMENT '任务标题',
    years               INT           NOT NULL COMMENT '快进年数',
    member_ids          TEXT          NULL COMMENT '参与居民 id 列表(JSON)',
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED',
    est_cost_rmb        DECIMAL(12,6) NULL COMMENT '预估花费',
    actual_input_tokens  BIGINT       NULL,
    actual_output_tokens BIGINT       NULL,
    actual_cost_rmb      DECIMAL(12,6) NULL,
    report              MEDIUMTEXT    NULL COMMENT '世界报告(LLM 叙事)',
    error_msg           VARCHAR(512)  NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sbx_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 沙盒事件时间线(供回放:出生/死亡/结婚/恋爱/产物/事件/里程碑)
CREATE TABLE IF NOT EXISTS sandbox_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id          BIGINT        NOT NULL,
    sim_date        DATE          NULL COMMENT '事件内在日期',
    seq             INT           NOT NULL COMMENT '回放步序',
    type            VARCHAR(24)   NOT NULL COMMENT 'birth/death/marriage/dating/product/event/milestone',
    agent_id        BIGINT        NULL,
    target_agent_id BIGINT        NULL,
    content         TEXT          NULL,
    meta_json       TEXT          NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sbxev_run (run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 智能体小世界:自由评价(对作品 product 或居民 agent 的评论)
CREATE TABLE IF NOT EXISTS agent_comment (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_type  VARCHAR(16)  NOT NULL COMMENT '评论对象:product/agent',
    target_id    BIGINT       NOT NULL COMMENT '对象 id',
    author_id    VARCHAR(64)  NULL COMMENT '发表者 ownerId(u:<id>/g:<uuid>)',
    author_name  VARCHAR(64)  NULL COMMENT '发表者展示名快照',
    content      TEXT         NOT NULL COMMENT '评论正文',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_comment_target (target_type, target_id, id)
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
