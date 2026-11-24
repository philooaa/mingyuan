-- Read-only post-migration check. Run with the intended production database selected.
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SELECT required.column_name AS missing_column
FROM (
    SELECT 'schedule_rule_id' AS column_name UNION ALL
    SELECT 'schedule_rule_name' UNION ALL
    SELECT 'schedule_rule_version' UNION ALL
    SELECT 'custom_dates'
) required
LEFT JOIN information_schema.columns actual
  ON actual.table_schema=DATABASE() AND actual.table_name='edu_course_schedule'
 AND actual.column_name=required.column_name
WHERE actual.column_name IS NULL;
SELECT required.table_name AS missing_table
FROM (
    SELECT 'edu_schedule_rule' AS table_name UNION ALL
    SELECT 'edu_schedule_rule_date'
) required
LEFT JOIN information_schema.tables actual
  ON actual.table_schema=DATABASE() AND actual.table_name=required.table_name
WHERE actual.table_name IS NULL;
SELECT column_name,column_type,is_nullable,column_default
FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='edu_course_schedule'
  AND column_name IN ('schedule_rule_id','schedule_rule_name','schedule_rule_version','custom_dates');
SELECT menu_id,parent_id,component,perms FROM sys_menu
WHERE component='system/scheduleRule/index';
SELECT r.role_key FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.role_id
JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.component='system/scheduleRule/index';
SELECT COUNT(*) AS custom_course_count FROM edu_course_schedule WHERE class_pattern='CUSTOM_DATES';
