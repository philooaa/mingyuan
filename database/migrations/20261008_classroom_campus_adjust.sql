-- 教室数据订正（2026-10-08）
-- 需求：万达补到12间（新增万达24-7）；百家湖保留 2-1~2-5 共5间；
--       被删教室(2-6/2-7/A/B/C/D)上的课改挂到"待重排"占位教室，后续手动重排。
-- 注意：edu_course_schedule 有 uk_course_timetable_cell 唯一约束
--       (classroom_id, course_year, term_name, period_name, time_slot, del_flag)，
--       因此每间被删教室使用各自独立的占位教室，避免时段冲突。

START TRANSACTION;

-- 1. 万达新增 24-7
INSERT INTO edu_classroom (classroom_code, campus_name, classroom_name, capacity, status, create_by, create_time, remark)
VALUES ('ROOM024', '万达', '万达24-7', 14, '0', 'admin', NOW(), '2026-10-08 教室订正新增');

-- 2. 为百家湖 6 间待撤教室各建一个"待重排"占位（校区=未分配）
INSERT INTO edu_classroom (classroom_code, campus_name, classroom_name, capacity, status, create_by, create_time, remark)
VALUES
('ROOM025', '未分配', '待重排-百家湖2-6', NULL, '0', 'admin', NOW(), '原百家湖2-6撤并，课待重排'),
('ROOM026', '未分配', '待重排-百家湖2-7', NULL, '0', 'admin', NOW(), '原百家湖2-7撤并，课待重排'),
('ROOM027', '未分配', '待重排-百家湖A',   NULL, '0', 'admin', NOW(), '原百家湖A撤并，课待重排'),
('ROOM028', '未分配', '待重排-百家湖B',   NULL, '0', 'admin', NOW(), '原百家湖B撤并，课待重排'),
('ROOM029', '未分配', '待重排-百家湖C',   NULL, '0', 'admin', NOW(), '原百家湖C撤并，课待重排'),
('ROOM030', '未分配', '待重排-百家湖D',   NULL, '0', 'admin', NOW(), '原百家湖D撤并，课待重排');

-- 3. 课表改挂到对应占位教室（按编号定位，避免依赖自增ID）
UPDATE edu_course_schedule s
  JOIN edu_classroom p ON p.classroom_code = 'ROOM025' SET s.classroom_id = p.classroom_id WHERE s.classroom_id = 17; -- 2-6
UPDATE edu_course_schedule s
  JOIN edu_classroom p ON p.classroom_code = 'ROOM026' SET s.classroom_id = p.classroom_id WHERE s.classroom_id = 18; -- 2-7
UPDATE edu_course_schedule s
  JOIN edu_classroom p ON p.classroom_code = 'ROOM027' SET s.classroom_id = p.classroom_id WHERE s.classroom_id = 19; -- A
UPDATE edu_course_schedule s
  JOIN edu_classroom p ON p.classroom_code = 'ROOM028' SET s.classroom_id = p.classroom_id WHERE s.classroom_id = 22; -- B
UPDATE edu_course_schedule s
  JOIN edu_classroom p ON p.classroom_code = 'ROOM029' SET s.classroom_id = p.classroom_id WHERE s.classroom_id = 20; -- C
UPDATE edu_course_schedule s
  JOIN edu_classroom p ON p.classroom_code = 'ROOM030' SET s.classroom_id = p.classroom_id WHERE s.classroom_id = 21; -- D

-- 4. 删除撤并教室
DELETE FROM edu_classroom WHERE classroom_id IN (17, 18, 19, 20, 21, 22);

COMMIT;
