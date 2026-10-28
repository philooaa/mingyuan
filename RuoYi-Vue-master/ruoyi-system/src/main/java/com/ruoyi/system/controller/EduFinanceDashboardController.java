package com.ruoyi.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.IEduFinanceDashboardService;

/**
 * 财务驾驶舱Controller
 *
 * @author ruoyi
 * @date 2026-10-07
 */
@RestController
@RequestMapping("/system/finance")
public class EduFinanceDashboardController extends BaseController
{
    @Autowired
    private IEduFinanceDashboardService dashboardService;

    /**
     * 驾驶舱汇总（合同金额/已确认收入/欠款/老师工资/毛利/净利润/成本支出 + 图表数据）
     */
    @PreAuthorize("@ss.hasPermi('system:finance:list')")
    @GetMapping("/dashboard")
    public AjaxResult dashboard(@RequestParam(value = "beginMonth", required = false) String beginMonth,
            @RequestParam(value = "endMonth", required = false) String endMonth)
    {
        return success(dashboardService.getDashboard(beginMonth, endMonth));
    }
}
