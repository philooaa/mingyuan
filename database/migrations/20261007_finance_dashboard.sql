-- 财务报告驾驶舱迁移：可重复执行。执行前先备份。
-- 1) 排课表增加课时单价
-- 2) 成本支出登记表
-- 3) 成本类型字典
-- 4) 财务报告菜单（与教务工具同级）
USE mingyuaneduminiapp;

DELIMITER $$
DROP PROCEDURE IF EXISTS fin_add_column$$
CREATE PROCEDURE fin_add_column(IN t varchar(64), IN c varchar(64), IN definition text)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=t AND column_name=c) THEN
  SET @fin_ddl=CONCAT('ALTER TABLE ',t,' ADD COLUMN ',c,' ',definition);
  PREPARE s FROM @fin_ddl; EXECUTE s; DEALLOCATE PREPARE s;
 END IF;
END$$
DELIMITER ;

CALL fin_add_column('edu_course_schedule','unit_price','decimal(10,2) DEFAULT 0 COMMENT ''课时单价（元/人·次）''');
DROP PROCEDURE fin_add_column;

CREATE TABLE IF NOT EXISTS edu_finance_expense(
  expense_id bigint NOT NULL AUTO_INCREMENT COMMENT '支出ID',
  expense_type varchar(30) NOT NULL COMMENT '支出类型（字典 edu_expense_type）',
  title varchar(100) NOT NULL COMMENT '摘要',
  amount decimal(10,2) NOT NULL COMMENT '金额（元）',
  detail varchar(500) NOT NULL COMMENT '明细',
  expense_date date NOT NULL COMMENT '支出日期',
  register_by varchar(64) DEFAULT '' COMMENT '登记人',
  status char(1) DEFAULT '0' COMMENT '状态（0正常 1停用）',
  del_flag char(1) DEFAULT '0' COMMENT '删除标志（0存在 2删除）',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (expense_id),
  KEY idx_fin_expense_date (expense_date,del_flag),
  KEY idx_fin_expense_type (expense_type),
  CONSTRAINT ck_fin_expense_money CHECK (amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成本支出登记表';

-- 成本类型字典（增量，不覆盖人工维护项）
INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,remark)
SELECT '成本类型','edu_expense_type','0','admin',NOW(),'财务成本支出类型'
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_type WHERE dict_type='edu_expense_type');
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,is_default,status,create_by,create_time)
SELECT defaults.sort_order,defaults.label,defaults.code,'edu_expense_type','N','0','admin',NOW()
FROM (SELECT 1 AS sort_order,'房租' AS label,'rent' AS code
      UNION ALL SELECT 2,'水电','utility'
      UNION ALL SELECT 3,'教材印刷','printing'
      UNION ALL SELECT 4,'市场推广','marketing'
      UNION ALL SELECT 5,'办公','office'
      UNION ALL SELECT 6,'其他','other') defaults
WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data existing WHERE existing.dict_type='edu_expense_type' AND existing.dict_value=defaults.code);

-- 菜单：一级目录"财务报告"（与教务工具同级），子菜单"财务驾驶舱""成本登记"及按钮权限。
-- 用固定 menu_id 段 3000-3010，重复执行时按主键幂等。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3000,'财务报告',0,5,'finance',NULL,'',1,0,'M','0','0','','money','admin',NOW(),'财务驾驶舱与成本登记'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3000);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3001,'财务驾驶舱',3000,1,'dashboard','system/finance/index','',1,0,'C','0','0','system:finance:list','chart','admin',NOW(),'收入/成本/利润驾驶舱'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3001);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3002,'成本登记',3000,2,'expense','system/expense/index','',1,0,'C','0','0','system:expense:list','form','admin',NOW(),'成本支出明细登记'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3002);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3003,'财务查询',3001,1,'#','','',1,0,'F','0','0','system:finance:query','#','admin',NOW(),''
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3003);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3004,'成本查询',3002,1,'#','','',1,0,'F','0','0','system:expense:query','#','admin',NOW(),''
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3004);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3005,'成本新增',3002,2,'#','','',1,0,'F','0','0','system:expense:add','#','admin',NOW(),''
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3005);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3006,'成本修改',3002,3,'#','','',1,0,'F','0','0','system:expense:edit','#','admin',NOW(),''
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3006);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3007,'成本删除',3002,4,'#','','',1,0,'F','0','0','system:expense:remove','#','admin',NOW(),''
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3007);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 3008,'成本导出',3002,5,'#','','',1,0,'F','0','0','system:expense:export','#','admin',NOW(),''
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=3008);

-- 角色授权：仅 admin（role_id=1）
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT 1,m.menu_id FROM sys_menu m WHERE m.menu_id BETWEEN 3000 AND 3008
  AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.menu_id);
