package com.ruoyi.system.controller;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.AttendanceLeaveReportService;

@RestController
@RequestMapping("/miniapp/attendance/leave-report")
@PreAuthorize("@ss.hasAnyRoles('admin,education,business_admin,teacher')")
public class AttendanceLeaveReportController {
    private final AttendanceLeaveReportService service;
    public AttendanceLeaveReportController(AttendanceLeaveReportService service){this.service=service;}
    @GetMapping public AjaxResult list(@RequestParam Map<String,String> query){return AjaxResult.success(service.report(query));}
}
