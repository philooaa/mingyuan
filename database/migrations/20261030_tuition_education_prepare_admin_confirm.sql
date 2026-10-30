-- 教务：第一步核价、第二步登记；管理员及业务管理员：第三步确认与关闭。
-- 保留管理员 collect 菜单权限供关闭付款接口使用，登记操作另有服务端角色校验。
START TRANSACTION;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
ON m.perms IN ('system:tuition:list','system:tuition:query','system:tuition:price','system:tuition:collect')
WHERE r.role_key='education' AND r.status='0' AND r.del_flag='0';
DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE r.role_key='education' AND m.perms='system:tuition:confirm';
DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE r.role_key='business_admin' AND m.perms='system:tuition:price';
COMMIT;
