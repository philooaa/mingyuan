package com.ruoyi.system.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.ReferralService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/referral")
@PreAuthorize("@ss.hasPermi('edu:referral:manage')")
public class EduReferralController {
  private final ReferralService referrals;
  public EduReferralController(ReferralService referrals){this.referrals=referrals;}
  @GetMapping public TableDataInfo list(@RequestParam Map<String,String> q){return EduTuitionController.table(referrals.list(SecurityUtils.getUserId(),true,q));}
  @GetMapping("/counts") public AjaxResult counts(){return EduTuitionController.ok(referrals.counts(SecurityUtils.getUserId()));}
  @PostMapping("/bindings/{id}/correct") @Log(title="推荐关系纠错",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
  public AjaxResult correct(@PathVariable long id,@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.correct(SecurityUtils.getUserId(),id,b));}
  @PostMapping("/preview") public AjaxResult preview(@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.previewReward(SecurityUtils.getUserId(),b));}
  @PostMapping("/grant") @Log(title="推荐奖励发券",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
  public AjaxResult grant(@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.grant(SecurityUtils.getUserId(),b));}
  @PostMapping("/rewards/{id}/revoke") @Log(title="推荐奖励撤回",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
  public AjaxResult revoke(@PathVariable long id,@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.revoke(SecurityUtils.getUserId(),id,b));}
  @PostMapping("/anomalies/{id}/resolve") @Log(title="推荐异常核查",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
  public AjaxResult resolve(@PathVariable long id,@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.resolve(SecurityUtils.getUserId(),id,b));}
}
