package com.ruoyi.system.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.AttendanceMakeupService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/attendance/leave-report/makeup")
@PreAuthorize("@ss.hasAnyRoles('teacher,admin,education,business_admin')")
public class AttendanceMakeupController {
    private final AttendanceMakeupService service;
    public AttendanceMakeupController(AttendanceMakeupService service) { this.service=service; }
    @GetMapping("/options") public AjaxResult options(@RequestParam long signInId,
            @RequestParam(required=false) Long scheduleId,@RequestParam(defaultValue="") String keyword) {
        return AjaxResult.success(service.options(signInId,scheduleId,keyword));
    }
    @PostMapping public AjaxResult arrange(@RequestBody AttendanceMakeupService.ArrangeRequest input) {
        return AjaxResult.success(service.arrange(input));
    }
    @PostMapping("/cancel") public AjaxResult cancel(@RequestBody AttendanceMakeupService.CancelRequest input) {
        return AjaxResult.success(service.cancel(input));
    }
}
