-- 先在备份后的数据库执行，再部署后端。可重复执行，不修改课程归属或报名记录。
SET @add_campus = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='edu_course_schedule' AND COLUMN_NAME='campus_name')=0,
  'ALTER TABLE edu_course_schedule ADD COLUMN campus_name varchar(50) DEFAULT NULL COMMENT ''排课校区，教室可后续分配''',
  'SELECT 1');
PREPARE campus_stmt FROM @add_campus;
EXECUTE campus_stmt;
DEALLOCATE PREPARE campus_stmt;

-- Older schemas still require a room. Keep its foreign key, allow campus-only drafts.
SET @nullable_room = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='edu_course_schedule' AND COLUMN_NAME='classroom_id' AND IS_NULLABLE='NO'),
  'ALTER TABLE edu_course_schedule MODIFY COLUMN classroom_id bigint NULL COMMENT ''教室，排课时可暂不分配''',
  'SELECT 1');
PREPARE room_stmt FROM @nullable_room;
EXECUTE room_stmt;
DEALLOCATE PREPARE room_stmt;

UPDATE edu_course_schedule s JOIN edu_classroom c ON c.classroom_id=s.classroom_id
SET s.campus_name=c.campus_name
WHERE s.campus_name IS NULL OR TRIM(s.campus_name)='';
