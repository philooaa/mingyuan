package com.ruoyi.framework.web.service;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.service.ISysUserService;
import static org.junit.jupiter.api.Assertions.*;

class WebLoginRoleTest
{
    private UserDetailsServiceImpl service;
    private SysUser user;
    private List<SysRole> assignedRoles;

    @BeforeEach
    void setup() throws Exception
    {
        service = new UserDetailsServiceImpl();
        user = new SysUser(20L);
        user.setUserName("staff");
        user.setPhonenumber("13800000020");
        user.setStatus("0");
        user.setDelFlag("0");
        assignedRoles = List.of(role("teacher"));
        user.setRoles(assignedRoles);
        // Only database access, Redis-backed password validation and menu loading are replaced.
        set("userService", Proxy.newProxyInstance(ISysUserService.class.getClassLoader(),
                new Class<?>[] { ISysUserService.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "selectUserByUserName" -> user.getUserName().equals(args[0]) ? user : null;
                    case "selectUserByPhonenumber" -> user.getPhonenumber().equals(args[0]) ? user : null;
                    default -> throw new AssertionError(method.getName());
                }));
        set("passwordService", new SysPasswordService() {
            @Override public void validate(SysUser value) { }
        });
        set("permissionService", new SysPermissionService() {
            @Override public Set<String> getMenuPermission(SysUser value) { return Set.of(); }
        });
        set("roleMapper", Proxy.newProxyInstance(SysRoleMapper.class.getClassLoader(),
                new Class<?>[] { SysRoleMapper.class }, (proxy, method, args) -> {
                    if (!"selectRolePermissionByUserId".equals(method.getName()))
                        throw new AssertionError(method.getName());
                    assertEquals(user.getUserId(), args[0]);
                    return assignedRoles;
                }));
    }

    @Test
    void businessAdministratorMayLoginWithOtherIdentities()
    {
        assignedRoles = List.of(role("business_admin"), role("teacher"), role("parent"));
        assertEquals(20L, ((LoginUser) service.loadUserByUsername("13800000020")).getUserId());
        assignedRoles.get(0).setStatus("1");
        assertThrows(ServiceException.class, () -> service.loadUserByUsername("staff"));
    }

    @Test
    void teacherAndParentWithoutEducationCannotLogin()
    {
        for (List<SysRole> roles : List.of(List.of(role("teacher")), List.of(role("parent")),
                List.of(role("parent"), role("teacher")), List.<SysRole>of(), List.of(role("admin"))))
        {
            assignedRoles = roles;
            user.setRoles(roles);
            assertThrows(ServiceException.class, () -> service.loadUserByUsername("staff"));
        }
    }

    @Test
    void educationMayLoginWithOtherRolesByUsernameOrPhone() throws Exception
    {
        assignedRoles = List.of(role("parent"), role("teacher"), role("education"));
        for (String login : List.of("staff", "13800000020"))
        {
            // Mirrors LIMIT 1 in the phone lookup: the first role alone is insufficient.
            user.setRoles(List.of(role("parent")));
            LoginUser result = (LoginUser) service.loadUserByUsername(login);
            assertEquals(20L, result.getUserId());
            assertEquals(List.of("parent", "teacher", "education"), result.getUser().getRoles()
                    .stream().map(SysRole::getRoleKey).toList());
        }
    }

    @Test
    void disabledOrDeletedEducationCannotGrantLogin()
    {
        SysRole education = role("education");
        assignedRoles = List.of(role("teacher"), education);
        user.setRoles(assignedRoles);
        education.setStatus("1");
        assertThrows(ServiceException.class, () -> service.loadUserByUsername("staff"));
        education.setStatus("0");
        education.setDelFlag("2");
        assertThrows(ServiceException.class, () -> service.loadUserByUsername("staff"));
    }

    @Test
    void builtinSuperAdminMayLoginButAdminNameCannotBypass()
    {
        user.setUserId(1L);
        assertEquals(1L, ((LoginUser) service.loadUserByUsername("staff")).getUserId());
        user.setUserId(20L);
        user.setUserName("admin");
        assertThrows(ServiceException.class, () -> service.loadUserByUsername("admin"));
    }

    @Test
    void educationDoesNotBypassWrongPassword() throws Exception
    {
        assignedRoles = List.of(role("education"));
        user.setRoles(assignedRoles);
        set("passwordService", new SysPasswordService() {
            @Override public void validate(SysUser value) { throw new ServiceException("invalid password"); }
        });
        assertThrows(ServiceException.class, () -> service.loadUserByUsername("staff"));
    }

    private SysRole role(String key)
    {
        SysRole role = new SysRole(10L);
        role.setRoleKey(key);
        role.setStatus("0");
        role.setDelFlag("0");
        return role;
    }

    private void set(String name, Object value) throws Exception
    {
        var field = UserDetailsServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
