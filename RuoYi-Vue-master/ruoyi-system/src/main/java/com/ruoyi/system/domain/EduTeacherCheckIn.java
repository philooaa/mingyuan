package com.ruoyi.system.domain;

import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 教师课次WiFi打卡记录对象 edu_teacher_check_in
 *
 * @author ruoyi
 * @date 2026-10-10
 */
public class EduTeacherCheckIn extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 打卡ID */
    private Long checkInId;

    /** 排课ID */
    private Long scheduleId;

    /** 打卡日期 */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date classDate;

    /** 打卡类型 in上班 out下班 */
    private String checkType;

    /** 教师用户ID */
    private Long teacherId;

    /** 教师姓名 */
    private String teacherName;

    /** 打卡时所在校区 */
    private String campusName;

    /** 教室名 */
    private String classroomName;

    /** 打卡时连接的WiFi */
    private String ssid;

    /** 打卡时连接的WiFi路由器MAC */
    private String bssid;

    /** 打卡时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date checkInTime;

    // ============ 关联展示字段（非表字段） ============

    /** 课程班级名 */
    private String courseClassName;

    /** 上课时间段 */
    private String timeSlot;

    public void setCheckInId(Long checkInId)
    {
        this.checkInId = checkInId;
    }

    public Long getCheckInId()
    {
        return checkInId;
    }

    public void setScheduleId(Long scheduleId)
    {
        this.scheduleId = scheduleId;
    }

    public Long getScheduleId()
    {
        return scheduleId;
    }

    public void setClassDate(Date classDate)
    {
        this.classDate = classDate;
    }

    public Date getClassDate()
    {
        return classDate;
    }

    public void setCheckType(String checkType)
    {
        this.checkType = checkType;
    }

    public String getCheckType()
    {
        return checkType;
    }

    public void setTeacherId(Long teacherId)
    {
        this.teacherId = teacherId;
    }

    public Long getTeacherId()
    {
        return teacherId;
    }

    public void setTeacherName(String teacherName)
    {
        this.teacherName = teacherName;
    }

    public String getTeacherName()
    {
        return teacherName;
    }

    public void setCampusName(String campusName)
    {
        this.campusName = campusName;
    }

    public String getCampusName()
    {
        return campusName;
    }

    public void setClassroomName(String classroomName)
    {
        this.classroomName = classroomName;
    }

    public String getClassroomName()
    {
        return classroomName;
    }

    public void setSsid(String ssid)
    {
        this.ssid = ssid;
    }

    public String getSsid()
    {
        return ssid;
    }

    public void setBssid(String bssid)
    {
        this.bssid = bssid;
    }

    public String getBssid()
    {
        return bssid;
    }

    public void setCheckInTime(Date checkInTime)
    {
        this.checkInTime = checkInTime;
    }

    public Date getCheckInTime()
    {
        return checkInTime;
    }

    public void setCourseClassName(String courseClassName)
    {
        this.courseClassName = courseClassName;
    }

    public String getCourseClassName()
    {
        return courseClassName;
    }

    public void setTimeSlot(String timeSlot)
    {
        this.timeSlot = timeSlot;
    }

    public String getTimeSlot()
    {
        return timeSlot;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("checkInId", getCheckInId())
            .append("scheduleId", getScheduleId())
            .append("classDate", getClassDate())
            .append("checkType", getCheckType())
            .append("teacherId", getTeacherId())
            .append("teacherName", getTeacherName())
            .append("campusName", getCampusName())
            .append("classroomName", getClassroomName())
            .append("ssid", getSsid())
            .append("bssid", getBssid())
            .append("checkInTime", getCheckInTime())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("remark", getRemark())
            .toString();
    }
}
