package com.ruoyi.system.controller;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.*;

@RestController
@RequestMapping("/miniapp/parent/lesson-packages")
@PreAuthorize("@ss.hasRole('parent')")
public class CreditPackageController {
  private final CreditPackageService packages;private final TuitionService tuition;
  public CreditPackageController(CreditPackageService packages,TuitionService tuition){this.packages=packages;this.tuition=tuition;}
  @GetMapping("/catalog") public AjaxResult catalog(){return AjaxResult.success(packages.catalog());}
  @PostMapping("/quote") public AjaxResult quote(@RequestBody Map<String,Object> b){return AjaxResult.success(packages.quote(b));}
  @PostMapping("/orders") public AjaxResult create(@RequestBody Map<String,Object> b){long id=packages.create(b);return AjaxResult.success(tuition.bill(id,SecurityUtils.getUserId(),false));}
  @PostMapping("/orders/{id}/cancel") public AjaxResult cancel(@PathVariable long id,@RequestBody Map<String,Object> b){packages.cancel(id,b.get("accountId")==null?null:FinanceRules.id(b.get("accountId")));return AjaxResult.success(tuition.bill(id,SecurityUtils.getUserId(),false));}
}
