package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.common.core.domain.model.MiniAppSignReviewBody;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;
import com.ruoyi.system.domain.EduClassSignIn;

/**
 * 课次签到 服务接口
 *
 * @author ruoyi
 * @date 2026-08-30
 */
public interface IEduClassSignInService
{
    Map<String, Object> reject(MiniAppSignReviewBody body, Long adminId, String adminName);
    Map<String, Object> correct(MiniAppSignReviewBody body, Long adminId, String adminName);
    /**
     * 教师的排课列表（含最近一次签到汇总）
     */
    public List<EduClassSignIn> selectTeacherSchedules(Long teacherId);

    /**
     * 排课的报名学员列表
     */
    public List<Map<String, Object>> selectEnrolledStudents(Long scheduleId);

    /**
     * 课次签到明细 + 复核信息 + 到课统计
     */
    public Map<String, Object> selectDetail(Long scheduleId, String classDate);

    /**
     * 教师提交/覆盖签到
     *
     * @return 统计信息（totalCount/actualCount/byStatus）
     */
    public Map<String, Object> submitSignIn(MiniAppSignSubmitBody body, String operator);

    /**
     * 管理端：按课次聚合的签到列表
     */
    public List<EduClassSignIn> selectReviewList(String classDate);
    public List<Map<String,Object>> attendanceCourses(String keyword);
    public Map<String,Object> attendanceOverview(Long scheduleId,String from,String to);
    public Map<String,Object> attendanceStudent(Long scheduleId,String studentKey,boolean allCourses,String from,String to,int pageNum);

    /**
     * 教务或管理员单人审核确认；按已查看的点名表版本确认，完成后不覆盖原审核事实。
     *
     * @return 确认信息（confirmedCount/reviewStatus）
     */
    public Map<String, Object> review(MiniAppSignReviewBody body, Long adminId, String adminName);
}
