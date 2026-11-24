-- Additive migration only; existing schedules and accounting data are not rewritten.
-- Bound metadata-lock waits; rerun after resolving concurrent long transactions.
SET SESSION lock_wait_timeout = 15;
CREATE TABLE IF NOT EXISTS edu_schedule_rule (
    rule_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_name VARCHAR(100) NOT NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    version BIGINT NOT NULL DEFAULT 1,
    remark VARCHAR(500) DEFAULT NULL,
    del_flag CHAR(1) NOT NULL DEFAULT '0',
    create_by VARCHAR(64) DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by VARCHAR(64) DEFAULT NULL,
    update_time DATETIME DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS edu_schedule_rule_date (
    rule_id BIGINT NOT NULL,
    class_date DATE NOT NULL,
    PRIMARY KEY (rule_id, class_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_course_schedule' AND column_name='schedule_rule_id'), 'SELECT 1', 'ALTER TABLE edu_course_schedule ADD COLUMN schedule_rule_id BIGINT DEFAULT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_course_schedule' AND column_name='schedule_rule_name'), 'SELECT 1', 'ALTER TABLE edu_course_schedule ADD COLUMN schedule_rule_name VARCHAR(100) DEFAULT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_course_schedule' AND column_name='schedule_rule_version'), 'SELECT 1', 'ALTER TABLE edu_course_schedule ADD COLUMN schedule_rule_version BIGINT DEFAULT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_course_schedule' AND column_name='custom_dates'), 'SELECT 1', 'ALTER TABLE edu_course_schedule ADD COLUMN custom_dates TEXT DEFAULT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Resolve the existing teaching-tools menu by identity, never by local numeric IDs.
SET @parent = (SELECT menu_id FROM sys_menu WHERE menu_name='教务工具' AND menu_type='M' ORDER BY menu_id LIMIT 1);
SET @parent = COALESCE(@parent, (SELECT parent_id FROM sys_menu WHERE component IN ('system/timetable/index','system/schedule/index') ORDER BY menu_id LIMIT 1));
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '排课规则',COALESCE(@parent,0),9,'scheduleRule','system/scheduleRule/index',1,0,'C','0','0','system:scheduleRule:list','date','admin',NOW(),'维护自定义上课日期；不联动已有排课'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:scheduleRule:list' OR component='system/scheduleRule/index');
SET @rule_menu=(SELECT menu_id FROM sys_menu WHERE component='system/scheduleRule/index' ORDER BY menu_id LIMIT 1);
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,@rule_menu FROM sys_role WHERE role_key IN ('admin','business_admin','education') AND del_flag='0' AND @rule_menu IS NOT NULL;
