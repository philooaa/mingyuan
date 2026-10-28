SET NAMES utf8mb4;
-- Stop the backend and back up first. Run after 20261007 and the single-admin increment.
-- Incremental and repeatable; no business records or user-role assignments are changed.
DELIMITER $$
DROP PROCEDURE IF EXISTS tuition_offline_check$$
CREATE PROCEDURE tuition_offline_check(IN n varchar(64), IN expected text)
BEGIN
  DECLARE actual TEXT; DECLARE canonical TEXT; DECLARE probe_created BOOLEAN DEFAULT FALSE;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN IF probe_created THEN DROP TABLE __tuition_offline_verify; END IF; RESIGNAL; END;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='__tuition_offline_verify') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Inspect interrupted offline refund verification probe';
  END IF;
  SELECT cc.check_clause INTO actual FROM information_schema.check_constraints cc
    JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
    WHERE tc.constraint_schema=DATABASE() AND tc.table_name='edu_tuition_refund' AND tc.constraint_name=n AND tc.enforced='YES';
  IF actual IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing refund check'; END IF;
  CREATE TABLE __tuition_offline_verify LIKE edu_tuition_refund;
  SET probe_created=TRUE;
  SET @ddl=CONCAT('ALTER TABLE __tuition_offline_verify ADD CONSTRAINT ck_tuition_offline_verify CHECK (',expected,')');
  PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  SELECT check_clause INTO canonical FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_tuition_offline_verify';
  DROP TABLE __tuition_offline_verify; SET probe_created=FALSE;
  IF NOT(BINARY actual<=>BINARY canonical) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting refund check expression';
  END IF;
END$$
DROP PROCEDURE IF EXISTS tuition_offline_upgrade$$
CREATE PROCEDURE tuition_offline_upgrade()
BEGIN
  DECLARE upgraded INT DEFAULT 0;
  DECLARE actual text;
  IF EXISTS(SELECT 1 FROM sys_role WHERE role_key='education' AND
      (role_name<>'教务' OR status<>'0' OR del_flag<>'0'))
      OR (SELECT COUNT(*) FROM sys_role WHERE role_key='education')>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting education role; review manually';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='edu_tuition_payment' AND index_name='uk_tp_effective' AND column_name='effective_enrollment_id' AND non_unique=0)
      OR NOT EXISTS(SELECT 1 FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()
      AND table_name='edu_tuition_payment' AND constraint_name='fk_tp_recorder' AND column_name='recorded_by'
      AND referenced_table_name='sys_user' AND referenced_column_name='user_id') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing payment safety constraint';
  END IF;
  SELECT generation_expression INTO actual FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='edu_tuition_payment' AND column_name='effective_enrollment_id';
  IF REPLACE(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(LOWER(actual),CHAR(92),''),'`',''),'_utf8mb4',''),'[[:space:]()]',''),'elsenull','')
      <> 'casewhenpayment_statusin''pending'',''unknown'',''success''thenenrollment_idend' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting payment generated guard';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='edu_tuition_refund' AND column_name='requested_amount' AND column_type='decimal(10,2)')
      OR NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='edu_tuition_refund' AND index_name='uk_tr_active' AND column_name='active_payment_id' AND non_unique=0)
      OR NOT EXISTS(SELECT 1 FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='tuition_refund_immutable') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing or conflicting tuition prerequisite';
  END IF;
  SELECT COUNT(*) INTO upgraded FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND table_name='edu_tuition_refund' AND constraint_name='ck_tr_offline';
  IF upgraded=0 THEN
    CALL tuition_offline_check('ck_tr_money','requested_amount>0 AND (approved_amount IS NULL OR (approved_amount>0 AND approved_amount<=requested_amount))');
    CALL tuition_offline_check('ck_tr_state','refund_kind IN (''PARTIAL'',''WITHDRAWAL'') AND refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''SUCCESS'',''REJECTED'',''CANCELLED'',''FAILED'',''UNKNOWN'')');
    CALL tuition_offline_check('ck_tr_approved','refund_status NOT IN (''APPROVED'',''PROCESSING'',''SUCCESS'',''UNKNOWN'') OR approved_amount IS NOT NULL');
    IF (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name='edu_tuition_refund' AND constraint_name IN ('ck_tr_money','ck_tr_state','ck_tr_approved','ck_tr_success'))<>4 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing legacy refund constraints';
    END IF;
    ALTER TABLE edu_tuition_refund
      DROP CHECK ck_tr_money, DROP CHECK ck_tr_state, DROP CHECK ck_tr_approved,
      MODIFY requested_amount decimal(10,2) NULL,
      MODIFY active_payment_id bigint GENERATED ALWAYS AS
        (CASE WHEN refund_status IN ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','WAIT_EDU','WAIT_ADMIN') THEN payment_id END) STORED,
      ADD CONSTRAINT ck_tr_money CHECK ((requested_amount IS NULL OR requested_amount>0)
        AND (approved_amount IS NULL OR approved_amount>0)
        AND (requested_amount IS NULL OR approved_amount IS NULL OR approved_amount<=requested_amount)),
      ADD CONSTRAINT ck_tr_state CHECK (refund_kind IN ('PARTIAL','WITHDRAWAL') AND refund_status IN
        ('PENDING_REVIEW','APPROVED','PROCESSING','SUCCESS','REJECTED','CANCELLED','FAILED','UNKNOWN','WAIT_EDU','WAIT_ADMIN')),
      ADD CONSTRAINT ck_tr_approved CHECK (refund_status NOT IN ('APPROVED','PROCESSING','SUCCESS','UNKNOWN','WAIT_ADMIN') OR approved_amount IS NOT NULL),
      ADD CONSTRAINT ck_tr_offline CHECK (
        (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(calculation_snapshot,'$.ruleVersion')),'')='tuition-refund-offline-v2'
          AND requested_amount IS NULL AND refund_status IN ('WAIT_EDU','WAIT_ADMIN','SUCCESS','REJECTED')
          AND (refund_status<>'WAIT_EDU' OR (approved_amount IS NULL AND reviewer_id IS NULL AND review_time IS NULL))
          AND (refund_status NOT IN ('WAIT_ADMIN','SUCCESS','REJECTED') OR (reviewer_id IS NOT NULL AND review_time IS NOT NULL))
          AND (refund_status<>'SUCCESS' OR executor_id IS NOT NULL))
        OR (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(calculation_snapshot,'$.ruleVersion')),'')<>'tuition-refund-offline-v2'
          AND requested_amount IS NOT NULL AND refund_status NOT IN ('WAIT_EDU','WAIT_ADMIN')));
  ELSE
    IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='edu_tuition_refund' AND column_name='requested_amount' AND is_nullable='YES')
        OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='edu_tuition_refund' AND column_name='active_payment_id'
        AND generation_expression LIKE '%WAIT_EDU%' AND generation_expression LIKE '%WAIT_ADMIN%')
        OR (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
        AND table_name='edu_tuition_refund' AND constraint_name IN ('ck_tr_money','ck_tr_state','ck_tr_approved','ck_tr_success','ck_tr_offline') AND enforced='YES')<>5 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting upgraded refund constraints';
    END IF;
  END IF;
  CALL tuition_offline_check('ck_tr_money','(requested_amount IS NULL OR requested_amount>0) AND (approved_amount IS NULL OR approved_amount>0) AND (requested_amount IS NULL OR approved_amount IS NULL OR approved_amount<=requested_amount)');
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
      AND table_name='edu_tuition_refund' AND constraint_name='ck_session_target' AND enforced='YES') THEN
    CALL tuition_offline_check('ck_tr_state','refund_kind IN (''PARTIAL'',''WITHDRAWAL'',''SESSION'') AND refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''SUCCESS'',''REJECTED'',''CANCELLED'',''FAILED'',''UNKNOWN'',''WAIT_EDU'',''WAIT_ADMIN'')');
  ELSE
    CALL tuition_offline_check('ck_tr_state','refund_kind IN (''PARTIAL'',''WITHDRAWAL'') AND refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''SUCCESS'',''REJECTED'',''CANCELLED'',''FAILED'',''UNKNOWN'',''WAIT_EDU'',''WAIT_ADMIN'')');
  END IF;
  CALL tuition_offline_check('ck_tr_approved','refund_status NOT IN (''APPROVED'',''PROCESSING'',''SUCCESS'',''UNKNOWN'',''WAIT_ADMIN'') OR approved_amount IS NOT NULL');
  CALL tuition_offline_check('ck_tr_success','refund_status<>''SUCCESS'' OR completed_time IS NOT NULL');
  CALL tuition_offline_check('ck_tr_offline','
    (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(calculation_snapshot,''$.ruleVersion'')),'''')=''tuition-refund-offline-v2''
      AND requested_amount IS NULL AND refund_status IN (''WAIT_EDU'',''WAIT_ADMIN'',''SUCCESS'',''REJECTED'')
      AND (refund_status<>''WAIT_EDU'' OR (approved_amount IS NULL AND reviewer_id IS NULL AND review_time IS NULL))
      AND (refund_status NOT IN (''WAIT_ADMIN'',''SUCCESS'',''REJECTED'') OR (reviewer_id IS NOT NULL AND review_time IS NOT NULL))
      AND (refund_status<>''SUCCESS'' OR executor_id IS NOT NULL))
    OR (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(calculation_snapshot,''$.ruleVersion'')),'''')<>''tuition-refund-offline-v2''
      AND requested_amount IS NOT NULL AND refund_status NOT IN (''WAIT_EDU'',''WAIT_ADMIN''))');
  SELECT generation_expression INTO actual FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='edu_tuition_refund' AND column_name='active_payment_id';
  IF REPLACE(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(LOWER(actual),CHAR(92),''),'`',''),'_utf8mb4',''),'[[:space:]()]',''),'elsenull','')
      <> 'casewhenrefund_statusin''pending_review'',''approved'',''processing'',''unknown'',''wait_edu'',''wait_admin''thenpayment_idend' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Conflicting refund generated guard';
  END IF;
  IF (SELECT COUNT(*) FROM sys_menu WHERE perms='system:tuitionRefund:list')<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Refund menu must have one parent';
  END IF;
  INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_by,create_time)
    SELECT '教务','education',3,'0','0','SYSTEM',NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_role WHERE role_key='education');
  INSERT INTO sys_menu(menu_name,parent_id,path,menu_type,perms,create_by,create_time)
    SELECT '管理员已退款',(SELECT menu_id FROM sys_menu WHERE perms='system:tuitionRefund:list'),
      '#','F','system:tuitionRefund:markRefunded','SYSTEM',NOW()
    WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='system:tuitionRefund:markRefunded');
  INSERT INTO sys_role_menu(role_id,menu_id)
    SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON
      (r.role_key='admin' AND m.perms='system:tuitionRefund:markRefunded') OR
      (r.role_key='education' AND (m.menu_id=(SELECT parent_id FROM sys_menu WHERE perms='system:tuitionRefund:list')
        OR m.perms IN ('system:tuition:list','system:tuition:query','system:tuition:price','system:tuition:collect','system:tuition:confirm',
          'system:tuitionRefund:list','system:tuitionRefund:query','system:tuitionRefund:apply','system:tuitionRefund:review',
          'system:tuitionReceipt:list','system:tuitionReceipt:download')))
    WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
END$$
CALL tuition_offline_upgrade()$$
DROP PROCEDURE tuition_offline_upgrade$$
DROP PROCEDURE tuition_offline_check$$
DROP TRIGGER IF EXISTS tuition_refund_review_immutable$$
CREATE TRIGGER tuition_refund_review_immutable BEFORE UPDATE ON edu_tuition_refund FOR EACH ROW
BEGIN
  IF OLD.refund_status='SUCCESS' AND (NOT(OLD.review_time<=>NEW.review_time)
      OR NOT(OLD.review_remark<=>NEW.review_remark) OR NOT(OLD.remark<=>NEW.remark)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Successful refund review facts are immutable';
  END IF;
END$$
DELIMITER ;
