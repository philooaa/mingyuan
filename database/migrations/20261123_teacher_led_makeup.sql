-- Attendance-only sessions never create enrollment, payment or credit records.
SET @makeup_type_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_attendance_makeup' AND column_name='arrange_type')=0,'ALTER TABLE edu_attendance_makeup ADD COLUMN arrange_type VARCHAR(10) NOT NULL DEFAULT ''CLASS''','SELECT 1');
PREPARE makeup_type_stmt FROM @makeup_type_sql;
EXECUTE makeup_type_stmt;
DEALLOCATE PREPARE makeup_type_stmt;
CREATE TABLE IF NOT EXISTS edu_attendance_makeup_session (
 schedule_id BIGINT NOT NULL PRIMARY KEY,
 source_lesson_id BIGINT NOT NULL,
 teacher_id BIGINT NOT NULL,
 location VARCHAR(200) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_makeup_session_teacher (teacher_id),
 KEY idx_makeup_session_source (source_lesson_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
