package com.ruoyi.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.IMiniAppParentService;

/**
 * MiniApp parent enrollment APIs.
 */
@RestController
@RequestMapping("/miniapp/parent")
public class MiniAppParentController extends BaseController
{
    @Autowired
    private IMiniAppParentService miniAppParentService;
    @Autowired private com.ruoyi.system.service.OneToOneEnrollmentService singleService;
    @Autowired private com.ruoyi.system.service.LessonCreditService lessonCredits;
    @Autowired private com.ruoyi.system.service.ParentAccountService personal;

    @PreAuthorize("@ss.hasRole('parent')") @GetMapping("/account")
    public AjaxResult account() { return success(personal.detail(com.ruoyi.common.utils.SecurityUtils.getUserId(),false)); }
    @PreAuthorize("@ss.hasRole('parent')") @GetMapping("/account/records")
    public AjaxResult accountRecords(@RequestParam(defaultValue="enrollments") String kind,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="false") boolean pendingOnly,@RequestParam(required=false) Long accountId,@RequestParam(defaultValue="false") boolean unlinkedOnly,@RequestParam(defaultValue="false") boolean includePackages) { return success(personal.records(com.ruoyi.common.utils.SecurityUtils.getUserId(),false,kind,pageNum,pendingOnly,accountId,unlinkedOnly,includePackages)); }
    @PreAuthorize("@ss.hasRole('parent')") @GetMapping("/account/children/{id}")
    public AjaxResult childAccount(@PathVariable Long id) { return com.ruoyi.system.controller.EduTuitionController.ok(personal.childDetail(id)); }
    @PreAuthorize("@ss.hasRole('parent')") @GetMapping("/account/pending-count")
    public AjaxResult pending(@RequestParam(defaultValue="false") boolean actionable) { return success(actionable?personal.actionPending():personal.pending(false)); }
    @PreAuthorize("@ss.hasRole('parent')") @PostMapping("/children") @Log(title="家长新增孩子",businessType=BusinessType.INSERT)
    public AjaxResult createChild(@RequestBody com.ruoyi.system.service.LessonCreditService.AccountDraft body) { return success(lessonCredits.createMine(body)); }
    @PreAuthorize("@ss.hasRole('parent')") @org.springframework.web.bind.annotation.PutMapping("/children/{id}") @Log(title="家长修改孩子",businessType=BusinessType.UPDATE)
    public AjaxResult editChild(@PathVariable Long id,@RequestBody com.ruoyi.system.service.LessonCreditService.AccountDraft body) { lessonCredits.editChild(id,body,false); return success(); }
    @PreAuthorize("@ss.hasRole('parent')") @DeleteMapping("/children/{id}") @Log(title="家长删除未使用孩子",businessType=BusinessType.DELETE)
    public AjaxResult deleteChild(@PathVariable Long id) { lessonCredits.deleteChild(id,false); return success(); }

    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/lesson-accounts")
    public AjaxResult lessonAccounts() { return success(lessonCredits.mine()); }

    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/lesson-accounts/{id}/history")
    public AjaxResult lessonAccountHistory(@PathVariable Long id) { return success(lessonCredits.history(id,false)); }

    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @GetMapping("/schedules")
    public AjaxResult schedules(@RequestParam(value = "gradeName", required = false) String gradeName,
            @RequestParam(value = "subjectName", required = false) String subjectName,
            @RequestParam(value = "termName", required = false) String termName)
    {
        return success(miniAppParentService.selectAvailableSchedules(gradeName, subjectName, termName));
    }

    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @GetMapping("/enrollments")
    public AjaxResult enrollments(@RequestParam(required=false) Long accountId)
    {
        return success(personal.filterChild(miniAppParentService.selectMyEnrollments(),accountId));
    }

    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @GetMapping("/timetable")
    public AjaxResult timetable(@RequestParam(required=false) Long accountId)
    {
        return success(personal.filterChild(miniAppParentService.selectMyTimetable(),accountId));
    }

    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @GetMapping("/attendance")
    public AjaxResult attendance(@RequestParam(required=false) Long accountId)
    {
        return success(accountId==null?miniAppParentService.selectMyAttendanceSummary():personal.childAttendanceSummary(accountId));
    }

    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @GetMapping("/attendance/details")
    public AjaxResult attendanceDetails(@RequestParam(value="courseClassName",required=false) String courseClassName,@RequestParam(required=false) Long accountId,@RequestParam(required=false) Long enrollmentId)
    {
        if(accountId!=null){if(enrollmentId==null)throw new com.ruoyi.common.exception.ServiceException("请选择孩子的具体课程");return success(personal.childAttendance(accountId,enrollmentId));}
        return success(miniAppParentService.selectMyAttendanceDetails(courseClassName));
    }

    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @PostMapping("/enroll")
    public AjaxResult enroll(@RequestBody MiniAppEnrollmentBody body)
    {
        if(body.getAccountId()!=null){personal.ownedChild(body.getAccountId());if(!body.getAccountId().equals(body.getLessonAccountId()))throw new com.ruoyi.common.exception.ServiceException("报名孩子与当前孩子不一致");}
        return toAjax(miniAppParentService.enroll(body));
    }

    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/single/lessons")
    public AjaxResult singleLessons() { return success(singleService.available()); }

    public static class CancelRequest { public String reason; public Long accountId; }
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/enrollment/{id}/cancel-request")
    public AjaxResult cancelRequest(@PathVariable Long id,@RequestBody CancelRequest body) { personal.childEnrollment(body.accountId,id);return toAjax(singleService.requestCancellation(id,body.reason)); }

    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/enrollment/{id}/history")
    public AjaxResult history(@PathVariable Long id,@RequestParam(required=false) Long accountId) { personal.childEnrollment(accountId,id);return success(singleService.history(id,false)); }

    /**
     * 家长取消本人的课程报名
     * 财务安全取消：校验归属与收款状态，关闭账单并释放一次名额，保留历史考勤。
     */
    @PreAuthorize("@ss.hasAnyRoles('parent')")
    @Log(title = "家长取消报名", businessType = BusinessType.UPDATE)
    @DeleteMapping("/enrollment/{enrollmentId}")
    public AjaxResult cancelEnrollment(@PathVariable("enrollmentId") Long enrollmentId,@RequestParam(required=false) Long accountId)
    {
        personal.childEnrollment(accountId,enrollmentId);int count = miniAppParentService.cancelEnrollment(enrollmentId);
        return AjaxResult.success(count == 0 ? "该报名已取消" : "取消报名成功", count);
    }
}
