-- 日期级调课所需字段；保留历史单课记录，可重复执行。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='edu_schedule_adjustment' AND COLUMN_NAME='term_name'), 'SELECT 1',
  'ALTER TABLE edu_schedule_adjustment ADD COLUMN term_name varchar(50) DEFAULT NULL COMMENT ''学期''');
PREPARE adjustment_stmt FROM @ddl; EXECUTE adjustment_stmt; DEALLOCATE PREPARE adjustment_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='edu_schedule_adjustment' AND COLUMN_NAME='period_name'), 'SELECT 1',
  'ALTER TABLE edu_schedule_adjustment ADD COLUMN period_name varchar(50) DEFAULT NULL COMMENT ''期次或上课日''');
PREPARE adjustment_stmt FROM @ddl; EXECUTE adjustment_stmt; DEALLOCATE PREPARE adjustment_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='edu_schedule_adjustment' AND COLUMN_NAME='course_count'), 'SELECT 1',
  'ALTER TABLE edu_schedule_adjustment ADD COLUMN course_count int DEFAULT NULL COMMENT ''日期级记录影响课程数''');
PREPARE adjustment_stmt FROM @ddl; EXECUTE adjustment_stmt; DEALLOCATE PREPARE adjustment_stmt;

UPDATE edu_schedule_adjustment a JOIN edu_course_schedule s ON s.schedule_id=a.schedule_id
SET a.term_name=COALESCE(a.term_name,s.term_name), a.period_name=COALESCE(a.period_name,s.period_name)
WHERE a.schedule_id<>0 AND (a.term_name IS NULL OR a.period_name IS NULL);

-- schedule_id=0 时，不同学期/期次可以在同一天分别调课。
SET @old_index_columns = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='edu_schedule_adjustment' AND INDEX_NAME='uk_schedule_date');
SET @ddl = IF(@old_index_columns=4, 'SELECT 1',
  CONCAT('ALTER TABLE edu_schedule_adjustment ', IF(@old_index_columns>0,'DROP INDEX uk_schedule_date, ',''),
    'ADD UNIQUE INDEX uk_schedule_date(schedule_id,term_name,period_name,original_date)'));
PREPARE adjustment_stmt FROM @ddl; EXECUTE adjustment_stmt; DEALLOCATE PREPARE adjustment_stmt;
