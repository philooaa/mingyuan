package com.ruoyi.web.controller.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.MiniAppLoginBody;
import com.ruoyi.framework.web.service.MiniAppLoginService;

/**
 * WeChat mini program login controller.
 */
@RestController
public class MiniAppLoginController
{
    @Autowired
    private MiniAppLoginService miniAppLoginService;
    @Autowired private com.ruoyi.framework.web.service.MiniAppPhoneCodeService phoneCodes;

    public record PhoneCodeRequest(String phone) {}
    public record PhoneLoginRequest(String phone, String code) {}

    @Anonymous
    @GetMapping("/miniapp/auth/config")
    public AjaxResult phoneConfig() { return AjaxResult.success(phoneCodes.config()); }

    @Anonymous
    @PostMapping("/miniapp/auth/code")
    public AjaxResult phoneCode(@RequestBody PhoneCodeRequest body)
    {
        return AjaxResult.success(phoneCodes.issue(body.phone(), com.ruoyi.common.utils.ip.IpUtils.getIpAddr()));
    }

    @Anonymous
    @PostMapping("/miniapp/auth/login")
    public AjaxResult phoneLogin(@RequestBody PhoneLoginRequest body)
    {
        phoneCodes.verify(body.phone(), body.code());
        AjaxResult result = AjaxResult.success();
        result.put(Constants.TOKEN, miniAppLoginService.loginVerifiedPhone(body.phone()));
        return result;
    }

    @Anonymous
    @GetMapping("/miniapp/dev/session")
    public AjaxResult developmentSession()
    {
        return AjaxResult.success(java.util.Map.of("version", miniAppLoginService.developmentSessionVersion()));
    }

    @Anonymous
    @GetMapping("/miniapp/dev/teachers")
    public AjaxResult teachers()
    {
        return AjaxResult.success(miniAppLoginService.developmentTeachers());
    }

    @Anonymous
    @GetMapping("/miniapp/dev/parents")
    public AjaxResult parents()
    {
        return AjaxResult.success(miniAppLoginService.developmentParents());
    }

    @PostMapping("/miniapp/login")
    public AjaxResult login(@RequestBody MiniAppLoginBody loginBody)
    {
        AjaxResult ajax = AjaxResult.success();
        String token = miniAppLoginService.login(loginBody.getLoginCode(), loginBody.getPhoneCode(),
                loginBody.getNickName(), loginBody.getDevRole(), loginBody.getDevTeacherId(), loginBody.getDevParentId());
        ajax.put(Constants.TOKEN, token);
        String developmentVersion = miniAppLoginService.loginDevelopmentSessionVersion();
        if (developmentVersion != null)
        {
            ajax.put("devSessionVersion", developmentVersion);
        }
        return ajax;
    }
}
