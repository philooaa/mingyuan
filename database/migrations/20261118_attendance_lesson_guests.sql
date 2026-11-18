-- Temporary pupils belong to one stable lecture, independent of enrollment and tuition.
CREATE TABLE IF NOT EXISTS edu_attendance_lesson_guest (
 guest_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 lesson_id BIGINT NOT NULL,
 student_id BIGINT NOT NULL,
 source VARCHAR(16) NOT NULL,
 source_lesson_id BIGINT NULL,
 operator_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_guest_target (lesson_id,student_id),
 UNIQUE KEY uk_guest_origin (source_lesson_id,student_id),
 CONSTRAINT ck_guest_source CHECK ((source='TRIAL' AND source_lesson_id IS NULL) OR (source='TRANSFER' AND source_lesson_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
