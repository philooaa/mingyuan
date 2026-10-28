package com.ruoyi.system.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.ReferralService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/parent/referral")
@PreAuthorize("@ss.hasRole('parent')")
public class MiniAppReferralController {
  private final ReferralService referrals;
  public MiniAppReferralController(ReferralService referrals){this.referrals=referrals;}
  @GetMapping public AjaxResult home(){return EduTuitionController.ok(referrals.home(SecurityUtils.getUserId()));}
  @GetMapping("/records") public TableDataInfo records(@RequestParam Map<String,String> q){return EduTuitionController.table(referrals.list(SecurityUtils.getUserId(),false,q));}
  @PostMapping("/preview") public AjaxResult preview(@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.previewCode(SecurityUtils.getUserId(),String.valueOf(b.getOrDefault("code",""))));}
  @PostMapping("/bind") public AjaxResult bind(@RequestBody Map<String,Object> b){return EduTuitionController.ok(referrals.bind(SecurityUtils.getUserId(),b));}
}
