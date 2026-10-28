package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.EduCourseSchedule;

/**
 * 课程排课Mapper接口
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
public interface EduCourseScheduleMapper 
{
    /**
     * 查询课程排课
     * 
     * @param scheduleId 课程排课主键
     * @return 课程排课
     */
    public EduCourseSchedule selectEduCourseScheduleByScheduleId(Long scheduleId);

    EduCourseSchedule lockSchedule(Long scheduleId);

    /**
     * 查询课程排课列表
     * 
     * @param eduCourseSchedule 课程排课
     * @return 课程排课集合
     */
    public List<EduCourseSchedule> selectEduCourseScheduleList(EduCourseSchedule eduCourseSchedule);

    /**
     * 数据中实际出现过的「学期-期次」组合（去重，仅取两列）。
     * 用于课表维度下拉：导入时未识别学期/期次会写入占位值（未识别学期/未识别期次），
     * 这些值不在字典里，需要从数据里补齐，否则相关排课在课表上无法查看。
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
     * 删除课程排课
     * 
     * @param scheduleId 课程排课主键
     * @return 结果
     */
    public int deleteEduCourseScheduleByScheduleId(Long scheduleId);

    /**
     * 批量删除课程排课
     *
     * @param scheduleIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteEduCourseScheduleByScheduleIds(Long[] scheduleIds);

    /**
     * 可视化课表：按 (classroomId + timeSlot) 匹配某学期期次的所有排课
     * 用于网格渲染：先取 年+学期+期次+可选筛选 的所有正常排课
     *
     * @param eduCourseSchedule 查询条件（courseYear/termName/periodName 必带，其余 campus/subject/grade/teacher 可选）
     * @return 排课集合
     */
    public List<EduCourseSchedule> selectTimetableGrid(EduCourseSchedule eduCourseSchedule);

    /**
     * 可视化课表：一次性交换两个教室在指定「年份 + 学期 + 期次」下的全部排课。
     * 只把 classroom_id 在 A / B 之间对调，其余字段（时段、教师、课程等）一律不动；
     * 因两边都是整列对调，同一时段格子的课程数量不变，不会凭空产生冲突。
     *
     * @param courseYear   课程年份（可为空，为空则不按年份过滤）
     * @param termName     学期（必填）
     * @param periodName   期次（必填）
     * @param classroomIdA 教室 A 主键
     * @param classroomIdB 教室 B 主键
     * @param updateBy     操作人（写入 update_by，便于审计）
     * @return 受影响行数
     */
    public int swapClassroom(@Param("courseYear") Long courseYear,
                             @Param("termName") String termName,
                             @Param("periodName") String periodName,
                             @Param("classroomIdA") Long classroomIdA,
                             @Param("classroomIdB") Long classroomIdB,
                             @Param("updateBy") String updateBy);
}
