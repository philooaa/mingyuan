package com.ruoyi.system.controller;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.service.IEduCourseScheduleService;

/** 小程序教务和管理员排课；教室分配由后续独立功能接入。 */
@RestController
@RequestMapping("/miniapp/staff/schedule")
@PreAuthorize("@ss.hasAnyRoles('admin,education')")
public class MiniAppStaffScheduleController extends BaseController {
    @Autowired private IEduCourseScheduleService schedules;
    @Autowired private EduTimetableController timetable;

    @GetMapping("/options") public AjaxResult options() { return timetable.dimensions(); }
    @GetMapping("/list") public TableDataInfo list(EduCourseSchedule query) {
        startPage(); return getDataTable(schedules.selectEduCourseScheduleList(query));
    }
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable Long id) { return success(schedules.selectEduCourseScheduleByScheduleId(id)); }
    @PostMapping
    @Log(title="教务管理员新增排课",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody EduCourseSchedule input) {
        if (input.getClassroomId()!=null) throw new ServiceException("排课时只选择校区，教室稍后统一分配");
        schedules.insertEduCourseSchedule(input); return success(input.getScheduleId());
    }
    @PutMapping
    @Log(title="教务管理员修改排课",businessType=BusinessType.UPDATE)
    public AjaxResult edit(@RequestBody EduCourseSchedule input, @RequestParam(defaultValue="false") boolean force) {
        EduCourseSchedule old=schedules.selectEduCourseScheduleByScheduleId(input.getScheduleId());
        if (input.getClassroomId()!=null && !Objects.equals(input.getClassroomId(),old.getClassroomId()))
            throw new ServiceException("请通过后续教室分配功能调整教室");
        if (old.getClassroomId()!=null && input.getCampusName()!=null && !Objects.equals(input.getCampusName(),old.getCampusName()))
            throw new ServiceException("已分配教室的课程需通过教室分配功能调整校区");
        input.setClassroomId(old.getClassroomId());
        return toAjax(schedules.updateEduCourseSchedule(input, force));
    }
    @DeleteMapping("/{id}")
    @Log(title="教务管理员删除排课",businessType=BusinessType.DELETE)
    public AjaxResult remove(@PathVariable Long id, @RequestParam(defaultValue="false") boolean force) {
        return toAjax(force ? schedules.removeConfirmedSchedule(id) : schedules.deleteEduCourseScheduleByScheduleId(id));
    }
}
