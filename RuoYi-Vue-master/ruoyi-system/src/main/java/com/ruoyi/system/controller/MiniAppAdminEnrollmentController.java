package com.ruoyi.system.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.EduEnrollment;
import com.ruoyi.system.mapper.MiniAppAdminEnrollmentMapper;
import com.ruoyi.system.service.IEduEnrollmentService;

/** 小程序管理员报名管理：复用既有报名查询、统计及取消事务。 */
@RestController
@RequestMapping("/miniapp/admin/enrollment")
@PreAuthorize("@ss.hasRole('admin')")
public class MiniAppAdminEnrollmentController extends BaseController
{
    @Autowired private MiniAppAdminEnrollmentMapper viewMapper;
    @Autowired private IEduEnrollmentService enrollmentService;

    @GetMapping("/grades")
    public AjaxResult grades() { return success(viewMapper.selectGrades()); }

    @GetMapping("/schedules")
    public TableDataInfo schedules(@RequestParam(required = false) String gradeName)
    {
        startPage();
        return getDataTable(viewMapper.selectSchedules(gradeName));
    }

    @GetMapping("/schedule/{scheduleId}")
    public AjaxResult schedule(@PathVariable Long scheduleId)
    {
        Map<String, Object> schedule = viewMapper.selectSchedule(scheduleId);
        return schedule == null ? error("班次不存在或已删除") : success(schedule);
    }

    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) Long scheduleId,
                             @RequestParam(defaultValue = "") String keyword)
    {
        EduEnrollment query = new EduEnrollment();
        query.setScheduleId(scheduleId);
        query.setDelFlag("0");
        query.getParams().put("adminKeyword", keyword.trim());
        startPage();
        return getDataTable(enrollmentService.selectEduEnrollmentList(query));
    }

    @GetMapping("/{enrollmentId}")
    public AjaxResult detail(@PathVariable Long enrollmentId)
    {
        EduEnrollment enrollment = enrollmentService.selectEduEnrollmentByEnrollmentId(enrollmentId);
        if (enrollment == null || !"0".equals(enrollment.getDelFlag())) return error("报名不存在");
        Map<String, Object> data = new HashMap<>();
        data.put("enrollment", enrollment);
        data.put("schedule", viewMapper.selectSchedule(enrollment.getScheduleId()));
        data.put("history", viewMapper.selectHistory(enrollmentId));
        return success(data);
    }

    @PutMapping("/{enrollmentId}/cancel")
    @Log(title = "小程序取消报名", businessType = BusinessType.UPDATE)
    public AjaxResult cancel(@PathVariable Long enrollmentId)
    {
        int count = enrollmentService.cancelEduEnrollmentByEnrollmentIds(new Long[] {enrollmentId});
        return AjaxResult.success(count == 0 ? "报名已取消" : "取消报名成功", count);
    }
}
