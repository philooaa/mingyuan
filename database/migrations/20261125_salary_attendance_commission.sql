-- 在已有特价课/班课人次功能基础上，新增人次单价及结算快照。
-- 不重算历史工资，不修改已保存的人次、考勤、报名或财务记录。重复执行安全。
SET SESSION lock_wait_timeout=15;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_config' AND column_name='class_attendance_unit_price'),'SELECT 1','ALTER TABLE edu_teacher_salary_config ADD COLUMN class_attendance_unit_price DECIMAL(10,2) NOT NULL DEFAULT 0.00');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='class_attendance_count'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN class_attendance_count BIGINT NOT NULL DEFAULT 0');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='class_attendance_unit_price'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN class_attendance_unit_price DECIMAL(10,2) NOT NULL DEFAULT 0.00');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='class_attendance_commission'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN class_attendance_commission DECIMAL(10,2) NOT NULL DEFAULT 0.00');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
