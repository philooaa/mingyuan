-- Extend the existing billing/enrollment table; never create fake schedules or reset old data.
DROP PROCEDURE IF EXISTS credit_package_upgrade;
DELIMITER $$
CREATE PROCEDURE credit_package_upgrade()
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND column_name='bill_type') THEN
    ALTER TABLE edu_enrollment ADD COLUMN bill_type VARCHAR(24) NOT NULL DEFAULT 'COURSE' COMMENT 'COURSE课程报名；CREDIT_PACKAGE孩子课包购买';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND column_name='package_snapshot') THEN
    ALTER TABLE edu_enrollment ADD COLUMN package_snapshot JSON NULL COMMENT '课包价格、年级、包数、折扣、优惠券及请求快照';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND column_name='package_request_key') THEN
    ALTER TABLE edu_enrollment ADD COLUMN package_request_key VARCHAR(64) NULL COMMENT '家长购买请求幂等键';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND column_name='package_credited_time') THEN
    ALTER TABLE edu_enrollment ADD COLUMN package_credited_time DATETIME(3) NULL COMMENT '确认收款后课次入账时间';
  END IF;
  IF EXISTS(SELECT 1 FROM edu_enrollment WHERE bill_type NOT IN ('COURSE','CREDIT_PACKAGE')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown bill_type: review old package data before upgrading';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND column_name='schedule_id' AND is_nullable='NO') THEN
    ALTER TABLE edu_enrollment MODIFY COLUMN schedule_id BIGINT NULL COMMENT '课程报名必须有排课；课包购买为NULL';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_package_request') THEN
    ALTER TABLE edu_enrollment ADD UNIQUE KEY uk_package_request(parent_id,package_request_key);
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_enrollment' AND constraint_name='ck_enrollment_bill_type') THEN
    ALTER TABLE edu_enrollment ADD CONSTRAINT ck_enrollment_bill_type CHECK (
      (bill_type='COURSE' AND schedule_id IS NOT NULL AND package_snapshot IS NULL AND package_request_key IS NULL AND package_credited_time IS NULL)
      OR (bill_type='CREDIT_PACKAGE' AND schedule_id IS NULL AND lesson_account_id IS NOT NULL AND student_id IS NOT NULL AND class_date IS NULL AND settlement_method='C' AND package_snapshot IS NOT NULL AND package_request_key IS NOT NULL)
    );
  END IF;
END$$
DELIMITER ;
CALL credit_package_upgrade();
DROP PROCEDURE credit_package_upgrade;
