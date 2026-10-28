package com.ruoyi.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.EduTeacherProfileService;

/** 后台教师资料配置；不接受小程序教师自行修改。 */
@RestController
@RequestMapping("/system/teacherProfile")
@PreAuthorize("@ss.hasPermi('system:user:edit')")
public class TeacherProfileController extends BaseController {
    @Autowired private ISysUserService users;
    @Autowired private EduTeacherProfileService profiles;
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id) {
        users.checkUserDataScope(id);
        return success(profiles.get(id));
    }
    public static class Profile { public String teacherSubject; public String teacherLevel; }
    @PutMapping("/{id}")
    @Log(title="教师授课资料",businessType=BusinessType.UPDATE)
    public AjaxResult save(@PathVariable Long id,@RequestBody Profile p) {
        users.checkUserDataScope(id);
        profiles.save(id,p.teacherSubject,p.teacherLevel,getUsername());
        return success();
    }
}
