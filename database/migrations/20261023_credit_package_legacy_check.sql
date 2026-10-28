-- Apply after 20261020_credit_package_purchase.sql, with backend stopped and a backup.
-- Keep the legacy FK/archive tables and all rows; only reconcile its obsolete bill-type CHECK.
-- Do not silently convert historical LESSON_PACKAGE bills to the new CREDIT_PACKAGE model.
DELIMITER $$
DROP PROCEDURE IF EXISTS credit_package_legacy_check_upgrade$$
CREATE PROCEDURE credit_package_legacy_check_upgrade()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE() AND table_name='edu_enrollment'
      AND constraint_name='ck_enrollment_bill_type' AND enforced='YES') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Apply credit-package purchase migration first';
  END IF;
  IF EXISTS (SELECT 1 FROM edu_enrollment WHERE (
    (bill_type='COURSE' AND schedule_id IS NOT NULL AND lesson_package_id IS NULL)
    OR (bill_type='CREDIT_PACKAGE' AND schedule_id IS NULL AND student_id IS NOT NULL
        AND lesson_account_id IS NOT NULL AND lesson_package_id IS NULL)
  ) IS NOT TRUE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Legacy or malformed package bills require manual review; no rows changed';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema=DATABASE() AND table_name='edu_enrollment'
      AND constraint_name='ck_lenrollment_type' AND constraint_type='CHECK') THEN
    ALTER TABLE edu_enrollment DROP CHECK ck_lenrollment_type,
      ADD CONSTRAINT ck_lenrollment_type CHECK (
        (bill_type='COURSE' AND schedule_id IS NOT NULL AND lesson_package_id IS NULL)
        OR (bill_type='CREDIT_PACKAGE' AND schedule_id IS NULL AND student_id IS NOT NULL
            AND lesson_account_id IS NOT NULL AND lesson_package_id IS NULL)
      );
  ELSE
    ALTER TABLE edu_enrollment ADD CONSTRAINT ck_lenrollment_type CHECK (
      (bill_type='COURSE' AND schedule_id IS NOT NULL AND lesson_package_id IS NULL)
      OR (bill_type='CREDIT_PACKAGE' AND schedule_id IS NULL AND student_id IS NOT NULL
          AND lesson_account_id IS NOT NULL AND lesson_package_id IS NULL)
    );
  END IF;
END$$
CALL credit_package_legacy_check_upgrade()$$
DROP PROCEDURE credit_package_legacy_check_upgrade$$
DELIMITER ;
