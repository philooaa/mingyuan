-- MySQL 8.0 老带新：先备份、停后端，核对已安装课程财务；不改sys表结构。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS edu_referral_program (
 program_id TINYINT NOT NULL PRIMARY KEY,
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT ck_referral_program CHECK(program_id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐业务启用时点与关系并发锁';
INSERT INTO edu_referral_program(program_id) SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM edu_referral_program WHERE program_id=1);
CREATE TABLE IF NOT EXISTS edu_referral_profile (
 parent_id BIGINT NOT NULL PRIMARY KEY,
 referral_code VARCHAR(20) NULL,
 historical_customer TINYINT NOT NULL DEFAULT 0,
 first_payment_id BIGINT NULL,
 first_confirmed_at DATETIME(3) NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_referral_code(referral_code),
 CONSTRAINT fk_referral_profile_parent FOREIGN KEY(parent_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_first_payment FOREIGN KEY(first_payment_id) REFERENCES edu_tuition_payment(payment_id) ON DELETE RESTRICT,
 CONSTRAINT ck_referral_historical CHECK(historical_customer IN(0,1)),
 CONSTRAINT ck_referral_first_pair CHECK((first_payment_id IS NULL)=(first_confirmed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 历史已付/退款证据只阻断新人资格；绝不补造收款。重复执行不能把新业务倒灌为历史。
INSERT INTO edu_referral_profile(parent_id,historical_customer)
SELECT u.user_id,IF(EXISTS(SELECT 1 FROM edu_tuition_payment p JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE e.parent_id=u.user_id AND p.record_source='HISTORY_VERIFIED') OR
 EXISTS(SELECT 1 FROM edu_enrollment e WHERE e.parent_id=u.user_id AND e.billing_status='HISTORY_PENDING' AND e.pay_status IN('1','2','已支付','已退款','部分退款')) OR
 (EXISTS(SELECT 1 FROM edu_enrollment e WHERE e.parent_id=u.user_id AND e.pay_status IN('1','2','已支付','已退款','部分退款')) AND
 NOT EXISTS(SELECT 1 FROM edu_tuition_payment p JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE e.parent_id=u.user_id AND p.payment_status='SUCCESS' AND p.confirmed_by IS NOT NULL AND p.confirmed_time IS NOT NULL AND p.amount>0)),1,0)
FROM sys_user u WHERE NOT EXISTS(SELECT 1 FROM edu_referral_profile x WHERE x.parent_id=u.user_id)
AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='parent');
CREATE TABLE IF NOT EXISTS edu_referral_binding (
 binding_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 invitee_id BIGINT NOT NULL,
 referrer_id BIGINT NOT NULL,
 bound_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 locked_at DATETIME(3) NULL,
 version INT NOT NULL DEFAULT 1,
 history JSON NOT NULL,
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_referral_invitee(invitee_id),
 KEY idx_referral_referrer(referrer_id,update_time),
 CONSTRAINT fk_referral_invitee FOREIGN KEY(invitee_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_referrer FOREIGN KEY(referrer_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT,
 CONSTRAINT ck_referral_no_self CHECK(invitee_id<>referrer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_referral_record (
 record_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 binding_id BIGINT NOT NULL,
 enrollment_id BIGINT NOT NULL,
 payment_id BIGINT NOT NULL,
 review_status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 qualified_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 reviewed_by BIGINT NULL,
 reviewed_at DATETIME(3) NULL,
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_referral_qualified(binding_id),
 CONSTRAINT fk_referral_record_binding FOREIGN KEY(binding_id) REFERENCES edu_referral_binding(binding_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_record_enrollment FOREIGN KEY(enrollment_id) REFERENCES edu_enrollment(enrollment_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_record_payment FOREIGN KEY(payment_id) REFERENCES edu_tuition_payment(payment_id) ON DELETE RESTRICT,
 CONSTRAINT ck_referral_review CHECK(review_status IN('PENDING','REWARDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_referral_reward_batch (
 batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_referral_batch(actor_id,request_key),
 CONSTRAINT fk_referral_batch_actor FOREIGN KEY(actor_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_referral_reward (
 reward_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 record_id BIGINT NOT NULL,
 parent_id BIGINT NOT NULL,
 template_id BIGINT NOT NULL,
 recipient_role VARCHAR(3) NOT NULL,
 batch_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 history JSON NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_referral_reward(record_id,parent_id,template_id),
 UNIQUE KEY uk_referral_reward_owner(reward_id,parent_id,template_id),
 KEY idx_referral_reward_parent(parent_id,create_time),
 CONSTRAINT fk_referral_reward_record FOREIGN KEY(record_id) REFERENCES edu_referral_record(record_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_reward_parent FOREIGN KEY(parent_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_reward_template FOREIGN KEY(template_id) REFERENCES edu_coupon_template(template_id) ON DELETE RESTRICT,
 CONSTRAINT fk_referral_reward_batch FOREIGN KEY(batch_id) REFERENCES edu_referral_reward_batch(batch_id) ON DELETE RESTRICT,
 CONSTRAINT ck_referral_recipient CHECK(recipient_role IN('NEW','OLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_referral_anomaly (
 anomaly_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 record_id BIGINT NOT NULL,
 cause VARCHAR(20) NOT NULL,
 state VARCHAR(10) NOT NULL DEFAULT 'OPEN',
 history JSON NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_referral_anomaly(record_id,cause),
 KEY idx_referral_anomaly_state(state,update_time),
 CONSTRAINT fk_referral_anomaly_record FOREIGN KEY(record_id) REFERENCES edu_referral_record(record_id) ON DELETE RESTRICT,
 CONSTRAINT ck_referral_anomaly_state CHECK(state IN('OPEN','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS referral_coupon_upgrade$$
CREATE PROCEDURE referral_coupon_upgrade()
BEGIN
 DECLARE expression_text TEXT;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND column_name='referral_reward_id') THEN
  ALTER TABLE edu_user_coupon ADD COLUMN referral_reward_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND column_name='ordinary_parent_id') THEN
  ALTER TABLE edu_user_coupon ADD COLUMN ordinary_parent_id BIGINT GENERATED ALWAYS AS (CASE WHEN referral_reward_id IS NULL THEN parent_id ELSE NULL END) STORED;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND column_name='referral_reward_id' AND column_type='bigint' AND is_nullable='YES' AND COALESCE(generation_expression,'')='') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected coupon referral source column';
 END IF;
 SELECT generation_expression INTO expression_text FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND column_name='ordinary_parent_id' AND column_type='bigint' AND extra LIKE '%STORED GENERATED%';
 IF expression_text IS NULL OR REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(LOWER(expression_text),CHAR(92),''),'`',''),'_utf8mb4',''),'[[:space:]()]','')<>REGEXP_REPLACE(LOWER('CASE WHEN referral_reward_id IS NULL THEN parent_id ELSE NULL END'),'[[:space:]()]','') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected ordinary coupon generated identity';
 END IF;
 IF COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND index_name='uk_uc_parent' AND non_unique=0),'') NOT IN('template_id,parent_id','template_id,ordinary_parent_id') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected coupon uniqueness; inspect before referral migration';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND index_name='uk_uc_parent' AND column_name='parent_id') THEN
  ALTER TABLE edu_user_coupon DROP INDEX uk_uc_parent, ADD UNIQUE KEY uk_uc_parent(template_id,ordinary_parent_id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND index_name='uk_uc_referral_reward') THEN
  ALTER TABLE edu_user_coupon ADD UNIQUE KEY uk_uc_referral_reward(referral_reward_id);
 END IF;
 IF COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND index_name='uk_uc_parent' AND non_unique=0),'')<>'template_id,ordinary_parent_id' OR
 COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND index_name='uk_uc_referral_reward' AND non_unique=0),'')<>'referral_reward_id' OR
 COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='edu_user_coupon' AND index_name='uk_uc_request' AND non_unique=0),'')<>'granted_by,grant_request_key,parent_id' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected coupon unique/request indexes';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_user_coupon' AND constraint_name='fk_uc_referral_reward') THEN
  ALTER TABLE edu_user_coupon ADD CONSTRAINT fk_uc_referral_reward FOREIGN KEY(referral_reward_id,parent_id,template_id) REFERENCES edu_referral_reward(reward_id,parent_id,template_id) ON DELETE RESTRICT;
 END IF;
 IF COALESCE((SELECT GROUP_CONCAT(CONCAT(column_name,':',referenced_table_name,':',referenced_column_name) ORDER BY ordinal_position) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND table_name='edu_user_coupon' AND constraint_name='fk_uc_referral_reward'),'')<>'referral_reward_id:edu_referral_reward:reward_id,parent_id:edu_referral_reward:parent_id,template_id:edu_referral_reward:template_id' OR
 NOT EXISTS(SELECT 1 FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='edu_user_coupon' AND constraint_name='fk_uc_referral_reward' AND delete_rule='RESTRICT' AND update_rule IN('RESTRICT','NO ACTION')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected referral coupon source foreign key';
 END IF;
END$$
CALL referral_coupon_upgrade()$$
DROP PROCEDURE referral_coupon_upgrade$$
DROP TRIGGER IF EXISTS edu_referral_coupon_source_immutable$$
CREATE TRIGGER edu_referral_coupon_source_immutable BEFORE UPDATE ON edu_user_coupon FOR EACH ROW
BEGIN
 IF NOT(OLD.referral_reward_id<=>NEW.referral_reward_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Referral reward source is immutable'; END IF;
END$$
DELIMITER ;
-- 只添加业务菜单与管理员权限数据，不修改sys结构。
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '老带新管理',COALESCE((SELECT menu_id FROM sys_menu WHERE path='system' AND menu_type='M' LIMIT 1),0),16,'eduReferral','system/referral/index',1,0,'C','0','0','edu:referral:manage','peoples','migration',NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='edu:referral:manage');
INSERT INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms='edu:referral:manage' WHERE r.role_key='admin' AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
