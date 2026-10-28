package com.ruoyi.system.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.RoomAssignService;

/**
 * 教室按日期分配（小程序教务端 / 管理员端）。
 *
 * <p>只做两件事：按日期取当天两个校区的课表；把某门课从某天起换到另一个教室。
 * 权限沿用「教室管理」的角色范围：管理员与教务，不额外造菜单权限。
 */
@RestController
@RequestMapping("/system/roomAssign")
public class EduRoomAssignController extends BaseController
{
    @Autowired
    private RoomAssignService roomAssignService;

    /** 某一天两个校区的课表（教室 × 时段），供横向调整教室使用。 */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @GetMapping("/day")
    public AjaxResult day(@RequestParam("date") String date)
    {
        return success(roomAssignService.day(date));
    }

    /**
     * 把课程调到目标教室：从 date 起生效，直到下一次调整。
     * 目标教室同时段已有课时两门课互换。
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "教室分配-调整教室", businessType = BusinessType.UPDATE)
    @PostMapping("/move")
    public AjaxResult move(@RequestBody Map<String, Object> body)
    {
        Long scheduleId = toLong(body.get("scheduleId"));
        Long targetRoomId = toLong(body.get("targetClassroomId"));
        Object date = body.get("date");
        return success(roomAssignService.move(scheduleId, date == null ? null : String.valueOf(date),
                targetRoomId, SecurityUtils.getUsername()));
    }

    private static Long toLong(Object v)
    {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }
}
