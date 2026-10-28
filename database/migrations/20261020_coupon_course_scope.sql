-- Old coupons retain LEGACY scope (including any specific schedule/subject restriction).
DROP PROCEDURE IF EXISTS coupon_scope_upgrade;
DELIMITER $$
CREATE PROCEDURE coupon_scope_upgrade()
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_coupon_template' AND column_name='course_scope') THEN
    ALTER TABLE edu_coupon_template ADD COLUMN course_scope VARCHAR(16) NOT NULL DEFAULT 'LEGACY' COMMENT 'LEGACY历史规则；ONE_TO_ONE一对一；CLASS大班课；ALL两类通用';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_coupon_template' AND constraint_name='ck_coupon_course_scope') THEN
    ALTER TABLE edu_coupon_template ADD CONSTRAINT ck_coupon_course_scope CHECK(course_scope IN ('LEGACY','ONE_TO_ONE','CLASS','ALL') AND (course_scope='LEGACY' OR scope_schedule_id IS NULL));
  END IF;
END$$
DELIMITER ;
CALL coupon_scope_upgrade();
DROP PROCEDURE coupon_scope_upgrade;
