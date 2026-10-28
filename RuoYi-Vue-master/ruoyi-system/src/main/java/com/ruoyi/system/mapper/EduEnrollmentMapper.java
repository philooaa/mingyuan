package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.EduEnrollment;

/**
 * 课程报名Mapper接口
 * 
 * @author ruoyi
 * @date 2026-08-05
 */
public interface EduEnrollmentMapper 
{
    /**
     * 查询课程报名
     * 
     * @param enrollmentId 课程报名主键
     * @return 课程报名
     */
    public EduEnrollment selectEduEnrollmentByEnrollmentId(Long enrollmentId);

    /**
     * 查询课程报名列表
     * 
     * @param eduEnrollment 课程报名
     * @return 课程报名集合
     */
    public List<EduEnrollment> selectEduEnrollmentList(EduEnrollment eduEnrollment);

    /**
     * 新增课程报名
     * 
     * @param eduEnrollment 课程报名
     * @return 结果
     */
    public int insertEduEnrollment(EduEnrollment eduEnrollment);

    /**
     * 修改课程报名
     * 
     * @param eduEnrollment 课程报名
     * @return 结果
     */
    public int updateEduEnrollment(EduEnrollment eduEnrollment);

    /** Compatibility for existing single-lesson attendance invalidation. */
    public int invalidateUnusedAttendance(@org.apache.ibatis.annotations.Param("enrollmentId") Long enrollmentId, @org.apache.ibatis.annotations.Param("updateBy") String updateBy);

    // Cancellation is handled transactionally by TuitionService; no physical DELETE mapper.
}
