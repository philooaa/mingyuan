package com.ruoyi.system.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduEnrollment;
import com.ruoyi.system.mapper.MiniAppAdminEnrollmentMapper;
import com.ruoyi.system.mapper.MiniAppParentMapper;
import com.ruoyi.system.service.IEduEnrollmentService;
import com.ruoyi.system.service.IEduCourseScheduleService;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;

/** 教师排课 CRUD 和班级学员只读视图，复用现有排课、报名及课次历史。 */
@RestController
@RequestMapping("/miniapp/teacher/course")
@PreAuthorize("@ss.hasRole('teacher')")
public class MiniAppTeacherCourseController extends BaseController
{
    @Autowired private MiniAppAdminEnrollmentMapper viewMapper;
    @Autowired private MiniAppParentMapper parentMapper;
    @Autowired private IEduEnrollmentService enrollmentService;
    @Autowired private IEduCourseScheduleService scheduleService;
    @Autowired private com.ruoyi.system.service.OneToOneEnrollmentService singleService;
    @Autowired private com.ruoyi.system.service.ScheduleAvailabilityService availability;

    @GetMapping("/schedules")
    public AjaxResult schedules()
    {
        List<Map<String, Object>> courses = viewMapper.selectTeacherSchedules(SecurityUtils.getUserId(), null);
        for (Map<String, Object> course : courses)
        {
            singleService.decorateTeacher(course);
            course.put("adjustments", parentMapper.selectScheduleAdjustments(course.get("scheduleId")));
        }
        return success(courses);
    }

    @GetMapping("/schedule/{scheduleId}/edit")
    public AjaxResult editInfo(@PathVariable Long scheduleId)
    {
        ownSchedule(scheduleId);
        return success(scheduleService.selectEduCourseScheduleByScheduleId(scheduleId));
    }

    @PostMapping("/schedule")
    @Log(title = "教师新增排课", businessType = BusinessType.INSERT)
    public AjaxResult add(@RequestBody com.ruoyi.system.domain.TeacherOneToOneDraft draft)
    {
        EduCourseSchedule input = draft.toSchedule(singleService.subject(SecurityUtils.getUserId()),availability.slot(draft.timeSlot));
        fixedTeacher(input);
        scheduleService.insertEduCourseSchedule(input);
        return success(input.getScheduleId());
    }

    @PutMapping("/schedule")
    @Log(title = "教师修改排课", businessType = BusinessType.UPDATE)
    public AjaxResult edit(@RequestBody EduCourseSchedule input)
    {
        ownSchedule(input.getScheduleId());
        fixedTeacher(input);
        var current = scheduleService.selectEduCourseScheduleByScheduleId(input.getScheduleId());
        if ("2".equals(current.getClassMode())) { input.setSubjectName(current.getSubjectName()); input.setClassMode("2"); }
        return toAjax(scheduleService.updateEduCourseSchedule(input));
    }

    @DeleteMapping("/schedule/{scheduleId}")
    @Log(title = "教师删除排课", businessType = BusinessType.DELETE)
    public AjaxResult remove(@PathVariable Long scheduleId)
    {
        ownSchedule(scheduleId);
        return toAjax(scheduleService.deleteEduCourseScheduleByScheduleId(scheduleId));
    }

    @GetMapping("/profile")
    public AjaxResult profile() { return success(singleService.profile(SecurityUtils.getUserId())); }

    @GetMapping("/availability")
    public AjaxResult availability(@RequestParam java.time.LocalDate date,@RequestParam(required=false) Long excludeId) {
        if (excludeId != null) ownSchedule(excludeId);
        return success(availability.week(SecurityUtils.getUserId(),date,excludeId));
    }

    @GetMapping("/single/transactions")
    public AjaxResult transactions() { return success(singleService.transactions()); }

    public static class Decision { public String action; public String reason; }
    @PostMapping("/single/{id}/decision")
    @Log(title="处理一对一报名", businessType=BusinessType.UPDATE)
    public AjaxResult decision(@PathVariable Long id,@RequestBody Decision body) { return toAjax(singleService.decide(id,body.action,body.reason)); }

    @GetMapping("/single/{id}/history")
    public AjaxResult history(@PathVariable Long id) { return success(singleService.history(id,true)); }

    @PostMapping("/single/{id}/complete")
    @Log(title="完成一对一课程",businessType=BusinessType.UPDATE)
    public AjaxResult complete(@PathVariable Long id) { int count=singleService.complete(id); return AjaxResult.success(count == 0 ? "课程已完成" : "课程完成",count); }

    @GetMapping("/students")
    public TableDataInfo students(@RequestParam(required = false) Long scheduleId,
                                 @RequestParam(defaultValue = "") String keyword)
    {
        if (scheduleId != null) ownSchedule(scheduleId);
        EduEnrollment query = query();
        query.setScheduleId(scheduleId);
        query.getParams().put("adminKeyword", keyword.trim());
        startPage();
        return getDataTable(enrollmentService.selectEduEnrollmentList(query));
    }

    @GetMapping("/schedule/{scheduleId}")
    public AjaxResult schedule(@PathVariable Long scheduleId)
    {
        return success(ownSchedule(scheduleId));
    }

    @GetMapping("/student/{enrollmentId}")
    public AjaxResult student(@PathVariable Long enrollmentId)
    {
        EduEnrollment query = query();
        query.getParams().put("teacherEnrollmentId", enrollmentId);
        List<EduEnrollment> rows = enrollmentService.selectEduEnrollmentList(query);
        if (rows.isEmpty()) throw new ServiceException("学员不存在或不属于本人课程");
        EduEnrollment enrollment = rows.get(0);
        Map<String, Object> data = new HashMap<>();
        data.put("enrollment", enrollment);
        data.put("schedule", ownSchedule(enrollment.getScheduleId()));
        data.put("history", viewMapper.selectTeacherHistory(enrollmentId, SecurityUtils.getUserId()));
        return success(data);
    }

    private void fixedTeacher(EduCourseSchedule input)
    {
        if (input.getTeacherId() != null && !input.getTeacherId().equals(SecurityUtils.getUserId()))
            throw new ServiceException("只能为当前教师排课");
        input.setTeacherId(SecurityUtils.getUserId());
    }

    private EduEnrollment query()
    {
        EduEnrollment query = new EduEnrollment();
        query.setDelFlag("0");
        query.getParams().put("teacherUserId", SecurityUtils.getUserId());
        return query;
    }

    private Map<String, Object> ownSchedule(Long scheduleId)
    {
        List<Map<String, Object>> courses = viewMapper.selectTeacherSchedules(SecurityUtils.getUserId(), scheduleId);
        if (courses.isEmpty()) throw new ServiceException("课程不存在或不属于本人");
        return courses.get(0);
    }
}
