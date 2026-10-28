package com.ruoyi.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.service.IEduHomeworkService;

@RestController
@RequestMapping
public class EduHomeworkController extends BaseController
{
    @Autowired private IEduHomeworkService service;
    @Autowired private com.ruoyi.system.service.ParentAccountService accounts;
    @PreAuthorize("@ss.hasRole('parent')") @GetMapping("/miniapp/parent/homework/list") public AjaxResult parentList(@RequestParam(required=false) Long accountId){return success(service.parentList(accountId));}
    @PreAuthorize("@ss.hasRole('parent')") @GetMapping("/miniapp/parent/homework/{homeworkId}") public AjaxResult parentDetail(@PathVariable Long homeworkId, @RequestParam(required=false) Long scheduleId,@RequestParam(required=false) Long enrollmentId,@RequestParam(required=false) Long accountId){if(accountId!=null && (enrollmentId==null || !accounts.childEnrollmentIds(accountId).contains(String.valueOf(enrollmentId))))throw new com.ruoyi.common.exception.ServiceException("作业不属于当前孩子");return success(service.parentDetail(homeworkId, scheduleId,enrollmentId));}
    @PreAuthorize("@ss.hasRole('parent')") @PostMapping("/miniapp/parent/homework/submit") public AjaxResult submit(@RequestBody HomeworkSubmitBody body){return success().put("submissionId",service.submit(body));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @PostMapping("/miniapp/teacher/homework") public AjaxResult draft(@RequestBody HomeworkSaveBody body){return success().put("homeworkId",service.saveDraft(body));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @GetMapping("/miniapp/teacher/homework/{homeworkId}") public AjaxResult teacherDetail(@PathVariable Long homeworkId){return success(service.teacherDetail(homeworkId));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @PostMapping("/miniapp/teacher/homework/{homeworkId}/reuse") public AjaxResult reuse(@PathVariable Long homeworkId, @RequestBody(required=false) HomeworkSaveBody body){return success().put("homeworkId",service.reuse(homeworkId, body));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @PostMapping("/miniapp/teacher/homework/{homeworkId}/publish") public AjaxResult publish(@PathVariable Long homeworkId, @RequestBody(required=false) java.util.Map<String, Object> body){java.util.List<Long> scheduleIds=new java.util.ArrayList<>(); Object raw=body==null?null:body.get("scheduleIds"); if(raw instanceof java.util.List) for(Object v:(java.util.List<?>)raw) if(v!=null) scheduleIds.add(Long.valueOf(String.valueOf(v))); return toAjax(service.publish(homeworkId, scheduleIds));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @GetMapping("/miniapp/teacher/homework/list") public AjaxResult teacherList(){return success(service.teacherList());}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @GetMapping("/miniapp/teacher/homework/{homeworkId}/submissions") public AjaxResult submissions(@PathVariable Long homeworkId){return success(service.submissions(homeworkId));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @GetMapping("/miniapp/teacher/homework/{homeworkId}/submission-stats") public AjaxResult submissionStats(@PathVariable Long homeworkId){return success(service.submissionStats(homeworkId));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @PutMapping("/miniapp/teacher/homework/submission/review") public AjaxResult review(@RequestBody EduHomeworkSubmission body){return toAjax(service.review(body));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @PostMapping("/miniapp/teacher/homework/{homeworkId}/close") public AjaxResult close(@PathVariable Long homeworkId){return toAjax(service.close(homeworkId));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')") @PostMapping("/miniapp/teacher/homework/{homeworkId}/deadline") public AjaxResult updateDeadline(@PathVariable Long homeworkId, @RequestBody(required=false) java.util.Map<String, Object> body){Object raw=body==null?null:body.get("deadline"); return toAjax(service.updateDeadline(homeworkId, raw==null?null:String.valueOf(raw)));}
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')") @GetMapping("/admin/education/homework/list") public AjaxResult adminList(EduHomework query){return success(service.adminList(query));}
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')") @PostMapping("/admin/education/homework/{homeworkId}/archive") public AjaxResult archive(@PathVariable Long homeworkId){return toAjax(service.archive(homeworkId,true));}
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')") @PostMapping("/admin/education/homework/{homeworkId}/restore") public AjaxResult restore(@PathVariable Long homeworkId){return toAjax(service.archive(homeworkId,false));}
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')") @Log(title="作业删除",businessType=BusinessType.DELETE) @DeleteMapping("/admin/education/homework/{homeworkId}") public AjaxResult delete(@PathVariable Long homeworkId){return toAjax(service.delete(homeworkId));}
}
