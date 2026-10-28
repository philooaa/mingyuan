-- 学生出勤单人复核：仅扩展现有表；安装前备份，不改历史状态、签到ID或财务数据。
SET @attendance_review_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_reason'),'SELECT 1','ALTER TABLE edu_class_sign_in ADD COLUMN review_reason VARCHAR(500) NULL COMMENT ''退回更正原因''');
PREPARE attendance_review_stmt FROM @attendance_review_sql;
EXECUTE attendance_review_stmt;
DEALLOCATE PREPARE attendance_review_stmt;
SET @attendance_review_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_version'),'SELECT 1','ALTER TABLE edu_class_sign_in ADD COLUMN review_version BIGINT NOT NULL DEFAULT 0 COMMENT ''课次修改版本''');
PREPARE attendance_review_stmt FROM @attendance_review_sql;
EXECUTE attendance_review_stmt;
DEALLOCATE PREPARE attendance_review_stmt;
SET @attendance_review_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_history'),'SELECT 1','ALTER TABLE edu_class_sign_in ADD COLUMN review_history JSON NULL COMMENT ''提交、退回、复核历史及更正前后快照''');
PREPARE attendance_review_stmt FROM @attendance_review_sql;
EXECUTE attendance_review_stmt;
DEALLOCATE PREPARE attendance_review_stmt;
