-- Stable lesson identities and roster-only membership. No finance records are created or changed.
CREATE TABLE IF NOT EXISTS edu_attendance_lesson (
 lesson_id BIGINT PRIMARY KEY AUTO_INCREMENT,
 schedule_id BIGINT NOT NULL,
 lesson_no INT NOT NULL,
 original_date DATE NOT NULL,
 class_date DATE NULL,
 course_year INT NULL,
 term_name VARCHAR(64) NULL,
 teacher_id BIGINT NULL,
 course_name VARCHAR(200) NULL,
 start_time TIME NULL,
 end_time TIME NULL,
 cancelled TINYINT NOT NULL DEFAULT 0,
 locked TINYINT NOT NULL DEFAULT 0,
 roster_snapshot LONGTEXT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_attendance_original(schedule_id,original_date),
 UNIQUE KEY uq_attendance_lecture(schedule_id,lesson_no),
 KEY idx_attendance_actual(schedule_id,class_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_attendance_membership (
 membership_id BIGINT PRIMARY KEY AUTO_INCREMENT,
 schedule_id BIGINT NOT NULL,
 student_id BIGINT NOT NULL,
 enrollment_id BIGINT NULL,
 course_year INT NULL,
 term_name VARCHAR(64) NULL,
 start_lesson INT NOT NULL,
 end_lesson INT NULL,
 source VARCHAR(24) NOT NULL,
 payment_status VARCHAR(24) NOT NULL,
 reason VARCHAR(255) NOT NULL,
 operator_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NULL,
 UNIQUE KEY uq_attendance_member(schedule_id,student_id),
 KEY idx_attendance_enrollment(enrollment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_attendance_membership_history (
 history_id BIGINT PRIMARY KEY AUTO_INCREMENT,
 membership_id BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL,
 before_snapshot LONGTEXT NULL,
 after_snapshot LONGTEXT NOT NULL,
 operator_id BIGINT NOT NULL,
 reason VARCHAR(255) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotently add stable profile identity for roster-only attendance rows.
SET @attendance_student_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='student_id'), 'SELECT 1', 'ALTER TABLE edu_class_sign_in ADD COLUMN student_id BIGINT NULL');
PREPARE attendance_student_stmt FROM @attendance_student_ddl;
EXECUTE attendance_student_stmt;
DEALLOCATE PREPARE attendance_student_stmt;
