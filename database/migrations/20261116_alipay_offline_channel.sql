-- Add the Alipay offline channel while preserving the existing check expression and all payment rows.
-- Review the actual production schema and back up the database before applying.
DELIMITER $$
DROP PROCEDURE IF EXISTS add_alipay_payment_channel$$
CREATE PROCEDURE add_alipay_payment_channel()
BEGIN
  DECLARE clause_text LONGTEXT;
  DECLARE changed_text LONGTEXT;
  SELECT cc.CHECK_CLAUSE INTO clause_text FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='edu_tuition_payment' AND tc.CONSTRAINT_NAME='ck_tp_state' AND tc.CONSTRAINT_TYPE='CHECK';
  IF clause_text IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing edu_tuition_payment ck_tp_state; inspect schema before migration';
  END IF;
  SET clause_text=REPLACE(clause_text, CONCAT(CHAR(92),CHAR(39)), CHAR(39));
  IF LOCATE('ALIPAY_OFFLINE',clause_text)=0 THEN
    SET changed_text=REPLACE(clause_text,CONCAT(CHAR(39),'WECHAT_OFFLINE',CHAR(39)),CONCAT(CHAR(39),'WECHAT_OFFLINE',CHAR(39),',',CHAR(39),'ALIPAY_OFFLINE',CHAR(39)));
    IF changed_text=clause_text THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected ck_tp_state definition; inspect schema before migration';
    END IF;
    SET @alipay_ddl=CONCAT('ALTER TABLE edu_tuition_payment DROP CHECK ck_tp_state, ADD CONSTRAINT ck_tp_state CHECK ',changed_text);
    PREPARE alipay_stmt FROM @alipay_ddl;
    EXECUTE alipay_stmt;
    DEALLOCATE PREPARE alipay_stmt;
  END IF;
END$$
CALL add_alipay_payment_channel()$$
DROP PROCEDURE add_alipay_payment_channel$$
DELIMITER ;