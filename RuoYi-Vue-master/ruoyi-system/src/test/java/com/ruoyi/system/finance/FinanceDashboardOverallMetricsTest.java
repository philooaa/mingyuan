package com.ruoyi.system.finance;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.mapper.EduFinanceDashboardMapper;
import com.ruoyi.system.service.impl.EduFinanceDashboardServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

class FinanceDashboardOverallMetricsTest {
    @Test void refundIncomeAndGrossUseTheExistingOverallMonthlyData() throws Exception {
        var mapper=(EduFinanceDashboardMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EduFinanceDashboardMapper.class},(p,m,a)->switch(m.getName()) {
            case "selectLessonMonthly" -> List.of(Map.of("month","2026-01","contractAmount","100","confirmedAmount","80","refundAmount","20"));
            case "selectShopMonthly" -> List.of(Map.of("month","2026-01","contractAmount","50","confirmedAmount","40","refundAmount","10"));
            case "selectSalaryMonthly" -> List.of(Map.of("month","2026-01","salaryAmount","40"));
            default -> List.of();
        });
        var service=new EduFinanceDashboardServiceImpl();var field=service.getClass().getDeclaredField("dashboardMapper");field.setAccessible(true);field.set(service,mapper);
        @SuppressWarnings("unchecked") var summary=(Map<String,Object>)service.getDashboard("2026-01","2026-01").get("summary");
        assertEquals(0,new BigDecimal("120").compareTo((BigDecimal)summary.get("confirmedRevenue")));
        assertEquals(0,new BigDecimal("30").compareTo((BigDecimal)summary.get("refundTotal")));
        assertEquals(0,new BigDecimal("80").compareTo((BigDecimal)summary.get("grossProfit")));
    }
}
