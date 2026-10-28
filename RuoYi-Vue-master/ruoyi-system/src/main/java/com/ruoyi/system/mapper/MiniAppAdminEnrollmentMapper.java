package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 管理员报名视图；报名变更统一使用现有报名服务。 */
public interface MiniAppAdminEnrollmentMapper
{
    List<Map<String, Object>> selectTeacherSchedules(@Param("teacherId") Long teacherId, @Param("scheduleId") Long scheduleId);
    List<Map<String, Object>> selectTeacherHistory(@Param("enrollmentId") Long enrollmentId, @Param("teacherId") Long teacherId);
    List<String> selectGrades();
    List<Map<String, Object>> selectSchedules(@Param("gradeName") String gradeName);
    Map<String, Object> selectSchedule(@Param("scheduleId") Long scheduleId);
    List<Map<String, Object>> selectHistory(@Param("enrollmentId") Long enrollmentId);
}
