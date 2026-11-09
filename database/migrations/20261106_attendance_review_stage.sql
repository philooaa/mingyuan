-- Additive upgrade: legacy review_status=1 remains approved without invented reviewers.
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_attendance_review_20261106$$
CREATE PROCEDURE migrate_attendance_review_20261106()
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_stage') THEN
    ALTER TABLE edu_class_sign_in ADD COLUMN review_stage VARCHAR(24) NULL COMMENT 'PENDING_FIRST/PENDING_FINAL/FINAL/RETURNED; NULL legacy';
  END IF;
END$$
CALL migrate_attendance_review_20261106()$$
DROP PROCEDURE migrate_attendance_review_20261106$$
DELIMITER ;
