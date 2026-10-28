package com.ruoyi.framework.web.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.service.ISysUserService;

/**
 * 用户验证处理
 *
 * @author ruoyi
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService
{
    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    @Autowired
    private ISysUserService userService;
    
    @Autowired
    private SysPasswordService passwordService;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private SysRoleMapper roleMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException
    {
        // 网页端支持用户名或手机号；小程序登录由 MiniAppLoginService 独立处理。
        SysUser user = userService.selectUserByUserName(username);
        if (StringUtils.isNull(user) && StringUtils.isNotEmpty(username))
        {
            user = userService.selectUserByPhonenumber(username);
        }
        if (StringUtils.isNull(user))
        {
            log.info("登录用户：{} 不存在.", username);
            throw new ServiceException(MessageUtils.message("user.not.exists"));
        }
        else if (UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            log.info("登录用户：{} 已被删除.", username);
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        }
        else if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            log.info("登录用户：{} 已被停用.", username);
            throw new ServiceException(MessageUtils.message("user.blocked"));
        }

        passwordService.validate(user);

        if (!user.isAdmin())
        {
            // 手机号查询的 LIMIT 1 可能只带回一个角色，按用户重新读取全部有效角色。
            List<SysRole> roles = roleMapper.selectRolePermissionByUserId(user.getUserId()).stream()
                    .filter(role -> UserConstants.ROLE_NORMAL.equals(role.getStatus())
                            && UserConstants.NORMAL.equals(role.getDelFlag()))
                    .toList();
            if (roles.stream().noneMatch(role -> "education".equals(role.getRoleKey())
                    || com.ruoyi.common.constant.EduRoleConstants.BUSINESS_ADMIN.equals(role.getRoleKey())))
            {
                throw new ServiceException("仅教务、业务管理员和超级管理员可登录管理网页，教师和家长请使用小程序");
            }
            user.setRoles(roles);
        }

        return createLoginUser(user);
    }

    public UserDetails createLoginUser(SysUser user)
    {
        return new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
    }
}
