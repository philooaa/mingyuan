package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 课次签到对象 edu_class_sign_in
 * 行 = 课次 × 学员；教师/课程信息通过 schedule_id 关联排课表获得
 *
 * @author ruoyi
 * @date 2026-08-30
 */
public class EduClassSignIn extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 签到记录ID */
    private Long signInId;

    /** 排课ID（关联edu_course_schedule） */
    private Long scheduleId;

    /** 课次日期 */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date classDate;

    /** 报名记录ID（试听/调课学员为空） */
    private Long enrollmentId;

    /** 学生姓名 */
    private String studentName;

    /** 学员来源（1报名 2试听 3调课） */
    private String sourceType;

    /** 签到结果（1到课 2录播 3请假 4试听到课 5调课到课 6未到课） */
    private String signStatus;

    /** 复核状态（0待复核 1复核完成 2审核异常） */
    private String reviewStatus;
    private String reviewReason;
    private String reviewHistory;
    public String getReviewReason() { return reviewReason; }
    public void setReviewReason(String value) { reviewReason = value; }
    public String getReviewHistory() { return reviewHistory; }
    public void setReviewHistory(String value) { reviewHistory = value; }
    private Long reviewVersion;
    private java.util.List<java.util.Map<String,Object>> corrections;
    public java.util.List<java.util.Map<String,Object>> getCorrections() { return corrections; }
    public void setCorrections(java.util.List<java.util.Map<String,Object>> value) { corrections=value; }
    public Long getReviewVersion() { return reviewVersion; }
    public void setReviewVersion(Long value) { reviewVersion=value; }

    /** 第1位确认管理员用户ID */
    private Long review1AdminId;

    /** 第1位确认管理员姓名 */
    private String review1AdminName;

    /** 第1位管理员课堂图片路径，逗号分隔 */
    private String review1Images;

    /** 第1位确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date review1Time;

    /** 第2位确认管理员用户ID */
    private Long review2AdminId;

    /** 第2位确认管理员姓名 */
    private String review2AdminName;

    /** 第2位管理员课堂图片路径，逗号分隔 */
    private String review2Images;

    /** 第2位确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date review2Time;

    /** 删除标志（0代表存在 2代表删除） */
    private String delFlag;

    // ============ 关联展示字段（非表字段） ============

    /** 课程班级名 */
    private String courseClassName;

    /** 年级 */
    private String gradeName;

    /** 学科 */
    private String subjectName;

    /** 期次 */
    private String periodName;

    /** 上课时间段 */
    private String timeSlot;

    /** 教师 */
    private String teacherName;

    /** 教室名 */
    private String classroomName;

    /** 校区 */
    private String campusName;

    /** 明细总人数（同一课次行数） */
    private Long totalCount;

    /** 实际到课人数（到课+试听到课+调课到课） */
    private Long actualCount;

    /** 已确认管理员数（0/1/2） */
    private Integer confirmedCount;

    /** 最近一次签到日期（教师排课列表用） */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date lastSignDate;

    public void setSignInId(Long signInId)
    {
        this.signInId = signInId;
    }

    public Long getSignInId()
    {
        return signInId;
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

    public void setEnrollmentId(Long enrollmentId)
    {
        this.enrollmentId = enrollmentId;
    }

    public Long getEnrollmentId()
    {
        return enrollmentId;
    }

    public void setStudentName(String studentName)
    {
        this.studentName = studentName;
    }

    public String getStudentName()
    {
        return studentName;
    }

    public void setSourceType(String sourceType)
    {
        this.sourceType = sourceType;
    }

    public String getSourceType()
    {
        return sourceType;
    }

    public void setSignStatus(String signStatus)
    {
        this.signStatus = signStatus;
    }

    public String getSignStatus()
    {
        return signStatus;
    }

    public void setReviewStatus(String reviewStatus)
    {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewStatus()
    {
        return reviewStatus;
    }

    public void setReview1AdminId(Long review1AdminId)
    {
        this.review1AdminId = review1AdminId;
    }

    public Long getReview1AdminId()
    {
        return review1AdminId;
    }

    public void setReview1AdminName(String review1AdminName)
    {
        this.review1AdminName = review1AdminName;
    }

    public String getReview1AdminName()
    {
        return review1AdminName;
    }

    public void setReview1Images(String review1Images)
    {
        this.review1Images = review1Images;
    }

    public String getReview1Images()
    {
        return review1Images;
    }

    public void setReview1Time(Date review1Time)
    {
        this.review1Time = review1Time;
    }

    public Date getReview1Time()
    {
        return review1Time;
    }

    public void setReview2AdminId(Long review2AdminId)
    {
        this.review2AdminId = review2AdminId;
    }

    public Long getReview2AdminId()
    {
        return review2AdminId;
    }

    public String getReview2AdminName()
    {
        return review2AdminName;
    }

    public void setReview2AdminName(String review2AdminName)
    {
        this.review2AdminName = review2AdminName;
    }

    public void setReview2Images(String review2Images)
    {
        this.review2Images = review2Images;
    }

    public String getReview2Images()
    {
        return review2Images;
    }

    public void setReview2Time(Date review2Time)
    {
        this.review2Time = review2Time;
    }

    public Date getReview2Time()
    {
        return review2Time;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setCourseClassName(String courseClassName)
    {
        this.courseClassName = courseClassName;
    }

    public String getCourseClassName()
    {
        return courseClassName;
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

    public void setTeacherName(String teacherName)
    {
        this.teacherName = teacherName;
    }

    public String getTeacherName()
    {
        return teacherName;
    }

    public void setClassroomName(String classroomName)
    {
        this.classroomName = classroomName;
    }

    public String getClassroomName()
    {
        return classroomName;
    }

    public void setCampusName(String campusName)
    {
        this.campusName = campusName;
    }

    public String getCampusName()
    {
        return campusName;
    }

    public void setTotalCount(Long totalCount)
    {
        this.totalCount = totalCount;
    }

    public Long getTotalCount()
    {
        return totalCount;
    }

    public void setActualCount(Long actualCount)
    {
        this.actualCount = actualCount;
    }

    public Long getActualCount()
    {
        return actualCount;
    }

    public void setConfirmedCount(Integer confirmedCount)
    {
        this.confirmedCount = confirmedCount;
    }

    public Integer getConfirmedCount()
    {
        return confirmedCount;
    }

    public void setLastSignDate(Date lastSignDate)
    {
        this.lastSignDate = lastSignDate;
    }

    public Date getLastSignDate()
    {
        return lastSignDate;
    }
}
