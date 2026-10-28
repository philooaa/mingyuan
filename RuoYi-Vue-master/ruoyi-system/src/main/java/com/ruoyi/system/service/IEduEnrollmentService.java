package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.EduEnrollment;

/**
 * 课程报名Service接口
 * 
 * @author ruoyi
 * @date 2026-08-05
 */
public interface IEduEnrollmentService 
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

    /** 取消报名，保留报名及已出勤历史，只释放一次名额。 */
    public int cancelEduEnrollmentByEnrollmentIds(Long[] enrollmentIds);

    /**
     * 批量删除课程报名
     * 
     * @param enrollmentIds 需要删除的课程报名主键集合
     * @return 结果
     */
    public int deleteEduEnrollmentByEnrollmentIds(Long[] enrollmentIds);

    /**
     * 删除课程报名信息
     * 
     * @param enrollmentId 课程报名主键
     * @return 结果
     */
    public int deleteEduEnrollmentByEnrollmentId(Long enrollmentId);
}
