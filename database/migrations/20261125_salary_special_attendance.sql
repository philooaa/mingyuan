-- 特价课独立时薪；班课人次只记录数量，不参与金额计算。
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_config' AND column_name='rate_special_class'),'SELECT 1','ALTER TABLE edu_teacher_salary_config ADD COLUMN rate_special_class DECIMAL(10,2) NOT NULL DEFAULT 0.00');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='special_class_count'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN special_class_count BIGINT NOT NULL DEFAULT 0');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='special_class_hours'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN special_class_hours DECIMAL(8,1) NOT NULL DEFAULT 0.0');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;
SET @salary_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_teacher_salary_record' AND column_name='special_class_amount'),'SELECT 1','ALTER TABLE edu_teacher_salary_record ADD COLUMN special_class_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00');
PREPARE salary_stmt FROM @salary_ddl; EXECUTE salary_stmt; DEALLOCATE PREPARE salary_stmt;

CREATE TABLE IF NOT EXISTS edu_teacher_class_attendance_adjustment (
 teacher_id BIGINT NOT NULL,
 salary_month CHAR(7) NOT NULL,
 attendance_count BIGINT NOT NULL,
 update_by VARCHAR(64) NOT NULL DEFAULT '',
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(teacher_id,salary_month),
 CONSTRAINT ck_teacher_class_attendance_nonnegative CHECK(attendance_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,status,is_default,create_by,create_time)
SELECT COALESCE(MAX(dict_sort),0)+1,'特价课','特价课','edu_class_type','0','N','admin',NOW() FROM sys_dict_data
WHERE dict_type='edu_class_type'
HAVING NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND (dict_value='特价课' OR dict_label='特价课'));
