-- 一对一增量迁移（MySQL 8.0）。先核对当前库并备份；不得对未核验结构直接执行。
-- 本脚本仅供尚未迁移的数据库执行一次；目标字段或表已存在时，不要重复执行。
-- 不新增课程或报名平行表，不迁移/猜测历史一对一报名日期。
-- DDL 在 MySQL 中不能按普通事务回滚，执行前须保留结构及数据备份。
-- 教师资料已拆至 edu_teacher_profile；先执行 20261011_teacher_profile_incremental.sql。
-- 已执行过本脚本的数据库只需执行上述新迁移，不要重跑本脚本。
ALTER TABLE edu_course_schedule
 MODIFY COLUMN classroom_id bigint NULL COMMENT '班课教室；自由地点一对一可为空',
 ADD COLUMN lesson_location varchar(255) DEFAULT NULL COMMENT '教师自定义上课地点';
ALTER TABLE edu_enrollment
 ADD COLUMN class_date date DEFAULT NULL COMMENT '一对一单次课日期；班课和旧记录为空',
 ADD COLUMN teacher_confirm_by bigint DEFAULT NULL,
 ADD COLUMN teacher_confirm_time datetime DEFAULT NULL,
 ADD COLUMN cancel_request_status char(1) NOT NULL DEFAULT '0' COMMENT '0无申请 1待处理 2同意 3拒绝',
 ADD COLUMN cancel_request_reason varchar(255) DEFAULT NULL,
 ADD COLUMN cancel_request_time datetime DEFAULT NULL,
 ADD COLUMN cancel_processed_by bigint DEFAULT NULL,
 ADD COLUMN cancel_processed_time datetime DEFAULT NULL,
 ADD COLUMN legacy_once tinyint GENERATED ALWAYS AS (CASE WHEN class_date IS NULL THEN 1 ELSE NULL END) STORED,
 ADD COLUMN active_lesson tinyint GENERATED ALWAYS AS
 (CASE WHEN class_date IS NOT NULL AND del_flag='0' AND enrollment_status IN('0','1','待确认','报名成功','已确认') THEN 1 ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_single_lesson_seat (schedule_id,class_date,active_lesson),
 ADD KEY idx_enrollment_lesson_date (schedule_id,class_date),
 ADD UNIQUE KEY uk_legacy_enrollment_once (schedule_id,parent_id,student_name,legacy_once),
 DROP INDEX uk_edu_enrollment_once;
-- 班课/旧记录保留原唯一约束；单次日期名额只在有效报名状态占用。
CREATE TABLE edu_enrollment_event (
 event_id bigint NOT NULL AUTO_INCREMENT,
 enrollment_id bigint NOT NULL,
 action varchar(30) NOT NULL,
 actor_id bigint NOT NULL,
 actor_name varchar(64) NOT NULL,
 reason varchar(255) DEFAULT NULL,
 previous_status varchar(20) DEFAULT NULL,
 next_status varchar(20) DEFAULT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(event_id), KEY idx_enrollment_events(enrollment_id,event_id),
 CONSTRAINT fk_enrollment_event_record FOREIGN KEY(enrollment_id) REFERENCES edu_enrollment(enrollment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报名状态及取消申请操作历史（只追加）';
