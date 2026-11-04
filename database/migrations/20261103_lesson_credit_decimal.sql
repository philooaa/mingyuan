-- 仅在核对线上实际结构、备份并暂停所有课包写入后执行。
-- 此脚本不重置余额，不改变旧预约快照或历史流水。先在生产副本演练，检查 CHECK 约束与锁表时间。
ALTER TABLE edu_student_lesson_account
  MODIFY remaining_credits DECIMAL(12,1) NOT NULL DEFAULT 0 COMMENT '剩余额度，含暂占',
  MODIFY reserved_credits DECIMAL(12,1) NOT NULL DEFAULT 0,
  MODIFY consumed_credits DECIMAL(12,1) NOT NULL DEFAULT 0;
ALTER TABLE edu_enrollment MODIFY lesson_credits DECIMAL(12,1) NULL COMMENT '预约时额度快照';
ALTER TABLE edu_lesson_credit_event
  MODIFY quantity DECIMAL(12,1) NOT NULL,
  MODIFY remaining_after DECIMAL(12,1) NOT NULL,
  MODIFY reserved_after DECIMAL(12,1) NOT NULL,
  MODIFY consumed_after DECIMAL(12,1) NOT NULL;
