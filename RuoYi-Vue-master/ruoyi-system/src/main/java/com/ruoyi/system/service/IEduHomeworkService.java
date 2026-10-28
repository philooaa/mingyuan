package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.*;

public interface IEduHomeworkService
{
    List<EduHomework> parentList();
    List<EduHomework> parentList(Long accountId);
    Map<String,Object> parentDetail(Long homeworkId, Long scheduleId);
    Map<String,Object> parentDetail(Long homeworkId,Long scheduleId,Long enrollmentId);
    Long submit(HomeworkSubmitBody body);
    Long saveDraft(HomeworkSaveBody body);
    Map<String,Object> teacherDetail(Long homeworkId);
    Long reuse(Long homeworkId, HomeworkSaveBody body);
    int publish(Long homeworkId, List<Long> scheduleIds);
    List<EduHomework> teacherList();
    List<EduHomeworkSubmission> submissions(Long homeworkId);
    List<java.util.Map<String, Object>> submissionStats(Long homeworkId);
    int review(EduHomeworkSubmission submission);
    int close(Long homeworkId);
    int updateDeadline(Long homeworkId, String deadline);
    List<EduHomework> adminList(EduHomework query);
    int archive(Long homeworkId, boolean archive);
    int delete(Long homeworkId);
}
