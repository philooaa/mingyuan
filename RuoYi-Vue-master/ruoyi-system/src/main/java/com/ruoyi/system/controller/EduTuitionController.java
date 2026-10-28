package com.ruoyi.system.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.*;
import com.ruoyi.system.shop.ShopExport;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/tuition")
public class EduTuitionController {
  private final TuitionService tuition;

  public EduTuitionController(TuitionService tuition) {
    this.tuition = tuition;
  }

  public static AjaxResult ok(Map<String, Object> data) {
    return AjaxResult.success(FinanceRules.dto(data));
  }

  public static TableDataInfo table(Map<String, Object> page) {
    page = FinanceRules.dto(page);
    var result =
        new TableDataInfo((List<?>) page.get("rows"), ((Number) page.get("total")).longValue());
    result.setCode(200);
    result.setMsg("查询成功");
    return result;
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:query')")
  @GetMapping("/{id}/refund-targets")
  public AjaxResult refundTargets(@PathVariable long id) {
    return ok(tuition.refundTargets(id, SecurityUtils.getUserId(), true));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:list')")
  @GetMapping("/list")
  public TableDataInfo list(@RequestParam Map<String, String> q) {
    return table(tuition.listBills(SecurityUtils.getUserId(), true, q));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:list')")
  @GetMapping("/summary")
  public AjaxResult summary(@RequestParam Map<String, String> q) {
    return EduTuitionController.ok(tuition.summary(SecurityUtils.getUserId(), q));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:query')")
  @GetMapping("/{id}")
  public AjaxResult detail(@PathVariable long id) {
    return EduTuitionController.ok(tuition.bill(id, SecurityUtils.getUserId(), true));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:price')")
  @Log(
      title = "学费核价",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PutMapping("/{id}/price")
  public AjaxResult price(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.price(id, SecurityUtils.getUserId(), b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:query')")
  @PostMapping("/{id}/quote")
  public AjaxResult quote(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.quote(id, SecurityUtils.getUserId(), true, b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:historyVerify')")
  @Log(
      title = "历史学费核验",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/{id}/history-verify")
  public AjaxResult history(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.historyVerify(id, SecurityUtils.getUserId(), b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:collect')")
  @Log(
      title = "学费收款登记",
      businessType = BusinessType.INSERT,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/{id}/payments")
  public AjaxResult payment(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.createPayment(id, SecurityUtils.getUserId(), true, b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:confirm')")
  @Log(
      title = "学费收款确认",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/payments/{id}/confirm")
  public AjaxResult confirm(
      @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
    return EduTuitionController.ok(
        tuition.confirmPayment(id, SecurityUtils.getUserId(), true, b == null ? Map.of() : b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:collect')")
  @Log(
      title = "关闭学费收款",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/payments/{id}/close")
  public AjaxResult close(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.closePayment(id, SecurityUtils.getUserId(), b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:collect')")
  @Log(
      title = "安全取消报名",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/{id}/cancel")
  public AjaxResult cancel(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(
        tuition.cancelEnrollment(
            id, SecurityUtils.getUserId(), true, FinanceRules.reason(b, "reason", 255)));
  }

  @PreAuthorize("@ss.hasPermi('system:tuition:export')")
  @Log(
      title = "课程收费台账",
      businessType = BusinessType.EXPORT,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/export")
  public void export(@RequestParam Map<String, String> q, HttpServletResponse response)
      throws Exception {
    ShopExport.write(
        response,
        "课程收费台账",
        ShopExport.collect(q, p -> tuition.listBills(SecurityUtils.getUserId(), true, p)),
        List.of(
            "enrollmentCode",
            "studentName",
            "parentName",
            "courseClassName",
            "campusName",
            "financeMode",
            "billingStatus",
            "payStatus",
            "originalAmount",
            "discountAmount",
            "payableAmount",
            "receivedAmount",
            "refundedAmount",
            "netAmount",
            "dueAmount",
            "refundableAmount"));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:list')")
  @GetMapping("/refunds/list")
  public TableDataInfo refunds(@RequestParam Map<String, String> q) {
    return table(tuition.listRefunds(SecurityUtils.getUserId(), true, q));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:query')")
  @GetMapping("/refunds/{id}")
  public AjaxResult refund(@PathVariable long id) {
    return EduTuitionController.ok(tuition.refund(id, SecurityUtils.getUserId(), true));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:apply')")
  @Log(
      title = "学费退款申请",
      businessType = BusinessType.INSERT,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/refunds")
  public AjaxResult apply(@RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.applyRefund(SecurityUtils.getUserId(), true, b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:review')")
  @Log(
      title = "学费退款审核",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/refunds/{id}/review")
  public AjaxResult review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.reviewRefund(id, SecurityUtils.getUserId(), b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:apply')")
  @Log(
      title = "学费退款申请撤回",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/refunds/{id}/cancel")
  public AjaxResult cancelRefund(@PathVariable long id) {
    return EduTuitionController.ok(tuition.cancelRefund(id, SecurityUtils.getUserId(), true));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:execute')")
  @Log(
      title = "学费退款执行",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/refunds/{id}/execute")
  public AjaxResult execute(@PathVariable long id) {
    return EduTuitionController.ok(tuition.executeRefund(id, SecurityUtils.getUserId()));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:markRefunded')")
  @Log(
      title = "学费已退款登记",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/refunds/{id}/mark-refunded")
  public AjaxResult markRefunded(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.markRefunded(id, SecurityUtils.getUserId(), b));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionRefund:confirm')")
  @Log(
      title = "学费退款确认",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/refunds/{id}/confirm")
  public AjaxResult confirmRefund(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(tuition.confirmRefund(id, SecurityUtils.getUserId(), b));
  }
}
