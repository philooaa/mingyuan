-- 先备份并停止后端。在目标业务数据库中执行；只扩展原点名表，不更改历史签到或审核结果。
-- 可重复执行；已有字段类型不符则停止，不覆盖已有列。
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_sign_exception_20261019$$
CREATE PROCEDURE migrate_sign_exception_20261019()
BEGIN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_reason') THEN
    ALTER TABLE edu_class_sign_in ADD COLUMN review_reason VARCHAR(500) DEFAULT NULL COMMENT '最近一次课次审核异常原因';
  ELSEIF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_reason' AND data_type='varchar' AND character_maximum_length=500) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected review_reason definition; inspect before migration';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_history') THEN
    ALTER TABLE edu_class_sign_in ADD COLUMN review_history JSON DEFAULT NULL COMMENT '本行异常及出勤更正的追加式操作历史';
  ELSEIF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_class_sign_in' AND column_name='review_history' AND data_type='json') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected review_history definition; inspect before migration';
  END IF;
END$$
CALL migrate_sign_exception_20261019()$$
DROP PROCEDURE migrate_sign_exception_20261019$$
DELIMITER ;
