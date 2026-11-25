package com.ruoyi.system.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.TeacherClassAttendanceService;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/salaryConfig/class-attendance")
public class TeacherClassAttendanceController {
    private final TeacherClassAttendanceService service;
    public TeacherClassAttendanceController(TeacherClassAttendanceService service){this.service=service;}
    @GetMapping("/{teacherId}")
    @PreAuthorize("@ss.hasPermi('system:salaryConfig:list')")
    public AjaxResult summary(@PathVariable long teacherId,@RequestParam String salaryMonth){return AjaxResult.success(service.summary(teacherId,salaryMonth));}
    public record Adjustment(BigDecimal count) {}
    @PutMapping("/{teacherId}")
    @PreAuthorize("@ss.hasAnyRoles('admin,business_admin') and @ss.hasPermi('system:salaryConfig:edit')")
    @Log(title="教师班课人次调整",businessType=BusinessType.UPDATE)
    public AjaxResult adjust(@PathVariable long teacherId,@RequestParam String salaryMonth,@RequestBody Adjustment body){return AjaxResult.success(service.adjust(teacherId,salaryMonth,body.count()));}
}
