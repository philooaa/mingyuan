-- Run after unified children and tuition finance, with backend stopped.
-- The verified replacement index keeps child/date/active uniqueness, including same-name siblings.
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS enrollment_identity_compat$$
CREATE PROCEDURE enrollment_identity_compat()
BEGIN
 IF COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_edu_enrollment_once'),'') <> 'schedule_id,parent_id,tuition_identity_guard,active_enrollment_guard' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Install verified finance identity index before replacing legacy guard';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_legacy_enrollment_once') THEN
  IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_legacy_enrollment_once') <> 'schedule_id,parent_id,student_name,legacy_once' THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected legacy enrollment index; manual review required';
  END IF;
  ALTER TABLE edu_enrollment DROP INDEX uk_legacy_enrollment_once;
 END IF;
END$$
CALL enrollment_identity_compat()$$
DROP PROCEDURE enrollment_identity_compat$$
DELIMITER ;
