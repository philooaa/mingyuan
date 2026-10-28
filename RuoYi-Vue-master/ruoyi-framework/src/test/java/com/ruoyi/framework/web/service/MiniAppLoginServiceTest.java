package com.ruoyi.framework.web.service;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysUserService;
import static org.junit.jupiter.api.Assertions.*;

class MiniAppLoginServiceTest
{
    private MiniAppLoginService service;
    private List<SysUser> users;
    private SysRole teacherRole;
    private LoginUser issuedToken;
    private boolean developmentEnabled;
    private SysRole parentRole;
    private String[] profiles;

    @BeforeEach
    void setup() throws Exception
    {
        service = new MiniAppLoginService();
        teacherRole = new SysRole(7L);
        teacherRole.setRoleKey("teacher");
        teacherRole.setStatus("0");
        teacherRole.setDelFlag("0");
        parentRole=new SysRole(8L);parentRole.setRoleKey("parent");parentRole.setStatus("0");parentRole.setDelFlag("0");
        profiles=new String[]{"local"};
        set("environment",new org.springframework.core.env.StandardEnvironment(){@Override public String[] getActiveProfiles(){return profiles;}});
        users = List.of(user(10L, "teacher_a", "0", List.of(teacherRole)));
        issuedToken = null;
        developmentEnabled = true;
        set("developmentSession", new MiniAppDevelopmentSession() {
            @Override public boolean isEnabled() { return developmentEnabled; }
            @Override public String getVersion() {
                if (!developmentEnabled) throw new ServiceException("Development login disabled");
                return "test-session";
            }
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                proxy(HttpServletRequest.class, (method, args) -> switch (method) {
                    case "getHeader" -> null;
                    case "getRemoteAddr" -> "127.0.0.1";
                    default -> throw new AssertionError("Unexpected request call: " + method);
                })));
        set("mockEnabled", true);
        set("mockTeacherUsername", "");
        set("mockTeacherPhone", "");
        // Replace only database access, login metadata writes, permissions and Redis token storage.
        set("userService", proxy(ISysUserService.class, (method, args) -> switch (method) {
            case "selectUserByUserName" -> users.stream().filter(u -> u.getUserName().equals(args[0])).findFirst().orElse(null);
            case "selectUserByPhonenumber" -> users.stream().filter(u -> u.getPhonenumber().equals(args[0]))
                    .findFirst().map(u -> user(u.getUserId(), u.getUserName(), u.getStatus(),
                            u.getRoles().stream().limit(1).toList())).orElse(null);
            case "selectUserById" -> users.stream().filter(u -> u.getUserId().equals(args[0])).findFirst().orElse(null);
            case "updateLoginInfo" -> 1;
            case "insertUser" -> {
                SysUser created = (SysUser) args[0];
                assertArrayEquals(new Long[] { parentRole.getRoleId() }, created.getRoleIds());
                created.setUserId(44L); created.setDelFlag("0"); created.setRoles(List.of(parentRole));
                users = java.util.stream.Stream.concat(users.stream(), java.util.stream.Stream.of(created)).toList();
                yield 1;
            }
            default -> throw new AssertionError("Unexpected user service call: " + method);
        }));
        set("userMapper", proxy(SysUserMapper.class, (method, args) -> {
            if ("selectParentUserByPhone".equals(method)) return users.stream()
                    .filter(u -> u.getPhonenumber().equals(args[0]) && u.getRoles().stream().anyMatch(r -> "parent".equals(r.getRoleKey())))
                    .findFirst().orElse(null);
            if ("selectUserIdsByPhone".equals(method)) return users.stream()
                    .filter(u -> u.getPhonenumber().equals(args[0]) && "0".equals(u.getDelFlag())).map(SysUser::getUserId).toList();
            if (!"selectAllocatedList".equals(method)) throw new AssertionError(method);
            SysUser filter = (SysUser) args[0];
            return users.stream().filter(u -> u.getRoles().stream()
                    .anyMatch(r -> r.getRoleId().equals(filter.getRoleId()))).toList();
        }));
        set("roleMapper", proxy(SysRoleMapper.class, (method, args) -> switch (method) {
            case "checkRoleKeyUnique" -> "teacher".equals(args[0]) ? teacherRole : "parent".equals(args[0]) ? parentRole : null;
            case "selectRolePermissionByUserId" -> users.stream().filter(u -> u.getUserId().equals(args[0]))
                    .findFirst().orElseThrow().getRoles().stream().filter(r -> "0".equals(r.getDelFlag())).toList();
            default -> throw new AssertionError("Unexpected role mapper call: " + method);
        }));
        set("permissionService", new SysPermissionService() {
            @Override public Set<String> getMenuPermission(SysUser user) { return Set.of("teacher:test"); }
        });
        set("tokenService", new TokenService() {
            @Override public String createToken(LoginUser loginUser) {
                issuedToken = loginUser;
                return "test-token";
            }
            @Override public String createDevelopmentToken(LoginUser loginUser) {
                return createToken(loginUser);
            }
        });
    }

    @AfterEach
    void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void unspecifiedTeacherNeverSilentlySelectsAnotherTeacher() throws Exception
    {
        set("mockTeacherUsername", "teacher_a");
        assertThrows(ServiceException.class, () -> service.login("mock","mock","Teacher","teacher"));
        assertNull(issuedToken);
    }

    @Test
    void selectedTeacherRetainsAllActiveRoles() throws Exception
    {
        SysRole parent=new SysRole(8L); parent.setRoleKey("parent"); parent.setStatus("0"); parent.setDelFlag("0");
        users.get(0).setRoles(List.of(parent,teacherRole));
        assertEquals("test-token",login());
        assertEquals(List.of("parent","teacher"),issuedToken.getUser().getRoles().stream().map(SysRole::getRoleKey).toList());
    }

    @Test
    void missingSelectedTeacherDoesNotFallback() throws Exception
    {
        assertThrows(ServiceException.class, () -> service.login("mock","mock","Teacher","teacher",99L));
        assertNull(issuedToken);
    }

    @Test
    void explicitNonTeacherIsRejected() throws Exception
    {
        users = List.of(user(10L, "parent_a", "0", List.of()));
        set("mockTeacherUsername", "parent_a");
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void disabledOrDeletedExplicitTeacherIsRejected() throws Exception
    {
        set("mockTeacherUsername", "teacher_a");
        users.get(0).setStatus("1");
        assertThrows(ServiceException.class, this::login);
        users.get(0).setStatus("0");
        users.get(0).setDelFlag("2");
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void disabledOrDeletedTeacherRoleCannotIssueToken() throws Exception
    {
        set("mockTeacherUsername", "teacher_a");
        teacherRole.setStatus("1");
        assertThrows(ServiceException.class, this::login);
        teacherRole.setStatus("0");
        teacherRole.setDelFlag("2");
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void emptyTeacherDatabaseCannotIssueToken()
    {
        users = List.of();
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void disabledTeacherRoleCannotBeSelectedAutomatically()
    {
        teacherRole.setStatus("1");
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void devRoleCannotBypassRealWechatLoginWhenMockIsDisabled() throws Exception
    {
        set("mockEnabled", false);
        set("appid", "");
        set("secret", "");
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void selectedTeacherOverridesConfiguredFallback() throws Exception
    {
        users = List.of(users.get(0), user(20L, "teacher_b", "0", List.of(teacherRole)));
        set("mockTeacherUsername", "teacher_a");
        assertEquals("test-token", service.login("mock", "mock", "Teacher", "teacher", 20L));
        assertEquals(20L, issuedToken.getUserId());
    }

    @Test
    void administratorCannotEnterThroughTeacherLogin() throws Exception
    {
        SysRole administrator = new SysRole(8L);
        administrator.setRoleKey("administrator");
        administrator.setStatus("0");
        administrator.setDelFlag("0");
        users.get(0).setRoles(List.of(teacherRole, administrator));
        assertThrows(ServiceException.class, this::login);
        assertThrows(ServiceException.class, () -> service.login("mock", "mock", "Teacher", "teacher", 10L));
        assertTrue(service.developmentTeachers().isEmpty());
        assertNull(issuedToken);
    }

    @Test
    void teacherLoginAndTeacherListRequireLocalDevelopmentSession()
    {
        developmentEnabled = false;
        assertThrows(ServiceException.class, this::login);
        assertThrows(ServiceException.class, () -> service.login("mock", "mock", "Teacher", "teacher", 10L));
        assertThrows(ServiceException.class, service::developmentTeachers);
        assertNull(issuedToken);
    }

    @Test void businessAdminCannotUsePasswordFreeTeacherShortcut() throws Exception
    {
        SysRole business = new SysRole(9L);
        business.setRoleKey("business_admin"); business.setStatus("0"); business.setDelFlag("0");
        users.get(0).setRoles(List.of(teacherRole, business));
        assertTrue(service.developmentTeachers().isEmpty());
        assertThrows(ServiceException.class, this::login);
        assertNull(issuedToken);
    }

    @Test
    void selectedTeacherIdCannotBeUsedForAnotherRole()
    {
        assertThrows(ServiceException.class, () -> service.login("mock", "mock", "Teacher", "parent", 10L));
        assertNull(issuedToken);
    }

    @Test
    void teacherListContainsExistingEligibleTeachers()
    {
        users = List.of(users.get(0), user(20L, "disabled_teacher", "1", List.of(teacherRole)));
        assertEquals(1, service.developmentTeachers().size());
        assertEquals("10", service.developmentTeachers().get(0).get("userId"));
    }

    private String login() { return service.login("mock", "mock", "Test Teacher", "teacher", 10L); }

    @Test void selectedParentsIssueTheirOwnIdentitiesAndIndependentOptions() {
        users=List.of(user(30L,"dev_referral_a","0",List.of(parentRole)),user(31L,"dev_referral_b","0",List.of(parentRole)));
        assertEquals(2,service.developmentParents().size());
        assertEquals("test-token",service.login("mock","mock","Parent","parent",null,30L));assertEquals(30L,issuedToken.getUserId());
        assertEquals("test-token",service.login("mock","mock","Parent","parent",null,31L));assertEquals(31L,issuedToken.getUserId());
    }
    @Test void selectedParentCannotImpersonateStaffOrAnotherRole() {
        users=List.of(user(30L,"dev_referral_a","0",List.of(parentRole,teacherRole)));
        assertTrue(service.developmentParents().isEmpty());
        assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","parent",null,30L));
        assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","admin",null,30L));
        assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","teacher",10L,30L));
        assertNull(issuedToken);
    }
    @Test void disabledDeletedMissingParentsAndRolesCannotLogin() {
        users=List.of(user(30L,"dev_referral_a","1",List.of(parentRole)));
        assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","parent",null,30L));
        users.get(0).setStatus("0");users.get(0).setDelFlag("2");assertTrue(service.developmentParents().isEmpty());
        users.get(0).setDelFlag("0");parentRole.setStatus("1");assertTrue(service.developmentParents().isEmpty());
        assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","parent",null,30L));
        assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","parent",null,99L));
        assertNull(issuedToken);
    }
    @Test void parentSelectionNeverWorksInProductionEvenWithAccidentalMockFlag() {
        users=List.of(user(30L,"dev_referral_a","0",List.of(parentRole)));
        for(String[] value:List.of(new String[]{},new String[]{"prod"},new String[]{"local","production"})){
            profiles=value;assertThrows(ServiceException.class,service::developmentParents);assertThrows(ServiceException.class,()->service.login("mock","mock","Parent","parent",null,30L));
        }
        profiles=new String[]{"local"};developmentEnabled=false;assertThrows(ServiceException.class,service::developmentParents);
        assertNull(issuedToken);
    }

    @Test void legacyAdminShortcutRequiresAnEnabledDevelopmentSession() {
        developmentEnabled = false;
        assertThrows(ServiceException.class, () -> service.login("mock", "mock", "Admin", "admin"));
        assertNull(issuedToken);
    }

    @Test void developmentSessionRejectsProductionEvenWithMockFlag() throws Exception {
        MiniAppDevelopmentSession session = new MiniAppDevelopmentSession();
        var flag = MiniAppDevelopmentSession.class.getDeclaredField("mockEnabled");
        flag.setAccessible(true); flag.set(session, true);
        var env = MiniAppDevelopmentSession.class.getDeclaredField("environment");
        env.setAccessible(true); env.set(session, new org.springframework.core.env.StandardEnvironment() {
            @Override public String[] getActiveProfiles() { return profiles; }
        });
        assertTrue(session.isEnabled());
        for (String[] value : List.of(new String[] {}, new String[] { "prod" }, new String[] { "local", "production" })) {
            profiles = value;
            assertFalse(session.isEnabled());
            assertThrows(ServiceException.class, session::getVersion);
        }
    }

    @Test void verifiedPhoneKeepsMultipleExistingRolesWithoutAddingParent() {
        SysRole education = new SysRole(9L); education.setRoleKey("education"); education.setStatus("0"); education.setDelFlag("0");
        users.get(0).setRoles(List.of(teacherRole, education));
        assertEquals("test-token", service.loginVerifiedPhone("13800000010"));
        assertEquals(List.of("teacher", "education"), issuedToken.getUser().getRoles().stream().map(SysRole::getRoleKey).toList());
        assertEquals(1, users.size());
    }

    @Test void wechatEntryMatchesExistingTeacherInsteadOfCreatingAnotherParent() throws Exception {
        SysRole education = new SysRole(9L); education.setRoleKey("education"); education.setStatus("0"); education.setDelFlag("0");
        users.get(0).setRoles(List.of(teacherRole, education));
        set("mockPhone", "13800000010");
        assertEquals("test-token", service.login("wx-code", "phone-code", "微信用户", null));
        assertEquals(10L, issuedToken.getUserId());
        assertEquals(1, users.size());
        assertEquals(List.of("teacher", "education"), issuedToken.getUser().getRoles().stream().map(SysRole::getRoleKey).toList());
    }

    @Test void wechatEntryRejectsAmbiguousPhoneWithoutCreatingAccount() throws Exception {
        SysUser duplicate = user(20L, "other", "0", List.of(parentRole));
        duplicate.setPhonenumber("13800000010");
        users = List.of(users.get(0), duplicate);
        set("mockPhone", "13800000010");
        assertThrows(ServiceException.class, () -> service.login("wx-code", "phone-code", "微信用户", null));
        assertNull(issuedToken);
        assertEquals(2, users.size());
    }

    @Test void firstRegistrationCanOnlyReceiveExistingParentRole() {
        assertEquals("test-token", service.loginVerifiedPhone("13900000044"));
        assertEquals(List.of("parent"), issuedToken.getUser().getRoles().stream().map(SysRole::getRoleKey).toList());
        assertEquals("13900000044", issuedToken.getUser().getPhonenumber());
    }

    @Test void verifiedBusinessAdministratorKeepsItsExistingIdentity() {
        SysRole business = new SysRole(9L); business.setRoleKey("business_admin"); business.setStatus("0"); business.setDelFlag("0");
        users.get(0).setRoles(List.of(business));
        assertEquals("test-token", service.loginVerifiedPhone("13800000010"));
        assertEquals(10L, issuedToken.getUserId());
        assertEquals(List.of("business_admin"), issuedToken.getUser().getRoles().stream().map(SysRole::getRoleKey).toList());
    }

    @Test void wechatTransportDoesNotLogSecretOrToken() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"sensitive-test-token\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try(var out = exchange.getResponseBody()) { out.write(body); }
        });
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured); server.start();
        try {
            var request = MiniAppLoginService.class.getDeclaredMethod("requestJson", String.class, String.class);
            request.setAccessible(true);
            var response = (com.alibaba.fastjson2.JSONObject) request.invoke(service, "http://127.0.0.1:"+server.getAddress().getPort()+"/token", "secret=sensitive-test-secret");
            assertEquals("sensitive-test-token", response.getString("access_token"));
            assertTrue(captured.list.stream().noneMatch(e -> e.getFormattedMessage().contains("sensitive-test-")), "WeChat credentials and tokens must not enter logs");
        } finally { server.stop(0); logger.detachAppender(captured); captured.stop(); }
    }

    @Test void ambiguousPhoneNeverSelectsAnArbitraryPrivilegedAccount() {
        SysUser other = user(11L, "other", "0", List.of(parentRole)); other.setPhonenumber("13800000010");
        users = List.of(users.get(0), other);
        assertThrows(ServiceException.class, () -> service.loginVerifiedPhone("13800000010"));
        assertNull(issuedToken);
    }

    @Test void disabledAccountOrMissingActiveRoleDoesNotRegisterAReplacement() {
        users.get(0).setStatus("1");
        assertThrows(ServiceException.class, () -> service.loginVerifiedPhone("13800000010"));
        users.get(0).setStatus("0"); teacherRole.setStatus("1");
        assertThrows(ServiceException.class, () -> service.loginVerifiedPhone("13800000010"));
        assertEquals(1, users.size()); assertNull(issuedToken);
    }

    private SysUser user(long id, String name, String status, List<SysRole> roles)
    {
        SysUser user = new SysUser(id);
        user.setUserName(name);
        user.setPhonenumber("138000000" + id);
        user.setStatus(status);
        user.setDelFlag("0");
        user.setRoles(roles);
        return user;
    }

    private void set(String name, Object value) throws Exception
    {
        var field = MiniAppLoginService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private interface Call { Object invoke(String method, Object[] args); }

    private <T> T proxy(Class<T> type, Call call)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> call.invoke(method.getName(), args)));
    }
}
