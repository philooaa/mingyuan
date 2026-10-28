package com.ruoyi.system.domain;

import java.util.Date;
import com.ruoyi.common.core.domain.BaseEntity;

public class EduHomework extends BaseEntity
{
    private static final long serialVersionUID = 1L;
    private Long homeworkId;
    private Long scheduleId;
    private Long teacherId;
    private String title;
    private String description;
    private Date deadline;
    private Date publishTime;
    private String status;
    private String delFlag;
    private Integer totalScore;
    private String archiveFlag;
    private Long enrollmentId;
    private Long parentId;
    private String studentName;
    private String teacherName;
    private String scheduleName;
    private Integer submissionCount;
    private Integer reviewedCount;
    private String submissionStatus;
    private Integer finalScore;
    private String targetIds;
    private Long matchedScheduleId;
    private String matchedScheduleName;
    /** 按班级统计的提交情况，格式 scheduleId:提交数:已批阅数，逗号分隔 */
    private String targetCounts;
    public Long getHomeworkId(){return homeworkId;} public void setHomeworkId(Long v){homeworkId=v;}
    public Long getScheduleId(){return scheduleId;} public void setScheduleId(Long v){scheduleId=v;}
    public Long getTeacherId(){return teacherId;} public void setTeacherId(Long v){teacherId=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public Date getDeadline(){return deadline;} public void setDeadline(Date v){deadline=v;}
    public Date getPublishTime(){return publishTime;} public void setPublishTime(Date v){publishTime=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getDelFlag(){return delFlag;} public void setDelFlag(String v){delFlag=v;}
    public Integer getTotalScore(){return totalScore;} public void setTotalScore(Integer v){totalScore=v;}
    public String getArchiveFlag(){return archiveFlag;} public void setArchiveFlag(String v){archiveFlag=v;}
    public Long getEnrollmentId(){return enrollmentId;} public void setEnrollmentId(Long v){enrollmentId=v;}
    public Long getParentId(){return parentId;} public void setParentId(Long v){parentId=v;}
    public String getStudentName(){return studentName;} public void setStudentName(String v){studentName=v;}
    public String getTeacherName(){return teacherName;} public void setTeacherName(String v){teacherName=v;}
    public String getScheduleName(){return scheduleName;} public void setScheduleName(String v){scheduleName=v;}
    public Integer getSubmissionCount(){return submissionCount;} public void setSubmissionCount(Integer v){submissionCount=v;}
    public Integer getReviewedCount(){return reviewedCount;} public void setReviewedCount(Integer v){reviewedCount=v;}
    public String getSubmissionStatus(){return submissionStatus;} public void setSubmissionStatus(String v){submissionStatus=v;}
    public Integer getFinalScore(){return finalScore;} public void setFinalScore(Integer v){finalScore=v;}
    public String getTargetIds(){return targetIds;} public void setTargetIds(String v){targetIds=v;}
    public Long getMatchedScheduleId(){return matchedScheduleId;} public void setMatchedScheduleId(Long v){matchedScheduleId=v;}
    public String getMatchedScheduleName(){return matchedScheduleName;} public void setMatchedScheduleName(String v){matchedScheduleName=v;}
    public String getTargetCounts(){return targetCounts;} public void setTargetCounts(String v){targetCounts=v;}
}
