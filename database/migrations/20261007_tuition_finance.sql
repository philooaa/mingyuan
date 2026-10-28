-- 20261007 tuition finance. Additive, rerunnable. Backup and record SHA256 before manual execution.
-- MySQL 8.0.16+; run with service stopped. No business DB auto execution or destructive rollback.
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS tuition_column$$
CREATE PROCEDURE tuition_column(IN t varchar(64),IN c varchar(64),IN typ varchar(100),IN spec text,IN may_add boolean)
BEGIN
  DECLARE actual varchar(100); DECLARE msg varchar(128); DECLARE nullable varchar(3); DECLARE def text;
  SELECT column_type,is_nullable,column_default INTO actual,nullable,def FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=t AND column_name=c;
  IF actual IS NULL THEN
    IF NOT may_add THEN SET msg=CONCAT('Tuition schema missing column: ',t,'.',c); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
    SET @ddl=CONCAT('ALTER TABLE `',t,'` ADD COLUMN `',c,'` ',typ,' ',spec); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  ELSEIF LOWER(actual)<>LOWER(typ) THEN
    SET msg=CONCAT('Tuition schema type conflict: ',t,'.',c); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg;
  END IF;
  IF actual IS NOT NULL AND ((spec LIKE 'NOT NULL%' AND nullable<>'NO') OR (spec LIKE 'NULL%' AND nullable<>'YES') OR (spec LIKE '%DEFAULT NULL%' AND def IS NOT NULL) OR (spec LIKE '%DEFAULT 0%' AND NOT(def<=>'0')) OR (spec LIKE '%DEFAULT ''REAL''%' AND NOT(def<=>'REAL')) OR (spec LIKE '%DEFAULT ''HISTORY_PENDING''%' AND (def IS NULL OR def NOT IN ('HISTORY_PENDING','PENDING_PRICE')))) THEN SET msg=CONCAT('Tuition schema null/default conflict: ',t,'.',c); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
END$$
DROP PROCEDURE IF EXISTS tuition_constraint$$
CREATE PROCEDURE tuition_constraint(IN t varchar(64),IN n varchar(64),IN spec text)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name=t AND constraint_name=n) THEN
  SET @ddl=CONCAT('ALTER TABLE `',t,'` ADD ',spec); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 END IF;
END$$
DELIMITER ;
-- Unified child/date identity. Existing course and dated one-to-one records remain distinct.
CALL tuition_column('edu_enrollment','lesson_account_id','bigint','NULL',TRUE);
CALL tuition_column('edu_enrollment','student_id','bigint','NULL',TRUE);
CALL tuition_column('edu_enrollment','class_date','date','NULL',TRUE);
CALL tuition_column('edu_course_schedule','tuition_price','decimal(10,2)','NULL DEFAULT NULL',TRUE);
-- Adding with HISTORY_PENDING marks old rows atomically; interrupted installs cannot
-- lose the old/new distinction. Change the default only after every safety check.
CALL tuition_column('edu_enrollment','billing_status','varchar(20)','NOT NULL DEFAULT ''HISTORY_PENDING''',TRUE);
CALL tuition_column('edu_enrollment','finance_mode','varchar(4)','NOT NULL DEFAULT ''REAL''',TRUE);
CALL tuition_column('edu_enrollment','original_amount','decimal(10,2)','NULL DEFAULT NULL',TRUE);
CALL tuition_column('edu_enrollment','discount_amount','decimal(10,2)','NULL DEFAULT NULL',TRUE);
CALL tuition_column('edu_enrollment','payable_amount','decimal(10,2)','NULL DEFAULT NULL',TRUE);
CALL tuition_column('edu_enrollment','user_coupon_id','bigint','NULL',TRUE);
CALL tuition_column('edu_enrollment','fee_title_snapshot','varchar(200)','NULL',TRUE);
CALL tuition_column('edu_enrollment','campus_name_snapshot','varchar(50)','NULL',TRUE);
CALL tuition_column('edu_enrollment','price_confirmed_by','bigint','NULL',TRUE);
CALL tuition_column('edu_enrollment','price_confirmed_time','datetime(3)','NULL',TRUE);
CALL tuition_column('edu_enrollment','financial_version','int','NOT NULL DEFAULT 0',TRUE);
CALL tuition_column('edu_enrollment','billing_closed_time','datetime(3)','NULL',TRUE);
CALL tuition_column('edu_enrollment','billing_close_reason','varchar(255)','NULL',TRUE);
CALL tuition_column('edu_enrollment','history_verification_snapshot','json','NULL',TRUE);
-- UTF8 literals are 待确认 / 报名成功; hex avoids MySQL metadata charset drift.
CALL tuition_column('edu_enrollment','active_enrollment_guard','tinyint','GENERATED ALWAYS AS (CASE WHEN del_flag=''0'' AND enrollment_status IN (''0'',''1'',CONVERT(0xe5be85e7a1aee8aea4 USING utf8mb4),CONVERT(0xe68aa5e5908de68890e58a9f USING utf8mb4)) THEN 1 END) STORED',TRUE);
CALL tuition_column('edu_enrollment','tuition_identity_guard','varchar(100)','GENERATED ALWAYS AS (CONCAT(IF(lesson_account_id IS NULL,CONCAT(''LEGACY:'',student_name),CONCAT(''CHILD:'',lesson_account_id)),'':'',IFNULL(CAST(class_date AS CHAR CHARSET utf8mb4),''CLASS''))) STORED',TRUE);
CREATE TABLE IF NOT EXISTS edu_tuition_payment (
payment_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
create_by varchar(64) NOT NULL DEFAULT 'SYSTEM', update_by varchar(64) NOT NULL DEFAULT 'SYSTEM', version int NOT NULL DEFAULT 0, remark varchar(500) NULL,
payment_no varchar(40) NOT NULL, enrollment_id bigint NOT NULL, channel varchar(20) NOT NULL, payment_status varchar(16) NOT NULL, finance_mode varchar(4) NOT NULL,
amount decimal(10,2) NOT NULL, payer_name varchar(64) NOT NULL, provider_trade_no varchar(64) NULL, evidence_key varchar(255) NULL,
bill_snapshot json NOT NULL, recorded_by bigint NOT NULL, confirmed_by bigint NULL, confirmed_time datetime(3) NULL, paid_time datetime(3) NULL,
expire_time datetime(3) NULL, failure_reason varchar(255) NULL, record_source varchar(20) NOT NULL DEFAULT 'CURRENT',
idempotency_key varchar(64) NOT NULL, request_hash char(64) NOT NULL,
effective_enrollment_id bigint GENERATED ALWAYS AS (CASE WHEN payment_status IN ('PENDING','UNKNOWN','SUCCESS') THEN enrollment_id END) STORED,
create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
UNIQUE KEY uk_tp_no(payment_no), UNIQUE KEY uk_tp_request(recorded_by,idempotency_key), UNIQUE KEY uk_tp_provider(channel,provider_trade_no), UNIQUE KEY uk_tp_effective(effective_enrollment_id),
KEY idx_tp_enrollment(enrollment_id,create_time), KEY idx_tp_expiry(payment_status,expire_time),
CONSTRAINT fk_tp_enrollment FOREIGN KEY(enrollment_id) REFERENCES edu_enrollment(enrollment_id),
CONSTRAINT fk_tp_recorder FOREIGN KEY(recorded_by) REFERENCES sys_user(user_id), CONSTRAINT fk_tp_confirmer FOREIGN KEY(confirmed_by) REFERENCES sys_user(user_id),
CONSTRAINT ck_tp_money CHECK(amount>=0 AND (channel!='FREE' OR amount=0)),
CONSTRAINT ck_tp_state CHECK(payment_status IN ('PENDING','SUCCESS','FAILED','CLOSED','UNKNOWN') AND finance_mode IN ('REAL','MOCK') AND channel IN ('CASH','BANK_TRANSFER','WECHAT_OFFLINE','MOCK','FREE') AND (channel!='MOCK' OR finance_mode='MOCK') AND record_source IN ('CURRENT','HISTORY_VERIFIED')),
CONSTRAINT ck_tp_success CHECK(payment_status!='SUCCESS' OR (confirmed_time IS NOT NULL AND paid_time IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_tuition_refund (
refund_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, refund_no varchar(40) NOT NULL, payment_id bigint NOT NULL,
create_by varchar(64) NOT NULL DEFAULT 'SYSTEM', update_by varchar(64) NOT NULL DEFAULT 'SYSTEM', version int NOT NULL DEFAULT 0, remark varchar(500) NULL,
refund_kind varchar(16) NOT NULL, requested_amount decimal(10,2) NOT NULL, approved_amount decimal(10,2) NULL, refund_status varchar(20) NOT NULL,
reason varchar(500) NOT NULL, calculation_snapshot json NOT NULL, applicant_id bigint NOT NULL, reviewer_id bigint NULL,
review_time datetime(3) NULL, review_remark varchar(500) NULL, executor_id bigint NULL, processing_time datetime(3) NULL, completed_time datetime(3) NULL,
provider_channel varchar(20) NULL, provider_refund_no varchar(64) NULL, evidence_key varchar(255) NULL, failure_reason varchar(255) NULL,
idempotency_key varchar(64) NOT NULL, request_hash char(64) NOT NULL,
active_payment_id bigint GENERATED ALWAYS AS (CASE WHEN refund_status IN ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN') THEN payment_id END) STORED,
create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
UNIQUE KEY uk_tr_no(refund_no), UNIQUE KEY uk_tr_request(applicant_id,idempotency_key), UNIQUE KEY uk_tr_active(active_payment_id), UNIQUE KEY uk_tr_provider(provider_channel,provider_refund_no),
KEY idx_tr_payment(payment_id,refund_status), KEY idx_tr_status(refund_status,create_time),
CONSTRAINT fk_tr_payment FOREIGN KEY(payment_id) REFERENCES edu_tuition_payment(payment_id),
CONSTRAINT fk_tr_applicant FOREIGN KEY(applicant_id) REFERENCES sys_user(user_id), CONSTRAINT fk_tr_reviewer FOREIGN KEY(reviewer_id) REFERENCES sys_user(user_id), CONSTRAINT fk_tr_executor FOREIGN KEY(executor_id) REFERENCES sys_user(user_id),
CONSTRAINT ck_tr_money CHECK(requested_amount>0 AND (approved_amount IS NULL OR (approved_amount>0 AND approved_amount<=requested_amount))),
CONSTRAINT ck_tr_state CHECK(refund_kind IN ('PARTIAL','WITHDRAWAL') AND refund_status IN ('PENDING_REVIEW','APPROVED','PROCESSING','SUCCESS','REJECTED','CANCELLED','FAILED','UNKNOWN')),
CONSTRAINT ck_tr_approved CHECK(refund_status NOT IN ('APPROVED','PROCESSING','SUCCESS','UNKNOWN') OR approved_amount IS NOT NULL),
CONSTRAINT ck_tr_success CHECK(refund_status!='SUCCESS' OR completed_time IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_tuition_receipt (
receipt_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, receipt_no varchar(40) NOT NULL, payment_id bigint NOT NULL,
create_by varchar(64) NOT NULL DEFAULT 'SYSTEM', update_by varchar(64) NOT NULL DEFAULT 'SYSTEM', remark varchar(500) NULL,
document_type varchar(16) NOT NULL, receipt_status varchar(16) NOT NULL, amount decimal(10,2) NOT NULL, content_snapshot json NOT NULL, template_version varchar(20) NOT NULL,
file_status varchar(16) NOT NULL, file_key varchar(255) NULL, file_sha256 char(64) NULL, generation_error varchar(255) NULL,
issued_by bigint NULL, issued_time datetime(3) NOT NULL, void_by bigint NULL, void_time datetime(3) NULL, void_reason varchar(255) NULL, replaces_receipt_id bigint NULL,
version int NOT NULL DEFAULT 0,
active_payment_id bigint GENERATED ALWAYS AS (CASE WHEN receipt_status='ISSUED' THEN payment_id END) STORED,
create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
UNIQUE KEY uk_tc_no(receipt_no), UNIQUE KEY uk_tc_active(active_payment_id), UNIQUE KEY uk_tc_replaces(replaces_receipt_id),
KEY idx_tc_payment(payment_id,issued_time), KEY idx_tc_file(file_status,update_time),
CONSTRAINT fk_tc_payment FOREIGN KEY(payment_id) REFERENCES edu_tuition_payment(payment_id), CONSTRAINT fk_tc_issued FOREIGN KEY(issued_by) REFERENCES sys_user(user_id), CONSTRAINT fk_tc_void FOREIGN KEY(void_by) REFERENCES sys_user(user_id),
CONSTRAINT fk_tc_replaces FOREIGN KEY(replaces_receipt_id) REFERENCES edu_tuition_receipt(receipt_id),
CONSTRAINT ck_tc_state CHECK(amount>=0 AND document_type IN ('RECEIPT','WAIVER') AND receipt_status IN ('ISSUED','VOID') AND file_status IN ('PENDING','GENERATING','READY','FAILED') AND version>=0),
CONSTRAINT ck_tc_ready CHECK(file_status!='READY' OR (file_key IS NOT NULL AND file_sha256 IS NOT NULL)),
CONSTRAINT ck_tc_void CHECK(receipt_status!='VOID' OR (void_by IS NOT NULL AND void_time IS NOT NULL AND CHAR_LENGTH(void_reason)>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_coupon_template (
template_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, template_code varchar(40) NOT NULL, coupon_name varchar(100) NOT NULL, description varchar(500) NULL,
discount_amount decimal(10,2) NOT NULL, min_spend_amount decimal(10,2) NOT NULL DEFAULT 0, scope_schedule_id bigint NULL,
valid_from datetime(3) NOT NULL, valid_until datetime(3) NOT NULL, total_quantity int NOT NULL, issued_quantity int NOT NULL DEFAULT 0, template_status varchar(16) NOT NULL,
create_by varchar(64) NOT NULL DEFAULT 'SYSTEM', update_by varchar(64) NOT NULL DEFAULT 'SYSTEM', version int NOT NULL DEFAULT 0, remark varchar(500) NULL, create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
UNIQUE KEY uk_ct_code(template_code), KEY idx_ct_status(template_status,valid_until), KEY idx_ct_scope(scope_schedule_id),
CONSTRAINT fk_ct_schedule FOREIGN KEY(scope_schedule_id) REFERENCES edu_course_schedule(schedule_id),
CONSTRAINT ck_ct_rules CHECK(discount_amount>0 AND min_spend_amount>=0 AND valid_until>valid_from AND total_quantity>0 AND issued_quantity>=0 AND issued_quantity<=total_quantity AND template_status IN ('DRAFT','ACTIVE','PAUSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_user_coupon (
user_coupon_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, coupon_code varchar(40) NOT NULL, template_id bigint NOT NULL, parent_id bigint NOT NULL,
create_by varchar(64) NOT NULL DEFAULT 'SYSTEM', update_by varchar(64) NOT NULL DEFAULT 'SYSTEM', version int NOT NULL DEFAULT 0, remark varchar(500) NULL,
coupon_status varchar(16) NOT NULL, valid_from datetime(3) NOT NULL, valid_until datetime(3) NOT NULL, granted_by bigint NOT NULL, grant_request_key varchar(64) NOT NULL, grant_request_hash char(64) NOT NULL, grant_reason varchar(255) NOT NULL,
locked_enrollment_id bigint NULL, locked_until datetime(3) NULL, used_enrollment_id bigint NULL, used_time datetime(3) NULL, revoked_by bigint NULL, revoked_time datetime(3) NULL, revoke_reason varchar(255) NULL,
create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
UNIQUE KEY uk_uc_code(coupon_code), UNIQUE KEY uk_uc_parent(template_id,parent_id), UNIQUE KEY uk_uc_request(granted_by,grant_request_key,parent_id), UNIQUE KEY uk_uc_used(used_enrollment_id),
KEY idx_uc_parent(parent_id,coupon_status,valid_until), KEY idx_uc_lock(locked_enrollment_id),
CONSTRAINT fk_uc_template FOREIGN KEY(template_id) REFERENCES edu_coupon_template(template_id), CONSTRAINT fk_uc_parent FOREIGN KEY(parent_id) REFERENCES sys_user(user_id), CONSTRAINT fk_uc_grant FOREIGN KEY(granted_by) REFERENCES sys_user(user_id),
CONSTRAINT fk_uc_lock FOREIGN KEY(locked_enrollment_id) REFERENCES edu_enrollment(enrollment_id), CONSTRAINT fk_uc_used FOREIGN KEY(used_enrollment_id) REFERENCES edu_enrollment(enrollment_id), CONSTRAINT fk_uc_revoke FOREIGN KEY(revoked_by) REFERENCES sys_user(user_id),
CONSTRAINT ck_uc_state CHECK(coupon_status IN ('AVAILABLE','LOCKED','USED','REVOKED') AND valid_until>valid_from),
CONSTRAINT ck_uc_lock CHECK(coupon_status!='LOCKED' OR locked_enrollment_id IS NOT NULL),
CONSTRAINT ck_uc_used CHECK(coupon_status!='USED' OR (used_enrollment_id IS NOT NULL AND used_time IS NOT NULL)),
CONSTRAINT ck_uc_revoked CHECK(coupon_status!='REVOKED' OR (revoked_by IS NOT NULL AND revoked_time IS NOT NULL AND CHAR_LENGTH(revoke_reason)>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL tuition_column('edu_tuition_payment','payment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_payment','payment_no','varchar(40)','',FALSE);
CALL tuition_column('edu_tuition_payment','enrollment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_payment','channel','varchar(20)','',FALSE);
CALL tuition_column('edu_tuition_payment','payment_status','varchar(16)','',FALSE);
CALL tuition_column('edu_tuition_payment','finance_mode','varchar(4)','',FALSE);
CALL tuition_column('edu_tuition_payment','amount','decimal(10,2)','',FALSE);
CALL tuition_column('edu_tuition_payment','payer_name','varchar(64)','',FALSE);
CALL tuition_column('edu_tuition_payment','provider_trade_no','varchar(64)','',FALSE);
CALL tuition_column('edu_tuition_payment','evidence_key','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_payment','bill_snapshot','json','',FALSE);
CALL tuition_column('edu_tuition_payment','recorded_by','bigint','',FALSE);
CALL tuition_column('edu_tuition_payment','confirmed_by','bigint','',FALSE);
CALL tuition_column('edu_tuition_payment','confirmed_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_payment','paid_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_payment','expire_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_payment','failure_reason','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_payment','record_source','varchar(20)','',FALSE);
CALL tuition_column('edu_tuition_payment','idempotency_key','varchar(64)','',FALSE);
CALL tuition_column('edu_tuition_payment','request_hash','char(64)','',FALSE);
CALL tuition_column('edu_tuition_payment','effective_enrollment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_payment','create_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_payment','update_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_refund','refund_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_refund','refund_no','varchar(40)','',FALSE);
CALL tuition_column('edu_tuition_refund','payment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_refund','refund_kind','varchar(16)','',FALSE);
CALL tuition_column('edu_tuition_refund','requested_amount','decimal(10,2)','',FALSE);
CALL tuition_column('edu_tuition_refund','approved_amount','decimal(10,2)','',FALSE);
CALL tuition_column('edu_tuition_refund','refund_status','varchar(20)','',FALSE);
CALL tuition_column('edu_tuition_refund','reason','varchar(500)','',FALSE);
CALL tuition_column('edu_tuition_refund','calculation_snapshot','json','',FALSE);
CALL tuition_column('edu_tuition_refund','applicant_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_refund','reviewer_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_refund','review_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_refund','review_remark','varchar(500)','',FALSE);
CALL tuition_column('edu_tuition_refund','executor_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_refund','processing_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_refund','completed_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_refund','provider_channel','varchar(20)','',FALSE);
CALL tuition_column('edu_tuition_refund','provider_refund_no','varchar(64)','',FALSE);
CALL tuition_column('edu_tuition_refund','evidence_key','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_refund','failure_reason','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_refund','idempotency_key','varchar(64)','',FALSE);
CALL tuition_column('edu_tuition_refund','request_hash','char(64)','',FALSE);
CALL tuition_column('edu_tuition_refund','active_payment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_refund','create_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_refund','update_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_receipt','receipt_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_receipt','receipt_no','varchar(40)','',FALSE);
CALL tuition_column('edu_tuition_receipt','payment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_receipt','document_type','varchar(16)','',FALSE);
CALL tuition_column('edu_tuition_receipt','receipt_status','varchar(16)','',FALSE);
CALL tuition_column('edu_tuition_receipt','amount','decimal(10,2)','',FALSE);
CALL tuition_column('edu_tuition_receipt','content_snapshot','json','',FALSE);
CALL tuition_column('edu_tuition_receipt','template_version','varchar(20)','',FALSE);
CALL tuition_column('edu_tuition_receipt','file_status','varchar(16)','',FALSE);
CALL tuition_column('edu_tuition_receipt','file_key','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_receipt','generation_error','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_receipt','issued_by','bigint','',FALSE);
CALL tuition_column('edu_tuition_receipt','issued_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_receipt','void_by','bigint','',FALSE);
CALL tuition_column('edu_tuition_receipt','void_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_receipt','void_reason','varchar(255)','',FALSE);
CALL tuition_column('edu_tuition_receipt','replaces_receipt_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_receipt','version','int','',FALSE);
CALL tuition_column('edu_tuition_receipt','active_payment_id','bigint','',FALSE);
CALL tuition_column('edu_tuition_receipt','create_time','datetime(3)','',FALSE);
CALL tuition_column('edu_tuition_receipt','update_time','datetime(3)','',FALSE);
CALL tuition_column('edu_coupon_template','template_id','bigint','',FALSE);
CALL tuition_column('edu_coupon_template','template_code','varchar(40)','',FALSE);
CALL tuition_column('edu_coupon_template','coupon_name','varchar(100)','',FALSE);
CALL tuition_column('edu_coupon_template','description','varchar(500)','',FALSE);
CALL tuition_column('edu_coupon_template','discount_amount','decimal(10,2)','',FALSE);
CALL tuition_column('edu_coupon_template','min_spend_amount','decimal(10,2)','',FALSE);
CALL tuition_column('edu_coupon_template','scope_schedule_id','bigint','',FALSE);
CALL tuition_column('edu_coupon_template','valid_from','datetime(3)','',FALSE);
CALL tuition_column('edu_coupon_template','valid_until','datetime(3)','',FALSE);
CALL tuition_column('edu_coupon_template','total_quantity','int','',FALSE);
CALL tuition_column('edu_coupon_template','issued_quantity','int','',FALSE);
CALL tuition_column('edu_coupon_template','template_status','varchar(16)','',FALSE);
CALL tuition_column('edu_coupon_template','create_by','varchar(64)','',FALSE);
CALL tuition_column('edu_coupon_template','update_by','varchar(64)','',FALSE);
CALL tuition_column('edu_coupon_template','create_time','datetime(3)','',FALSE);
CALL tuition_column('edu_coupon_template','update_time','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','user_coupon_id','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','coupon_code','varchar(40)','',FALSE);
CALL tuition_column('edu_user_coupon','template_id','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','parent_id','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','coupon_status','varchar(16)','',FALSE);
CALL tuition_column('edu_user_coupon','valid_from','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','valid_until','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','granted_by','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','grant_request_key','varchar(64)','',FALSE);
CALL tuition_column('edu_user_coupon','grant_request_hash','char(64)','',FALSE);
CALL tuition_column('edu_user_coupon','grant_reason','varchar(255)','',FALSE);
CALL tuition_column('edu_user_coupon','locked_enrollment_id','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','locked_until','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','used_enrollment_id','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','used_time','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','revoked_by','bigint','',FALSE);
CALL tuition_column('edu_user_coupon','revoked_time','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','revoke_reason','varchar(255)','',FALSE);
CALL tuition_column('edu_user_coupon','create_time','datetime(3)','',FALSE);
CALL tuition_column('edu_user_coupon','update_time','datetime(3)','',FALSE);
CALL tuition_constraint('edu_enrollment','fk_te_coupon','CONSTRAINT fk_te_coupon FOREIGN KEY(user_coupon_id) REFERENCES edu_user_coupon(user_coupon_id)');
CALL tuition_constraint('edu_enrollment','fk_te_pricer','CONSTRAINT fk_te_pricer FOREIGN KEY(price_confirmed_by) REFERENCES sys_user(user_id)');
CALL tuition_constraint('edu_enrollment','ck_te_bill','CONSTRAINT ck_te_bill CHECK(billing_status IN (''PENDING_PRICE'',''OPEN'',''CLOSED'',''HISTORY_PENDING'') AND finance_mode IN (''REAL'',''MOCK'') AND financial_version>=0 AND (original_amount IS NULL OR original_amount>=0) AND (discount_amount IS NULL OR discount_amount>=0) AND (payable_amount IS NULL OR payable_amount>=0) AND (original_amount IS NULL OR discount_amount IS NULL OR (discount_amount<=original_amount AND payable_amount=original_amount-discount_amount)) AND (billing_status<>''OPEN'' OR (original_amount IS NOT NULL AND discount_amount IS NOT NULL AND payable_amount IS NOT NULL AND price_confirmed_by IS NOT NULL AND price_confirmed_time IS NOT NULL)))');
CALL tuition_constraint('edu_course_schedule','ck_ts_price','CONSTRAINT ck_ts_price CHECK(tuition_price IS NULL OR tuition_price>=0)');
DELIMITER $$
DROP PROCEDURE IF EXISTS tuition_active_index$$
CREATE PROCEDURE tuition_active_index()
BEGIN
 IF EXISTS(SELECT 1 FROM edu_enrollment WHERE active_enrollment_guard=1 GROUP BY schedule_id,parent_id,tuition_identity_guard HAVING COUNT(*)>1) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate active enrollments require review';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_edu_enrollment_once') THEN
   IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_edu_enrollment_once') NOT IN ('schedule_id,parent_id,student_name','schedule_id,parent_id,student_name,active_enrollment_guard','schedule_id,parent_id,tuition_identity_guard,active_enrollment_guard') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tuition enrollment unique index conflict'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_edu_enrollment_once' AND column_name='tuition_identity_guard') THEN
   IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_enrollment' AND index_name='uk_edu_enrollment_once') THEN ALTER TABLE edu_enrollment DROP INDEX uk_edu_enrollment_once; END IF;
   ALTER TABLE edu_enrollment ADD UNIQUE KEY uk_edu_enrollment_once(schedule_id,parent_id,tuition_identity_guard,active_enrollment_guard);
 END IF;
END$$
CALL tuition_active_index()$$
DROP PROCEDURE tuition_active_index$$
-- Verify all shared fields as well as monetary fields; do not accept partial installs.
CALL tuition_column('edu_tuition_payment','create_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_tuition_payment','update_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_tuition_payment','version','int','',FALSE)$$
CALL tuition_column('edu_tuition_payment','remark','varchar(500)','',FALSE)$$
CALL tuition_column('edu_tuition_refund','create_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_tuition_refund','update_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_tuition_refund','version','int','',FALSE)$$
CALL tuition_column('edu_tuition_refund','remark','varchar(500)','',FALSE)$$
CALL tuition_column('edu_tuition_receipt','create_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_tuition_receipt','update_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_tuition_receipt','version','int','',FALSE)$$
CALL tuition_column('edu_tuition_receipt','remark','varchar(500)','',FALSE)$$
CALL tuition_column('edu_coupon_template','create_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_coupon_template','update_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_coupon_template','version','int','',FALSE)$$
CALL tuition_column('edu_coupon_template','remark','varchar(500)','',FALSE)$$
CALL tuition_column('edu_user_coupon','create_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_user_coupon','update_by','varchar(64)','',FALSE)$$
CALL tuition_column('edu_user_coupon','version','int','',FALSE)$$
CALL tuition_column('edu_user_coupon','remark','varchar(500)','',FALSE)$$
DROP PROCEDURE tuition_column$$
DROP PROCEDURE tuition_constraint$$
DELIMITER ;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,icon,create_by,create_time) SELECT '收费财务',0,8,'finance','M','money','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='finance' AND menu_type='M');
SET @tuition_menu=(SELECT menu_id FROM sys_menu WHERE path='finance' AND menu_type='M' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,perms,icon,create_by,create_time) SELECT '课程收费',@tuition_menu,1,'tuition','system/tuition/index','C','system:tuition:list','money','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:list');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '课程收费-query',(SELECT menu_id FROM sys_menu WHERE perms='system:tuition:list' LIMIT 1),'#','F','system:tuition:query','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:query');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '课程收费-price',(SELECT menu_id FROM sys_menu WHERE perms='system:tuition:list' LIMIT 1),'#','F','system:tuition:price','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:price');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '课程收费-collect',(SELECT menu_id FROM sys_menu WHERE perms='system:tuition:list' LIMIT 1),'#','F','system:tuition:collect','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:collect');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '课程收费-confirm',(SELECT menu_id FROM sys_menu WHERE perms='system:tuition:list' LIMIT 1),'#','F','system:tuition:confirm','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:confirm');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '课程收费-historyVerify',(SELECT menu_id FROM sys_menu WHERE perms='system:tuition:list' LIMIT 1),'#','F','system:tuition:historyVerify','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:historyVerify');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '课程收费-export',(SELECT menu_id FROM sys_menu WHERE perms='system:tuition:list' LIMIT 1),'#','F','system:tuition:export','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuition:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,perms,icon,create_by,create_time) SELECT '收据管理',@tuition_menu,2,'tuitionReceipt','system/tuitionReceipt/index','C','system:tuitionReceipt:list','money','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionReceipt:list');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '收据管理-issue',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionReceipt:list' LIMIT 1),'#','F','system:tuitionReceipt:issue','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionReceipt:issue');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '收据管理-download',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionReceipt:list' LIMIT 1),'#','F','system:tuitionReceipt:download','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionReceipt:download');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '收据管理-replace',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionReceipt:list' LIMIT 1),'#','F','system:tuitionReceipt:replace','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionReceipt:replace');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,perms,icon,create_by,create_time) SELECT '退费管理',@tuition_menu,3,'tuitionRefund','system/tuitionRefund/index','C','system:tuitionRefund:list','money','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:list');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '退费管理-query',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionRefund:list' LIMIT 1),'#','F','system:tuitionRefund:query','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:query');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '退费管理-apply',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionRefund:list' LIMIT 1),'#','F','system:tuitionRefund:apply','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:apply');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '退费管理-review',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionRefund:list' LIMIT 1),'#','F','system:tuitionRefund:review','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:review');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '退费管理-execute',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionRefund:list' LIMIT 1),'#','F','system:tuitionRefund:execute','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:execute');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '退费管理-confirm',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionRefund:list' LIMIT 1),'#','F','system:tuitionRefund:confirm','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:confirm');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,perms,icon,create_by,create_time) SELECT '优惠券管理',@tuition_menu,4,'coupon','system/coupon/index','C','system:coupon:list','money','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:coupon:list');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '优惠券管理-query',(SELECT menu_id FROM sys_menu WHERE perms='system:coupon:list' LIMIT 1),'#','F','system:coupon:query','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:coupon:query');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '优惠券管理-add',(SELECT menu_id FROM sys_menu WHERE perms='system:coupon:list' LIMIT 1),'#','F','system:coupon:add','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:coupon:add');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '优惠券管理-edit',(SELECT menu_id FROM sys_menu WHERE perms='system:coupon:list' LIMIT 1),'#','F','system:coupon:edit','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:coupon:edit');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '优惠券管理-grant',(SELECT menu_id FROM sys_menu WHERE perms='system:coupon:list' LIMIT 1),'#','F','system:coupon:grant','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:coupon:grant');
INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time) SELECT '优惠券管理-revoke',(SELECT menu_id FROM sys_menu WHERE perms='system:coupon:list' LIMIT 1),'#','F','system:coupon:revoke','admin',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:coupon:revoke');
INSERT INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON (m.menu_id=@tuition_menu OR m.perms LIKE 'system:tuition:%' OR m.perms LIKE 'system:tuitionReceipt:%' OR m.perms LIKE 'system:tuitionRefund:%' OR m.perms LIKE 'system:coupon:%') WHERE r.role_key='admin' AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);

-- Database guards supplement service locks and old CRUD protection.

-- Reruns validate safety structures, not merely table existence.
DELIMITER $$
DROP PROCEDURE IF EXISTS tuition_verify_index$$
CREATE PROCEDURE tuition_verify_index(IN t varchar(64),IN n varchar(64),IN expected text,IN unique_required boolean)
BEGIN
 DECLARE actual text; DECLARE nonuniq int; DECLARE msg varchar(128);
 SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index),MAX(non_unique) INTO actual,nonuniq FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=t AND index_name=n;
 IF actual IS NULL OR actual<>expected OR nonuniq<>(CASE WHEN unique_required THEN 0 ELSE 1 END) THEN SET msg=CONCAT('Tuition safety index conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
END$$
DROP PROCEDURE IF EXISTS tuition_verify_fk$$
CREATE PROCEDURE tuition_verify_fk(IN t varchar(64),IN n varchar(64),IN col varchar(64),IN target varchar(64),IN targetcol varchar(64))
BEGIN
 DECLARE msg varchar(128);
 IF NOT EXISTS(SELECT 1 FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name=t AND k.constraint_name=n AND k.column_name=col AND k.referenced_table_schema=DATABASE() AND k.referenced_table_name=target AND k.referenced_column_name=targetcol AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION')) THEN SET msg=CONCAT('Tuition foreign key conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
END$$
DROP PROCEDURE IF EXISTS tuition_verify_generated$$
CREATE PROCEDURE tuition_verify_generated(IN t varchar(64),IN col varchar(64),IN expected text)
BEGIN
 DECLARE actual text; DECLARE ext varchar(255); DECLARE msg varchar(128);
 SELECT generation_expression,extra INTO actual,ext FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=t AND column_name=col;
 IF actual IS NULL OR ext NOT LIKE '%STORED GENERATED%' OR REPLACE(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(LOWER(actual),CHAR(92),''),'`',''),'_utf8mb4',''),'[[:space:]()]',''),'elsenull','')<>REPLACE(REGEXP_REPLACE(LOWER(expected),'[[:space:]()]',''),'elsenull','') THEN SELECT actual AS actual_expression,expected AS required_expression,ext AS column_extra; SET msg=CONCAT('Tuition generated guard conflict: ',t,'.',col); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
END$$
DROP PROCEDURE IF EXISTS tuition_verify_nullable$$
CREATE PROCEDURE tuition_verify_nullable(IN t varchar(64),IN col varchar(64),IN expected varchar(3))
BEGIN
 DECLARE msg varchar(128);
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=t AND column_name=col AND is_nullable=expected) THEN SET msg=CONCAT('Tuition nullable conflict: ',t,'.',col); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
END$$
DROP PROCEDURE IF EXISTS tuition_verify_check$$
CREATE PROCEDURE tuition_verify_check(IN t varchar(64),IN n varchar(64),IN expected text)
BEGIN
 DECLARE actual text; DECLARE msg varchar(128);
 SELECT cc.check_clause INTO actual FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name WHERE tc.constraint_schema=DATABASE() AND tc.table_name=t AND tc.constraint_name=n AND tc.enforced='YES';
 IF actual IS NULL OR REPLACE(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(LOWER(actual),CHAR(92),''),'`',''),'_utf8mb4',''),'[[:space:]()]',''),'!=','<>')<>REPLACE(REGEXP_REPLACE(LOWER(expected),'[[:space:]()]',''),'!=','<>') THEN SELECT actual AS actual_check,expected AS required_check; SET msg=CONCAT('Tuition check constraint conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
END$$
DELIMITER ;
CALL tuition_verify_index('edu_tuition_payment','uk_tp_no','payment_no',TRUE);
CALL tuition_verify_index('edu_tuition_payment','uk_tp_request','recorded_by,idempotency_key',TRUE);
CALL tuition_verify_index('edu_tuition_payment','uk_tp_provider','channel,provider_trade_no',TRUE);
CALL tuition_verify_index('edu_tuition_payment','uk_tp_effective','effective_enrollment_id',TRUE);
CALL tuition_verify_index('edu_tuition_payment','idx_tp_enrollment','enrollment_id,create_time',FALSE);
CALL tuition_verify_index('edu_tuition_payment','idx_tp_expiry','payment_status,expire_time',FALSE);
CALL tuition_verify_fk('edu_tuition_payment','fk_tp_enrollment','enrollment_id','edu_enrollment','enrollment_id');
CALL tuition_verify_fk('edu_tuition_payment','fk_tp_recorder','recorded_by','sys_user','user_id');
CALL tuition_verify_fk('edu_tuition_payment','fk_tp_confirmer','confirmed_by','sys_user','user_id');
CALL tuition_verify_generated('edu_tuition_payment','effective_enrollment_id','CASE WHEN payment_status IN (''PENDING'',''UNKNOWN'',''SUCCESS'') THEN enrollment_id END');
CALL tuition_verify_check('edu_tuition_payment','ck_tp_money','amount>=0 AND (channel!=''FREE'' OR amount=0)');
CALL tuition_verify_check('edu_tuition_payment','ck_tp_state','payment_status IN (''PENDING'',''SUCCESS'',''FAILED'',''CLOSED'',''UNKNOWN'') AND finance_mode IN (''REAL'',''MOCK'') AND channel IN (''CASH'',''BANK_TRANSFER'',''WECHAT_OFFLINE'',''MOCK'',''FREE'') AND (channel!=''MOCK'' OR finance_mode=''MOCK'') AND record_source IN (''CURRENT'',''HISTORY_VERIFIED'')');
CALL tuition_verify_check('edu_tuition_payment','ck_tp_success','payment_status!=''SUCCESS'' OR (confirmed_time IS NOT NULL AND paid_time IS NOT NULL)');
CALL tuition_verify_nullable('edu_tuition_payment','payment_id','NO');
CALL tuition_verify_nullable('edu_tuition_payment','payment_no','NO');
CALL tuition_verify_nullable('edu_tuition_payment','channel','NO');
CALL tuition_verify_nullable('edu_tuition_payment','finance_mode','NO');
CALL tuition_verify_nullable('edu_tuition_payment','payer_name','NO');
CALL tuition_verify_nullable('edu_tuition_payment','evidence_key','YES');
CALL tuition_verify_nullable('edu_tuition_payment','recorded_by','NO');
CALL tuition_verify_nullable('edu_tuition_payment','confirmed_time','YES');
CALL tuition_verify_nullable('edu_tuition_payment','expire_time','YES');
CALL tuition_verify_nullable('edu_tuition_payment','record_source','NO');
CALL tuition_verify_nullable('edu_tuition_payment','idempotency_key','NO');
CALL tuition_verify_nullable('edu_tuition_payment','create_time','NO');
CALL tuition_verify_nullable('edu_tuition_payment','update_time','NO');
CALL tuition_verify_nullable('edu_tuition_payment','create_by','NO');
CALL tuition_verify_nullable('edu_tuition_payment','update_by','NO');
CALL tuition_verify_nullable('edu_tuition_payment','version','NO');
CALL tuition_verify_nullable('edu_tuition_payment','remark','YES');
CALL tuition_verify_index('edu_tuition_payment','PRIMARY','payment_id',TRUE);
CALL tuition_verify_index('edu_tuition_refund','uk_tr_no','refund_no',TRUE);
CALL tuition_verify_index('edu_tuition_refund','uk_tr_request','applicant_id,idempotency_key',TRUE);
CALL tuition_verify_index('edu_tuition_refund','uk_tr_active','active_payment_id',TRUE);
CALL tuition_verify_index('edu_tuition_refund','uk_tr_provider','provider_channel,provider_refund_no',TRUE);
CALL tuition_verify_index('edu_tuition_refund','idx_tr_payment','payment_id,refund_status',FALSE);
CALL tuition_verify_index('edu_tuition_refund','idx_tr_status','refund_status,create_time',FALSE);
CALL tuition_verify_fk('edu_tuition_refund','fk_tr_payment','payment_id','edu_tuition_payment','payment_id');
CALL tuition_verify_fk('edu_tuition_refund','fk_tr_applicant','applicant_id','sys_user','user_id');
CALL tuition_verify_fk('edu_tuition_refund','fk_tr_reviewer','reviewer_id','sys_user','user_id');
CALL tuition_verify_fk('edu_tuition_refund','fk_tr_executor','executor_id','sys_user','user_id');
CALL tuition_verify_generated('edu_tuition_refund','active_payment_id','CASE WHEN refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''UNKNOWN'') THEN payment_id END');
CALL tuition_verify_check('edu_tuition_refund','ck_tr_money','requested_amount>0 AND (approved_amount IS NULL OR (approved_amount>0 AND approved_amount<=requested_amount))');
CALL tuition_verify_check('edu_tuition_refund','ck_tr_state','refund_kind IN (''PARTIAL'',''WITHDRAWAL'') AND refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''SUCCESS'',''REJECTED'',''CANCELLED'',''FAILED'',''UNKNOWN'')');
CALL tuition_verify_check('edu_tuition_refund','ck_tr_approved','refund_status NOT IN (''APPROVED'',''PROCESSING'',''SUCCESS'',''UNKNOWN'') OR approved_amount IS NOT NULL');
CALL tuition_verify_check('edu_tuition_refund','ck_tr_success','refund_status!=''SUCCESS'' OR completed_time IS NOT NULL');
CALL tuition_verify_nullable('edu_tuition_refund','refund_id','NO');
CALL tuition_verify_nullable('edu_tuition_refund','refund_no','NO');
CALL tuition_verify_nullable('edu_tuition_refund','refund_kind','NO');
CALL tuition_verify_nullable('edu_tuition_refund','approved_amount','YES');
CALL tuition_verify_nullable('edu_tuition_refund','reason','NO');
CALL tuition_verify_nullable('edu_tuition_refund','applicant_id','NO');
CALL tuition_verify_nullable('edu_tuition_refund','review_time','YES');
CALL tuition_verify_nullable('edu_tuition_refund','executor_id','YES');
CALL tuition_verify_nullable('edu_tuition_refund','completed_time','YES');
CALL tuition_verify_nullable('edu_tuition_refund','provider_refund_no','YES');
CALL tuition_verify_nullable('edu_tuition_refund','failure_reason','YES');
CALL tuition_verify_nullable('edu_tuition_refund','request_hash','NO');
CALL tuition_verify_nullable('edu_tuition_refund','create_time','NO');
CALL tuition_verify_nullable('edu_tuition_refund','update_time','NO');
CALL tuition_verify_nullable('edu_tuition_refund','create_by','NO');
CALL tuition_verify_nullable('edu_tuition_refund','update_by','NO');
CALL tuition_verify_nullable('edu_tuition_refund','version','NO');
CALL tuition_verify_nullable('edu_tuition_refund','remark','YES');
CALL tuition_verify_index('edu_tuition_refund','PRIMARY','refund_id',TRUE);
CALL tuition_verify_index('edu_tuition_receipt','uk_tc_no','receipt_no',TRUE);
CALL tuition_verify_index('edu_tuition_receipt','uk_tc_active','active_payment_id',TRUE);
CALL tuition_verify_index('edu_tuition_receipt','uk_tc_replaces','replaces_receipt_id',TRUE);
CALL tuition_verify_index('edu_tuition_receipt','idx_tc_payment','payment_id,issued_time',FALSE);
CALL tuition_verify_index('edu_tuition_receipt','idx_tc_file','file_status,update_time',FALSE);
CALL tuition_verify_fk('edu_tuition_receipt','fk_tc_payment','payment_id','edu_tuition_payment','payment_id');
CALL tuition_verify_fk('edu_tuition_receipt','fk_tc_issued','issued_by','sys_user','user_id');
CALL tuition_verify_fk('edu_tuition_receipt','fk_tc_void','void_by','sys_user','user_id');
CALL tuition_verify_fk('edu_tuition_receipt','fk_tc_replaces','replaces_receipt_id','edu_tuition_receipt','receipt_id');
CALL tuition_verify_generated('edu_tuition_receipt','active_payment_id','CASE WHEN receipt_status=''ISSUED'' THEN payment_id END');
CALL tuition_verify_check('edu_tuition_receipt','ck_tc_state','amount>=0 AND document_type IN (''RECEIPT'',''WAIVER'') AND receipt_status IN (''ISSUED'',''VOID'') AND file_status IN (''PENDING'',''GENERATING'',''READY'',''FAILED'') AND version>=0');
CALL tuition_verify_check('edu_tuition_receipt','ck_tc_ready','file_status!=''READY'' OR (file_key IS NOT NULL AND file_sha256 IS NOT NULL)');
CALL tuition_verify_check('edu_tuition_receipt','ck_tc_void','receipt_status!=''VOID'' OR (void_by IS NOT NULL AND void_time IS NOT NULL AND CHAR_LENGTH(void_reason)>0)');
CALL tuition_verify_nullable('edu_tuition_receipt','receipt_id','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','receipt_no','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','document_type','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','amount','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','template_version','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','file_key','YES');
CALL tuition_verify_nullable('edu_tuition_receipt','generation_error','YES');
CALL tuition_verify_nullable('edu_tuition_receipt','issued_time','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','void_time','YES');
CALL tuition_verify_nullable('edu_tuition_receipt','replaces_receipt_id','YES');
CALL tuition_verify_nullable('edu_tuition_receipt','create_time','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','update_time','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','create_by','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','update_by','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','version','NO');
CALL tuition_verify_nullable('edu_tuition_receipt','remark','YES');
CALL tuition_verify_index('edu_tuition_receipt','PRIMARY','receipt_id',TRUE);
CALL tuition_verify_index('edu_coupon_template','uk_ct_code','template_code',TRUE);
CALL tuition_verify_index('edu_coupon_template','idx_ct_status','template_status,valid_until',FALSE);
CALL tuition_verify_index('edu_coupon_template','idx_ct_scope','scope_schedule_id',FALSE);
CALL tuition_verify_fk('edu_coupon_template','fk_ct_schedule','scope_schedule_id','edu_course_schedule','schedule_id');
CALL tuition_verify_check('edu_coupon_template','ck_ct_rules','discount_amount>0 AND min_spend_amount>=0 AND valid_until>valid_from AND total_quantity>0 AND issued_quantity>=0 AND issued_quantity<=total_quantity AND template_status IN (''DRAFT'',''ACTIVE'',''PAUSED'')');
CALL tuition_verify_nullable('edu_coupon_template','template_id','NO');
CALL tuition_verify_nullable('edu_coupon_template','template_code','NO');
CALL tuition_verify_nullable('edu_coupon_template','description','YES');
CALL tuition_verify_nullable('edu_coupon_template','min_spend_amount','NO');
CALL tuition_verify_nullable('edu_coupon_template','scope_schedule_id','YES');
CALL tuition_verify_nullable('edu_coupon_template','valid_until','NO');
CALL tuition_verify_nullable('edu_coupon_template','issued_quantity','NO');
CALL tuition_verify_nullable('edu_coupon_template','template_status','NO');
CALL tuition_verify_nullable('edu_coupon_template','update_time','NO');
CALL tuition_verify_nullable('edu_coupon_template','create_by','NO');
CALL tuition_verify_nullable('edu_coupon_template','update_by','NO');
CALL tuition_verify_nullable('edu_coupon_template','version','NO');
CALL tuition_verify_nullable('edu_coupon_template','remark','YES');
CALL tuition_verify_index('edu_coupon_template','PRIMARY','template_id',TRUE);
CALL tuition_verify_index('edu_user_coupon','uk_uc_code','coupon_code',TRUE);
CALL tuition_verify_index('edu_user_coupon','uk_uc_parent','template_id,parent_id',TRUE);
CALL tuition_verify_index('edu_user_coupon','uk_uc_request','granted_by,grant_request_key,parent_id',TRUE);
CALL tuition_verify_index('edu_user_coupon','uk_uc_used','used_enrollment_id',TRUE);
CALL tuition_verify_index('edu_user_coupon','idx_uc_parent','parent_id,coupon_status,valid_until',FALSE);
CALL tuition_verify_index('edu_user_coupon','idx_uc_lock','locked_enrollment_id',FALSE);
CALL tuition_verify_fk('edu_user_coupon','fk_uc_template','template_id','edu_coupon_template','template_id');
CALL tuition_verify_fk('edu_user_coupon','fk_uc_parent','parent_id','sys_user','user_id');
CALL tuition_verify_fk('edu_user_coupon','fk_uc_grant','granted_by','sys_user','user_id');
CALL tuition_verify_fk('edu_user_coupon','fk_uc_lock','locked_enrollment_id','edu_enrollment','enrollment_id');
CALL tuition_verify_fk('edu_user_coupon','fk_uc_used','used_enrollment_id','edu_enrollment','enrollment_id');
CALL tuition_verify_fk('edu_user_coupon','fk_uc_revoke','revoked_by','sys_user','user_id');
CALL tuition_verify_check('edu_user_coupon','ck_uc_state','coupon_status IN (''AVAILABLE'',''LOCKED'',''USED'',''REVOKED'') AND valid_until>valid_from');
CALL tuition_verify_check('edu_user_coupon','ck_uc_lock','coupon_status!=''LOCKED'' OR locked_enrollment_id IS NOT NULL');
CALL tuition_verify_check('edu_user_coupon','ck_uc_used','coupon_status!=''USED'' OR (used_enrollment_id IS NOT NULL AND used_time IS NOT NULL)');
CALL tuition_verify_check('edu_user_coupon','ck_uc_revoked','coupon_status!=''REVOKED'' OR (revoked_by IS NOT NULL AND revoked_time IS NOT NULL AND CHAR_LENGTH(revoke_reason)>0)');
CALL tuition_verify_nullable('edu_user_coupon','user_coupon_id','NO');
CALL tuition_verify_nullable('edu_user_coupon','coupon_code','NO');
CALL tuition_verify_nullable('edu_user_coupon','parent_id','NO');
CALL tuition_verify_nullable('edu_user_coupon','valid_from','NO');
CALL tuition_verify_nullable('edu_user_coupon','granted_by','NO');
CALL tuition_verify_nullable('edu_user_coupon','grant_request_hash','NO');
CALL tuition_verify_nullable('edu_user_coupon','locked_enrollment_id','YES');
CALL tuition_verify_nullable('edu_user_coupon','used_enrollment_id','YES');
CALL tuition_verify_nullable('edu_user_coupon','revoked_by','YES');
CALL tuition_verify_nullable('edu_user_coupon','revoke_reason','YES');
CALL tuition_verify_nullable('edu_user_coupon','update_time','NO');
CALL tuition_verify_nullable('edu_user_coupon','create_by','NO');
CALL tuition_verify_nullable('edu_user_coupon','update_by','NO');
CALL tuition_verify_nullable('edu_user_coupon','version','NO');
CALL tuition_verify_nullable('edu_user_coupon','remark','YES');
CALL tuition_verify_index('edu_user_coupon','PRIMARY','user_coupon_id',TRUE);
CALL tuition_verify_generated('edu_enrollment','active_enrollment_guard','CASE WHEN del_flag=''0'' AND enrollment_status IN (''0'',''1'',CONVERT(0xe5be85e7a1aee8aea4 USING utf8mb4),CONVERT(0xe68aa5e5908de68890e58a9f USING utf8mb4)) THEN 1 END');
CALL tuition_verify_generated('edu_enrollment','tuition_identity_guard','CONCAT(IF(lesson_account_id IS NULL,CONCAT(''LEGACY:'',student_name),CONCAT(''CHILD:'',lesson_account_id)),'':'',IFNULL(CAST(class_date AS CHAR CHARSET utf8mb4),''CLASS''))');
CALL tuition_verify_index('edu_enrollment','uk_edu_enrollment_once','schedule_id,parent_id,tuition_identity_guard,active_enrollment_guard',TRUE);
CALL tuition_verify_fk('edu_enrollment','fk_te_coupon','user_coupon_id','edu_user_coupon','user_coupon_id');
CALL tuition_verify_fk('edu_enrollment','fk_te_pricer','price_confirmed_by','sys_user','user_id');
DROP PROCEDURE tuition_verify_index;
DROP PROCEDURE tuition_verify_fk;
DROP PROCEDURE tuition_verify_generated;
DROP PROCEDURE tuition_verify_nullable;
DROP PROCEDURE tuition_verify_check;

DELIMITER $$
DROP TRIGGER IF EXISTS tuition_payment_immutable$$
CREATE TRIGGER tuition_payment_immutable BEFORE UPDATE ON edu_tuition_payment FOR EACH ROW
BEGIN
IF OLD.payment_status='SUCCESS' AND (NOT(OLD.enrollment_id<=>NEW.enrollment_id) OR NOT(OLD.channel<=>NEW.channel) OR NOT(OLD.payment_status<=>NEW.payment_status) OR NOT(OLD.finance_mode<=>NEW.finance_mode) OR NOT(OLD.amount<=>NEW.amount) OR NOT(OLD.payer_name<=>NEW.payer_name) OR NOT(OLD.provider_trade_no<=>NEW.provider_trade_no) OR NOT(OLD.evidence_key<=>NEW.evidence_key) OR NOT(OLD.bill_snapshot<=>NEW.bill_snapshot) OR NOT(OLD.recorded_by<=>NEW.recorded_by) OR NOT(OLD.confirmed_by<=>NEW.confirmed_by) OR NOT(OLD.confirmed_time<=>NEW.confirmed_time) OR NOT(OLD.paid_time<=>NEW.paid_time) OR NOT(OLD.record_source<=>NEW.record_source) OR NOT(OLD.idempotency_key<=>NEW.idempotency_key) OR NOT(OLD.request_hash<=>NEW.request_hash) OR NOT(OLD.create_time<=>NEW.create_time)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Successful tuition payment facts are immutable'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_receipt_immutable$$
CREATE TRIGGER tuition_receipt_immutable BEFORE UPDATE ON edu_tuition_receipt FOR EACH ROW
BEGIN
IF NOT(OLD.content_snapshot<=>NEW.content_snapshot) OR NOT(OLD.amount<=>NEW.amount) OR NOT(OLD.payment_id<=>NEW.payment_id) OR NOT(OLD.receipt_no<=>NEW.receipt_no) OR NOT(OLD.document_type<=>NEW.document_type) OR (OLD.receipt_status='VOID' AND NEW.receipt_status<>'VOID') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Issued receipt content is immutable; void and replace'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_refund_immutable$$
CREATE TRIGGER tuition_refund_immutable BEFORE UPDATE ON edu_tuition_refund FOR EACH ROW
BEGIN
IF OLD.refund_status='SUCCESS' AND (NOT(OLD.payment_id<=>NEW.payment_id) OR NOT(OLD.refund_no<=>NEW.refund_no) OR NOT(OLD.refund_kind<=>NEW.refund_kind) OR NOT(OLD.requested_amount<=>NEW.requested_amount) OR NOT(OLD.approved_amount<=>NEW.approved_amount) OR NOT(OLD.refund_status<=>NEW.refund_status) OR NOT(OLD.completed_time<=>NEW.completed_time) OR NOT(OLD.provider_channel<=>NEW.provider_channel) OR NOT(OLD.provider_refund_no<=>NEW.provider_refund_no) OR NOT(OLD.evidence_key<=>NEW.evidence_key) OR NOT(OLD.applicant_id<=>NEW.applicant_id) OR NOT(OLD.reviewer_id<=>NEW.reviewer_id) OR NOT(OLD.executor_id<=>NEW.executor_id) OR NOT(OLD.calculation_snapshot<=>NEW.calculation_snapshot) OR NOT(OLD.reason<=>NEW.reason) OR NOT(OLD.create_time<=>NEW.create_time)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Successful tuition refund facts are immutable'; END IF;
IF NEW.reviewer_id=NEW.applicant_id AND (SELECT finance_mode FROM edu_tuition_payment WHERE payment_id=NEW.payment_id)='REAL' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Real refund requires a different reviewer'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_refund_checker$$
CREATE TRIGGER tuition_refund_checker BEFORE INSERT ON edu_tuition_refund FOR EACH ROW
BEGIN
IF NEW.reviewer_id=NEW.applicant_id AND (SELECT finance_mode FROM edu_tuition_payment WHERE payment_id=NEW.payment_id)='REAL' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Real refund requires a different reviewer'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_payment_mode$$
CREATE TRIGGER tuition_payment_mode BEFORE INSERT ON edu_tuition_payment FOR EACH ROW
BEGIN
IF NEW.finance_mode<>(SELECT finance_mode FROM edu_enrollment WHERE enrollment_id=NEW.enrollment_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tuition payment mode differs from bill'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_bill_guard$$
CREATE TRIGGER tuition_bill_guard BEFORE UPDATE ON edu_enrollment FOR EACH ROW
BEGIN
IF EXISTS(SELECT 1 FROM edu_tuition_payment WHERE enrollment_id=OLD.enrollment_id) THEN
 IF NOT(OLD.parent_id<=>NEW.parent_id) OR NOT(OLD.schedule_id<=>NEW.schedule_id) OR NOT(OLD.student_name<=>NEW.student_name) OR NOT(OLD.enrollment_code<=>NEW.enrollment_code) OR NOT(OLD.finance_mode<=>NEW.finance_mode) OR NOT(OLD.del_flag<=>NEW.del_flag) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Financial enrollment ownership and mode are immutable'; END IF;
 IF EXISTS(SELECT 1 FROM edu_tuition_payment WHERE enrollment_id=OLD.enrollment_id AND payment_status='SUCCESS') AND (NOT(OLD.original_amount<=>NEW.original_amount) OR NOT(OLD.discount_amount<=>NEW.discount_amount) OR NOT(OLD.payable_amount<=>NEW.payable_amount) OR NOT(OLD.user_coupon_id<=>NEW.user_coupon_id) OR NOT(OLD.fee_title_snapshot<=>NEW.fee_title_snapshot) OR NOT(OLD.campus_name_snapshot<=>NEW.campus_name_snapshot) OR NOT(OLD.history_verification_snapshot<=>NEW.history_verification_snapshot)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Successful tuition bill snapshot is immutable'; END IF;
END IF;
END$$
DROP TRIGGER IF EXISTS tuition_bill_delete$$
CREATE TRIGGER tuition_bill_delete BEFORE DELETE ON edu_enrollment FOR EACH ROW
BEGIN
IF OLD.billing_status='HISTORY_PENDING' OR OLD.history_verification_snapshot IS NOT NULL OR EXISTS(SELECT 1 FROM edu_tuition_payment WHERE enrollment_id=OLD.enrollment_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Financial enrollment history cannot be deleted'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_coupon_rules$$
CREATE TRIGGER tuition_coupon_rules BEFORE UPDATE ON edu_coupon_template FOR EACH ROW
BEGIN
IF OLD.issued_quantity>0 AND (NOT(OLD.coupon_name<=>NEW.coupon_name) OR NOT(OLD.description<=>NEW.description) OR NOT(OLD.discount_amount<=>NEW.discount_amount) OR NOT(OLD.min_spend_amount<=>NEW.min_spend_amount) OR NOT(OLD.scope_schedule_id<=>NEW.scope_schedule_id) OR NOT(OLD.valid_from<=>NEW.valid_from) OR NOT(OLD.valid_until<=>NEW.valid_until) OR NEW.total_quantity<OLD.total_quantity OR NEW.issued_quantity<OLD.issued_quantity) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Issued coupon rules and quantities cannot be reduced'; END IF;
END$$
DROP TRIGGER IF EXISTS tuition_coupon_identity$$
CREATE TRIGGER tuition_coupon_identity BEFORE UPDATE ON edu_user_coupon FOR EACH ROW
BEGIN
IF NOT(OLD.parent_id<=>NEW.parent_id) OR NOT(OLD.template_id<=>NEW.template_id) OR NOT(OLD.coupon_code<=>NEW.coupon_code) OR NOT(OLD.valid_from<=>NEW.valid_from) OR NOT(OLD.valid_until<=>NEW.valid_until) OR NOT(OLD.granted_by<=>NEW.granted_by) OR NOT(OLD.grant_request_key<=>NEW.grant_request_key) OR NOT(OLD.grant_request_hash<=>NEW.grant_request_hash) OR (OLD.coupon_status='USED' AND (NEW.coupon_status<>'USED' OR NOT(OLD.used_enrollment_id<=>NEW.used_enrollment_id))) OR (OLD.coupon_status='REVOKED' AND NEW.coupon_status<>'REVOKED') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Coupon ownership and used history are immutable'; END IF;
END$$
DROP TRIGGER IF EXISTS edu_tuition_payment_preserve$$
CREATE TRIGGER edu_tuition_payment_preserve BEFORE DELETE ON edu_tuition_payment FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tuition financial facts cannot be deleted'; END$$
DROP TRIGGER IF EXISTS edu_tuition_refund_preserve$$
CREATE TRIGGER edu_tuition_refund_preserve BEFORE DELETE ON edu_tuition_refund FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tuition financial facts cannot be deleted'; END$$
DROP TRIGGER IF EXISTS edu_tuition_receipt_preserve$$
CREATE TRIGGER edu_tuition_receipt_preserve BEFORE DELETE ON edu_tuition_receipt FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tuition financial facts cannot be deleted'; END$$
DROP TRIGGER IF EXISTS edu_user_coupon_preserve$$
CREATE TRIGGER edu_user_coupon_preserve BEFORE DELETE ON edu_user_coupon FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tuition financial facts cannot be deleted'; END$$
DELIMITER ;
UPDATE sys_menu SET route_name=CASE perms WHEN 'system:tuition:list' THEN 'Tuition' WHEN 'system:tuitionReceipt:list' THEN 'TuitionReceipt' WHEN 'system:tuitionRefund:list' THEN 'TuitionRefund' WHEN 'system:coupon:list' THEN 'Coupon' END WHERE perms IN ('system:tuition:list','system:tuitionReceipt:list','system:tuitionRefund:list','system:coupon:list') AND COALESCE(route_name,'')='';
-- This is the final step: existing rows stay HISTORY_PENDING; future rows are new bills.
ALTER TABLE edu_enrollment ALTER COLUMN billing_status SET DEFAULT 'PENDING_PRICE';
