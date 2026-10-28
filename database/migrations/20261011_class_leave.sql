-- 班课请假：先核对目标库并备份；只新增业务表，不修改 sys 或历史考勤。
-- 依赖现有 edu_enrollment / edu_course_schedule / edu_student / 学生课次账户。
-- 历史申请与审批放在只追加的 history JSON 中，撤回/拒绝后重提仍保留历史。
CREATE TABLE IF NOT EXISTS edu_class_leave_request (
  leave_id BIGINT NOT NULL AUTO_INCREMENT,
  enrollment_id BIGINT NOT NULL,
  schedule_id BIGINT NOT NULL,
  parent_id BIGINT NOT NULL,
  student_id BIGINT DEFAULT NULL,
  lesson_account_id BIGINT DEFAULT NULL,
  child_key VARCHAR(60) GENERATED ALWAYS AS
    (COALESCE(CONCAT('S:',student_id),CONCAT('A:',lesson_account_id),CONCAT('E:',enrollment_id))) STORED,
  student_name VARCHAR(30) NOT NULL COMMENT '申请时姓名快照',
  course_name VARCHAR(100) NOT NULL COMMENT '申请时课程名快照',
  class_date DATE NOT NULL COMMENT '调课后的实际日期',
  start_at DATETIME NOT NULL,
  end_at DATETIME NOT NULL,
  reason VARCHAR(255) NOT NULL,
  state VARCHAR(12) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN',
  version INT NOT NULL DEFAULT 1 COMMENT '防止过期审批处理重提申请',
  reviewed_by BIGINT DEFAULT NULL,
  review_reason VARCHAR(255) DEFAULT NULL,
  reviewed_at DATETIME(3) DEFAULT NULL,
  history JSON NOT NULL COMMENT '申请、撤回和审批历史；服务只追加',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(leave_id),
  UNIQUE KEY uk_class_leave_child_date(schedule_id,class_date,child_key),
  KEY idx_class_leave_parent(parent_id,update_time,leave_id),
  KEY idx_class_leave_enrollment(enrollment_id,class_date),
  KEY idx_class_leave_pending(state,schedule_id,update_time),
  CONSTRAINT fk_class_leave_enrollment FOREIGN KEY(enrollment_id) REFERENCES edu_enrollment(enrollment_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_class_leave_schedule FOREIGN KEY(schedule_id) REFERENCES edu_course_schedule(schedule_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_class_leave_student_owner FOREIGN KEY(student_id,parent_id) REFERENCES edu_student(student_id,parent_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_class_leave_account FOREIGN KEY(lesson_account_id) REFERENCES edu_student_lesson_account(account_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_class_leave_state CHECK(state IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT ck_class_leave_time CHECK(end_at>start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班课请假申请与审批历史；不涉及费用及补课';
