-- 课程批量定价只保留管理端：收回教务（education）角色的课程定价菜单与权限
-- 菜单：3044 课程定价 / 3045 课程定价查询 / 3046 课程定价修改
-- 影响：网页端左侧菜单不再显示「课程定价」，小程序教务端首页也不再出现该入口
--       （小程序 utils/access.js 按 system:coursePrice:list 过滤入口）
-- 注意：已登录的教务会话需退出重登，Redis 里缓存的 LoginUser.permissions 才会刷新

DELETE rm
FROM sys_role_menu rm
         JOIN sys_role r ON r.role_id = rm.role_id
WHERE r.role_key = 'education'
  AND rm.menu_id IN (3044, 3045, 3046);
