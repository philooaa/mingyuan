-- Integrated child identity, MySQL 8.0. Stop backend and back up first.
-- Requires the dated-enrollment, teacher-profile and fixed-slot-credit migrations.
-- No name matching, balance changes, sys schema changes, or discarded financial history.
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS unified_children_preflight$$
CREATE PROCEDURE unified_children_preflight()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='edu_student_lesson_account') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Install fixed-slot lesson accounts before unified children';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('edu_student_package','edu_one_to_one_booking','edu_one_to_one_slot','edu_lesson_unit_log')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Competing lesson schema found: review its records before integration';
 END IF;
END$$
CALL unified_children_preflight()$$
DROP PROCEDURE unified_children_preflight$$
DELIMITER ;
CREATE TABLE IF NOT EXISTS edu_student (
 student_id BIGINT NOT NULL AUTO_INCREMENT,
 parent_id BIGINT NOT NULL,
 student_name VARCHAR(30) NOT NULL,
 student_phone VARCHAR(11) NULL,
 grade_code VARCHAR(30) NULL,
 status CHAR(1) NOT NULL DEFAULT '0',
 del_flag CHAR(1) NOT NULL DEFAULT '0',
 create_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM',
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 remark VARCHAR(500) NULL,
 PRIMARY KEY(student_id),
 UNIQUE KEY uk_lstudent_owner(student_id,parent_id),
 KEY idx_lstudent_parent(parent_id,del_flag,status,student_id),
 CONSTRAINT fk_lstudent_parent FOREIGN KEY(parent_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一孩子档案；课次账户仅维护额度';
DELIMITER $$
DROP PROCEDURE IF EXISTS unified_children_columns$$
CREATE PROCEDURE unified_children_columns()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_student_lesson_account' AND column_name='student_id') THEN
  ALTER TABLE edu_student_lesson_account ADD COLUMN student_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND column_name='student_id') THEN
  ALTER TABLE edu_enrollment ADD COLUMN student_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_student_lesson_account' AND index_name='uk_credit_student') THEN
  ALTER TABLE edu_student_lesson_account ADD UNIQUE KEY uk_credit_student(student_id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_student_lesson_account' AND constraint_name='fk_credit_student_owner') THEN
  ALTER TABLE edu_student_lesson_account ADD CONSTRAINT fk_credit_student_owner FOREIGN KEY(student_id,parent_id) REFERENCES edu_student(student_id,parent_id) ON DELETE RESTRICT ON UPDATE RESTRICT;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_enrollment' AND constraint_name='fk_enrollment_student_owner') THEN
  ALTER TABLE edu_enrollment ADD CONSTRAINT fk_enrollment_student_owner FOREIGN KEY(student_id,parent_id) REFERENCES edu_student(student_id,parent_id) ON DELETE RESTRICT ON UPDATE RESTRICT;
 END IF;
END$$
CALL unified_children_columns()$$
DROP PROCEDURE unified_children_columns$$
DROP PROCEDURE IF EXISTS unified_children_backfill$$
CREATE PROCEDURE unified_children_backfill()
BEGIN
 DECLARE done BOOLEAN DEFAULT FALSE;
 DECLARE account BIGINT; DECLARE owner BIGINT; DECLARE child BIGINT;
 DECLARE child_name VARCHAR(30); DECLARE grade VARCHAR(20); DECLARE phone VARCHAR(11);
 DECLARE operator_name VARCHAR(64); DECLARE created DATETIME; DECLARE grade_value VARCHAR(30);
 DECLARE accounts CURSOR FOR SELECT account_id,parent_id,student_name,grade_name,contact_phone,create_by,create_time FROM edu_student_lesson_account WHERE student_id IS NULL ORDER BY account_id;
 DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=TRUE;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 -- Existing independent student records must be explicitly mapped, never guessed by name.
 IF EXISTS(SELECT 1 FROM edu_student s WHERE NOT EXISTS(SELECT 1 FROM edu_student_lesson_account a WHERE a.student_id=s.student_id)) AND EXISTS(SELECT 1 FROM edu_student_lesson_account WHERE student_id IS NULL) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unmapped independent students found: explicit identity mapping required';
 END IF;
 START TRANSACTION;
 OPEN accounts;
 account_loop: LOOP
  FETCH accounts INTO account,owner,child_name,grade,phone,operator_name,created;
  IF done THEN LEAVE account_loop; END IF;
  SET grade_value=(SELECT MIN(dict_value) FROM sys_dict_data WHERE dict_type='edu_grade' AND dict_label=grade AND status='0');
  IF grade_value IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown account grade: review before child migration'; END IF;
  INSERT INTO edu_student(parent_id,student_name,student_phone,grade_code,create_by,create_time,update_by)
   VALUES(owner,child_name,phone,grade_value,operator_name,created,'migration');
  SET child=LAST_INSERT_ID();
  UPDATE edu_student_lesson_account SET student_id=child WHERE account_id=account AND student_id IS NULL;
 END LOOP;
 CLOSE accounts;
 -- Only follow already-established account foreign keys. Legacy name-only enrollments stay unlinked.
 UPDATE edu_enrollment e JOIN edu_student_lesson_account a ON a.account_id=e.lesson_account_id AND a.parent_id=e.parent_id
  SET e.student_id=a.student_id WHERE e.student_id IS NULL;
 IF EXISTS(SELECT 1 FROM edu_enrollment e JOIN edu_student_lesson_account a ON a.account_id=e.lesson_account_id WHERE e.parent_id<>a.parent_id OR NOT(e.student_id<=>a.student_id)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Enrollment/account identity mismatch; backfill rolled back';
 END IF;
 COMMIT;
END$$
CALL unified_children_backfill()$$
DROP PROCEDURE unified_children_backfill$$
DELIMITER ;
