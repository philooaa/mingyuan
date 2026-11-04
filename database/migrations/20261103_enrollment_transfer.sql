-- 本地班课换课：仅新增换课历史及教务的报名只读菜单权限，不改动账单或历史考勤。
CREATE TABLE IF NOT EXISTS edu_enrollment_transfer (
  transfer_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  enrollment_id BIGINT NOT NULL,
  from_schedule_id BIGINT NOT NULL,
  to_schedule_id BIGINT NOT NULL,
  from_course_name VARCHAR(200) NOT NULL,
  to_course_name VARCHAR(200) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  operator_id BIGINT NOT NULL,
  operator_name VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  connection_id BIGINT NOT NULL,
  applied_at DATETIME DEFAULT NULL,
  KEY idx_enrollment_transfer(enrollment_id,transfer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
  ON m.perms IN ('system:enrollment:list','system:enrollment:query')
  OR m.menu_id IN (SELECT parent_id FROM sys_menu WHERE perms='system:enrollment:list')
WHERE r.role_key='education'
 AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);

-- Only this connection's single-use staff audit record can authorize a course change.
DELIMITER $$
DROP TRIGGER IF EXISTS tuition_bill_guard$$
CREATE TRIGGER tuition_bill_guard BEFORE UPDATE ON edu_enrollment FOR EACH ROW
BEGIN
DECLARE transfer BIGINT DEFAULT NULL;
IF NOT(OLD.schedule_id<=>NEW.schedule_id) THEN
 SELECT MAX(x.transfer_id) INTO transfer FROM edu_enrollment_transfer x
 JOIN sys_user u ON u.user_id=x.operator_id AND u.status='0' AND u.del_flag='0'
 WHERE x.enrollment_id=OLD.enrollment_id AND x.from_schedule_id=OLD.schedule_id AND x.to_schedule_id=NEW.schedule_id
   AND x.connection_id=CONNECTION_ID() AND x.applied_at IS NULL
   AND x.create_time>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)
   AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
       WHERE ur.user_id=u.user_id AND r.role_key IN ('admin','business_admin','education') AND r.status='0' AND r.del_flag='0');
 IF transfer IS NULL OR OLD.class_date IS NOT NULL OR NOT(OLD.class_date<=>NEW.class_date)
   OR OLD.enrollment_status NOT IN ('0','1','待确认','已确认','报名成功')
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Use the audited staff enrollment transfer'; END IF;
 UPDATE edu_enrollment_transfer SET applied_at=NOW() WHERE transfer_id=transfer;
END IF;
IF EXISTS(SELECT 1 FROM edu_tuition_payment WHERE enrollment_id=OLD.enrollment_id) THEN
 IF NOT(OLD.parent_id<=>NEW.parent_id) OR NOT(OLD.student_name<=>NEW.student_name) OR NOT(OLD.enrollment_code<=>NEW.enrollment_code) OR NOT(OLD.finance_mode<=>NEW.finance_mode) OR NOT(OLD.del_flag<=>NEW.del_flag) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Financial enrollment ownership and mode are immutable'; END IF;
 IF EXISTS(SELECT 1 FROM edu_tuition_payment WHERE enrollment_id=OLD.enrollment_id AND payment_status='SUCCESS') AND (NOT(OLD.original_amount<=>NEW.original_amount) OR NOT(OLD.discount_amount<=>NEW.discount_amount) OR NOT(OLD.payable_amount<=>NEW.payable_amount) OR NOT(OLD.user_coupon_id<=>NEW.user_coupon_id) OR NOT(OLD.fee_title_snapshot<=>NEW.fee_title_snapshot) OR NOT(OLD.campus_name_snapshot<=>NEW.campus_name_snapshot) OR NOT(OLD.history_verification_snapshot<=>NEW.history_verification_snapshot)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Successful tuition bill snapshot is immutable'; END IF;
END IF;
END$$
DELIMITER ;
