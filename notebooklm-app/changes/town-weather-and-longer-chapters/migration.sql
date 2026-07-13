-- 迁移:小镇天气系统温度列(town-weather-and-longer-chapters)
-- 背景:JPA ddl-auto=none,init.sql 仅在全新库生效。已初始化的库需手动执行本文件。
-- 执行方式:在 docker exec 内进入 MySQL 后运行(DB 写入需用户授权,建议用 `!` 前缀在本会话执行)。
--   ! docker exec -i nblm-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" notebooklm < notebooklm-app/changes/town-weather-and-longer-chapters/migration.sql

ALTER TABLE agent_world_settings ADD COLUMN temperature INT NULL COMMENT '当前温度(℃,可空)' AFTER weather;
ALTER TABLE world_daily_report   ADD COLUMN temperature INT NULL COMMENT '当日温度(℃,可空)' AFTER weather;
