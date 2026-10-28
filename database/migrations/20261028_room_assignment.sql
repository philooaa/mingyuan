-- 2026-10-28 教室按日期重新分配
--
-- 背景：edu_course_schedule 是「学期 + 期次 + 时段 + 教室」的循环模板，
--       具体上课日由 class_pattern 展开；edu_schedule_adjustment 只支持停课/改日期，不能改教室。
-- 需求：教务/管理员按日期看当天课表，把课程横向调到同一时段的其他教室；
--       调整后该课程从这一天起都在新教室上课，直到下一次重新分配。
--
-- 生效规则（读取时按日期解析，不改排课模板，历史日期不受影响）：
--   某日 D 的有效教室 = edu_room_assignment 中 effective_date <= D 的最近一条记录的 classroom_id；
--   没有记录时回落到 edu_course_schedule.classroom_id。
--
-- 幂等：可重复执行；已有库只新增这一张表，不动任何既有数据。

CREATE TABLE IF NOT EXISTS `edu_room_assignment` (
  `assignment_id`     bigint       NOT NULL AUTO_INCREMENT COMMENT '分配记录ID',
  `schedule_id`       bigint       NOT NULL COMMENT '排课ID',
  `effective_date`    date         NOT NULL COMMENT '生效起始日期（含当天）',
  `classroom_id`      bigint       NOT NULL COMMENT '该日期起的教室ID',
  `from_classroom_id` bigint       DEFAULT NULL COMMENT '变更前教室ID（审计）',
  `op_type`           char(1)      NOT NULL DEFAULT '1' COMMENT '操作类型（1分配 2互换）',
  `swap_schedule_id`  bigint       DEFAULT NULL COMMENT '互换时的对端排课ID（审计）',
  `create_by`         varchar(64)  DEFAULT '' COMMENT '操作人',
  `create_time`       datetime     DEFAULT NULL COMMENT '操作时间',
  `remark`            varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`assignment_id`),
  UNIQUE KEY `uk_room_assignment` (`schedule_id`, `effective_date`),
  KEY `idx_room_assignment_date` (`effective_date`),
  KEY `idx_room_assignment_room` (`classroom_id`),
  CONSTRAINT `fk_room_assignment_schedule`  FOREIGN KEY (`schedule_id`)  REFERENCES `edu_course_schedule` (`schedule_id`),
  CONSTRAINT `fk_room_assignment_classroom` FOREIGN KEY (`classroom_id`) REFERENCES `edu_classroom` (`classroom_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='排课教室按日期分配记录';
