package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.EduFinanceDashboardMapper;
import com.ruoyi.system.service.IEduFinanceDashboardService;

/**
 * 财务驾驶舱 服务层实现
 *
 * @author ruoyi
 * @date 2026-10-07
 */
@Service
public class EduFinanceDashboardServiceImpl implements IEduFinanceDashboardService
{
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private EduFinanceDashboardMapper dashboardMapper;

    @Override
    public Map<String, Object> getDashboard(String beginMonth, String endMonth)
    {
        YearMonth end = parseMonth(endMonth, YearMonth.now());
        YearMonth begin = parseMonth(beginMonth, end.minusMonths(11));
        if (begin.isAfter(end))
        {
            YearMonth tmp = begin;
            begin = end;
            end = tmp;
        }
        String beginStr = begin.format(MONTH_FMT);
        String endStr = end.format(MONTH_FMT);

        Map<String, BigDecimal> lessonContract = new LinkedHashMap<>();
        Map<String, BigDecimal> lessonConfirmed = new LinkedHashMap<>();
        for (Map<String, Object> row : dashboardMapper.selectLessonMonthly(beginStr, endStr))
        {
            lessonContract.put((String) row.get("month"), toDecimal(row.get("contractAmount")));
            lessonConfirmed.put((String) row.get("month"), toDecimal(row.get("confirmedAmount")));
        }
        Map<String, BigDecimal> shopContract = new LinkedHashMap<>();
        Map<String, BigDecimal> shopConfirmed = new LinkedHashMap<>();
        for (Map<String, Object> row : dashboardMapper.selectShopMonthly(beginStr, endStr))
        {
            shopContract.put((String) row.get("month"), toDecimal(row.get("contractAmount")));
            shopConfirmed.put((String) row.get("month"), toDecimal(row.get("confirmedAmount")));
        }
        Map<String, BigDecimal> salary = new LinkedHashMap<>();
        for (Map<String, Object> row : dashboardMapper.selectSalaryMonthly(beginStr, endStr))
        {
            salary.put((String) row.get("month"), toDecimal(row.get("salaryAmount")));
        }
        Map<String, BigDecimal> expense = new LinkedHashMap<>();
        for (Map<String, Object> row : dashboardMapper.selectExpenseMonthly(beginStr, endStr))
        {
            expense.put((String) row.get("month"), toDecimal(row.get("expenseAmount")));
        }

        // 逐月合并（缺失月补 0）
        List<Map<String, Object>> monthlyTrend = new ArrayList<>();
        BigDecimal totalContract = BigDecimal.ZERO, totalConfirmed = BigDecimal.ZERO,
                totalSalary = BigDecimal.ZERO, totalExpense = BigDecimal.ZERO;
        for (YearMonth m = begin; !m.isAfter(end); m = m.plusMonths(1))
        {
            String key = m.format(MONTH_FMT);
            BigDecimal contract = lessonContract.getOrDefault(key, BigDecimal.ZERO)
                    .add(shopContract.getOrDefault(key, BigDecimal.ZERO));
            BigDecimal confirmed = lessonConfirmed.getOrDefault(key, BigDecimal.ZERO)
                    .add(shopConfirmed.getOrDefault(key, BigDecimal.ZERO));
            BigDecimal sal = salary.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal exp = expense.getOrDefault(key, BigDecimal.ZERO);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", key);
            item.put("contractAmount", contract);
            item.put("confirmedRevenue", confirmed);
            item.put("receivable", contract.subtract(confirmed));
            item.put("teacherSalary", sal);
            item.put("expenseAmount", exp);
            item.put("grossProfit", confirmed.subtract(sal));
            item.put("netProfit", confirmed.subtract(sal).subtract(exp));
            monthlyTrend.add(item);
            totalContract = totalContract.add(contract);
            totalConfirmed = totalConfirmed.add(confirmed);
            totalSalary = totalSalary.add(sal);
            totalExpense = totalExpense.add(exp);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contractAmount", totalContract);
        summary.put("confirmedRevenue", totalConfirmed);
        summary.put("receivable", totalContract.subtract(totalConfirmed));
        summary.put("teacherSalary", totalSalary);
        summary.put("expenseTotal", totalExpense);
        summary.put("grossProfit", totalConfirmed.subtract(totalSalary));
        summary.put("netProfit", totalConfirmed.subtract(totalSalary).subtract(totalExpense));

        // 收入来源（按已确认收入）
        BigDecimal lessonConfirmedTotal = lessonConfirmed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shopConfirmedTotal = shopConfirmed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> revenueBySource = new ArrayList<>();
        revenueBySource.add(sourceItem("课时费", lessonConfirmedTotal));
        revenueBySource.add(sourceItem("资料商城", shopConfirmedTotal));

        // 成本构成（按类型，label 用字典值，前端翻译）
        List<Map<String, Object>> expenseByType = new ArrayList<>();
        for (Map<String, Object> row : dashboardMapper.selectExpenseByType(beginStr, endStr))
        {
            expenseByType.add(sourceItem((String) row.get("expenseType"), toDecimal(row.get("expenseAmount"))));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("beginMonth", beginStr);
        result.put("endMonth", endStr);
        result.put("summary", summary);
        result.put("monthlyTrend", monthlyTrend);
        result.put("revenueBySource", revenueBySource);
        result.put("expenseByType", expenseByType);
        return result;
    }

    private Map<String, Object> sourceItem(String name, BigDecimal value)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        return item;
    }

    private YearMonth parseMonth(String value, YearMonth fallback)
    {
        if (StringUtils.isBlank(value) || !value.matches("\\d{4}-\\d{2}"))
        {
            return fallback;
        }
        return YearMonth.parse(value, MONTH_FMT);
    }

    private BigDecimal toDecimal(Object value)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }
}
