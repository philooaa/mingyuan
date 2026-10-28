package com.ruoyi.system.controller;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.EduTeacherSalaryConfig;
import com.ruoyi.system.mapper.EduTeacherSalaryRecordMapper;
import com.ruoyi.system.service.IEduTeacherSalaryConfigService;

/**
 * 教师薪资标准Controller
 *
 * @author ruoyi
 * @date 2026-09-04
 */
@RestController
@RequestMapping("/system/salaryConfig")
public class EduTeacherSalaryConfigController extends BaseController
{
    @Autowired
    private IEduTeacherSalaryConfigService configService;

    @Autowired
    private EduTeacherSalaryRecordMapper recordMapper;

    /**
     * 查询薪资标准列表
     *
     * 以教师账号为主体返回：未配置薪资标准的教师也会出现在列表中（configId 为空），
     * 并附带该教师负责的小学班课门数、中学班课门数、一对一门数。
     */
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:list')")
    @GetMapping("/list")
    public AjaxResult list(EduTeacherSalaryConfig config)
    {
        List<EduTeacherSalaryConfig> list = configService.selectTeacherSalaryList(config);
        return success(list);
    }

    /**
     * 教师账号下拉（teacher角色）
     */
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:list')")
    @GetMapping("/teachers")
    public AjaxResult teachers()
    {
        return success(recordMapper.selectTeacherUsers());
    }

    /**
     * 获取薪资标准详情
     */
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:query')")
    @GetMapping("/{configId}")
    public AjaxResult getInfo(@PathVariable("configId") Long configId)
    {
        return success(configService.selectConfigById(configId));
    }

    /**
     * 新增薪资标准
     */
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:add')")
    @Log(title = "教师薪资标准", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody EduTeacherSalaryConfig config)
    {
        return toAjax(configService.insertConfig(config));
    }

    /**
     * 修改薪资标准
     */
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:edit')")
    @Log(title = "教师薪资标准", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EduTeacherSalaryConfig config)
    {
        return toAjax(configService.updateConfig(config));
    }

    /**
     * 删除薪资标准
     */
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:remove')")
    @Log(title = "教师薪资标准", businessType = BusinessType.DELETE)
    @DeleteMapping("/{configIds}")
    public AjaxResult remove(@PathVariable Long[] configIds)
    {
        return toAjax(configService.deleteConfigByIds(configIds));
    }
}
