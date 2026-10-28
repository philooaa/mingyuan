package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.EduTeacherCheckIn;

/**
 * 教师课次WiFi打卡 数据层
 *
 * @author ruoyi
 * @date 2026-10-10
 */
public interface EduTeacherCheckInMapper
{
    /**
     * 某教师某日期的打卡记录（上班/下班）
     */
    public List<EduTeacherCheckIn> selectByTeacherAndDate(@Param("teacherId") Long teacherId,
            @Param("classDate") String classDate);

    /**
     * 某教师某日期某类型是否已打卡
     */
    public int countByTeacherDateType(@Param("teacherId") Long teacherId,
            @Param("classDate") String classDate, @Param("checkType") String checkType);

    /**
     * 某日期全部打卡记录（teacherId 可为空）
     */
    public List<EduTeacherCheckIn> selectByDate(@Param("classDate") String classDate,
            @Param("teacherId") Long teacherId);

    /**
     * 日期区间打卡记录（teacherId 可为空，统计表/明细）
     */
    public List<EduTeacherCheckIn> selectByRange(@Param("start") String start, @Param("end") String end,
            @Param("teacherId") Long teacherId);

    /**
     * 新增打卡记录
     */
    public int insert(EduTeacherCheckIn record);

    /**
     * 修改打卡记录（只更新非空字段）
     */
    public int update(EduTeacherCheckIn record);

    /**
     * 删除打卡记录
     */
    public int deleteById(@Param("checkInId") Long checkInId);
}
