package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 课程排课对象 edu_course_schedule
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
public class EduCourseSchedule extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 排课ID */
    private Long scheduleId;

    /** 排课编码 */
    @Excel(name = "排课编码")
    private String scheduleCode;

    /** 教室ID */
    @Excel(name = "教室ID")
    private Long classroomId;

    /** 课程年份 */
    @Excel(name = "课程年份")
    private Long courseYear;

    /** 学期（暑假/秋季） */
    @Excel(name = "学期")
    private String termName;

    /** 期次或上课日 */
    @Excel(name = "期次或上课日")
    private String periodName;

    /** 时段 */
    @Excel(name = "时段")
    private String timeSlot;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endTime;

    /** 年级 */
    @Excel(name = "年级")
    private String gradeName;

    /** 科目 */
    @Excel(name = "科目")
    private String subjectName;

    /** 教师姓名 */
    @Excel(name = "教师姓名")
    private String teacherName;

    /** 班型 */
    @Excel(name = "班型")
    private String classType;

    /** 已报名人数 */
    @Excel(name = "已报名人数")
    private Long enrolledCount;

    /** 招生状态（0可报名 1停招 2满班） */
    @Excel(name = "招生状态", readConverterExp = "0=可报名,1=停招,2=满班")
    private String recruitStatus;

    /** 课程班名称 */
    @Excel(name = "课程班名称")
    private String courseClassName;

    /** 来源Sheet */
    @Excel(name = "来源Sheet")
    private String sourceSheet;

    /** 来源行 */
    @Excel(name = "来源行")
    private Long sourceRow;

    /** 来源列 */
    @Excel(name = "来源列")
    private String sourceCol;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    /** 删除标志（0代表存在 2代表删除） */
    private String delFlag;

    public void setScheduleId(Long scheduleId) 
    {
        this.scheduleId = scheduleId;
    }

    public Long getScheduleId() 
    {
        return scheduleId;
    }

    public void setScheduleCode(String scheduleCode) 
    {
        this.scheduleCode = scheduleCode;
    }

    public String getScheduleCode() 
    {
        return scheduleCode;
    }

    public void setClassroomId(Long classroomId) 
    {
        this.classroomId = classroomId;
    }

    public Long getClassroomId() 
    {
        return classroomId;
    }

    public void setCourseYear(Long courseYear) 
    {
        this.courseYear = courseYear;
    }

    public Long getCourseYear() 
    {
        return courseYear;
    }

    public void setTermName(String termName) 
    {
        this.termName = termName;
    }

    public String getTermName() 
    {
        return termName;
    }

    public void setPeriodName(String periodName) 
    {
        this.periodName = periodName;
    }

    public String getPeriodName() 
    {
        return periodName;
    }

    public void setTimeSlot(String timeSlot) 
    {
        this.timeSlot = timeSlot;
    }

    public String getTimeSlot() 
    {
        return timeSlot;
    }

    public void setStartTime(Date startTime) 
    {
        this.startTime = startTime;
    }

    public Date getStartTime() 
    {
        return startTime;
    }

    public void setEndTime(Date endTime) 
    {
        this.endTime = endTime;
    }

    public Date getEndTime() 
    {
        return endTime;
    }

    public void setGradeName(String gradeName) 
    {
        this.gradeName = gradeName;
    }

    public String getGradeName() 
    {
        return gradeName;
    }

    public void setSubjectName(String subjectName) 
    {
        this.subjectName = subjectName;
    }

    public String getSubjectName() 
    {
        return subjectName;
    }

    public void setTeacherName(String teacherName) 
    {
        this.teacherName = teacherName;
    }

    public String getTeacherName() 
    {
        return teacherName;
    }

    public void setClassType(String classType) 
    {
        this.classType = classType;
    }

    public String getClassType() 
    {
        return classType;
    }

    public void setEnrolledCount(Long enrolledCount) 
    {
        this.enrolledCount = enrolledCount;
    }

    public Long getEnrolledCount() 
    {
        return enrolledCount;
    }

    public void setRecruitStatus(String recruitStatus) 
    {
        this.recruitStatus = recruitStatus;
    }

    public String getRecruitStatus() 
    {
        return recruitStatus;
    }

    public void setCourseClassName(String courseClassName) 
    {
        this.courseClassName = courseClassName;
    }

    public String getCourseClassName() 
    {
        return courseClassName;
    }

    public void setSourceSheet(String sourceSheet) 
    {
        this.sourceSheet = sourceSheet;
    }

    public String getSourceSheet() 
    {
        return sourceSheet;
    }

    public void setSourceRow(Long sourceRow) 
    {
        this.sourceRow = sourceRow;
    }

    public Long getSourceRow() 
    {
        return sourceRow;
    }

    public void setSourceCol(String sourceCol) 
    {
        this.sourceCol = sourceCol;
    }

    public String getSourceCol() 
    {
        return sourceCol;
    }

    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }

    public void setDelFlag(String delFlag) 
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("scheduleId", getScheduleId())
            .append("scheduleCode", getScheduleCode())
            .append("classroomId", getClassroomId())
            .append("courseYear", getCourseYear())
            .append("termName", getTermName())
            .append("periodName", getPeriodName())
            .append("timeSlot", getTimeSlot())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("gradeName", getGradeName())
            .append("subjectName", getSubjectName())
            .append("teacherName", getTeacherName())
            .append("classType", getClassType())
            .append("enrolledCount", getEnrolledCount())
            .append("recruitStatus", getRecruitStatus())
            .append("courseClassName", getCourseClassName())
            .append("sourceSheet", getSourceSheet())
            .append("sourceRow", getSourceRow())
            .append("sourceCol", getSourceCol())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
