-- 教师业务资料拆表（MySQL 8.0）。停止对应后端并备份后执行。
-- 仅创建 edu 业务表并复制旧资料，不新增、修改或删除 sys 表字段。
-- 可用于标准若依用户表或已有 teacher_subject / teacher_level 的数据库。
-- 已存在的新表资料优先，重复执行不会覆盖管理员后续修改。
CREATE TABLE IF NOT EXISTS edu_teacher_profile (
 user_id bigint NOT NULL COMMENT '教师账号ID，与sys_user一对一，不自增',
 teacher_subject varchar(20) DEFAULT NULL COMMENT '后台配置的授课学科',
 teacher_level varchar(20) DEFAULT NULL COMMENT 'elite精英/senior资深；未配置为空',
 create_by varchar(64) DEFAULT '' COMMENT '创建者',
 create_time datetime DEFAULT NULL COMMENT '创建时间',
 update_by varchar(64) DEFAULT '' COMMENT '更新者',
 update_time datetime DEFAULT NULL COMMENT '更新时间',
 PRIMARY KEY (user_id),
 CONSTRAINT fk_edu_teacher_profile_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
   ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师业务资料';

-- 动态判断旧字段，标准若依用户表没有这些列时不会引用不存在的字段。
-- 分别处理列，兼容只执行过部分旧迁移的环境；保留空值，不猜测学科或等级。
SET @edu_legacy_subject = (
 SELECT IF(COUNT(*)=1,'u.teacher_subject','NULL') FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='teacher_subject'
);
SET @edu_legacy_level = (
 SELECT IF(COUNT(*)=1,'u.teacher_level','NULL') FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='teacher_level'
);
SET @edu_copy_teacher_profiles = CONCAT(
 'INSERT INTO edu_teacher_profile (user_id,teacher_subject,teacher_level,create_by,create_time,update_by,update_time) ',
 'SELECT u.user_id,',@edu_legacy_subject,',',@edu_legacy_level,',u.create_by,u.create_time,u.update_by,u.update_time ',
 'FROM sys_user u WHERE (NULLIF(TRIM(',@edu_legacy_subject,'),'''') IS NOT NULL ',
 'OR NULLIF(TRIM(',@edu_legacy_level,'),'''') IS NOT NULL) ',
 'AND NOT EXISTS(SELECT 1 FROM edu_teacher_profile p WHERE p.user_id=u.user_id)'
);
PREPARE edu_copy_teacher_profiles_stmt FROM @edu_copy_teacher_profiles;
EXECUTE edu_copy_teacher_profiles_stmt;
DEALLOCATE PREPARE edu_copy_teacher_profiles_stmt;
SET @edu_legacy_subject=NULL, @edu_legacy_level=NULL, @edu_copy_teacher_profiles=NULL;
