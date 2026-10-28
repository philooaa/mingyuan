-- 20261012 放开课表冲突：允许同一教室、同年、同学期、同期次、同时段存在多条排课
-- 背景：可视化课表改为「横轴=教室、纵轴=时段」，同格出现多条排课时由前端用红色感叹号提示冲突，
--       不再由数据库唯一约束直接拒绝。教师维度的时间冲突仍在业务层保留校验。
-- 幂等：重复执行安全（索引不存在时跳过）。
--
-- 执行：
--   mysql --default-character-set=utf8mb4 -u root -p mingyuaneduminiapp \
--         --execute="source database/migrations/20261012_allow_timetable_conflicts.sql"

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'edu_course_schedule'
               AND INDEX_NAME = 'uk_course_timetable_cell');

SET @sql := IF(@idx > 0,
               'ALTER TABLE edu_course_schedule DROP INDEX uk_course_timetable_cell',
               'SELECT ''uk_course_timetable_cell 不存在，跳过'' AS notice');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 保留一个普通索引，供按 教室+年份+学期+期次+时段 查询网格使用（非唯一，允许重复）
SET @idx2 := (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'edu_course_schedule'
                AND INDEX_NAME = 'idx_course_timetable_cell');

SET @sql2 := IF(@idx2 = 0,
                'ALTER TABLE edu_course_schedule ADD INDEX idx_course_timetable_cell (classroom_id, course_year, term_name, period_name, time_slot)',
                'SELECT ''idx_course_timetable_cell 已存在，跳过'' AS notice');

PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
