package com.ruoyi.system.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/parent")
@PreAuthorize("@ss.hasRole('parent')")
public class MiniAppTuitionController {
  private final TuitionService tuition;
  private final CouponService coupons;

  public MiniAppTuitionController(TuitionService tuition, CouponService coupons) {
    this.tuition = tuition;
    this.coupons = coupons;
  }

  @GetMapping("/tuition")
  public TableDataInfo bills(@RequestParam Map<String, String> q) {
    return EduTuitionController.table(tuition.listBills(SecurityUtils.getUserId(), false, q));
  }

  @GetMapping("/tuition/{id}")
  public AjaxResult detail(@PathVariable long id,@RequestParam(required=false) Long accountId) {
    child(id,accountId);
    return EduTuitionController.ok(tuition.bill(id, SecurityUtils.getUserId(), false));
  }

  @GetMapping("/tuition/{id}/refund-targets")
  public AjaxResult refundTargets(@PathVariable long id,@RequestParam(required=false) Long accountId) {
    child(id,accountId);
    return EduTuitionController.ok(tuition.refundTargets(id, SecurityUtils.getUserId(), false));
  }

  @PostMapping("/tuition/{id}/quote")
  public AjaxResult quote(
      @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
    child(id,b);
    return EduTuitionController.ok(
        tuition.quote(id, SecurityUtils.getUserId(), false, b == null ? Map.of() : b));
  }

  @PostMapping("/tuition/{id}/payments")
  public AjaxResult payment(@PathVariable long id, @RequestBody Map<String, Object> b) {
    child(id,b);
    return EduTuitionController.ok(tuition.createPayment(id, SecurityUtils.getUserId(), false, b));
  }

  @GetMapping("/tuition/payments/{id}")
  public AjaxResult payment(@PathVariable long id) {
    return EduTuitionController.ok(tuition.payment(id, SecurityUtils.getUserId(), false));
  }

  @PostMapping("/tuition/payments/{id}/mock-confirm")
  public AjaxResult confirm(@PathVariable long id) {
    return EduTuitionController.ok(
        tuition.confirmPayment(id, SecurityUtils.getUserId(), false, Map.of()));
  }

  @PostMapping("/tuition/refunds")
  public AjaxResult apply(@RequestBody Map<String, Object> b) {
    if(b.get("accountId")!=null){var p=tuition.payment(FinanceRules.id(b.get("paymentId")),SecurityUtils.getUserId(),false);child(FinanceRules.id(p.get("enrollmentId")),b);}
    return EduTuitionController.ok(tuition.applyRefund(SecurityUtils.getUserId(), false, b));
  }

  @GetMapping("/tuition/refunds")
  public TableDataInfo refunds(@RequestParam Map<String, String> q) {
    return EduTuitionController.table(tuition.listRefunds(SecurityUtils.getUserId(), false, q));
  }

  @GetMapping("/tuition/refunds/{id}")
  public AjaxResult refund(@PathVariable long id) {
    return EduTuitionController.ok(tuition.refund(id, SecurityUtils.getUserId(), false));
  }

  @PostMapping("/tuition/refunds/{id}/cancel")
  public AjaxResult cancel(@PathVariable long id) {
    return EduTuitionController.ok(tuition.cancelRefund(id, SecurityUtils.getUserId()));
  }

  @GetMapping("/coupons")
  public TableDataInfo coupons(@RequestParam Map<String, String> q) {
    return EduTuitionController.table(coupons.myCoupons(SecurityUtils.getUserId(), q));
  }
  private void child(long enrollment,Map<String,Object> body){if(body!=null && body.get("accountId")!=null)child(enrollment,FinanceRules.id(body.get("accountId")));}
  private void child(long enrollment,Long account){if(account!=null)tuition.checkChild(enrollment,SecurityUtils.getUserId(),account);}
}
