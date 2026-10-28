package com.ruoyi.system.domain;

import java.util.List;

public class HomeworkSubmitBody
{
    private Long homeworkId;
    private Long enrollmentId;
    private Long accountId;
    public Long getAccountId(){return accountId;} public void setAccountId(Long v){accountId=v;}
    private List<EduHomeworkAnswer> answers;
    private List<EduHomeworkFile> files;
    private String description;
    public Long getHomeworkId(){return homeworkId;} public void setHomeworkId(Long v){homeworkId=v;}
    public Long getEnrollmentId(){return enrollmentId;} public void setEnrollmentId(Long v){enrollmentId=v;}
    public List<EduHomeworkAnswer> getAnswers(){return answers;} public void setAnswers(List<EduHomeworkAnswer> v){answers=v;}
    public List<EduHomeworkFile> getFiles(){return files;} public void setFiles(List<EduHomeworkFile> v){files=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
}
