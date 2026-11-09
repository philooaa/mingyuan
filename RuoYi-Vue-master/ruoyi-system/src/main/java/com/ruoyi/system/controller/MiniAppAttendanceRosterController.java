package com.ruoyi.system.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.AttendanceLessonRosterService;
import org.springframework.web.bind.annotation.*;

/** Actual live account/role checks are in the shared service, including reads. */
@RestController
public class MiniAppAttendanceRosterController extends BaseController {
    private final AttendanceLessonRosterService roster;
    public MiniAppAttendanceRosterController(AttendanceLessonRosterService roster){this.roster=roster;}
    @GetMapping("/miniapp/staff/attendance/courses") public AjaxResult courses(@RequestParam(defaultValue="") String keyword){return success(roster.courses(keyword));}
    @GetMapping("/miniapp/staff/attendance/students") public AjaxResult students(@RequestParam(defaultValue="") String keyword){return success(roster.students(keyword));}
    @GetMapping("/miniapp/staff/attendance/lessons") public AjaxResult lessons(@RequestParam long scheduleId){return success(roster.lessons(scheduleId));}
    @GetMapping("/miniapp/staff/attendance/summary") public AjaxResult summary(@RequestParam long scheduleId,@RequestParam(required=false) String from,@RequestParam(required=false) String to){return success(roster.summary(scheduleId,from,to));}
    @GetMapping("/miniapp/staff/attendance/roster") public AjaxResult roster(@RequestParam long scheduleId,@RequestParam long lessonId){return success(roster.roster(scheduleId,lessonId));}
    @PostMapping("/miniapp/staff/attendance/roster") public AjaxResult add(@RequestBody AttendanceLessonRosterService.MembershipRequest input){return success(roster.add(input));}
    @PutMapping("/miniapp/staff/attendance/roster/{id}") public AjaxResult remove(@PathVariable long id,@RequestBody AttendanceLessonRosterService.RemovalRequest input){roster.remove(id,input);return success();}
    @PutMapping("/miniapp/staff/attendance/roster/enrollment/{id}") public AjaxResult removeEnrollment(@PathVariable long id,@RequestBody AttendanceLessonRosterService.RemovalRequest input){roster.removeEnrollment(id,input);return success();}
    @GetMapping("/miniapp/attendance/todos") public AjaxResult todos(@RequestParam String role){return success(roster.todos(role));}
}
