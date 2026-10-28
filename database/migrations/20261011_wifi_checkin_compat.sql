-- 团队 WiFi 上下班打卡结构补齐；先备份、停后端，再在目标业务库执行。
-- 不导入白名单或打卡数据；旧课次打卡有记录时停止，不能猜测为上班卡。
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_wifi_checkin_20261011$$
CREATE PROCEDURE migrate_wifi_checkin_20261011()
BEGIN
  DECLARE table_exists INT DEFAULT 0;
  DECLARE column_exists INT DEFAULT 0;
  DECLARE index_exists INT DEFAULT 0;
  SELECT COUNT(*) INTO table_exists FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='edu_teacher_check_in';
  IF table_exists > 0 THEN
    SELECT COUNT(*) INTO column_exists FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='edu_teacher_check_in' AND column_name='check_type';
    IF column_exists=0 THEN
      SET @wifi_legacy_count=0;
      SET @wifi_sql='SELECT COUNT(*) INTO @wifi_legacy_count FROM edu_teacher_check_in';
      PREPARE wifi_stmt FROM @wifi_sql; EXECUTE wifi_stmt; DEALLOCATE PREPARE wifi_stmt;
      IF @wifi_legacy_count>0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Legacy schedule check-ins require reviewed type mapping; no records changed';
      END IF;
    END IF;
  END IF;
  CREATE TABLE IF NOT EXISTS edu_wifi_config (
    wifi_id BIGINT NOT NULL AUTO_INCREMENT,
    campus_name VARCHAR(50) NOT NULL, ssid VARCHAR(64) NOT NULL,
    bssid VARCHAR(64) DEFAULT NULL, status CHAR(1) DEFAULT '0',
    create_by VARCHAR(64), create_time DATETIME,
    update_by VARCHAR(64), update_time DATETIME, remark VARCHAR(500),
    PRIMARY KEY(wifi_id), UNIQUE KEY uk_campus_ssid(campus_name,ssid)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校区WiFi白名单';
  CREATE TABLE IF NOT EXISTS edu_teacher_check_in (
    check_in_id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT DEFAULT NULL COMMENT '兼容历史课次引用；上下班卡不绑定排课',
    class_date DATE NOT NULL,
    check_type VARCHAR(3) NOT NULL COMMENT 'in上班 out下班',
    teacher_id BIGINT NOT NULL, teacher_name VARCHAR(30) DEFAULT NULL,
    campus_name VARCHAR(50) DEFAULT NULL, classroom_name VARCHAR(50) DEFAULT NULL,
    ssid VARCHAR(64) DEFAULT NULL, bssid VARCHAR(64) DEFAULT NULL,
    check_in_time DATETIME NOT NULL,
    create_by VARCHAR(64), create_time DATETIME, remark VARCHAR(500),
    PRIMARY KEY(check_in_id), UNIQUE KEY uk_teacher_date_type(teacher_id,class_date,check_type)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师WiFi上下班打卡记录';
  IF table_exists>0 AND column_exists=0 THEN
    ALTER TABLE edu_teacher_check_in ADD COLUMN check_type VARCHAR(3) NOT NULL COMMENT 'in上班 out下班' AFTER class_date;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='edu_teacher_check_in' AND column_name='schedule_id' AND is_nullable='NO') THEN
    ALTER TABLE edu_teacher_check_in MODIFY COLUMN schedule_id BIGINT DEFAULT NULL COMMENT '兼容历史课次引用；上下班卡不绑定排课';
  END IF;
  SELECT COUNT(*) INTO index_exists FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='edu_teacher_check_in' AND index_name='uk_teacher_date_type';
  IF index_exists=0 THEN
    ALTER TABLE edu_teacher_check_in ADD UNIQUE KEY uk_teacher_date_type(teacher_id,class_date,check_type);
  ELSEIF COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='edu_teacher_check_in' AND index_name='uk_teacher_date_type' AND non_unique=0),'')<>'teacher_id,class_date,check_type' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected WiFi check-in index; inspect before migration';
  END IF;
END$$
CALL migrate_wifi_checkin_20261011()$$
DROP PROCEDURE migrate_wifi_checkin_20261011$$
DELIMITER ;
