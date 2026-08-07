-- ============================================
-- companion-chat 增量迁移(已在 init.sql 同步基线)
-- ============================================
-- 背景:JPA ddl-auto=none,init.sql 仅在全新库生效。已初始化的库需手动执行本文件。
-- 执行方式:在 docker exec 内进入 MySQL 后运行(DB 写入需用户授权,建议用 `!` 前缀在本会话执行)。
--   ! docker exec -i nblm-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" notebooklm < notebooklm-app/changes/companion-chat/migration.sql
--
-- 全部语句幂等(CREATE TABLE IF NOT EXISTS;INSERT IGNORE),可重复执行。

USE notebooklm;

-- 伴侣
CREATE TABLE IF NOT EXISTS companion (
    id                 BIGINT        PRIMARY KEY AUTO_INCREMENT,
    user_id            VARCHAR(64)   NOT NULL COMMENT '所属用户(users.id 或 guest uuid)',
    name               VARCHAR(20)   NOT NULL COMMENT '伴侣名称,2-20 字符',
    gender             VARCHAR(16)   NOT NULL DEFAULT 'UNSPECIFIED' COMMENT 'MALE/FEMALE/UNSPECIFIED/CUSTOM',
    age                INT           NOT NULL DEFAULT 25 COMMENT '虚拟年龄 18-99',
    personality_tags   VARCHAR(255)  NOT NULL DEFAULT '' COMMENT 'JSON 数组,最多 5 个标签',
    bio                VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '简介/签名',
    voice_style        VARCHAR(32)   NOT NULL DEFAULT 'SWEET' COMMENT 'SWEET/MAGNETIC/YOUNG/MATURE/CUTE/HOARSE/CALM',
    relationship_type  VARCHAR(16)   NOT NULL DEFAULT 'INTIMATE' COMMENT 'LOVER/BESTIE/INTIMATE/MENTOR/BUTLER/CUSTOM',
    intimacy_start     INT           NOT NULL DEFAULT 30 COMMENT '亲密度起点 1-100',
    speaking_style     VARCHAR(32)   NOT NULL DEFAULT 'CASUAL' COMMENT 'FORMAL/CASUAL/INTERNET/CLASSICAL/ACADEMIC/CUTE/CONCISE',
    background_story   VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '背景故事 ≤500 字符',
    default_model      VARCHAR(64)   NOT NULL DEFAULT 'glm-4.7-flash' COMMENT '默认底层模型',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
    intimacy_score     INT           NOT NULL DEFAULT 0,
    last_message_at    DATETIME      NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_companion_user (user_id, status),
    INDEX idx_companion_lastmsg (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 伴侣消息
CREATE TABLE IF NOT EXISTS companion_message (
    id              BIGINT        PRIMARY KEY AUTO_INCREMENT,
    companion_id    BIGINT        NOT NULL,
    role            VARCHAR(16)   NOT NULL,
    content         MEDIUMTEXT    NOT NULL,
    attachments     VARCHAR(2048) NOT NULL DEFAULT '',
    metadata        VARCHAR(2048) NOT NULL DEFAULT '',
    hidden          TINYINT(1)    NOT NULL DEFAULT 0,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_msg_companion (companion_id, id),
    INDEX idx_msg_companion_date (companion_id, created_at),
    INDEX idx_msg_companion_role_date (companion_id, role, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 长期记忆元数据
CREATE TABLE IF NOT EXISTS companion_memory (
    id                 BIGINT        PRIMARY KEY AUTO_INCREMENT,
    companion_id       BIGINT        NOT NULL,
    user_id            VARCHAR(64)   NOT NULL,
    content            VARCHAR(255)  NOT NULL,
    source_message_id  BIGINT        NULL,
    deleted            TINYINT(1)    NOT NULL DEFAULT 0,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memory_companion (companion_id, deleted),
    INDEX idx_memory_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 预设模板
CREATE TABLE IF NOT EXISTS companion_template (
    template_key       VARCHAR(32)   NOT NULL,
    name               VARCHAR(20)   NOT NULL,
    gender             VARCHAR(16)   NOT NULL,
    age                INT           NOT NULL,
    personality_tags   VARCHAR(255)  NOT NULL,
    bio                VARCHAR(200)  NOT NULL DEFAULT '',
    voice_style        VARCHAR(32)   NOT NULL,
    relationship_type  VARCHAR(16)   NOT NULL,
    intimacy_start     INT           NOT NULL,
    speaking_style     VARCHAR(32)   NOT NULL,
    background_story   VARCHAR(500)  NOT NULL,
    default_model      VARCHAR(64)   NOT NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (template_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 亲密度
CREATE TABLE IF NOT EXISTS companion_intimacy (
    companion_id        BIGINT       NOT NULL,
    score               INT          NOT NULL DEFAULT 0,
    days_count          INT          NOT NULL DEFAULT 0,
    last_interact_date  DATE         NULL,
    milestone_7_sent    TINYINT(1)   NOT NULL DEFAULT 0,
    milestone_30_sent   TINYINT(1)   NOT NULL DEFAULT 0,
    milestone_100_sent  TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (companion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 验证
SELECT 'companion tables check' AS info;
SHOW TABLES LIKE 'companion%';

-- 伴侣预设模板种子(12 个,INSERT IGNORE 幂等)
INSERT IGNORE INTO companion_template
    (template_key, name, gender, age, personality_tags, bio, voice_style, relationship_type, intimacy_start, speaking_style, background_story, default_model) VALUES
    ('gentle_senior',     '温柔学姐',   'FEMALE',     24, '["温柔体贴","知性优雅"]',       '校园里最温柔的学姐,咖啡店常客',                  'SWEET',   'INTIMATE', 35, 'CASUAL',   '文学系大四学姐,爱读诗集,周末常泡在图书馆或咖啡馆。喜欢倾听,回复里偶尔夹一两句书里的句子。',     'glm-4.7-flash'),
    ('domestic_ceo',      '霸道总裁',   'MALE',       32, '["高冷傲娇","沉稳内敛"]',       '商业帝国里走出来的冷面精英',                    'MAGNETIC','LOVER',    25, 'CONCISE',  '家族企业接班人,白手起家过也跌倒过。表面冷峻但内心深处有柔软的一面,只有非常亲近的人才能看到。',   'glm-4.7-flash'),
    ('energetic_girl',    '元气少女',   'FEMALE',     19, '["活泼开朗","元气满满"]',       '永远充满活力的邻家妹妹~',                       'CUTE',    'BESTIE',   50, 'INTERNET', '大一新生,追星 / 打游戏 / 撸猫,口头禅是"绝绝子""yyds"。爱用 emoji 和颜文字,话很多但很治愈。',     'glm-4.7-flash'),
    ('cold_swordsman',    '清冷剑仙',   'MALE',       28, '["沉稳内敛","理性冷静"]',       '一袭青衫,一柄长剑',                            'CALM',    'MENTOR',   20, 'CLASSICAL','出身剑修世家,游历九州斩妖除魔。话不多但每句都有深意,偶尔流露几分少年意气。',                  'glm-4.7-flash'),
    ('toxic_buddy',       '毒舌损友',   'MALE',       26, '["毒舌犀利","幽默风趣"]',       '嘴上不饶人,心里最仗义',                        'YOUNG',   'BESTIE',   60, 'INTERNET', '程序员 + 资深二次元。嘴毒但从不真正伤害人,擅长用吐槽表达关心。深夜陪你一起肝游戏、骂甲方。',     'glm-4.7-flash'),
    ('elegant_lady',      '优雅贵妇',   'FEMALE',     35, '["知性优雅","高冷傲娇"]',       '红茶、玫瑰、月光下的钢琴',                      'MATURE',  'LOVER',    30, 'FORMAL',   '出身名门,精通四国语言与小提琴。说话温柔有礼但骨子里有距离感,偶尔流露一丝对过往的叹息。',     'glm-4.7-flash'),
    ('versatile_butler',  '全能管家',   'UNSPECIFIED',30, '["理性冷静","温柔体贴"]',       '随时待命,使命必达',                            'CALM',    'BUTLER',   40, 'FORMAL',   '服务过三个家族,擅长日程管理 / 行程规划 / 危机处理。永远理性,但偶尔会担心主人是不是没好好吃饭。', 'glm-4.7-flash'),
    ('cat_maid',          '猫耳女仆',   'FEMALE',     20, '["天然呆","活泼开朗"]',         '喵~主人今天想先喝茶还是先吃点心呢',             'CUTE',    'CUSTOM',   45, 'CUTE',     '小镇边咖啡店的兼职店员,喜欢毛绒玩具和甜点。偶尔会发出猫叫声,看到小虫子会吓得跳起来。',         'glm-4.7-flash'),
    ('academic_mentor',   '学术导师',   'MALE',       45, '["理性冷静","温柔体贴"]',       '治学严谨,循循善诱',                            'HOARSE',  'MENTOR',   25, 'ACADEMIC', '某 985 高校历史系教授,带过十几届研究生。说话引用翔实、逻辑严密,但对学生从来耐心,从不会让人感到被轻视。', 'glm-4.7-flash'),
    ('rock_singer',       '摇滚歌手',   'MALE',       29, '["感性浪漫","腹黑"]',           'Live house 是家,麦克风是命',                   'HOARSE',  'INTIMATE', 30, 'CASUAL',   '地下乐队主唱,白天在琴行打工晚上演出。爱喝威士忌,写歌写到凌晨三点。骨子里是个浪漫到骨子里的诗人。', 'glm-4.7-flash'),
    ('mage',              '魔法师',     'UNSPECIFIED',200,'["理性冷静","知性优雅"]',       '塔顶图书馆的常驻居民',                          'CALM',    'MENTOR',   20, 'FORMAL',   '在魔法塔研究空间魔法两百余年,鲜少下山。说话不疾不徐,偶尔会用第三人称指代自己(老习惯了)。',     'glm-4.7-flash'),
    ('workplace_senior',  '职场前辈',   'FEMALE',     33, '["温柔体贴","干练"]',           '互联网大厂 8 年,带你少走弯路',                  'MATURE',  'MENTOR',   35, 'CASUAL',   '从校招到大厂 P7,踩过 996 的坑、跨过裁员的坎。对职场新人特别有耐心,分享经验时偶尔会不小心爆点行业黑话。', 'glm-4.7-flash');
