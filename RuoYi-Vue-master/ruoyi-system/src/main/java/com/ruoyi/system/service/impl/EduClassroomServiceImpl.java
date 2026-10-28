package com.ruoyi.system.service.impl;

import java.util.List;
import java.util.Map;
import com.ruoyi.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.EduClassroomMapper;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.service.IEduClassroomService;

/**
 * 教室信息Service业务层处理
 *
 * @author ruoyi
 * @date 2026-08-01
 */
@Service
public class EduClassroomServiceImpl implements IEduClassroomService
{
    @Autowired
    private EduClassroomMapper eduClassroomMapper;
    @Autowired private com.ruoyi.system.shop.ShopRepository db;
    @Autowired private com.ruoyi.system.mapper.EduCourseScheduleMapper schedules;
    @Autowired private com.ruoyi.system.service.ScheduleAvailabilityService availability;

    /**
     * 查询教室信息
     *
     * @param classroomId 教室信息主键
     * @return 教室信息
     */
    @Override
    public EduClassroom selectEduClassroomByClassroomId(Long classroomId)
    {
        return eduClassroomMapper.selectEduClassroomByClassroomId(classroomId);
    }

    /**
     * 查询教室信息列表
     *
     * @param eduClassroom 教室信息
     * @return 教室信息
     */
    @Override
    public List<EduClassroom> selectEduClassroomList(EduClassroom eduClassroom)
    {
        return eduClassroomMapper.selectEduClassroomList(eduClassroom);
    }

    /**
     * 校区分类统计（教室管理页的校区分类用）
     *
     * @return [{campusName, roomCount}]
     */
    @Override
    public List<Map<String, Object>> selectCampusStats()
    {
        return eduClassroomMapper.selectCampusStats();
    }

    /**
     * 新增教室信息
     *
     * @param eduClassroom 教室信息
     * @return 结果
     */
    @Override
    public int insertEduClassroom(EduClassroom eduClassroom)
    {
        eduClassroom.setCreateTime(DateUtils.getNowDate());
        return eduClassroomMapper.insertEduClassroom(eduClassroom);
    }

    /**
     * 修改教室信息
     *
     * @param eduClassroom 教室信息
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public int updateEduClassroom(EduClassroom eduClassroom)
    {
        db.one("SELECT classroom_id FROM edu_classroom WHERE classroom_id=? FOR UPDATE",eduClassroom.getClassroomId());
        if ("1".equals(eduClassroom.getStatus())) requireNoFuture(eduClassroom.getClassroomId());
        // 教室容量（capacity）已随「教室精简」从 edu_classroom 移除，报名名额不限，故不再做容量校验。
        eduClassroom.setUpdateTime(DateUtils.getNowDate());
        return eduClassroomMapper.updateEduClassroom(eduClassroom);
    }

    /**
     * 批量删除教室信息
     *
     * @param classroomIds 需要删除的教室信息主键
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public int deleteEduClassroomByClassroomIds(Long[] classroomIds)
    {
        for (Long id : java.util.Arrays.stream(classroomIds).distinct().sorted().toList()) {
            db.one("SELECT classroom_id FROM edu_classroom WHERE classroom_id=? FOR UPDATE",id);
            requireNoFuture(id);
            if (!db.rows("SELECT schedule_id FROM edu_course_schedule WHERE classroom_id=? LIMIT 1",id).isEmpty()) throw new com.ruoyi.common.exception.ServiceException("教室已有排课引用，请保留历史并使用停用");
        }
        return eduClassroomMapper.deleteEduClassroomByClassroomIds(classroomIds);
    }

    /**
     * 删除教室信息信息
     *
     * @param classroomId 教室信息主键
     * @return 结果
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public int deleteEduClassroomByClassroomId(Long classroomId)
    {
        return deleteEduClassroomByClassroomIds(new Long[]{classroomId});
    }
    private void requireNoFuture(Long id) {
        var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (var row : db.rows("SELECT schedule_id FROM edu_course_schedule WHERE classroom_id=? AND del_flag='0' AND status='0' FOR UPDATE",id)) {
            var s = schedules.selectEduCourseScheduleByScheduleId(Long.valueOf(row.get("scheduleId").toString()));
            if (availability.dates(s,availability.adjustments(s.getScheduleId(),true)).stream().anyMatch(date -> s.getEndTime() == null ? !date.isBefore(now.toLocalDate()) : java.time.LocalDateTime.of(date,s.getEndTime()).isAfter(now)))
                throw new com.ruoyi.common.exception.ServiceException("教室仍有未来课程，请先处理排课再停用或删除");
        }
    }
}
