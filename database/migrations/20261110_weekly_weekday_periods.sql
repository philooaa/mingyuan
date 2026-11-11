-- Complete weekly class days; preserve existing dictionary IDs and course data.
-- Repeatable. Evict Redis key sys_dict:edu_term_period after applying.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO sys_dict_data
    (dict_sort, dict_label, dict_value, dict_type, css_class, list_class,
     is_default, status, create_by, create_time, remark)
SELECT days.sort_order, days.day_name, days.day_name, 'edu_term_period', '',
       'default', 'N', '0', 'admin', NOW(), ''
FROM (
    SELECT 4 AS sort_order, '周一' AS day_name
    UNION ALL SELECT 5, '周二'
    UNION ALL SELECT 6, '周三'
    UNION ALL SELECT 7, '周四'
    UNION ALL SELECT 8, '周五'
) days
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data
    WHERE dict_type='edu_term_period'
      AND (dict_label=days.day_name OR dict_value=days.day_name)
);
UPDATE sys_dict_data
SET dict_sort=3+FIELD(dict_label,'周一','周二','周三','周四','周五','周六','周日')
WHERE dict_type='edu_term_period'
  AND dict_label IN ('周一','周二','周三','周四','周五','周六','周日');
COMMIT;
