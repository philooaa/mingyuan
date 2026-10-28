-- 课程定价（按年级单价）+ 课程报名购物车（一车一单）
-- 可重复执行：只补缺失结构，不覆盖已人工调整过的价格。
-- 执行顺序：先备份数据库，再执行本脚本，最后部署后端。

-- ---------------------------------------------------------------------------
-- 1. 定价规则表：一年级/二年级 150，三年级~六年级 220，初中 250，高中 280
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS edu_course_price_rule (
  rule_id     bigint       NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  grade_name  varchar(20)  NOT NULL COMMENT '年级',
  unit_price  decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '每节课单价（元/人·次）',
  status      char(1)      NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  remark      varchar(255) DEFAULT NULL COMMENT '说明',
  create_by   varchar(64)  DEFAULT '' COMMENT '创建者',
  create_time datetime     DEFAULT NULL COMMENT '创建时间',
  update_by   varchar(64)  DEFAULT '' COMMENT '更新者',
  update_time datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (rule_id),
  UNIQUE KEY uk_course_price_grade (grade_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程定价规则（按年级）';

INSERT IGNORE INTO edu_course_price_rule(grade_name, unit_price, remark, create_by, create_time) VALUES
  ('一年级', 150.00, '小学一二年级 150 元/节', 'SYSTEM', NOW()),
  ('二年级', 150.00, '小学一二年级 150 元/节', 'SYSTEM', NOW()),
  ('三年级', 220.00, '小学三至六年级 220 元/节', 'SYSTEM', NOW()),
  ('四年级', 220.00, '小学三至六年级 220 元/节', 'SYSTEM', NOW()),
  ('五年级', 220.00, '小学三至六年级 220 元/节', 'SYSTEM', NOW()),
  ('六年级', 220.00, '小学三至六年级 220 元/节', 'SYSTEM', NOW()),
  ('初一',   250.00, '初中 250 元/节', 'SYSTEM', NOW()),
  ('初二',   250.00, '初中 250 元/节', 'SYSTEM', NOW()),
  ('初三',   250.00, '初中 250 元/节', 'SYSTEM', NOW()),
  ('高一',   280.00, '高中 280 元/节', 'SYSTEM', NOW()),
  ('高二',   280.00, '高中 280 元/节', 'SYSTEM', NOW()),
  ('高三',   280.00, '高中 280 元/节', 'SYSTEM', NOW());

-- ---------------------------------------------------------------------------
-- 2. 排课增加课次数字段（课程总价 = 每节单价 × 课次数）
-- ---------------------------------------------------------------------------
SET @add_lesson_count = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='edu_course_schedule' AND COLUMN_NAME='lesson_count')=0,
  'ALTER TABLE edu_course_schedule ADD COLUMN lesson_count int DEFAULT NULL COMMENT ''本期课次数量（按开课/结课日期与上课模式推算）'' AFTER end_date',
  'SELECT 1');
PREPARE lesson_count_stmt FROM @add_lesson_count;
EXECUTE lesson_count_stmt;
DEALLOCATE PREPARE lesson_count_stmt;

-- 课次数回填：与 ScheduleCalendar 的展开口径一致
--   WEEKLY     每周固定星期（春秋季），period_name 为「周一」~「周日」
--   DAILY_5_1  上5休1（寒暑假），从开课日算起第 1~5 天上课、第 6 天休息
-- 停课/调课不改变课次总数，因此这里按基准日历计算。
DROP TEMPORARY TABLE IF EXISTS tmp_lesson_count;
CREATE TEMPORARY TABLE tmp_lesson_count (
  schedule_id bigint NOT NULL PRIMARY KEY,
  lesson_cnt  int    NOT NULL
);

INSERT INTO tmp_lesson_count(schedule_id, lesson_cnt)
WITH RECURSIVE day_seq AS (
  SELECT MIN(start_date) AS d, MAX(end_date) AS e
  FROM edu_course_schedule
  WHERE del_flag='0' AND start_date IS NOT NULL AND end_date IS NOT NULL
  UNION ALL
  SELECT d + INTERVAL 1 DAY, e FROM day_seq WHERE d < e
)
SELECT s.schedule_id, COUNT(*) AS lesson_cnt
FROM edu_course_schedule s
JOIN day_seq ON day_seq.d BETWEEN s.start_date AND s.end_date
WHERE s.del_flag='0'
  AND s.start_date IS NOT NULL
  AND s.end_date IS NOT NULL
  AND (
    (
      COALESCE(NULLIF(TRIM(s.class_pattern),''),
               CASE WHEN s.period_name IN ('周六','周日') THEN 'WEEKLY' ELSE '' END) = 'WEEKLY'
      AND WEEKDAY(day_seq.d) + 1 = FIELD(s.period_name,'周一','周二','周三','周四','周五','周六','周日')
    )
    OR (
      COALESCE(NULLIF(TRIM(s.class_pattern),''),
               CASE WHEN s.period_name IN ('周六','周日') THEN 'WEEKLY' ELSE '' END) = 'DAILY_5_1'
      AND MOD(DATEDIFF(day_seq.d, s.start_date), 6) < 5
    )
  )
GROUP BY s.schedule_id;

UPDATE edu_course_schedule s
JOIN tmp_lesson_count t ON t.schedule_id = s.schedule_id
SET s.lesson_count = t.lesson_cnt
WHERE s.lesson_count IS NULL OR s.lesson_count = 0;

DROP TEMPORARY TABLE tmp_lesson_count;

-- ---------------------------------------------------------------------------
-- 3. 按年级规则回填单价与课程总价（只填还没定价的课程）
-- ---------------------------------------------------------------------------
UPDATE edu_course_schedule s
JOIN edu_course_price_rule r ON r.grade_name = s.grade_name AND r.status='0'
SET s.unit_price   = r.unit_price,
    s.tuition_price = ROUND(r.unit_price * GREATEST(COALESCE(s.lesson_count,1),1), 2)
WHERE s.del_flag='0'
  AND (s.unit_price IS NULL OR s.unit_price = 0);

-- ---------------------------------------------------------------------------
-- 4. 报名表增加购物车订单关联
--    bill_type = 'CART_ORDER'   → 购物车订单主账单（schedule_id 为空，一车一条）
--    bill_type = 'COURSE_ITEM'  → 订单里的单门课程报名（order_enrollment_id 指向主账单）
-- ---------------------------------------------------------------------------
SET @add_order_link = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='edu_enrollment' AND COLUMN_NAME='order_enrollment_id')=0,
  'ALTER TABLE edu_enrollment ADD COLUMN order_enrollment_id bigint DEFAULT NULL COMMENT ''所属购物车订单（主账单 enrollment_id）''',
  'SELECT 1');
PREPARE order_link_stmt FROM @add_order_link;
EXECUTE order_link_stmt;
DEALLOCATE PREPARE order_link_stmt;

SET @add_order_index = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='edu_enrollment' AND INDEX_NAME='idx_enrollment_order')=0,
  'ALTER TABLE edu_enrollment ADD KEY idx_enrollment_order (order_enrollment_id)',
  'SELECT 1');
PREPARE order_index_stmt FROM @add_order_index;
EXECUTE order_index_stmt;
DEALLOCATE PREPARE order_index_stmt;

-- ---------------------------------------------------------------------------
-- 5. 菜单：课程定价（教务工具下）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
                            menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (3044, '课程定价', 3, 3, 'coursePrice', 'system/coursePrice/index', 1, 0, 'C', '0', '0',
   'system:coursePrice:list', 'money', 'admin', NOW(), '按年级、学科批量设置课程单价'),
  (3045, '课程定价查询', 3044, 1, '', '', 1, 0, 'F', '0', '0', 'system:coursePrice:query', '#', 'admin', NOW(), ''),
  (3046, '课程定价修改', 3044, 2, '', '', 1, 0, 'F', '0', '0', 'system:coursePrice:edit', '#', 'admin', NOW(), '');

-- 管理员、业务管理员：课程定价
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (1, 3044), (1, 3045), (1, 3046);
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (106, 3044), (106, 3045), (106, 3046);
-- 教务：可查看、可改价（定价属于教务职责），但收款确认与收据只归管理员
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (105, 3044), (105, 3045), (105, 3046);

-- ---------------------------------------------------------------------------
-- 6. 收款权限收紧：教务只保留核价，取消「登记收款」「确认收款」
--    收据开具（system:tuitionReceipt:issue）本来就只给管理员和业务管理员
-- ---------------------------------------------------------------------------
DELETE rm FROM sys_role_menu rm
JOIN sys_menu m ON m.menu_id = rm.menu_id
JOIN sys_role r ON r.role_id = rm.role_id
WHERE r.role_key = 'education'
  AND m.perms IN ('system:tuition:collect', 'system:tuition:confirm');

-- ---------------------------------------------------------------------------
-- 7. 放宽报名表的检查约束：允许购物车订单（CART_ORDER）与订单内课程（COURSE_ITEM）
--    原有 COURSE / CREDIT_PACKAGE 语义完全保留，只是新增两个分支。
-- ---------------------------------------------------------------------------
SET @drop_bill_type = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
   WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='edu_enrollment'
     AND CONSTRAINT_NAME='ck_enrollment_bill_type')>0,
  'ALTER TABLE edu_enrollment DROP CHECK ck_enrollment_bill_type',
  'SELECT 1');
PREPARE s1 FROM @drop_bill_type; EXECUTE s1; DEALLOCATE PREPARE s1;

ALTER TABLE edu_enrollment ADD CONSTRAINT ck_enrollment_bill_type CHECK (
  (bill_type='COURSE' AND schedule_id IS NOT NULL AND package_snapshot IS NULL
     AND package_request_key IS NULL AND package_credited_time IS NULL)
  OR (bill_type='CREDIT_PACKAGE' AND schedule_id IS NULL AND lesson_account_id IS NOT NULL
     AND student_id IS NOT NULL AND class_date IS NULL AND settlement_method='C'
     AND package_snapshot IS NOT NULL AND package_request_key IS NOT NULL)
  OR (bill_type='CART_ORDER' AND schedule_id IS NULL AND lesson_account_id IS NOT NULL
     AND student_id IS NOT NULL AND class_date IS NULL AND settlement_method='C'
     AND package_snapshot IS NULL AND package_credited_time IS NULL)
  OR (bill_type='COURSE_ITEM' AND schedule_id IS NOT NULL AND order_enrollment_id IS NOT NULL
     AND package_snapshot IS NULL AND package_request_key IS NULL)
);

SET @drop_lenroll_type = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
   WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='edu_enrollment'
     AND CONSTRAINT_NAME='ck_lenrollment_type')>0,
  'ALTER TABLE edu_enrollment DROP CHECK ck_lenrollment_type',
  'SELECT 1');
PREPARE s2 FROM @drop_lenroll_type; EXECUTE s2; DEALLOCATE PREPARE s2;

ALTER TABLE edu_enrollment ADD CONSTRAINT ck_lenrollment_type CHECK (
  (bill_type='COURSE' AND schedule_id IS NOT NULL AND lesson_package_id IS NULL)
  OR (bill_type='CREDIT_PACKAGE' AND schedule_id IS NULL AND student_id IS NOT NULL
     AND lesson_account_id IS NOT NULL AND lesson_package_id IS NULL)
  OR (bill_type='CART_ORDER' AND schedule_id IS NULL AND lesson_account_id IS NOT NULL)
  OR (bill_type='COURSE_ITEM' AND schedule_id IS NOT NULL AND order_enrollment_id IS NOT NULL)
);

SET @drop_te_bill = IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
   WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='edu_enrollment'
     AND CONSTRAINT_NAME='ck_te_bill')>0,
  'ALTER TABLE edu_enrollment DROP CHECK ck_te_bill',
  'SELECT 1');
PREPARE s3 FROM @drop_te_bill; EXECUTE s3; DEALLOCATE PREPARE s3;

-- ORDER_BOUND = 属于购物车订单、由订单统一收款，不单独成账单
ALTER TABLE edu_enrollment ADD CONSTRAINT ck_te_bill CHECK (
  billing_status IN ('PENDING_PRICE','OPEN','CLOSED','HISTORY_PENDING','ORDER_BOUND')
  AND finance_mode IN ('REAL','MOCK')
  AND financial_version >= 0
  AND (original_amount IS NULL OR original_amount >= 0)
  AND (discount_amount IS NULL OR discount_amount >= 0)
  AND (payable_amount IS NULL OR payable_amount >= 0)
  AND (original_amount IS NULL OR discount_amount IS NULL
       OR (discount_amount <= original_amount AND payable_amount = original_amount - discount_amount))
  AND (billing_status <> 'OPEN'
       OR (original_amount IS NOT NULL AND discount_amount IS NOT NULL AND payable_amount IS NOT NULL
           AND price_confirmed_by IS NOT NULL AND price_confirmed_time IS NOT NULL))
);
