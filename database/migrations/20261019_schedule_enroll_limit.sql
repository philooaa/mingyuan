-- 课程排课增加「报名上限」：0 或 NULL 表示不限人数。
-- 幂等：重复执行安全。
SET @schedule_enroll_limit_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_course_schedule' AND column_name='enroll_limit'),'SELECT 1','ALTER TABLE edu_course_schedule ADD COLUMN enroll_limit INT NULL DEFAULT NULL COMMENT ''报名上限，NULL或0表示不限''');
PREPARE schedule_enroll_limit_stmt FROM @schedule_enroll_limit_sql;
EXECUTE schedule_enroll_limit_stmt;
DEALLOCATE PREPARE schedule_enroll_limit_stmt;
