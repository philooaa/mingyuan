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
    java.util.List<Map<String, Object>> getCourseDimensions();
    Map<String, Object> getStudentCount(Long year, String term, String stage, String grade, String subject, Long teacherId);
    java.util.List<Map<String,Object>> getStudents(Long year, String term, String stage, String grade, String subject, Long teacherId);
    /**
     * 驾驶舱汇总数据
     *
     * @param beginMonth 起始月份 yyyy-MM
     * @param endMonth   结束月份 yyyy-MM
     */
    public Map<String, Object> getDashboard(String beginMonth, String endMonth);
}
