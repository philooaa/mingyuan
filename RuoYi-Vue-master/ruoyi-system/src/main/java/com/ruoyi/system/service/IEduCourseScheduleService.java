package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.EduCourseSchedule;

/**
 * 课程排课Service接口
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
public interface IEduCourseScheduleService 
{
    /**
     * 查询课程排课
     * 
     * @param scheduleId 课程排课主键
     * @return 课程排课
     */
    public EduCourseSchedule selectEduCourseScheduleByScheduleId(Long scheduleId);

    /**
     * 查询课程排课列表
     * 
     * @param eduCourseSchedule 课程排课
     * @return 课程排课集合
     */
    public List<EduCourseSchedule> selectEduCourseScheduleList(EduCourseSchedule eduCourseSchedule);

    /**
     * 数据中实际出现过的「学期-期次」组合（含导入写入的占位值），供课表维度下拉补齐。
     *
     * @return [{termName, periodName}]
     */
    public List<Map<String, Object>> selectTermPeriodPairs();

    /**
     * 新增课程排课
     * 
     * @param eduCourseSchedule 课程排课
     * @return 结果
     */
    public int insertEduCourseSchedule(EduCourseSchedule eduCourseSchedule);

    /**
     * 修改课程排课
     * 
     * @param eduCourseSchedule 课程排课
     * @return 结果
     */
    public int updateEduCourseSchedule(EduCourseSchedule eduCourseSchedule);

    /**
     * 批量删除课程排课
     * 
     * @param scheduleIds 需要删除的课程排课主键集合
     * @return 结果
     */
    public int deleteEduCourseScheduleByScheduleIds(Long[] scheduleIds);

    /**
     * 删除课程排课信息
     *
     * @param scheduleId 课程排课主键
     * @return 结果
     */
    public int deleteEduCourseScheduleByScheduleId(Long scheduleId);

    /**
     * 该排课是否「无任何关联」——没有有效报名，也没有签到、考勤、调课、作业等任何带 schedule_id 的引用，
     * 因而可以安全删除。
     * <p>
     * 供课表导入清理「新课表里已经没有了」的历史格子使用：先问一次能不能删，
     * 能删的才批量删，避免删到一半因报名保护抛异常导致整次导入回滚。
     *
     * @param scheduleId 课程排课主键
     * @return true 表示可以安全删除
     */
    public boolean isDeletable(Long scheduleId);

    /**
     * 可视化课表：网格查询
     */
    public List<EduCourseSchedule> selectTimetableGrid(EduCourseSchedule eduCourseSchedule);

    /**
     * 可视化课表：一次性交换两个教室在指定「年份 + 学期 + 期次」下的全部排课。
     * 只对调 classroom_id，其余字段不动；整列对调不会改变每格课程数量，因此不会产生新冲突。
     *
     * @param courseYear   课程年份（可空）
     * @param termName     学期（必填）
     * @param periodName   期次（必填）
     * @param classroomIdA 教室 A
     * @param classroomIdB 教室 B
     * @return 受影响的排课条数
     */
    public int swapClassrooms(Long courseYear, String termName, String periodName, Long classroomIdA, Long classroomIdB);
}
