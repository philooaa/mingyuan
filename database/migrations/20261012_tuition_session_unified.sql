SET NAMES utf8mb4;
-- Unified enrollment / offline tuition session refunds. MySQL 8.0.
-- Run after 20261010_tuition_offline_two_stage.sql; back up and stop the backend first.
-- No old slot/booking/package tables, no balance changes or historical record rewrites.
DELIMITER $$
DROP PROCEDURE IF EXISTS session_column$$
CREATE PROCEDURE session_column(IN t varchar(64),IN c varchar(64),IN typ varchar(100),IN spec text,IN nullable varchar(3),IN def text,IN expression text)
BEGIN
 DECLARE actual varchar(100); DECLARE actualnull varchar(3); DECLARE actualdef text; DECLARE actualexpr text; DECLARE ext text; DECLARE msg varchar(128); DECLARE canonical text; DECLARE probe_created boolean DEFAULT FALSE;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN IF probe_created THEN DROP TABLE __session_verify_probe; END IF; RESIGNAL; END;
 SELECT column_type,is_nullable,column_default,generation_expression,extra INTO actual,actualnull,actualdef,actualexpr,ext FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=t AND column_name=c;
 IF actual IS NULL THEN
  SET @ddl=CONCAT('ALTER TABLE `',t,'` ADD COLUMN `',c,'` ',typ,' ',spec); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 ELSE
 IF expression IS NOT NULL THEN
  SET @ddl=CONCAT('CREATE TABLE __session_verify_probe LIKE `',t,'`'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET probe_created=TRUE;
  SET @ddl=CONCAT('ALTER TABLE __session_verify_probe MODIFY `',c,'` ',typ,' ',spec); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  SELECT generation_expression INTO canonical FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='__session_verify_probe' AND column_name=c;
  DROP TABLE __session_verify_probe; SET probe_created=FALSE;
 END IF;
 -- Both expressions are canonicalized by MySQL; binary equality preserves quoted literals.
 IF LOWER(actual)<>LOWER(typ) OR actualnull<>nullable OR NOT(BINARY actualdef<=>BINARY def) OR (expression IS NULL AND COALESCE(actualexpr,'')<>'') OR (expression IS NOT NULL AND (ext NOT LIKE '%STORED GENERATED%' OR NOT(BINARY actualexpr<=>BINARY canonical))) OR (spec LIKE '%AUTO_INCREMENT%' AND ext NOT LIKE '%auto_increment%') THEN
  SET msg=CONCAT('Lesson column/generated conflict: ',t,'.',c); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg;
 END IF;
 END IF;
END$$
DROP PROCEDURE IF EXISTS session_index$$
CREATE PROCEDURE session_index(IN t varchar(64),IN n varchar(64),IN cols text,IN uniq boolean)
BEGIN
 DECLARE actual text; DECLARE nonuniq int; DECLARE msg varchar(128);
 IF NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=t AND engine='InnoDB' AND table_collation LIKE 'utf8mb4%') THEN SET msg=CONCAT('Lesson table engine/charset conflict: ',t); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
 SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index),MAX(non_unique) INTO actual,nonuniq FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=t AND index_name=n;
 IF actual IS NULL THEN
  SET @ddl=CONCAT('ALTER TABLE `',t,'` ADD ',IF(n='PRIMARY','PRIMARY KEY',CONCAT(IF(uniq,'UNIQUE KEY ','KEY '),'`',n,'`')),' (',cols,')'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 ELSEIF actual<>cols OR nonuniq<>IF(uniq,0,1) OR EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=t AND index_name=n AND (sub_part IS NOT NULL OR is_visible<>'YES' OR index_type<>'BTREE')) THEN
  SET msg=CONCAT('Lesson index conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg;
 END IF;
END$$
DROP PROCEDURE IF EXISTS session_fk$$
CREATE PROCEDURE session_fk(IN t varchar(64),IN n varchar(64),IN cols text,IN target varchar(64),IN targetcols text)
BEGIN
 DECLARE actual text; DECLARE refs text; DECLARE targets text; DECLARE rules text; DECLARE msg varchar(128);
 SELECT GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position),GROUP_CONCAT(k.referenced_column_name ORDER BY k.ordinal_position),GROUP_CONCAT(DISTINCT k.referenced_table_name),GROUP_CONCAT(DISTINCT CONCAT(r.update_rule,':',r.delete_rule))
 INTO actual,refs,targets,rules FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.constraint_schema=DATABASE() AND k.table_name=t AND k.constraint_name=n;
 IF actual IS NULL THEN
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name=t AND constraint_name=n) THEN SET msg=CONCAT('Lesson foreign key kind conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
  SET @ddl=CONCAT('ALTER TABLE `',t,'` ADD CONSTRAINT `',n,'` FOREIGN KEY (',cols,') REFERENCES `',target,'` (',targetcols,')'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 ELSEIF actual<>cols OR refs<>targetcols OR targets<>target OR rules NOT IN ('NO ACTION:NO ACTION','RESTRICT:RESTRICT') OR EXISTS(SELECT 1 FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND table_name=t AND constraint_name=n AND referenced_table_schema<>DATABASE()) THEN
  SET msg=CONCAT('Lesson foreign key conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg;
 END IF;
END$$
DROP PROCEDURE IF EXISTS session_check$$
CREATE PROCEDURE session_check(IN t varchar(64),IN n varchar(64),IN expression text)
BEGIN
 DECLARE actual text; DECLARE msg varchar(128); DECLARE canonical text; DECLARE probe_created boolean DEFAULT FALSE;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN IF probe_created THEN DROP TABLE __session_verify_probe; END IF; RESIGNAL; END;
 SELECT cc.check_clause INTO actual FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name WHERE tc.constraint_schema=DATABASE() AND tc.table_name=t AND tc.constraint_name=n AND tc.enforced='YES';
 IF actual IS NULL AND NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name=t AND constraint_name=n) THEN
  SET @ddl=CONCAT('ALTER TABLE `',t,'` ADD CONSTRAINT `',n,'` CHECK (',expression,')'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 ELSE
  IF actual IS NOT NULL THEN
   SET @ddl=CONCAT('CREATE TABLE __session_verify_probe LIKE `',t,'`'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt; SET probe_created=TRUE;
   SET @ddl=CONCAT('ALTER TABLE __session_verify_probe ADD CONSTRAINT ck_session_verify_probe CHECK (',expression,')'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
   SELECT check_clause INTO canonical FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_session_verify_probe';
   DROP TABLE __session_verify_probe; SET probe_created=FALSE;
  END IF;
  IF actual IS NULL OR NOT(BINARY actual<=>BINARY canonical) THEN SET msg=CONCAT('Lesson check conflict: ',t,'.',n); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=msg; END IF;
 END IF;
END$$

DROP PROCEDURE IF EXISTS session_unified_preflight$$
CREATE PROCEDURE session_unified_preflight()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_tuition_refund' AND constraint_name='ck_tr_offline' AND enforced='YES')
 OR NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Apply offline two-stage and existing attendance schema first';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('edu_one_to_one_booking','edu_student_package','__session_verify_probe')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Legacy lesson schema or interrupted probe requires review before unified refund migration';
 END IF;
END$$
CALL session_unified_preflight()$$
DROP PROCEDURE session_unified_preflight$$
DROP PROCEDURE IF EXISTS session_upgrade_check$$
CREATE PROCEDURE session_upgrade_check(IN t varchar(64),IN n varchar(64),IN oldexpr text,IN newexpr text)
BEGIN
 DECLARE actual text; DECLARE canonical text;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN DROP TABLE IF EXISTS __session_verify_probe; RESIGNAL; END;
 SELECT cc.check_clause INTO actual FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name WHERE tc.constraint_schema=DATABASE() AND tc.table_name=t AND tc.constraint_name=n AND tc.enforced='YES';
 SET @ddl=CONCAT('CREATE TABLE __session_verify_probe LIKE ',t); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 SET @ddl=CONCAT('ALTER TABLE __session_verify_probe ADD CONSTRAINT ck_session_verify_probe CHECK (',newexpr,')'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 SELECT check_clause INTO canonical FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_session_verify_probe';
 DROP TABLE __session_verify_probe;
 IF NOT(BINARY actual<=>BINARY canonical) THEN
  IF actual IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing prerequisite check'; END IF;
  CALL session_check(t,n,oldexpr);
  SET @ddl=CONCAT('ALTER TABLE ',t,' DROP CHECK ',n,', ADD CONSTRAINT ',n,' CHECK (',newexpr,')'); PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
 END IF;
END$$
DELIMITER ;
CALL session_column('edu_tuition_refund','class_sign_in_id','bigint','NULL','YES',NULL,NULL);
-- Retain the request/response field so stale old-client requests are explicitly rejected.
CALL session_column('edu_tuition_refund','lesson_booking_id','bigint','NULL','YES',NULL,NULL);
CALL session_upgrade_check('edu_tuition_refund','ck_tr_state','refund_kind IN (''PARTIAL'',''WITHDRAWAL'') AND refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''SUCCESS'',''REJECTED'',''CANCELLED'',''FAILED'',''UNKNOWN'',''WAIT_EDU'',''WAIT_ADMIN'')','refund_kind IN (''PARTIAL'',''WITHDRAWAL'',''SESSION'') AND refund_status IN (''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''SUCCESS'',''REJECTED'',''CANCELLED'',''FAILED'',''UNKNOWN'',''WAIT_EDU'',''WAIT_ADMIN'')');
CALL session_check('edu_tuition_refund','ck_session_target','lesson_booking_id IS NULL AND ((refund_kind=''SESSION'' AND class_sign_in_id IS NOT NULL) OR (refund_kind<>''SESSION'' AND class_sign_in_id IS NULL))');
CALL session_fk('edu_tuition_refund','fk_session_sign','class_sign_in_id','edu_class_sign_in','sign_in_id');
CALL session_column('edu_tuition_refund','active_class_sign_in_id','bigint','GENERATED ALWAYS AS (CASE WHEN refund_status IN (''WAIT_EDU'',''WAIT_ADMIN'',''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''UNKNOWN'',''SUCCESS'') THEN class_sign_in_id END) STORED','YES',NULL,'CASE WHEN refund_status IN (''WAIT_EDU'',''WAIT_ADMIN'',''PENDING_REVIEW'',''APPROVED'',''PROCESSING'',''UNKNOWN'',''SUCCESS'') THEN class_sign_in_id END');
CALL session_index('edu_tuition_refund','uk_session_class_sign_in_id','active_class_sign_in_id',TRUE);
DELIMITER $$
DROP TRIGGER IF EXISTS session_refund_identity$$
CREATE TRIGGER session_refund_identity BEFORE UPDATE ON edu_tuition_refund FOR EACH ROW
BEGIN
 IF NOT(OLD.class_sign_in_id<=>NEW.class_sign_in_id) OR NOT(OLD.lesson_booking_id<=>NEW.lesson_booking_id) OR (OLD.refund_kind='SESSION' AND (NOT(OLD.payment_id<=>NEW.payment_id) OR NOT(OLD.refund_kind<=>NEW.refund_kind))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Refund session targets are immutable'; END IF;
END$$
DROP TRIGGER IF EXISTS session_refund_owner$$
CREATE TRIGGER session_refund_owner BEFORE INSERT ON edu_tuition_refund FOR EACH ROW
BEGIN
 IF NEW.lesson_booking_id IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Legacy booking targets are not part of unified enrollment'; END IF;
 IF NEW.class_sign_in_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM edu_class_sign_in s JOIN edu_tuition_payment p ON p.enrollment_id=s.enrollment_id JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE p.payment_id=NEW.payment_id AND s.sign_in_id=NEW.class_sign_in_id AND s.schedule_id=e.schedule_id AND s.source_type='1') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Class refund target ownership mismatch'; END IF;
END$$
DROP PROCEDURE session_upgrade_check$$
DROP PROCEDURE session_column$$
DROP PROCEDURE session_index$$
DROP PROCEDURE session_fk$$
DROP PROCEDURE session_check$$
DELIMITER ;
