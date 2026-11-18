-- 未填写限购表示不限购。保留所有已有商品的限购值，不回写历史订单。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='edu_material' AND column_name='purchase_limit' AND is_nullable='NO'), 'ALTER TABLE edu_material MODIFY COLUMN purchase_limit INT NULL DEFAULT NULL', 'SELECT 1');
PREPARE material_limit_stmt FROM @ddl;
EXECUTE material_limit_stmt;
DEALLOCATE PREPARE material_limit_stmt;
