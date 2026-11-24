-- Explicit leave-to-makeup links. Cancelled plans remain as immutable history.
CREATE TABLE IF NOT EXISTS edu_attendance_makeup (
 plan_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 sign_in_id BIGINT NOT NULL,
 source_lesson_id BIGINT NOT NULL,
 student_id BIGINT NOT NULL,
 target_lesson_id BIGINT NOT NULL,
 guest_id BIGINT NOT NULL,
 cancelled TINYINT NOT NULL DEFAULT 0,
 remark VARCHAR(500) NOT NULL DEFAULT '',
 operator_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 cancel_by BIGINT NULL,
 cancel_reason VARCHAR(500) NULL,
 cancel_time DATETIME NULL,
 active_sign_in_id BIGINT GENERATED ALWAYS AS (IF(cancelled=0,sign_in_id,NULL)) STORED,
 UNIQUE KEY uk_makeup_active_sign_in (active_sign_in_id),
 KEY idx_makeup_source (source_lesson_id,student_id),
 KEY idx_makeup_target (target_lesson_id,student_id),
 CONSTRAINT ck_makeup_cancelled CHECK (cancelled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
