package com.ruoyi.common.core.domain.model;

/**
 * WeChat mini program login parameters.
 */
public class MiniAppLoginBody
{
    private String loginCode;

    private String phoneCode;

    private String nickName;

    /**
     * Development role override: parent, teacher or admin.
     * Only works when wechat.miniapp.mock-enabled is true.
     */
    private String devRole;

    /** Selected teacher user ID; only accepted by local development login. */
    private Long devTeacherId;

    /** Selected parent user ID; only accepted by local development login. */
    private Long devParentId;

    public Long getDevTeacherId()
    {
        return devTeacherId;
    }

    public void setDevTeacherId(Long devTeacherId)
    {
        this.devTeacherId = devTeacherId;
    }

    public Long getDevParentId()
    {
        return devParentId;
    }

    public void setDevParentId(Long devParentId)
    {
        this.devParentId = devParentId;
    }

    public String getLoginCode()
    {
        return loginCode;
    }

    public void setLoginCode(String loginCode)
    {
        this.loginCode = loginCode;
    }

    public String getPhoneCode()
    {
        return phoneCode;
    }

    public void setPhoneCode(String phoneCode)
    {
        this.phoneCode = phoneCode;
    }

    public String getNickName()
    {
        return nickName;
    }

    public void setNickName(String nickName)
    {
        this.nickName = nickName;
    }

    public String getDevRole()
    {
        return devRole;
    }

    public void setDevRole(String devRole)
    {
        this.devRole = devRole;
    }
}
