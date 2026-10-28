package com.ruoyi.framework.web.service;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import com.ruoyi.system.service.impl.SysRoleServiceImpl;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

class BusinessAdminSecurityTest
{
    private SysRole businessRole;
    private SysUser actor;
    private SysUserServiceImpl users;
    private int writes;

    @BeforeEach void setup() throws Exception
    {
        businessRole = new SysRole(9L);
        businessRole.setRoleKey("business_admin");
        businessRole.setStatus("0");
        businessRole.setDelFlag("0");
        actor = new SysUser(20L);
        actor.setRoles(List.of(businessRole));
        login(actor);
        users = new SysUserServiceImpl();
        inject(users, "roleMapper", proxy(SysRoleMapper.class, (name, args) -> switch (name) {
            case "selectRolePermissionByUserId" -> Long.valueOf(30L).equals(args[0]) ? List.of(businessRole) : List.of();
            case "selectRoleById" -> businessRole;
            default -> throw new AssertionError(name);
        }));
        inject(users, "userMapper", proxy(SysUserMapper.class, (name, args) -> {
            writes++;
            return 1;
        }));
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void businessAdminPassesBusinessRoleChecksButIsNotSuperAdmin()
    {
        assertFalse(SecurityUtils.isAdmin());
        assertTrue(SecurityUtils.hasRole("admin"));
        assertTrue(new PermissionService().hasRole("admin"));
        businessRole.setStatus("1");
        assertFalse(SecurityUtils.hasRole("admin"));
        assertFalse(new PermissionService().hasRole("admin"));
    }

    @Test void businessAdminCanManageOrdinaryUsersButCannotGainRoleOrMenuControl() throws Exception
    {
        SysPermissionService permissions = new SysPermissionService();
        inject(permissions, "menuService", proxy(ISysMenuService.class, (name, args) ->
                Set.of("system:tuitionRefund:list", "system:user:add", "system:role:edit", "system:menu:edit", "*:*:*")));
        inject(permissions, "roleService", proxy(ISysRoleService.class, (name, args) -> Set.of("business_admin")));
        Set<String> result = permissions.getMenuPermission(actor);
        assertTrue(result.contains("system:tuitionRefund:list"));
        assertFalse(result.contains("*:*:*"));
        assertTrue(result.contains("system:user:add"));
        assertFalse(result.stream().anyMatch(p -> p.startsWith("system:role:") || p.startsWith("system:menu:")));
    }

    @Test void onlyOriginalAdminMayCreateOrPromoteBusinessAdmin()
    {
        SysUser candidate = new SysUser();
        candidate.setRoleIds(new Long[] {9L});
        assertThrows(ServiceException.class, () -> users.insertUser(candidate));
        assertEquals(0, writes);
        assertThrows(ServiceException.class, () -> users.insertUserAuth(40L, new Long[] {9L}));
    }

    @Test void onlyOriginalAdminMayEditDisableResetOrDeleteBusinessAdmin()
    {
        SysUser target = new SysUser(30L);
        assertThrows(ServiceException.class, () -> users.checkUserAllowed(target));
        assertThrows(ServiceException.class, () -> users.updateUser(target));
        assertThrows(ServiceException.class, () -> users.updateUserStatus(target));
        assertThrows(ServiceException.class, () -> users.resetPwd(target));
        assertThrows(ServiceException.class, () -> users.deleteUserById(30L));
        assertThrows(ServiceException.class, () -> users.insertUserAuth(30L, new Long[0]));
        assertEquals(0, writes);
    }

    @Test void originalAdminCanManageBusinessAdmin()
    {
        actor.setUserId(1L);
        login(actor);
        assertDoesNotThrow(() -> users.checkUserAllowed(new SysUser(30L)));
        assertEquals(1, users.updateUserStatus(new SysUser(30L)));
    }

    @Test void businessAdminCanEditDisableAndResetOrdinaryUser() {
        SysUser target = new SysUser(40L);
        assertDoesNotThrow(() -> users.checkUserAllowed(target));
        assertEquals(1, users.updateUserStatus(target));
        assertEquals(1, users.resetPwd(target));
    }

    @Test void businessAdminMayManageOtherTeachersTimetable() throws Exception
    {
        var controller = new com.ruoyi.system.controller.EduTimetableController();
        inject(controller, "scheduleService", proxy(com.ruoyi.system.service.IEduCourseScheduleService.class, (name, args) -> {
            if ("deleteEduCourseScheduleByScheduleIds".equals(name)) return 1;
            if ("selectEduCourseScheduleByScheduleId".equals(name)) {
                var schedule = new com.ruoyi.system.domain.EduCourseSchedule();
                schedule.setScheduleId(10L); schedule.setTeacherId(99L); return schedule;
            }
            throw new AssertionError(name);
        }));
        assertEquals(200, controller.remove(new Long[] {10L}).get("code"));
    }

    @Test void businessAdminMayReadOtherTeachersHomeworkStats() throws Exception
    {
        var homework = new com.ruoyi.system.domain.EduHomework();
        homework.setTeacherId(99L);
        var service = new com.ruoyi.system.service.impl.EduHomeworkServiceImpl();
        inject(service, "mapper", proxy(com.ruoyi.system.mapper.EduHomeworkMapper.class, (name, args) -> switch (name) {
            case "selectById" -> homework;
            // The existing SQL treats teacherId=1 as the cross-teacher query scope.
            case "selectSubmissionStats" -> Long.valueOf(1L).equals(args[1]) ? List.of(java.util.Map.of("submitted", 1)) : List.of();
            default -> throw new AssertionError(name);
        }));
        assertEquals(1, service.submissionStats(10L).size());
        businessRole.setRoleKey("teacher");
        assertThrows(ServiceException.class, () -> service.submissionStats(10L));
    }

    @Test void nonSuperAdminCannotUseRoleOrMenuWritesToEscalate()
    {
        SysRoleServiceImpl roles = new SysRoleServiceImpl();
        SysRole role = new SysRole(9L);
        assertThrows(ServiceException.class, () -> roles.insertRole(role));
        assertThrows(ServiceException.class, () -> roles.updateRole(role));
        assertThrows(ServiceException.class, () -> roles.updateRoleStatus(role));
        assertThrows(ServiceException.class, () -> roles.authDataScope(role));
        assertThrows(ServiceException.class, () -> roles.deleteRoleById(9L));
        assertThrows(ServiceException.class, () -> roles.insertAuthUsers(9L, new Long[] {40L}));
        assertThrows(ServiceException.class, () -> roles.deleteAuthUsers(9L, new Long[] {30L}));
        SysMenuServiceImpl menus = new SysMenuServiceImpl();
        assertThrows(ServiceException.class, () -> menus.insertMenu(new SysMenu()));
        assertThrows(ServiceException.class, () -> menus.updateMenu(new SysMenu()));
        assertThrows(ServiceException.class, () -> menus.deleteMenuById(1L));
    }

    private void login(SysUser user)
    {
        LoginUser identity = new LoginUser(user.getUserId(), null, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
    }

    private static void inject(Object object, String name, Object value) throws Exception
    {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private interface Call { Object invoke(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> call.invoke(method.getName(), args)));
    }
}
