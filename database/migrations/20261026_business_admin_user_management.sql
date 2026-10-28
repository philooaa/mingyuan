-- Extend the existing business role to ordinary-user management only.
-- Backend guards still reserve administrator identities, roles and menu writes to userId=1.
-- Do not assign any users or change existing role keys/status/data scope.
START TRANSACTION;

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
WITH RECURSIVE user_management AS (
    SELECT menu_id FROM sys_menu
    WHERE perms LIKE 'system:user:%' OR component='system/user/index'
    UNION ALL
    SELECT m.menu_id FROM sys_menu m JOIN user_management p ON m.parent_id=p.menu_id
)
SELECT r.role_id,m.menu_id
FROM sys_role r JOIN sys_menu m ON m.menu_id IN (SELECT menu_id FROM user_management)
WHERE r.role_key='business_admin' AND r.status='0' AND r.del_flag='0' AND m.status='0'
  AND COALESCE(m.perms,'') NOT REGEXP '^system:(role|menu):';

COMMIT;
