package com.ruoyi.framework.web.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.uuid.IdUtils;

/** Local mini-program sessions last only for this backend process. */
@Component
public class MiniAppDevelopmentSession
{
    private final String version = IdUtils.fastUUID();

    @Value("${wechat.miniapp.mock-enabled:false}")
    private boolean mockEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    /** Explicit local/test profiles are required; a mock flag never enables production shortcuts. */
    public boolean isEnabled()
    {
        var profiles = java.util.Arrays.asList(environment.getActiveProfiles());
        return mockEnabled && (profiles.contains("local") || profiles.contains("test"))
                && !profiles.contains("prod") && !profiles.contains("production");
    }

    public String getVersion()
    {
        if (!isEnabled())
        {
            throw new ServiceException("角色免密切换仅在本地开发环境开放");
        }
        return version;
    }

    public boolean matches(String candidate)
    {
        return isEnabled() && version.equals(candidate);
    }
}
