package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.EduClassSignIn;

/**
 * 课次签到 数据层
 *
 * @author ruoyi
 * @date 2026-08-30
 */
public interface EduClassSignInMapper
{
    /**
     * 教师的排课列表（含最近一次签到汇总）
     */
    public List<EduClassSignIn> selectScheduleListForTeacher(@Param("teacherId") Long teacherId);

    /**
     * 管理端：按课次聚合的签到列表（可选日期过滤）
     */
    public List<EduClassSignIn> selectReviewList(@Param("classDate") String classDate);

    /**
     * 某课次的签到明细行
     */
    public List<EduClassSignIn> selectDetailList(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate);

    /**
     * 某课次的复核信息（取任一行冗余列）
     */
    public Map<String, Object> selectReviewInfo(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate);

    /**
     * 某课次签到行数
     */
    public int countByScheduleAndDate(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate);

    /**
     * 排课的报名学员（报名成功状态）
     */
    public List<Map<String, Object>> selectEnrolledStudents(@Param("scheduleId") Long scheduleId);

    /**
     * 删除某课次旧签到明细（覆盖式提交）
     */
    public int deleteByScheduleAndDate(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate);

    /**
     * 批量插入签到明细
     */
    public int batchInsert(@Param("list") List<EduClassSignIn> list);

    /**
     * 写入第1位管理员确认（仅当未写入时）
     */
    public int updateReview1(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate,
            @Param("adminId") Long adminId, @Param("adminName") String adminName, @Param("images") String images);

    /** Archive an old partial review while preserving its original confirmation. */
    public int completeLegacyReview(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate,
            @Param("adminId") Long adminId, @Param("adminName") String adminName, @Param("images") String images);

    /**
     * 写入第2位管理员确认并置复核完成（仅当未写入时）
     */
    public int updateReview2(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate,
            @Param("adminId") Long adminId, @Param("adminName") String adminName, @Param("images") String images);

    /**
     * 更新第1位管理员图片（本人已确认时）
     */
    public int updateReview1Images(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate,
            @Param("adminId") Long adminId, @Param("adminName") String adminName, @Param("images") String images);

    /**
     * 更新第2位管理员图片（本人已确认时）
     */
    public int updateReview2Images(@Param("scheduleId") Long scheduleId, @Param("classDate") String classDate,
            @Param("adminId") Long adminId, @Param("adminName") String adminName, @Param("images") String images);
}
