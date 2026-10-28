package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.EduTeacherSalaryRecord;

/**
 * 教师薪资结算 数据层
 *
 * @author ruoyi
 * @date 2026-09-04
 */
public interface EduTeacherSalaryRecordMapper
{
    public List<EduTeacherSalaryRecord> selectRecordList(EduTeacherSalaryRecord query);

    public EduTeacherSalaryRecord selectRecordById(Long recordId);

    public EduTeacherSalaryRecord selectRecordByTeacherMonth(@Param("teacherId") Long teacherId,
            @Param("salaryMonth") String salaryMonth);

    public int insertRecord(EduTeacherSalaryRecord record);

    public int updateRecord(EduTeacherSalaryRecord record);

    public int deleteRecordByIds(Long[] recordIds);

    /**
     * 全部教师账号（teacher角色）
     */
    public List<Map<String, Object>> selectTeacherUsers();

    /**
     * 某月各教师已签到课次的聚合（按 教师×课次 分组）：
     * 返回 teacherId/scheduleId/classDate/classMode/stage/startTime/endTime/总行数/实到行数
     */
    public List<Map<String, Object>> selectMonthlySessions(@Param("salaryMonth") String salaryMonth);

    /**
     * 寒假 / 春季排课（薪资课时来源，含起止日期与上课模式）
     */
    public List<Map<String, Object>> selectTermSchedules();
}
