-- 核对并备份后执行。只规范当前幼小课程年级及补充年级选项；保留历史报名、考勤和财务快照。
-- 只识别名称明确以“幼小”开头的课程，不把其他无法识别的年级自动归为幼小衔接。
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time,remark)
SELECT 0,'幼小衔接','g0','edu_grade','N','0','SYSTEM',NOW(),'幼小课程统一年级'
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data WHERE dict_type='edu_grade' AND (dict_label='幼小衔接' OR dict_value='g0'));

UPDATE edu_course_schedule
SET grade_name='幼小衔接'
WHERE grade_name IN ('幼小拼音班','未识别年级')
  AND course_class_name LIKE '幼小%';

COMMIT;
