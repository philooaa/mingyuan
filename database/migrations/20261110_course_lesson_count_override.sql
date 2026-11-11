-- Billing lesson count override. Does not alter enrollment, payment or receipt snapshots.
SET NAMES utf8mb4;
SET @count_override_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='edu_course_schedule' AND column_name='lesson_count_override'),
  'SELECT 1',
  'ALTER TABLE edu_course_schedule ADD COLUMN lesson_count_override INT NULL COMMENT "手动计费课次数；NULL为系统自动计算", ADD COLUMN lesson_count_override_reason VARCHAR(255) NULL COMMENT "手动计费课次数修改原因"');
PREPARE count_override_stmt FROM @count_override_ddl;
EXECUTE count_override_stmt;
DEALLOCATE PREPARE count_override_stmt;
