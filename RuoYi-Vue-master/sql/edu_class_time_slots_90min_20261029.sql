-- 班课新增90分钟固定时段；保留原有时段。一对一仍由后端限定120分钟。
START TRANSACTION;
INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,status,is_default,create_by,create_time)
SELECT 0,'8:30-10:00','8:30-10:00','edu_time_slot','0','N','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='8:30-10:00');
INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,status,is_default,create_by,create_time)
SELECT 0,'10:30-12:00','10:30-12:00','edu_time_slot','0','N','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='10:30-12:00');
INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,status,is_default,create_by,create_time)
SELECT 0,'13:30-15:00','13:30-15:00','edu_time_slot','0','N','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='13:30-15:00');
INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,status,is_default,create_by,create_time)
SELECT 0,'15:30-17:00','15:30-17:00','edu_time_slot','0','N','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='15:30-17:00');
INSERT INTO sys_dict_data (dict_sort,dict_label,dict_value,dict_type,status,is_default,create_by,create_time)
SELECT 0,'18:30-20:00','18:30-20:00','edu_time_slot','0','N','admin',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_time_slot' AND dict_value='18:30-20:00');
-- 按实际开始时间排序，同开始时间再按结束时间排序。
UPDATE sys_dict_data SET dict_sort=TIME_TO_SEC(SUBSTRING_INDEX(dict_value,'-',1))/60*1440+TIME_TO_SEC(SUBSTRING_INDEX(dict_value,'-',-1))/60
WHERE dict_type='edu_time_slot' AND dict_value REGEXP '^[0-9]{1,2}:[0-9]{2}-[0-9]{1,2}:[0-9]{2}$';
COMMIT;
