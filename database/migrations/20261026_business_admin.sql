-- New privileged business role. Never assign it to any existing user automatically.
-- Run with the matching backend: only original admin may grant or maintain this role.
START TRANSACTION;

INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark)
SELECT '业务管理员','business_admin',4,'1','0','0','admin',NOW(),
       '业务管理权限与管理员一致，管理员账号及角色菜单维护仅限原始admin'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key='business_admin');

SET @business_admin_role_id = (SELECT role_id FROM sys_role WHERE role_key='business_admin');

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
WITH RECURSIVE account_control AS (
    SELECT menu_id FROM sys_menu
    WHERE perms REGEXP '^system:(user|role|menu):'
       OR component IN ('system/user/index','system/role/index','system/menu/index')
    UNION ALL
    SELECT m.menu_id FROM sys_menu m JOIN account_control p ON m.parent_id=p.menu_id
)
SELECT @business_admin_role_id,m.menu_id FROM sys_menu m
WHERE m.status='0' AND m.menu_id NOT IN (SELECT menu_id FROM account_control);

COMMIT;
