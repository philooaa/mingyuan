const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const database = `mingyuan_business_admin_test_${Date.now()}`;
const migration = path.resolve(__dirname, '../../database/migrations/20261026_business_admin.sql');
const args = ['-u', process.env.MYSQL_USER || 'root', '--default-character-set=utf8mb4', '-N', '-B'];
const sql = (input, useDatabase = true) => execFileSync(process.env.MYSQL_BIN || 'mysql',
  [...args, ...(useDatabase ? [database] : [])], {input, encoding:'utf8', stdio:['pipe','pipe','pipe']}).trim();
sql(`CREATE DATABASE ${database} CHARACTER SET utf8mb4`, false);
try {
  sql(`CREATE TABLE sys_role(role_id BIGINT AUTO_INCREMENT PRIMARY KEY,role_name VARCHAR(30),role_key VARCHAR(100),role_sort INT,
       data_scope CHAR(1),status CHAR(1),del_flag CHAR(1),create_by VARCHAR(64),create_time DATETIME,remark VARCHAR(500));
       CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,parent_id BIGINT,status CHAR(1),perms VARCHAR(100),component VARCHAR(255));
       CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id));
       CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT);
       INSERT INTO sys_role VALUES(1,'超级管理员','admin',1,'1','0','0','admin',NOW(),'original');
       INSERT INTO sys_menu VALUES(1,0,'0','',''),(2,1,'0','system:user:list','system/user/index'),
       (3,2,'0','',''),(4,1,'0','system:role:edit','system/role/index'),(5,1,'0','system:menu:edit','system/menu/index'),
       (6,0,'0','system:tuitionRefund:list','finance/refund/index'),(7,6,'0','system:tuitionRefund:execute',''),
       (8,0,'1','system:disabled:list','');`);
  const upgrade = fs.existsSync(migration) ? fs.readFileSync(migration,'utf8') : 'SELECT 1;';
  sql(upgrade); sql(upgrade);
  assert.equal(sql("SELECT COUNT(*) FROM sys_role WHERE role_key='business_admin'"),'1');
  assert.equal(sql("SELECT GROUP_CONCAT(rm.menu_id ORDER BY rm.menu_id) FROM sys_role_menu rm JOIN sys_role r USING(role_id) WHERE role_key='business_admin'"),'1,6,7');
  assert.equal(sql('SELECT COUNT(*) FROM sys_user_role'),'0');
  assert.equal(sql("SELECT remark FROM sys_role WHERE role_id=1"),'original');
  const userManagement = path.resolve(__dirname, '../../database/migrations/20261026_business_admin_user_management.sql');
  if (fs.existsSync(userManagement)) { sql(fs.readFileSync(userManagement,'utf8')); sql(fs.readFileSync(userManagement,'utf8')); }
  assert.equal(sql("SELECT GROUP_CONCAT(rm.menu_id ORDER BY rm.menu_id) FROM sys_role_menu rm JOIN sys_role r USING(role_id) WHERE role_key='business_admin'"),'1,2,3,6,7');
  assert.equal(sql('SELECT COUNT(*) FROM sys_user_role'),'0');
  console.log('PASS: base migration keeps privileged controls closed; follow-up enables ordinary-user management only; both repeat safely and grant no users automatically');
} finally { sql(`DROP DATABASE ${database}`,false); }
