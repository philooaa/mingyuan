-- MySQL 8.0 增量迁移；先备份并核对已完成 20261009 一对一、20261011 教师资料迁移。
-- 不修改任何 sys 表结构，不导入学生或余额。ALTER 部分只执行一次。
CREATE TABLE IF NOT EXISTS edu_student_lesson_account (
  account_id BIGINT NOT NULL AUTO_INCREMENT,
  parent_id BIGINT NOT NULL,
  student_name VARCHAR(30) NOT NULL,
  grade_name VARCHAR(20) NOT NULL,
  contact_phone VARCHAR(11) NULL,
  remaining_credits INT NOT NULL DEFAULT 0 COMMENT '剩余额度，含暂占',
  reserved_credits INT NOT NULL DEFAULT 0,
  consumed_credits INT NOT NULL DEFAULT 0,
  request_key VARCHAR(100) NOT NULL,
  create_by VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) NULL,
  update_time DATETIME NULL,
  PRIMARY KEY (account_id), UNIQUE KEY uk_lesson_account_request (request_key),
  KEY idx_lesson_account_parent (parent_id),
  CONSTRAINT fk_lesson_account_parent FOREIGN KEY (parent_id) REFERENCES sys_user(user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_lesson_account_balance CHECK (remaining_credits >= reserved_credits AND reserved_credits >= 0 AND consumed_credits >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='明确学生身份的一对一课次账户；不按姓名自动合并';

ALTER TABLE edu_enrollment
  ADD COLUMN settlement_method CHAR(1) NOT NULL DEFAULT 'C' COMMENT 'C单次付费 P课包',
  ADD COLUMN lesson_account_id BIGINT NULL,
  ADD COLUMN lesson_credits INT NULL COMMENT '预约时后台教师等级决定的额度快照',
  ADD COLUMN teacher_level_snapshot VARCHAR(20) NULL,
  ADD COLUMN lesson_completed_time DATETIME NULL,
  ADD COLUMN lesson_completed_by BIGINT NULL,
  ADD CONSTRAINT fk_enrollment_lesson_account FOREIGN KEY (lesson_account_id) REFERENCES edu_student_lesson_account(account_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE IF NOT EXISTS edu_lesson_credit_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  enrollment_id BIGINT NULL,
  action VARCHAR(20) NOT NULL COMMENT 'GRANT WITHDRAW HOLD RELEASE CONSUME',
  quantity INT NOT NULL,
  remaining_after INT NOT NULL,
  reserved_after INT NOT NULL,
  consumed_after INT NOT NULL,
  request_key VARCHAR(100) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(64) NOT NULL,
  reason VARCHAR(255) NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (event_id), UNIQUE KEY uk_lesson_credit_request (request_key),
  KEY idx_lesson_credit_account (account_id,event_id),
  CONSTRAINT fk_lesson_credit_account FOREIGN KEY (account_id) REFERENCES edu_student_lesson_account(account_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_lesson_credit_enrollment FOREIGN KEY (enrollment_id) REFERENCES edu_enrollment(enrollment_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_lesson_credit_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课次发放收回暂占消耗历史，只追加';

-- 班课网格唯一规则保留；一对一改按真实日期/时间资源锁校验，停用历史不阻断重新发布。
ALTER TABLE edu_course_schedule
  ADD COLUMN class_grid TINYINT GENERATED ALWAYS AS (CASE WHEN class_mode='2' THEN NULL ELSE 1 END) STORED,
  DROP INDEX uk_course_timetable_cell,
  ADD UNIQUE KEY uk_course_timetable_cell (classroom_id,course_year,term_name,period_name,time_slot,del_flag,class_grid);

-- 仅添加普通菜单数据，沿用若依权限管理；接口另行强制管理员角色。
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '交易管理',COALESCE((SELECT menu_id FROM sys_menu WHERE path='system' AND menu_type='M' LIMIT 1),0),15,'eduTransactions','system/transaction/index',1,0,'C','0','0','edu:transaction:manage','money','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='edu:transaction:manage');
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms='edu:transaction:manage'
WHERE r.role_key='admin' AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
