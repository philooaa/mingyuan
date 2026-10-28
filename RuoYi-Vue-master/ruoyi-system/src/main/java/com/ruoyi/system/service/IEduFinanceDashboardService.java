package com.ruoyi.system.service;

import java.util.Map;

/**
 * 财务驾驶舱 服务层
 *
 * @author ruoyi
 * @date 2026-10-07
 */
public interface IEduFinanceDashboardService
{
    /**
     * 驾驶舱汇总数据
     *
     * @param beginMonth 起始月份 yyyy-MM
     * @param endMonth   结束月份 yyyy-MM
     */
    public Map<String, Object> getDashboard(String beginMonth, String endMonth);
}
