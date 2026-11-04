-- 仅补充缺失的班型字典项，不改变已有字典及课程记录。
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, is_default, create_by, create_time)
SELECT x.sort_order, x.name, x.name, 'edu_class_type', '0', 'N', 'admin', NOW()
FROM (SELECT 7 sort_order, '卓越班' name UNION ALL SELECT 8, '巩固版' UNION ALL SELECT 9, '985班' UNION ALL SELECT 10, '211班' UNION ALL SELECT 11, '奥数班') x
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type='edu_class_type' AND (d.dict_value=x.name OR d.dict_label=x.name));
