package com.ruoyi.framework.web.service;

import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.constant.EduRoleConstants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.mapper.SysUserRoleMapper;
import com.ruoyi.system.service.ISysUserService;

/**
 * WeChat mini program login service.
 */
@Component
public class MiniAppLoginService
{
    private static final HttpClient WECHAT_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Value("${wechat.miniapp.appid:}")
    private String appid;

    @Value("${wechat.miniapp.secret:}")
    private String secret;

    @Value("${wechat.miniapp.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${wechat.miniapp.mock-phone:13800000000}")
    private String mockPhone;

    @Value("${wechat.miniapp.mock-admin-username:admin}")
    private String mockAdminUsername;

    @Value("${wechat.miniapp.mock-teacher-username:}")
    private String mockTeacherUsername;

    @Value("${wechat.miniapp.mock-teacher-phone:}")
    private String mockTeacherPhone;

    @Autowired
    private MiniAppDevelopmentSession developmentSession;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private TokenService tokenService;

    public String login(String loginCode, String phoneCode, String nickName, String devRole)
    {
        return login(loginCode, phoneCode, nickName, devRole, null);
    }

    public String login(String loginCode, String phoneCode, String nickName, String devRole, Long devTeacherId)
    {
        return login(loginCode, phoneCode, nickName, devRole, devTeacherId, null);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String login(String loginCode, String phoneCode, String nickName, String devRole, Long devTeacherId, Long devParentId)
    {
        if (devParentId != null)
        {
            if (!EduRoleConstants.PARENT.equalsIgnoreCase(StringUtils.trim(devRole)))
                throw new ServiceException("家长账号参数仅适用于家长开发登录");
            assertDevelopmentParentLogin();
        }
        if (devTeacherId != null && !EduRoleConstants.TEACHER.equalsIgnoreCase(StringUtils.trim(devRole)))
        {
            throw new ServiceException("教师账号参数仅适用于教师开发登录");
        }
        if (devTeacherId != null || EduRoleConstants.TEACHER.equalsIgnoreCase(StringUtils.trim(devRole)))
        {
            assertDevelopmentTeacherLogin();
        }
        if (mockEnabled && StringUtils.isNotBlank(devRole))
        {
            developmentSession.getVersion();
            return loginMockRole(devRole, nickName, devTeacherId, devParentId);
        }

        String phoneNumber;
        if (mockEnabled && developmentSession.isEnabled())
        {
            phoneNumber = mockPhone;
        }
        else
        {
            if (StringUtils.isBlank(appid) || StringUtils.isBlank(secret))
            {
                throw new ServiceException("Please configure wechat.miniapp.appid and secret");
            }
            if (StringUtils.isBlank(loginCode) || StringUtils.isBlank(phoneCode))
            {
                throw new ServiceException("WeChat login code and phone code are required");
            }
            getOpenid(loginCode);
            phoneNumber = getPhoneNumber(phoneCode);
        }

        // 微信已核验手机号；与验证码入口共用账号匹配，不能只查家长或自动提升角色。
        return loginVerifiedPhone(phoneNumber);
    }

    /** Called only after a phone challenge has been consumed; clients cannot choose their roles. */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String loginVerifiedPhone(String value)
    {
        String phone = MiniAppPhoneCodeService.phone(value);
        List<Long> ids = userMapper.selectUserIdsByPhone(phone);
        if (ids.size() > 1) throw new ServiceException("此手机号关联多个历史账号，请联系管理员核对后再登录");
        SysUser user;
        if (ids.isEmpty())
        {
            SysRole parent = getParentRole();
            if (!"0".equals(parent.getStatus()) || !"0".equals(parent.getDelFlag()))
                throw new ServiceException("家长注册暂未开放，请联系管理员");
            user = new SysUser();
            user.setUserName("mp_" + phone + "_" + IdUtils.fastSimpleUUID().substring(0, 8));
            user.setNickName("家长" + phone.substring(7));
            user.setPhonenumber(phone);
            user.setPassword(SecurityUtils.encryptPassword(IdUtils.fastSimpleUUID()));
            user.setStatus("0"); user.setCreateBy("miniapp-phone");
            user.setRoleIds(new Long[] { parent.getRoleId() });
            if (userService.insertUser(user) <= 0) throw new ServiceException("注册未完成，请稍后重试");
            user = userService.selectUserById(user.getUserId());
        }
        else user = userService.selectUserById(ids.get(0));
        if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag()))
            throw new ServiceException("账号已停用，请联系管理员");
        List<SysRole> activeRoles = roleMapper.selectRolePermissionByUserId(user.getUserId()).stream()
                .filter(r -> "0".equals(r.getStatus()) && "0".equals(r.getDelFlag())).toList();
        if (activeRoles.stream().noneMatch(r -> Set.of("parent", "teacher", "education", "admin", EduRoleConstants.BUSINESS_ADMIN).contains(r.getRoleKey())))
            throw new ServiceException("账号尚未分配小程序角色，请联系管理员");
        user.setRoles(activeRoles);
        return createLoginToken(user);
    }

    private String loginMockRole(String devRole, String nickName, Long devTeacherId, Long devParentId)
    {
        String role = StringUtils.defaultString(devRole).trim().toLowerCase();
        SysUser user;
        if (EduRoleConstants.ADMIN.equals(role))
        {
            // 支持配置为用户名或手机号
            user = userService.selectUserByUserName(mockAdminUsername);
            if (user == null)
            {
                user = userService.selectUserByPhonenumber(mockAdminUsername);
            }
            if (user == null)
            {
                throw new ServiceException("Mock admin user does not exist: " + mockAdminUsername);
            }
        }
        else if (EduRoleConstants.TEACHER.equals(role))
        {
            if(devTeacherId == null) throw new ServiceException("请先选择要登录的教师");
            user = userService.selectUserById(devTeacherId);
            if (!isEligibleTeacher(user))
            {
                throw new ServiceException("该教师不存在、已停用或不具备独立教师身份，请刷新教师列表");
            }
        }
        else if (EduRoleConstants.PARENT.equals(role))
        {
            if (devParentId == null)
                user = findOrCreateParentUser(mockPhone, nickName, "mock-openid-" + mockPhone);
            else {
                user = userService.selectUserById(devParentId);
                if (!isEligibleParent(user))
                    throw new ServiceException("该家长不存在、已停用或并非独立家长账号，请刷新列表");
            }
        }
        else
        {
            throw new ServiceException("Unsupported mock role: " + devRole);
        }

        if (UserConstants.USER_DISABLE.equals(user.getStatus()))
        {
            throw new ServiceException("Account is disabled");
        }

        return createLoginToken(user);
    }

    private void assertDevelopmentTeacherLogin()
    {
        developmentSession.getVersion();
    }

    public String developmentSessionVersion()
    {
        return developmentSession.getVersion();
    }

    public String loginDevelopmentSessionVersion()
    {
        return developmentSession.isEnabled() ? developmentSession.getVersion() : null;
    }

    public List<Map<String, Object>> developmentTeachers()
    {
        assertDevelopmentTeacherLogin();
        List<Map<String, Object>> options = new ArrayList<>();
        SysRole teacherRole = roleMapper.checkRoleKeyUnique(EduRoleConstants.TEACHER);
        if (teacherRole == null || !"0".equals(teacherRole.getStatus()) || !"0".equals(teacherRole.getDelFlag()))
        {
            return options;
        }
        SysUser query = new SysUser();
        query.setRoleId(teacherRole.getRoleId());
        // Reuse the existing teacher-role query used by timetable management.
        List<SysUser> users = new ArrayList<>(userMapper.selectAllocatedList(query));
        users.sort(Comparator.comparing(SysUser::getNickName, Comparator.nullsLast(String::compareTo))
                .thenComparing(SysUser::getUserId));
        for (SysUser candidate : users)
        {
            SysUser user = userService.selectUserById(candidate.getUserId());
            if (!isEligibleTeacher(user))
            {
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("userId", String.valueOf(user.getUserId()));
            option.put("name", StringUtils.defaultIfBlank(user.getNickName(), user.getUserName()));
            option.put("userName", user.getUserName());
            options.add(option);
        }
        return options;
    }

    public List<Map<String, Object>> developmentParents()
    {
        assertDevelopmentParentLogin();
        List<Map<String, Object>> options = new ArrayList<>();
        SysRole parentRole = roleMapper.checkRoleKeyUnique(EduRoleConstants.PARENT);
        if (parentRole == null || !"0".equals(parentRole.getStatus()) || !"0".equals(parentRole.getDelFlag())) return options;
        SysUser query = new SysUser();query.setRoleId(parentRole.getRoleId());
        List<SysUser> users = new ArrayList<>(userMapper.selectAllocatedList(query));
        users.sort(Comparator.comparing((SysUser u) -> !u.getUserName().startsWith("dev_referral_"))
                .thenComparing(SysUser::getUserId));
        for (SysUser candidate : users) {
            SysUser user = userService.selectUserById(candidate.getUserId());
            if (!isEligibleParent(user)) continue;
            Map<String,Object> option = new LinkedHashMap<>();
            option.put("userId",String.valueOf(user.getUserId()));
            option.put("name",StringUtils.defaultIfBlank(user.getNickName(),user.getUserName()));
            option.put("userName",user.getUserName());options.add(option);
        }
        return options;
    }

    private void assertDevelopmentParentLogin()
    {
        developmentSession.getVersion();
        List<String> profiles=java.util.Arrays.asList(environment.getActiveProfiles());
        if(!mockEnabled || (!profiles.contains("local") && !profiles.contains("test")) || profiles.contains("prod") || profiles.contains("production"))
            throw new ServiceException("家长账号选择仅在local/test模拟开发环境开放");
    }

    private boolean isEligibleParent(SysUser user)
    {
        if (user == null || user.isAdmin() || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) return false;
        if(user.getRoles()==null || user.getRoles().stream().anyMatch(r -> !EduRoleConstants.PARENT.equals(r.getRoleKey()))) return false;
        List<SysRole> roles = roleMapper.selectRolePermissionByUserId(user.getUserId()).stream()
                .filter(r -> "0".equals(r.getStatus()) && "0".equals(r.getDelFlag())).toList();
        // Never allow the password-free parent selector to issue a staff identity.
        return !roles.isEmpty() && roles.stream().allMatch(r -> EduRoleConstants.PARENT.equals(r.getRoleKey()));
    }

    private boolean isEligibleTeacher(SysUser user)
    {
        if (user == null || user.isAdmin() || !"0".equals(user.getStatus())
                || !"0".equals(user.getDelFlag()) || user.getRoles() == null)
        {
            return false;
        }
        for (SysRole role : user.getRoles())
        {
            // An administrator account must not enter through the teacher shortcut.
            if (role.isAdmin() || EduRoleConstants.ADMIN.equals(role.getRoleKey())
                    || EduRoleConstants.BUSINESS_ADMIN.equals(role.getRoleKey())
                    || "administrator".equals(role.getRoleKey()))
            {
                return false;
            }
        }
        // Check active role assignments directly so a deleted teacher role cannot grant login.
        return roleMapper.selectRolePermissionByUserId(user.getUserId()).stream()
                .anyMatch(role -> EduRoleConstants.TEACHER.equals(role.getRoleKey())
                        && UserConstants.ROLE_NORMAL.equals(role.getStatus())
                        && UserConstants.NORMAL.equals(role.getDelFlag()));
    }

    private String createLoginToken(SysUser user)
    {
        userService.updateLoginInfo(user.getUserId(), IpUtils.getIpAddr(), DateUtils.getNowDate());
        Set<String> permissions = permissionService.getMenuPermission(user);
        LoginUser loginUser = new LoginUser(user.getUserId(), user.getDeptId(), user, permissions);
        if (developmentSession.isEnabled())
        {
            return tokenService.createDevelopmentToken(loginUser);
        }
        return tokenService.createToken(loginUser);
    }

    private SysUser findOrCreateParentUser(String phoneNumber, String nickName, String openid)
    {
        SysRole parentRole = getParentRole();
        // 家长账号的user_name为学生姓名(导入数据)，登录统一按手机号字段匹配；
        // 同一手机号可能同时绑定教师/管理员账号，此处仅匹配含家长角色的账号
        SysUser user = userMapper.selectParentUserByPhone(phoneNumber, parentRole.getRoleId());
        if (user == null)
        {
            user = new SysUser();
            user.setUserName(phoneNumber);
            user.setNickName(StringUtils.defaultIfBlank(nickName, "MiniApp Parent"));
            user.setPhonenumber(phoneNumber);
            user.setPassword(SecurityUtils.encryptPassword(IdUtils.fastSimpleUUID()));
            user.setStatus(UserConstants.NORMAL);
            user.setCreateBy("miniapp");
            user.setRemark("Mini program register, openid=" + openid);
            user.setRoleIds(new Long[] { parentRole.getRoleId() });
            if (userService.insertUser(user) <= 0)
            {
                throw new ServiceException("Failed to create parent account");
            }
        }
        else
        {
            ensureParentRole(user, parentRole);
        }
        return userService.selectUserById(user.getUserId());
    }

    private void ensureParentRole(SysUser user, SysRole parentRole)
    {
        List<SysRole> roles = roleMapper.selectRolePermissionByUserId(user.getUserId());
        boolean hasParentRole = roles.stream().anyMatch(role -> EduRoleConstants.PARENT.equals(role.getRoleKey()));
        if (!hasParentRole && !user.isAdmin())
        {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getUserId());
            userRole.setRoleId(parentRole.getRoleId());
            userRoleMapper.batchUserRole(Collections.singletonList(userRole));
        }
    }

    private SysRole getParentRole()
    {
        SysRole parentRole = roleMapper.checkRoleKeyUnique(EduRoleConstants.PARENT);
        if (parentRole == null)
        {
            throw new ServiceException("Parent role does not exist. Please run edu_roles.sql first");
        }
        return parentRole;
    }

    private String getOpenid(String loginCode)
    {
        String params = "appid=" + encode(appid)
                + "&secret=" + encode(secret)
                + "&js_code=" + encode(loginCode)
                + "&grant_type=authorization_code";
        JSONObject json = requestJson("https://api.weixin.qq.com/sns/jscode2session", params);
        String openid = json.getString("openid");
        if (StringUtils.isBlank(openid))
        {
            throw new ServiceException("WeChat login failed: " + json.getString("errmsg"));
        }
        return openid;
    }

    private String getPhoneNumber(String phoneCode)
    {
        String accessToken = getAccessToken();
        String url = "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=" + encode(accessToken);
        JSONObject body = new JSONObject();
        body.put("code", phoneCode);
        JSONObject json = requestWechat(url, body.toJSONString());
        JSONObject phoneInfo = json.getJSONObject("phone_info");
        String phoneNumber = phoneInfo == null ? null : phoneInfo.getString("purePhoneNumber");
        if (StringUtils.isBlank(phoneNumber) && phoneInfo != null) phoneNumber = phoneInfo.getString("phoneNumber");
        if (StringUtils.isBlank(phoneNumber))
        {
            throw new ServiceException("Failed to get WeChat phone number: " + json.getString("errmsg"));
        }
        return phoneNumber;
    }

    private String getAccessToken()
    {
        String params = "grant_type=client_credential"
                + "&appid=" + encode(appid)
                + "&secret=" + encode(secret);
        JSONObject json = requestJson("https://api.weixin.qq.com/cgi-bin/token", params);
        String accessToken = json.getString("access_token");
        if (StringUtils.isBlank(accessToken))
        {
            throw new ServiceException("Failed to get WeChat access_token: " + json.getString("errmsg"));
        }
        return accessToken;
    }

    private JSONObject requestJson(String url, String params)
    {
        return requestWechat(url + "?" + params, null);
    }

    /** 通用HttpUtils会记录完整URL及响应；微信凭据和手机号响应不能写入日志。 */
    private JSONObject requestWechat(String url, String body)
    {
        try
        {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
            if (body != null) request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            var response = WECHAT_HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new ServiceException("微信服务暂不可用，请稍后重试");
            return parseWechatResponse(response.body());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("微信授权请求已中断，请重试");
        }
        catch (java.io.IOException e)
        {
            throw new ServiceException("无法连接微信服务，请稍后重试");
        }
    }

    private JSONObject parseWechatResponse(String response)
    {
        if (StringUtils.isBlank(response))
        {
            throw new ServiceException("WeChat API has no response");
        }
        JSONObject json = JSONObject.parseObject(response);
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0)
        {
            throw new ServiceException("微信授权失败（错误码 " + errcode + "），请重试或联系管理员");
        }
        return json;
    }

    private String encode(String value)
    {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }
}
