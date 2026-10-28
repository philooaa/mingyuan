package com.ruoyi.common.core.domain.model;

/**
 * MiniApp parent enrollment request.
 */
public class MiniAppEnrollmentBody
{
    private Long scheduleId;
    private String settlementMethod;
    private Long lessonAccountId;
    private Long accountId;
    public Long getAccountId(){return accountId;}
    public void setAccountId(Long value){accountId=value;}
    private String studentRequestKey;
    public String getStudentRequestKey() { return studentRequestKey; }
    public void setStudentRequestKey(String value) { studentRequestKey=value; }
    public String getSettlementMethod() { return settlementMethod; }
    public void setSettlementMethod(String value) { settlementMethod=value; }
    public Long getLessonAccountId() { return lessonAccountId; }
    public void setLessonAccountId(Long value) { lessonAccountId=value; }
    private String classDate;
    public String getClassDate() { return classDate; }
    public void setClassDate(String value) { classDate = value; }

    private String studentName;

    private String studentPhone;

    private String contactPhone;

    public Long getScheduleId()
    {
        return scheduleId;
    }

    public void setScheduleId(Long scheduleId)
    {
        this.scheduleId = scheduleId;
    }

    public String getStudentName()
    {
        return studentName;
    }

    public void setStudentName(String studentName)
    {
        this.studentName = studentName;
    }

    public String getStudentPhone()
    {
        return studentPhone;
    }

    public void setStudentPhone(String studentPhone)
    {
        this.studentPhone = studentPhone;
    }

    public String getContactPhone()
    {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone)
    {
        this.contactPhone = contactPhone;
    }
}
