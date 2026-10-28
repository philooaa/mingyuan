-- Reorder existing subjects only. Keep IDs, labels, values, enabled flags and all business rows unchanged.
-- Repeatable. After applying, evict only Redis key sys_dict:edu_subject and refresh browser/miniapp dictionaries.
SET NAMES utf8mb4;
START TRANSACTION;
UPDATE sys_dict_data
SET dict_sort = FIELD(dict_label, '语文','数学','英语','政治','历史','地理','物理','化学','生物')
WHERE dict_type = 'edu_subject'
  AND dict_label IN ('语文','数学','英语','政治','历史','地理','物理','化学','生物');
COMMIT;
