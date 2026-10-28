-- 签到稳定身份兼容：先备份、停后端；不更新或删除任何历史考勤。
-- 有重复报名身份或不符合预期的旧索引时停止，须人工核对，不能自动合并考勤。
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_class_sign_identity_20261011$$
CREATE PROCEDURE migrate_class_sign_identity_20261011()
BEGIN
  DECLARE expression_text TEXT;
  DECLARE column_extra VARCHAR(255);
  IF EXISTS(SELECT 1 FROM edu_class_sign_in GROUP BY schedule_id,class_date,
      CASE WHEN enrollment_id IS NOT NULL THEN CONCAT('E:',enrollment_id) ELSE CONCAT('N:',student_name) END HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate attendance identities require manual review; no attendance changed';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND index_name='uk_schedule_date_student')
    AND COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND index_name='uk_schedule_date_student' AND non_unique=0),'')<>'schedule_id,class_date,student_name' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected legacy attendance index; inspect before migration';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='sign_identity') THEN
    ALTER TABLE edu_class_sign_in ADD COLUMN sign_identity VARCHAR(100) GENERATED ALWAYS AS
      (CASE WHEN enrollment_id IS NOT NULL THEN CONCAT('E:',CAST(enrollment_id AS CHAR CHARSET utf8mb4)) ELSE CONCAT('N:',student_name) END) STORED;
  END IF;
  SELECT generation_expression,extra INTO expression_text,column_extra FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='sign_identity';
  IF expression_text IS NULL OR column_extra NOT LIKE '%STORED GENERATED%'
    OR REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(LOWER(expression_text),CHAR(92),''),'`',''),'_utf8mb4',''),'[[:space:]()]','')
      <>REGEXP_REPLACE(LOWER('CASE WHEN enrollment_id IS NOT NULL THEN CONCAT(''E:'',CAST(enrollment_id AS CHAR CHARSET utf8mb4)) ELSE CONCAT(''N:'',student_name) END'),'[[:space:]()]','') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected attendance generated identity; no legacy index removed';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND index_name='uk_sign_occurrence_identity') THEN
    ALTER TABLE edu_class_sign_in ADD UNIQUE KEY uk_sign_occurrence_identity(schedule_id,class_date,sign_identity);
  ELSEIF COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND index_name='uk_sign_occurrence_identity' AND non_unique=0),'')<>'schedule_id,class_date,sign_identity' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected attendance identity index; inspect before migration';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND index_name='uk_schedule_date_student') THEN
    ALTER TABLE edu_class_sign_in DROP INDEX uk_schedule_date_student;
  END IF;
END$$
CALL migrate_class_sign_identity_20261011()$$
DROP PROCEDURE migrate_class_sign_identity_20261011$$
DELIMITER ;
