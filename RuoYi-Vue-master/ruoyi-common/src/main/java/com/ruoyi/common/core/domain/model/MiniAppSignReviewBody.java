package com.ruoyi.common.core.domain.model;

import java.util.List;

/**
 * 小程序课次出勤审核确认体（教务／管理员）
 */
public class MiniAppSignReviewBody
{
    private String action;
    private Long reviewVersion;
    public String getAction() { return action; }
    public void setAction(String value) { action=value; }
    public Long getReviewVersion() { return reviewVersion; }
    public void setReviewVersion(Long value) { reviewVersion=value; }
    /** 排课ID */
    private Long scheduleId;

    /** 课次日期（yyyy-MM-dd） */
    private String classDate;

    /** Version of the submitted sheet inspected by the reviewer. */
    private String submissionVersion;
    private String reason;
    private Long signInId;
    private String signStatus;
    /** 批量更正；旧版单人请求仍可使用signInId/signStatus/reason。 */
    private List<Correction> corrections;
    public List<Correction> getCorrections() { return corrections; }
    public void setCorrections(List<Correction> value) { corrections = value; }

    public static class Correction {
        private Long signInId;
        private String signStatus;
        private String reason;
        public Correction() {}
        public Correction(Long id, String status, String reason) {
            this.signInId = id; this.signStatus = status; this.reason = reason;
        }
        public Long getSignInId() { return signInId; }
        public void setSignInId(Long value) { signInId = value; }
        public String getSignStatus() { return signStatus; }
        public void setSignStatus(String value) { signStatus = value; }
        public String getReason() { return reason; }
        public void setReason(String value) { reason = value; }
    }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public Long getSignInId() { return signInId; }
    public void setSignInId(Long value) { signInId = value; }
    public String getSignStatus() { return signStatus; }
    public void setSignStatus(String value) { signStatus = value; }

    public String getSubmissionVersion() { return submissionVersion; }
    public void setSubmissionVersion(String value) { submissionVersion = value; }

    /** 课堂图片路径列表 */
    private List<String> images;

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

    public void setImages(List<String> images)
    {
        this.images = images;
    }

    public List<String> getImages()
    {
        return images;
    }
}
