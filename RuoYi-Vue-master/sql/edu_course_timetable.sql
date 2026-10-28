-- =================================================================
-- 名远教育·可视化课表 SQL（与已有 edu_course_schedule 表配套增强）
-- 执行顺序：先执行"字典 & 约束"，再执行"菜单权限"
-- =================================================================

-- =================================================================
-- 1. 索引：同一教室×同年×同学期×同期次×同时段的网格查询加速
-- =================================================================
-- 说明：自 2026-10-12 起允许同格存在多条排课（课表界面用红色感叹号提示冲突），
--       因此这里改为普通索引。若历史环境仍是 UNIQUE KEY，执行
--       database/migrations/20261012_allow_timetable_conflicts.sql 降级。
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'edu_course_schedule'
               AND INDEX_NAME IN ('uk_course_timetable_cell','idx_course_timetable_cell'));
SET @sql := IF(@idx = 0,
               'ALTER TABLE edu_course_schedule ADD INDEX idx_course_timetable_cell (classroom_id, course_year, term_name, period_name, time_slot)',
               'SELECT ''课表网格索引已存在，跳过'' AS notice');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =================================================================
-- 2. 字典：学期（暑假/秋季/春季）
-- =================================================================
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark, create_by, create_time, update_by, update_time)
SELECT '学期', 'edu_term', '0', '名远教务·可视化课表-学期', 'admin', NOW(), '', NULL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'edu_term');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 1, '暑假', '暑假', 'edu_term', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_term' AND dict_value='暑假');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 2, '秋季', '秋季', 'edu_term', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_term' AND dict_value='秋季');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 3, '春季', '春季', 'edu_term', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_term' AND dict_value='春季');

-- =================================================================
-- 3. 字典：期次（一期/二期/三期）
-- =================================================================
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark, create_by, create_time, update_by, update_time)
SELECT '期次', 'edu_term_period', '0', '名远教务·可视化课表-期次', 'admin', NOW(), '', NULL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'edu_term_period');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 1, '一期', '一期', 'edu_term_period', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_term_period' AND dict_value='一期');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 2, '二期', '二期', 'edu_term_period', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_term_period' AND dict_value='二期');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 3, '三期', '三期', 'edu_term_period', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_term_period' AND dict_value='三期');

-- =================================================================
-- 4. 字典：时段（6 个固定时段，对齐 Excel 总表）
-- =================================================================
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark, create_by, create_time, update_by, update_time)
SELECT '时段', 'edu_time_slot', '0', '名远教务·可视化课表-时段', 'admin', NOW(), '', NULL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'edu_time_slot');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 1, '8:00-10:00',   '8:00-10:00',   'edu_time_slot', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='8:00-10:00');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 2, '10:15-12:15', '10:15-12:15', 'edu_time_slot', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='10:15-12:15');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 3, '13:15-15:15', '13:15-15:15', 'edu_time_slot', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='13:15-15:15');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 4, '15:30-17:30', '15:30-17:30', 'edu_time_slot', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='15:30-17:30');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 5, '18:30-20:30', '18:30-20:30', 'edu_time_slot', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='18:30-20:30');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 6, '20:30-22:30', '20:30-22:30', 'edu_time_slot', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='20:30-22:30');

-- =================================================================
-- 5. 字典：班型（校优/提高/尖子/拓展/特长生班/班课）
-- =================================================================
INSERT INTO sys_dict_type (dict_name, dict_type, status, remark, create_by, create_time, update_by, update_time)
SELECT '班型', 'edu_class_type', '0', '名远教务·班型', 'admin', NOW(), '', NULL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'edu_class_type');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 1, '校优班',     '校优班',     'edu_class_type', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='校优班');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 2, '提高班',     '提高班',     'edu_class_type', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='提高班');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 3, '尖子班',     '尖子班',     'edu_class_type', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='尖子班');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 4, '拓展班',     '拓展班',     'edu_class_type', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='拓展班');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 5, '特长生班',   '特长生班',   'edu_class_type', '0', 'N', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='特长生班');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time, update_by, update_time)
SELECT 6, '班课',       '班课',       'edu_class_type', '0', 'Y', 'admin', NOW(), '', NULL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='班课');

-- =================================================================
-- 6. 菜单：可视化课表（parent=3 系统工具，order_num=2，紧接课程排课之后）
-- =================================================================
-- 先清理旧的（如存在）
DELETE FROM sys_role_menu WHERE menu_id IN (2050, 2051, 2052, 2053);
DELETE FROM sys_menu WHERE menu_id IN (2050, 2051, 2052, 2053);

-- 主菜单：可视化课表（C 型）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES (2050, '可视化课表', 3, 2, 'courseTimetable', 'system/timetable/index', '', '', 1, 0, 'C', '0', '0', 'system:courseschedule:list', 'education', 'admin', NOW(), '', NULL, '矩阵式课表：纵向教室 横向时段');

-- 按钮：查询
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES (2051, '课表查询', 2050, 1,  '', '', '', '', 1, 0, 'F', '0', '0', 'system:courseschedule:query', '#', 'admin', NOW(), '', NULL, '');

-- 按钮：新增
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES (2052, '课表新增', 2050, 2,  '', '', '', '', 1, 0, 'F', '0', '0', 'system:courseschedule:add',    '#', 'admin', NOW(), '', NULL, '');

-- 按钮：修改
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES (2053, '课表修改', 2050, 3,  '', '', '', '', 1, 0, 'F', '0', '0', 'system:courseschedule:edit',   '#', 'admin', NOW(), '', NULL, '');

-- 按钮：删除
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES (2054, '课表删除', 2050, 4,  '', '', '', '', 1, 0, 'F', '0', '0', 'system:courseschedule:remove', '#', 'admin', NOW(), '', NULL, '');

-- 把 5 个菜单的权限赋给 admin 角色（role_id=1）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2050) ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2051) ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2052) ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2053) ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 2054) ON DUPLICATE KEY UPDATE menu_id=menu_id;

-- 给教师角色（假设 teacher 角色已有 role_id，先查）也赋查询+新增+改+删权限
-- 如果你有自定义 teacher 角色 ID，把下面行里的 role_id 改成对应的值；默认 role_key='teacher' 的 role_id：
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id FROM sys_role r, sys_menu m
WHERE r.role_key = 'teacher' AND m.menu_id IN (2050, 2051, 2052, 2053, 2054);
