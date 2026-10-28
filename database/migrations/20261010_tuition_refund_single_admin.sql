-- 单管理员开发阶段：允许本人审核；上线真实收款前须恢复双人复核。
-- 先停止后端并备份。依赖 20261007_tuition_finance.sql；在目标库执行，可重复运行。
-- 不更新退款记录，不改变金额、状态、用户、角色；保留成功退款事实不可修改保护。
DELIMITER $$
DROP TRIGGER IF EXISTS tuition_refund_immutable$$
CREATE TRIGGER tuition_refund_immutable BEFORE UPDATE ON edu_tuition_refund FOR EACH ROW
BEGIN
IF OLD.refund_status='SUCCESS' AND (NOT(OLD.payment_id<=>NEW.payment_id) OR NOT(OLD.refund_no<=>NEW.refund_no) OR NOT(OLD.refund_kind<=>NEW.refund_kind) OR NOT(OLD.requested_amount<=>NEW.requested_amount) OR NOT(OLD.approved_amount<=>NEW.approved_amount) OR NOT(OLD.refund_status<=>NEW.refund_status) OR NOT(OLD.completed_time<=>NEW.completed_time) OR NOT(OLD.provider_channel<=>NEW.provider_channel) OR NOT(OLD.provider_refund_no<=>NEW.provider_refund_no) OR NOT(OLD.evidence_key<=>NEW.evidence_key) OR NOT(OLD.applicant_id<=>NEW.applicant_id) OR NOT(OLD.reviewer_id<=>NEW.reviewer_id) OR NOT(OLD.executor_id<=>NEW.executor_id) OR NOT(OLD.calculation_snapshot<=>NEW.calculation_snapshot) OR NOT(OLD.reason<=>NEW.reason) OR NOT(OLD.create_time<=>NEW.create_time)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Successful tuition refund facts are immutable'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_refund_checker$$
DELIMITER ;
