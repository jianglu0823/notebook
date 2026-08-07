-- 迁移:十月怀胎 + 孩子重名规避(town-pregnancy-unique-names)
-- 背景:JPA ddl-auto=none,init.sql 仅在全新库生效。已初始化的库需手动执行本文件。
-- 执行方式:在 docker exec 内进入 MySQL 后运行(DB 写入需用户授权,建议用 `!` 前缀在本会话执行)。
--   ! docker exec -i nblm-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" notebooklm < notebooklm-app/changes/town-pregnancy-unique-names/migration.sql

ALTER TABLE agent_employee ADD COLUMN pregnant_since DATE NULL COMMENT '怀孕起始日(孕育方有值,null=未怀孕)' AFTER parent_ids;
ALTER TABLE agent_employee ADD COLUMN due_date       DATE NULL COMMENT '预产期(到期由每日结算触发分娩)' AFTER pregnant_since;
