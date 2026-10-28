-- 教师薪资「提成」：固定金额（元/月），不按比例计算。
-- 薪资标准里直接填提成金额；月度结算原样带入，应发 = 底薪 + 课时费 + 提成。
-- 幂等：重复执行安全；同时清理早期误加的按比例字段 commission_rate。
SET @salary_commission_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_config' AND column_name='commission_amount'),'SELECT 1','ALTER TABLE edu_teacher_salary_config ADD COLUMN commission_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''固定提成金额(元/月)''');
PREPARE salary_commission_stmt FROM @salary_commission_sql;
EXECUTE salary_commission_stmt;
DEALLOCATE PREPARE salary_commission_stmt;

SET @salary_commission_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_config' AND column_name='commission_rate'),'ALTER TABLE edu_teacher_salary_config DROP COLUMN commission_rate','SELECT 1');
PREPARE salary_commission_stmt FROM @salary_commission_sql;
EXECUTE salary_commission_stmt;
DEALLOCATE PREPARE salary_commission_stmt;

SET @salary_commission_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='commission_amount'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN commission_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''提成金额''');
PREPARE salary_commission_stmt FROM @salary_commission_sql;
EXECUTE salary_commission_stmt;
DEALLOCATE PREPARE salary_commission_stmt;

SET @salary_commission_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='commission_rate'),'ALTER TABLE edu_teacher_salary_record DROP COLUMN commission_rate','SELECT 1');
PREPARE salary_commission_stmt FROM @salary_commission_sql;
EXECUTE salary_commission_stmt;
DEALLOCATE PREPARE salary_commission_stmt;
