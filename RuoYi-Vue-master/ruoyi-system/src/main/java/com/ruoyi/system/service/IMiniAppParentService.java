package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;

/**
 * MiniApp parent service.
 */
public interface IMiniAppParentService
{
    public List<Map<String, Object>> selectAvailableSchedules(String gradeName, String subjectName, String termName, Long accountId);

    public List<Map<String, Object>> selectMyEnrollments();

    public List<Map<String, Object>> selectMyTimetable();

    public List<Map<String, Object>> selectMyAttendanceSummary();

    public List<Map<String, Object>> selectMyAttendanceDetails(String courseClassName);

    public int enroll(MiniAppEnrollmentBody body);

    /**
     * 家长取消自己的报名：逻辑删除报名记录+同步删除考勤记录+释放排课名额。
     * 仅允许取消本人（parentId==当前登录用户）且未删除的报名。
     * @param enrollmentId 报名ID
     * @return 影响行数（成功=1）
     */
    public int cancelEnrollment(Long enrollmentId);
}
