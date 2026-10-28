package com.ruoyi.system.domain;

import java.util.Date;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;
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

    private String lessonLocation;
    public String getLessonLocation() { return lessonLocation; }
    public void setLessonLocation(String value) { lessonLocation = value; }

    /** 课程年份 */
    @Excel(name = "课程年份")
    private Long courseYear;

    /** 学期（暑假/秋季/寒假/春季） */
    @Excel(name = "学期")
    private String termName;

    /** 期次或上课日 */
    @Excel(name = "期次或上课日")
    private String periodName;

    /** 时段 */
    @Excel(name = "时段")
    private String timeSlot;

    /** 开始时间 */
    @JsonFormat(pattern = "HH:mm:ss")
    @DateTimeFormat(pattern = "HH:mm:ss")
    @Excel(name = "开始时间", width = 30)
    private LocalTime startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "HH:mm:ss")
    @DateTimeFormat(pattern = "HH:mm:ss")
    @Excel(name = "结束时间", width = 30)
    private LocalTime endTime;

    /** 开课日期（本期第一次上课日） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "开课日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startDate;

    /** 结课日期（本期最后一次上课日） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "结课日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endDate;

    /** 上课模式（WEEKLY每周/DAILY_5_1上5休1） */
    @Excel(name = "上课模式", readConverterExp = "WEEKLY=每周一次,DAILY_5_1=上5天休1天")
    private String classPattern;

    /** 年级 */
    @Excel(name = "年级")
    private String gradeName;

    /** 科目 */
    @Excel(name = "科目")
    private String subjectName;

    /** 教师用户ID，归属以ID为准，不按姓名推断 */
    private Long teacherId;

    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }

    /** 教师姓名 */
    @Excel(name = "教师姓名")
    private String teacherName;

    /** 班型 */
    @Excel(name = "班型")
    private String classType;

    /** 授课形式（1班课 2一对一） */
    @Excel(name = "授课形式", readConverterExp = "1=班课,2=一对一")
    private String classMode;

    /** 已报名人数 */
    @Excel(name = "已报名人数")
    private Long enrolledCount;

    /** 报名上限（NULL 或 0 表示不限） */
    @Excel(name = "报名上限")
    private Integer enrollLimit;

    /** 课时单价（元/人·次） */
    @Excel(name = "课时单价(元)")
    private java.math.BigDecimal unitPrice;

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

    public void setStartTime(LocalTime startTime)
    {
        this.startTime = startTime;
    }

    public LocalTime getStartTime()
    {
        return startTime;
    }

    public void setEndTime(LocalTime endTime)
    {
        this.endTime = endTime;
    }

    public LocalTime getEndTime()
    {
        return endTime;
    }

    public void setStartDate(Date startDate)
    {
        this.startDate = startDate;
    }

    public Date getStartDate()
    {
        return startDate;
    }

    public void setEndDate(Date endDate)
    {
        this.endDate = endDate;
    }

    public Date getEndDate()
    {
        return endDate;
    }

    public void setClassPattern(String classPattern)
    {
        this.classPattern = classPattern;
    }

    public String getClassPattern()
    {
        return classPattern;
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

    public void setClassMode(String classMode)
    {
        this.classMode = classMode;
    }

    public String getClassMode()
    {
        return classMode;
    }

    public void setEnrolledCount(Long enrolledCount) 
    {
        this.enrolledCount = enrolledCount;
    }

    public Long getEnrolledCount() 
    {
        return enrolledCount;
    }

    public void setEnrollLimit(Integer enrollLimit)
    {
        this.enrollLimit = enrollLimit;
    }

    public Integer getEnrollLimit()
    {
        return enrollLimit;
    }

    public void setUnitPrice(java.math.BigDecimal unitPrice)
    {
        this.unitPrice = unitPrice;
    }

    public java.math.BigDecimal getUnitPrice()
    {
        return unitPrice;
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
            .append("startDate", getStartDate())
            .append("endDate", getEndDate())
            .append("classPattern", getClassPattern())
            .append("gradeName", getGradeName())
            .append("subjectName", getSubjectName())
            .append("teacherName", getTeacherName())
            .append("classType", getClassType())
            .append("enrolledCount", getEnrolledCount())
            .append("enrollLimit", getEnrollLimit())
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
