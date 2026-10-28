-- ============================================================
-- 2026-10-12 教室信息精简
--   1) edu_classroom 去掉 classroom_size（教室规格）与 capacity（建议容量）
--   2) 教室最终为：万达校区 12 间 + 百家湖校区 5 间（共 17 间）
--   3) 「未分配」占位教室（缺教室、待重排-百家湖…）下的课程解绑教室后删除占位教室
-- 说明：报名名额改为「不限」，课程报名不再受教室容量限制。
--       财务审计保护禁止删除报名历史，故课程仅解绑教室（classroom_id 置空），不删除。
-- ============================================================

-- 1) 占位教室下的课程解绑教室（避免外键阻塞删除）
UPDATE edu_course_schedule
SET classroom_id = NULL, update_by = 'admin', update_time = NOW()
WHERE classroom_id IN (
    SELECT classroom_id FROM edu_classroom WHERE campus_name = '未分配'
);

-- 2) 删除「未分配」占位教室
DELETE FROM edu_classroom WHERE campus_name = '未分配';

-- 3) 去掉规格与容量两列
ALTER TABLE edu_classroom DROP COLUMN classroom_size;
ALTER TABLE edu_classroom DROP COLUMN capacity;
