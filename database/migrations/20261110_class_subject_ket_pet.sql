-- Shared subject options for class scheduling, editing and enrollment filters.
-- Repeatable. Evict Redis key sys_dict:edu_subject after applying.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO sys_dict_data
    (dict_sort, dict_label, dict_value, dict_type, css_class, list_class,
     is_default, status, create_by, create_time, remark)
SELECT 10, 'KET', 'ket', 'edu_subject', '', 'default', 'N', '0', 'admin', NOW(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_subject' AND (dict_label='KET' OR dict_value='ket'));
INSERT INTO sys_dict_data
    (dict_sort, dict_label, dict_value, dict_type, css_class, list_class,
     is_default, status, create_by, create_time, remark)
SELECT 11, 'PET', 'pet', 'edu_subject', '', 'default', 'N', '0', 'admin', NOW(), ''
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='edu_subject' AND (dict_label='PET' OR dict_value='pet'));
COMMIT;
