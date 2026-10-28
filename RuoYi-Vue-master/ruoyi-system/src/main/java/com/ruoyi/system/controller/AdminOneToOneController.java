package com.ruoyi.system.controller;

import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.EduCourseScheduleMapper;
import com.ruoyi.system.service.*;
import com.ruoyi.system.shop.ShopRepository;

/** 现有管理网页的管理员入口；不模拟教师登录，不另建预约或课次数据源。 */
@RestController
@RequestMapping("/system/schedule/single")
@PreAuthorize("@ss.hasRole('admin')")
public class AdminOneToOneController extends BaseController {
    @Autowired private OneToOneEnrollmentService single;
    @Autowired private ScheduleAvailabilityService availability;
    @Autowired private IEduCourseScheduleService schedules;
    @Autowired private EduCourseScheduleMapper mapper;
    @Autowired private ShopRepository db;

    public static class Draft extends TeacherOneToOneDraft { public Long teacherId; }
    public static class Decision { public String action; public String reason; }

    @GetMapping("/profile")
    public AjaxResult profile(@RequestParam Long teacherId) { return success(single.profile(teacherId)); }

    @GetMapping("/availability")
    public AjaxResult availability(@RequestParam Long teacherId,@RequestParam LocalDate date,@RequestParam(required=false) Long excludeId) {
        single.profile(teacherId);
        if (excludeId != null && !Objects.equals(course(excludeId).getTeacherId(),teacherId))
            throw new ServiceException("调整时段须使用原课程教师");
        return success(availability.week(teacherId,date,excludeId));
    }

    @GetMapping("/courses")
    public AjaxResult courses(@RequestParam(required=false) Long teacherId) {
        EduCourseSchedule query=new EduCourseSchedule();query.setClassMode("2");
        if(teacherId!=null)query.getParams().put("teacherUserId",teacherId);
        // 管理员只读取一对一课程；不依赖通用排课服务对教师身份的自动过滤。
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var s:mapper.selectEduCourseScheduleList(query)) {
            var row=new LinkedHashMap<String,Object>();
            row.put("scheduleId",s.getScheduleId());row.put("teacherId",s.getTeacherId());row.put("teacherName",s.getTeacherName());
            row.put("courseClassName",s.getCourseClassName());row.put("gradeName",s.getGradeName());row.put("subjectName",s.getSubjectName());
            row.put("startDate",ScheduleCalendar.date(s.getStartDate()));row.put("endDate",ScheduleCalendar.date(s.getEndDate()));
            row.put("timeSlot",s.getTimeSlot());row.put("status",s.getStatus());row.put("recruitStatus",s.getRecruitStatus());
            row.put("dates",single.dates(s));row.put("singleBookings",single.bookings(s.getScheduleId()));
            boolean active=!db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND del_flag='0' AND COALESCE(enrollment_status,'') NOT IN ('2','3','已取消','已拒绝','老师已拒绝')",s.getScheduleId()).isEmpty();
            boolean history=!db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND class_date IS NOT NULL UNION ALL SELECT sign_in_id FROM edu_class_sign_in WHERE schedule_id=?",s.getScheduleId(),s.getScheduleId()).isEmpty();
            row.put("canEdit",!active && !history);row.put("canWithdraw",!active && "0".equals(s.getStatus()));
            rows.add(row);
        }
        return success(rows);
    }

    private EduCourseSchedule course(Long id) {
        var s=schedules.selectEduCourseScheduleByScheduleId(id);
        if(!"2".equals(s.getClassMode()))throw new ServiceException("请选择一对一课程");
        return s;
    }
    private EduCourseSchedule build(Draft body) {
        if(body==null || body.teacherId==null)throw new ServiceException("请选择教师账号");
        var s=body.toSchedule(single.subject(body.teacherId),availability.slot(body.timeSlot));s.setTeacherId(body.teacherId);
        return s;
    }
    @PostMapping("/courses")
    @Log(title="管理员发布一对一",businessType=BusinessType.INSERT)
    public AjaxResult publish(@RequestBody Draft body) {
        var s=build(body);schedules.insertEduCourseSchedule(s);return success(s.getScheduleId());
    }
    @PutMapping("/courses/{id}")
    @Log(title="管理员调整一对一",businessType=BusinessType.UPDATE)
    public AjaxResult edit(@PathVariable Long id,@RequestBody Draft body) {
        var before=course(id);
        if(body==null || !Objects.equals(before.getTeacherId(),body.teacherId))throw new ServiceException("更换教师须先撤回原课程，再重新发布");
        var s=build(body);s.setScheduleId(id);return toAjax(schedules.updateEduCourseSchedule(s));
    }
    @PostMapping("/courses/{id}/withdraw")
    @Log(title="管理员撤回一对一",businessType=BusinessType.UPDATE)
    public AjaxResult withdraw(@PathVariable Long id) {
        course(id);var patch=new EduCourseSchedule();patch.setScheduleId(id);patch.setStatus("1");
        return toAjax(schedules.updateEduCourseSchedule(patch));
    }
    @GetMapping("/bookings")
    public AjaxResult bookings(@RequestParam(required=false) Long teacherId,@RequestParam(required=false) Long scheduleId) {
        return success(single.adminTransactions(teacherId,scheduleId));
    }
    @PostMapping("/bookings/{id}/decision")
    @Log(title="管理员处理一对一预约",businessType=BusinessType.UPDATE)
    public AjaxResult decide(@PathVariable Long id,@RequestBody Decision body) {
        if(body==null)throw new ServiceException("请选择处理操作");return toAjax(single.adminDecide(id,body.action,body.reason));
    }
    @PostMapping("/bookings/{id}/complete")
    @Log(title="管理员完成一对一",businessType=BusinessType.UPDATE)
    public AjaxResult complete(@PathVariable Long id) {
        int count=single.adminComplete(id);return AjaxResult.success(count==0?"课程已完成":"课程完成",count);
    }
    @GetMapping("/bookings/{id}/history")
    public AjaxResult history(@PathVariable Long id) { return success(single.adminHistory(id)); }
}
