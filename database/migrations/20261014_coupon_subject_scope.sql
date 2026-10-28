-- Additive migration. NULL/[] means all subjects; issued template rules remain immutable.
-- No account data, seed coupons, or sys table schema changes.
DELIMITER $$
DROP PROCEDURE IF EXISTS edu_coupon_subject_scope_patch$$
CREATE PROCEDURE edu_coupon_subject_scope_patch()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='edu_coupon_template' AND column_name='subject_names') THEN
    ALTER TABLE edu_coupon_template ADD COLUMN subject_names JSON NULL COMMENT '适用学科名称数组；NULL或空数组表示不限';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_coupon_template' AND column_name='subject_names' AND data_type='json') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='edu_coupon_template.subject_names must be JSON; review the existing schema';
  END IF;
END$$
CALL edu_coupon_subject_scope_patch()$$
DROP PROCEDURE edu_coupon_subject_scope_patch$$
DELIMITER ;

-- Keep old permission/route records for compatibility; expose one canonical entry.
UPDATE sys_menu SET menu_name='优惠券管理' WHERE component='system/coupon/index';
UPDATE sys_menu SET menu_name='推荐奖励（兼容入口）',visible='1' WHERE component='system/referral/index';
