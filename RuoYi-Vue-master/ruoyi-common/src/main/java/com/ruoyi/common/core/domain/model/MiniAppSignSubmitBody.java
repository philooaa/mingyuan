package com.ruoyi.common.core.domain.model;

import java.util.List;

/**
 * 小程序上课签到提交体
 */
public class MiniAppSignSubmitBody
{
    private Long reviewVersion;
    public Long getReviewVersion() { return reviewVersion; }
    public void setReviewVersion(Long value) { reviewVersion=value; }
    /** 排课ID */
    private Long scheduleId;

    /** 课次日期（yyyy-MM-dd） */
    private String classDate;

    /** 学员签到明细 */
    private List<Detail> details;

    public static class Detail
    {
        /** 报名记录ID（试听/调课学员为空） */
        private Long enrollmentId;

        /** 学生姓名 */
        private String studentName;

        /** 学员来源（1报名 2试听 3调课） */
        private String sourceType;

        /** 签到结果（1到课 2录播 3请假 4试听到课 5调课到课） */
        private String signStatus;

        /** 备注（试听/调课学员联系方式等） */
        private String remark;

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

        public void setRemark(String remark)
        {
            this.remark = remark;
        }

        public String getRemark()
        {
            return remark;
        }
    }

    public void setScheduleId(Long scheduleId)
    {
        this.scheduleId = scheduleId;
    }

    public Long getScheduleId()
    {
        return scheduleId;
    }

    public void setClassDate(String classDate)
    {
        this.classDate = classDate;
    }

    public String getClassDate()
    {
        return classDate;
    }

    public void setDetails(List<Detail> details)
    {
        this.details = details;
    }

    public List<Detail> getDetails()
    {
        return details;
    }
}
