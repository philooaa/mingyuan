package com.ruoyi.system.controller;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.LessonCreditService;

/** 若依和小程序管理员使用相同接口及余额；角色限制不能由客户端切换绕过。 */
@RestController
@RequestMapping("/system/eduTransaction")
@PreAuthorize("@ss.hasRole('admin')")
public class EduTransactionController extends BaseController {
    @Autowired private LessonCreditService credits;
    @Autowired private com.ruoyi.system.service.ParentAccountService personal;
    @GetMapping("/personal-accounts") public AjaxResult personalList(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int pageNum) { return success(personal.list(keyword,pageNum)); }
    @GetMapping("/personal-accounts/{id}") public AjaxResult personalDetail(@PathVariable Long id) { return success(personal.detail(id,true)); }
    @GetMapping("/personal-accounts/{id}/records") public AjaxResult personalRecords(@PathVariable Long id,@RequestParam(defaultValue="enrollments") String kind,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="false") boolean pendingOnly) { return success(personal.records(id,true,kind,pageNum,pendingOnly)); }
    @GetMapping("/pending-count") public AjaxResult pending() { return success(personal.pending(true)); }
    @PostMapping("/enrollments/{id}/child") @Log(title="核对历史报名孩子归属",businessType=BusinessType.UPDATE)
    public AjaxResult link(@PathVariable Long id,@RequestBody com.ruoyi.system.service.ParentAccountService.Link body) { personal.link(id,body); return success(); }
    @PutMapping("/accounts/{id}") @Log(title="修改孩子资料",businessType=BusinessType.UPDATE)
    public AjaxResult editChild(@PathVariable Long id,@RequestBody LessonCreditService.AccountDraft body) { credits.editChild(id,body,true); return success(); }
    @DeleteMapping("/accounts/{id}") @Log(title="删除未使用孩子资料",businessType=BusinessType.DELETE)
    public AjaxResult deleteChild(@PathVariable Long id) { credits.deleteChild(id,true); return success(); }
    @GetMapping("/parents") public AjaxResult parents(@RequestParam(defaultValue="") String keyword) { return success(credits.parents(keyword)); }
    @GetMapping("/accounts") public AjaxResult accounts(@RequestParam(defaultValue="") String keyword) { return success(credits.accounts(keyword)); }
    @PostMapping("/accounts") @Log(title="建立学生课次账户",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody LessonCreditService.AccountDraft body) { return success(credits.create(body)); }
    @PostMapping("/accounts/{id}/adjust") @Log(title="课次发放收回",businessType=BusinessType.UPDATE)
    public AjaxResult adjust(@PathVariable Long id,@RequestBody LessonCreditService.Adjustment body) { credits.adjust(id,body); return success(); }
    @GetMapping("/accounts/{id}/history") public AjaxResult history(@PathVariable Long id) { return success(credits.history(id,true)); }
}
