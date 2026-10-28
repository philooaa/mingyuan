package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.EduClassroom;

/**
 * 教室信息Mapper接口
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
public interface EduClassroomMapper 
{
    /**
     * 查询教室信息
     * 
     * @param classroomId 教室信息主键
     * @return 教室信息
     */
    public EduClassroom selectEduClassroomByClassroomId(Long classroomId);

    /**
     * 查询教室信息列表
     * 
     * @param eduClassroom 教室信息
     * @return 教室信息集合
     */
    public List<EduClassroom> selectEduClassroomList(EduClassroom eduClassroom);

    /**
     * 校区分类统计（教室管理页的校区分类用，不受分页影响）
     *
     * @return [{campusName, roomCount}]
     */
    public List<Map<String, Object>> selectCampusStats();

    /**
     * 新增教室信息
     * 
     * @param eduClassroom 教室信息
     * @return 结果
     */
    public int insertEduClassroom(EduClassroom eduClassroom);

    /**
     * 修改教室信息
     * 
     * @param eduClassroom 教室信息
     * @return 结果
     */
    public int updateEduClassroom(EduClassroom eduClassroom);

    /**
     * 删除教室信息
     * 
     * @param classroomId 教室信息主键
     * @return 结果
     */
    public int deleteEduClassroomByClassroomId(Long classroomId);

    /**
     * 批量删除教室信息
     * 
     * @param classroomIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteEduClassroomByClassroomIds(Long[] classroomIds);
}
