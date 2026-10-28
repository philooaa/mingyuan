package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.*;

public interface EduHomeworkMapper
{
    List<EduHomework> selectParentList(@Param("parentId") Long parentId);
    List<EduHomework> selectParentChildList(@Param("parentId") Long parentId,@Param("accountId") Long accountId);
    EduHomework selectParentEnrollmentDetail(@Param("homeworkId") Long homeworkId,@Param("parentId") Long parentId,@Param("scheduleId") Long scheduleId,@Param("enrollmentId") Long enrollmentId);
    EduHomework selectParentDetail(@Param("homeworkId") Long homeworkId, @Param("parentId") Long parentId, @Param("scheduleId") Long scheduleId);
    EduHomework selectById(Long homeworkId);
    List<EduHomework> selectTeacherList(@Param("teacherId") Long teacherId);
    List<EduHomework> selectAdminList(EduHomework query);
    int insertHomework(EduHomework homework);
    int updateHomework(EduHomework homework);
    int updateHomeworkContent(EduHomework homework);
    int countSubmissions(@Param("homeworkId") Long homeworkId);
    int updateStatus(@Param("homeworkId") Long homeworkId, @Param("status") String status, @Param("user") String user);
    int updateDeadline(@Param("homeworkId") Long homeworkId, @Param("deadline") java.util.Date deadline, @Param("user") String user);
    int updateArchive(@Param("homeworkId") Long homeworkId, @Param("archiveFlag") String archiveFlag, @Param("user") String user);
    int deleteHomework(Long homeworkId);
    int insertQuestion(EduHomeworkQuestion question);
    List<EduHomeworkQuestion> selectQuestions(Long homeworkId);
    int deleteQuestions(Long homeworkId);
    int insertFile(EduHomeworkFile file);
    List<EduHomeworkFile> selectHomeworkFiles(Long homeworkId);
    int deleteHomeworkFiles(Long homeworkId);
    List<EduHomeworkFile> selectAnswerFiles(Long submissionId);
    List<EduHomeworkFile> selectReviewFiles(Long submissionId);
    int deleteReviewFiles(Long submissionId);
    int insertSubmission(EduHomeworkSubmission submission);
    EduHomeworkSubmission selectSubmissionByHomeworkEnrollment(@Param("homeworkId") Long homeworkId, @Param("enrollmentId") Long enrollmentId);
    EduHomeworkSubmission selectSubmissionById(Long submissionId);
    List<EduHomeworkSubmission> selectSubmissions(@Param("homeworkId") Long homeworkId, @Param("teacherId") Long teacherId);
    int insertAnswer(EduHomeworkAnswer answer);
    List<EduHomeworkAnswer> selectAnswers(Long submissionId);
    int reviewSubmission(EduHomeworkSubmission submission);
    Long selectParentEnrollment(@Param("homeworkId") Long homeworkId, @Param("parentId") Long parentId);
    List<Long> selectTargets(Long homeworkId);
    int insertTarget(@Param("homeworkId") Long homeworkId, @Param("scheduleId") Long scheduleId);
    int deleteTargets(Long homeworkId);
    Long selectScheduleTeacher(@Param("scheduleId") Long scheduleId);
    List<java.util.Map<String, Object>> selectSubmissionStats(@Param("homeworkId") Long homeworkId, @Param("teacherId") Long teacherId);
}
