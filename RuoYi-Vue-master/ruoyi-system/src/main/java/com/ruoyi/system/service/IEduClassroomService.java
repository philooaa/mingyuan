package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.EduClassroom;

/**
 * 教室信息Service接口
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
public interface IEduClassroomService 
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
     * 校区分类统计（教室管理页的校区分类用）
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
     * 批量删除教室信息
     * 
     * @param classroomIds 需要删除的教室信息主键集合
     * @return 结果
     */
    public int deleteEduClassroomByClassroomIds(Long[] classroomIds);

    /**
     * 删除教室信息信息
     * 
     * @param classroomId 教室信息主键
     * @return 结果
     */
    public int deleteEduClassroomByClassroomId(Long classroomId);
}
