-- ============================================================================
-- 迁移:智能体小镇 经济与金融体系(town-economy-finance)
-- 目标库:notebooklm(容器 nblm-mysql)。项目 ddl-auto:none,需手工执行本脚本。
-- 全部可重复执行:表用 CREATE TABLE IF NOT EXISTS;索引/种子见各段说明。
-- 执行方式(示例):
--   docker exec -i nblm-mysql mysql -unblm -p'<PW>' notebooklm < migration.sql
-- ============================================================================

-- 1) 每日经济快照(每日一行,PK=sim_date 天然幂等)
CREATE TABLE IF NOT EXISTS town_economy_daily (
    sim_date            DATE          NOT NULL COMMENT '小镇日期(主键)',
    total_coins         BIGINT        NOT NULL DEFAULT 0 COMMENT '结算后全体活跃居民金币总量',
    total_income        BIGINT        NOT NULL DEFAULT 0 COMMENT '当日总收入(正向流水之和)',
    total_expense       BIGINT        NOT NULL DEFAULT 0 COMMENT '当日总支出(负向流水绝对值之和)',
    total_place_revenue BIGINT        NOT NULL DEFAULT 0 COMMENT '当日场所营收总额',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (sim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) 场所营收(每日每场所一行)
CREATE TABLE IF NOT EXISTS place_revenue (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    sim_date    DATE          NOT NULL COMMENT '小镇日期',
    place_key   VARCHAR(32)   NOT NULL COMMENT '场所 key(对齐 TownMap:restaurant/grocery/clinic/market)',
    amount      BIGINT        NOT NULL DEFAULT 0 COMMENT '当日营收',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_place_rev (sim_date, place_key),
    INDEX idx_place_rev_date (sim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) 股票定义(种子数据,code 唯一)
CREATE TABLE IF NOT EXISTS town_stock (
    code        VARCHAR(16)   NOT NULL COMMENT '股票代码(主键)',
    name        VARCHAR(64)   NOT NULL COMMENT '股票名称',
    sector      VARCHAR(32)   NULL COMMENT '板块(餐饮/文创/医疗/零售…,可挂钩场所)',
    base_price  BIGINT        NOT NULL DEFAULT 100 COMMENT '初始价(正数)',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4) 每日行情(每股每日一行)
CREATE TABLE IF NOT EXISTS stock_daily (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    sim_date    DATE          NOT NULL COMMENT '小镇日期',
    code        VARCHAR(16)   NOT NULL COMMENT '股票代码',
    open_price  BIGINT        NOT NULL DEFAULT 0 COMMENT '开盘价(=前收)',
    close_price BIGINT        NOT NULL DEFAULT 0 COMMENT '收盘价(正数)',
    change_pct  DECIMAL(6,4)  NOT NULL DEFAULT 0 COMMENT '当日涨跌幅(小数,如 0.0800=+8%)',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_daily (sim_date, code),
    INDEX idx_stock_daily_code (code, sim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5) 居民持仓(每人每股一行)
CREATE TABLE IF NOT EXISTS stock_holding (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id    BIGINT        NOT NULL COMMENT '居民 id',
    code        VARCHAR(16)   NOT NULL COMMENT '股票代码',
    shares      BIGINT        NOT NULL DEFAULT 0 COMMENT '持有股数',
    cost        BIGINT        NOT NULL DEFAULT 0 COMMENT '累计买入总成本(金币),浮盈=现价×股数-cost',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_holding (agent_id, code),
    INDEX idx_holding_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6) agent_transaction 加复合索引以支撑日/月净收入聚合。
--    MySQL 8 无 "ADD INDEX IF NOT EXISTS";若重复执行报 1061(Duplicate key name)可忽略。
ALTER TABLE agent_transaction ADD INDEX idx_txn_agent_date (agent_id, sim_date);

-- 7) 股票种子(可重复执行:INSERT IGNORE 按 code 去重)
INSERT IGNORE INTO town_stock (code, name, sector, base_price) VALUES
    ('CATER', '鹿鸣食业',  'catering', 100),
    ('CULT',  '林间文创',  'culture',  120),
    ('MED',   '回春医健',  'medical',  150),
    ('RETAIL','井畔零售',  'retail',    80),
    ('ARTS',  '匠作工坊',  'crafts',    90);
