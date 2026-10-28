package com.ruoyi.system.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.CouponService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/coupon")
public class EduCouponController {
  private final CouponService coupons;

  public EduCouponController(CouponService coupons) {
    this.coupons = coupons;
  }

  @PreAuthorize("@ss.hasPermi('system:coupon:list')")
  @GetMapping("/templates")
  public TableDataInfo list(@RequestParam Map<String, String> q) {
    return EduTuitionController.table(coupons.listTemplates(q));
  }
  @PreAuthorize("@ss.hasPermi('system:coupon:list')")
  @GetMapping("/options") public AjaxResult options(){return EduTuitionController.ok(coupons.options(SecurityUtils.getUserId()));}

  @PreAuthorize("@ss.hasPermi('system:coupon:add')")
  @Log(
      title = "优惠券模板",
      businessType = BusinessType.INSERT,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/templates")
  public AjaxResult add(@RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(coupons.saveTemplate(SecurityUtils.getUserId(), null, b));
  }

  @PreAuthorize("@ss.hasPermi('system:coupon:edit')")
  @Log(
      title = "优惠券模板",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PutMapping("/templates/{id}")
  public AjaxResult edit(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(coupons.saveTemplate(SecurityUtils.getUserId(), id, b));
  }

  @PreAuthorize("@ss.hasPermi('system:coupon:grant')")
  @Log(
      title = "优惠券批量发放",
      businessType = BusinessType.INSERT,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/templates/{id}/grant")
  public AjaxResult grant(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(coupons.issue(id, SecurityUtils.getUserId(), b));
  }

  @PreAuthorize("@ss.hasPermi('system:coupon:query')")
  @GetMapping("/grants")
  public TableDataInfo grants(@RequestParam Map<String, String> q) {
    return EduTuitionController.table(coupons.grants(SecurityUtils.getUserId(), true, q));
  }

  @PreAuthorize("@ss.hasPermi('system:coupon:grant')")
  @GetMapping("/parents")
  public TableDataInfo parents(@RequestParam Map<String, String> q) {
    return EduTuitionController.table(coupons.parents(SecurityUtils.getUserId(), q));
  }

  @PreAuthorize("@ss.hasPermi('system:coupon:revoke')")
  @Log(
      title = "优惠券撤回",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/grants/{id}/revoke")
  public AjaxResult revoke(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return EduTuitionController.ok(coupons.revoke(id, SecurityUtils.getUserId(), b));
  }
}
