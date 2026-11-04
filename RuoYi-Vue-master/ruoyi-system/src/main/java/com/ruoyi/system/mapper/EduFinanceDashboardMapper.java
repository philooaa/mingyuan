package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 财务驾驶舱统计 数据层（只读聚合）
 *
 * @author ruoyi
 * @date 2026-10-07
 */
public interface EduFinanceDashboardMapper
{
    List<Map<String, Object>> selectCourseDimensions();
    Map<String, Object> selectStudentCount(@Param("year") Long year, @Param("term") String term,
        @Param("stage") String stage, @Param("grade") String grade, @Param("subject") String subject, @Param("teacherId") Long teacherId);
    List<Map<String,Object>> selectStudents(@Param("year") Long year, @Param("term") String term,
        @Param("stage") String stage, @Param("grade") String grade, @Param("subject") String subject, @Param("teacherId") Long teacherId);
    /**
     * 课时费按月：month / contractAmount / confirmedAmount
     * 兼容历史状态值：enrollment_status 取消='2'/'已取消'，pay_status 已支付='1'/'已支付'
     */
    public List<Map<String, Object>> selectLessonMonthly(@Param("begin") String begin, @Param("end") String end);

    /**
     * 资料商城按月：month / contractAmount / confirmedAmount
     */
    public List<Map<String, Object>> selectShopMonthly(@Param("begin") String begin, @Param("end") String end);

    /**
     * 教师工资按月：month / salaryAmount
     */
    public List<Map<String, Object>> selectSalaryMonthly(@Param("begin") String begin, @Param("end") String end);

    /**
     * 成本支出按月：month / expenseAmount
     */
    public List<Map<String, Object>> selectExpenseMonthly(@Param("begin") String begin, @Param("end") String end);

    /**
     * 成本支出按类型：expenseType / expenseAmount
     */
    public List<Map<String, Object>> selectExpenseByType(@Param("begin") String begin, @Param("end") String end);
}
