package com.ruoyi.system.service.impl;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.EduHomeworkMapper;
import com.ruoyi.system.service.IEduHomeworkService;

@Service
public class EduHomeworkServiceImpl implements IEduHomeworkService
{
    @Autowired private EduHomeworkMapper mapper;
    @Autowired private com.ruoyi.system.service.ParentAccountService accounts;

    public List<EduHomework> parentList(){return mapper.selectParentList(SecurityUtils.getUserId());}
    public List<EduHomework> parentList(Long accountId){if(accountId==null)return parentList();accounts.ownedChild(accountId);return mapper.selectParentChildList(SecurityUtils.getUserId(),accountId);}

    public Map<String,Object> parentDetail(Long id,Long scheduleId)
    { return parentDetail(id,scheduleId,null); }
    public Map<String,Object> parentDetail(Long id,Long scheduleId,Long enrollmentId)
    {
        EduHomework homework=enrollmentId==null?mapper.selectParentDetail(id, SecurityUtils.getUserId(), scheduleId):mapper.selectParentEnrollmentDetail(id,SecurityUtils.getUserId(),scheduleId,enrollmentId);
        if(homework==null) throw new ServiceException("作业不存在或无权查看");
        Map<String,Object> result=new HashMap<>(); result.put("homework",homework);
        List<EduHomeworkQuestion> questions=mapper.selectQuestions(id);
        for(EduHomeworkQuestion q:questions) q.setCorrectAnswer(null);
        result.put("questions",questions); result.put("files",mapper.selectHomeworkFiles(id));
        EduHomeworkSubmission submission=mapper.selectSubmissionByHomeworkEnrollment(id,homework.getEnrollmentId());
        result.put("submission",submission);
        if(submission!=null){ result.put("submissionFiles",mapper.selectAnswerFiles(submission.getSubmissionId())); submission.setReviewFiles(mapper.selectReviewFiles(submission.getSubmissionId())); }
        return result;
    }

    @Transactional(rollbackFor=Exception.class)
    public Long submit(HomeworkSubmitBody body)
    {
        if(body==null||body.getHomeworkId()==null) throw new ServiceException("作业不能为空");
        Long parentId=SecurityUtils.getUserId();
        EduHomework homework=mapper.selectById(body.getHomeworkId());
        if(homework==null||!"1".equals(homework.getStatus())||"1".equals(homework.getArchiveFlag())) throw new ServiceException("作业当前不可提交");
        if(homework.getDeadline()!=null && new Date().after(homework.getDeadline())) throw new ServiceException("作业已超过截止时间");
        Long enrollmentId=body.getEnrollmentId();
        if(enrollmentId==null)enrollmentId=mapper.selectParentEnrollment(body.getHomeworkId(),parentId);
        else if(mapper.selectParentEnrollmentDetail(body.getHomeworkId(),parentId,null,enrollmentId)==null)throw new ServiceException("无权为该学员提交作业");
        if(enrollmentId==null) throw new ServiceException("当前家长未报名该作业所属班级");
        if(body.getAccountId()!=null && !accounts.childEnrollmentIds(body.getAccountId()).contains(String.valueOf(enrollmentId)))throw new ServiceException("作业报名不属于当前孩子");
        body.setEnrollmentId(enrollmentId);
        if(mapper.selectSubmissionByHomeworkEnrollment(body.getHomeworkId(),body.getEnrollmentId())!=null) throw new ServiceException("该作业已提交，不能重复提交");
        List<EduHomeworkQuestion> questions=mapper.selectQuestions(body.getHomeworkId());
        Map<Long,EduHomeworkQuestion> questionMap=new HashMap<>(); for(EduHomeworkQuestion q:questions) questionMap.put(q.getQuestionId(),q);
        EduHomeworkSubmission submission=new EduHomeworkSubmission(); submission.setHomeworkId(body.getHomeworkId()); submission.setEnrollmentId(body.getEnrollmentId()); submission.setParentId(parentId); submission.setSubmitTime(new Date()); submission.setStatus("1"); submission.setDescription(body.getDescription()); submission.setCreateBy(SecurityUtils.getUsername()); submission.setCreateTime(new Date()); submission.setAutoScore(0); submission.setFinalScore(0);
        try{mapper.insertSubmission(submission);}catch(DuplicateKeyException e){throw new ServiceException("该作业已提交，不能重复提交");}
        if (body.getFiles()!=null) for(EduHomeworkFile file:body.getFiles()){if(StringUtils.isBlank(file.getFilePath())) continue; file.setHomeworkId(body.getHomeworkId()); file.setSubmissionId(submission.getSubmissionId()); file.setAnswerId(null); file.setFileType("answer"); file.setCreateBy(SecurityUtils.getUsername()); file.setCreateTime(new Date()); mapper.insertFile(file);}
        int autoScore=0; boolean manual=false;
        Set<Long> answered=new HashSet<>();
        if(body.getAnswers()!=null) for(EduHomeworkAnswer answer:body.getAnswers())
        {
            EduHomeworkQuestion q=questionMap.get(answer.getQuestionId()); if(q==null || !answered.add(answer.getQuestionId())) throw new ServiceException("答案中包含无效或重复题目");
            answer.setSubmissionId(submission.getSubmissionId());
            if("single".equals(q.getQuestionType())||"judge".equals(q.getQuestionType())||"fill".equals(q.getQuestionType()))
            { boolean ok=normalize(q.getCorrectAnswer()).equals(normalize(answer.getAnswerText())); answer.setCorrectFlag(ok?"1":"0"); answer.setScore(ok?q.getScore():0); autoScore+=answer.getScore(); }
            else {answer.setCorrectFlag("2"); answer.setScore(0); manual=true;}
            mapper.insertAnswer(answer);
            if(answer.getFiles()!=null) for(EduHomeworkFile file:answer.getFiles()){if(StringUtils.isBlank(file.getFilePath())) continue; file.setHomeworkId(body.getHomeworkId()); file.setSubmissionId(submission.getSubmissionId()); file.setAnswerId(answer.getAnswerId()); file.setFileType("answer"); file.setCreateBy(SecurityUtils.getUsername()); file.setCreateTime(new Date()); mapper.insertFile(file);}
        }
        // 只有「题目全部作答且都是可自动判分的客观题」才算自动批阅完成；否则一律待教师批阅，
        // 避免小程序端不答题直接提交时被误标为「已批阅」（教师端看不到待批记录、家长端看到空评语）。
        submission.setAutoScore(autoScore); submission.setFinalScore(autoScore); submission.setStatus((manual || questions.isEmpty() || answered.size() < questions.size()) ? "1" : "2"); submission.setReviewerId(null); submission.setReviewTime(null); mapper.reviewSubmission(submission);
        return submission.getSubmissionId();
    }
    private String normalize(String value){return value==null?"":value.trim().replaceAll("\\s+","").toLowerCase();}

    public Map<String,Object> teacherDetail(Long id)
    {
        EduHomework homework=mapper.selectById(id);
        assertTeacher(id);
        Map<String,Object> result=new HashMap<>();
        result.put("homework", homework);
        result.put("files", mapper.selectHomeworkFiles(id));
        return result;
    }

    @Transactional(rollbackFor=Exception.class)
    public Long saveDraft(HomeworkSaveBody body)
    {
        if(body==null||body.getScheduleId()==null||StringUtils.isBlank(body.getTitle())) throw new ServiceException("排课和作业标题不能为空");
        Long uid=SecurityUtils.getUserId(); Date now=new Date();
        Long scheduleTeacher=mapper.selectScheduleTeacher(body.getScheduleId());
        if(scheduleTeacher==null || (!uid.equals(scheduleTeacher) && !(SecurityUtils.isAdmin(uid) || SecurityUtils.hasRole("admin")))) throw new ServiceException("只能为自己负责的班级创建作业");
        // 只有显式传入题目时才替换题目与总分；未传题目（小程序端不管理题目）时保持原有题目不变，避免误删
        boolean replaceQuestions = body.getQuestions()!=null && !body.getQuestions().isEmpty();
        boolean isUpdate = body.getHomeworkId()!=null;
        if(isUpdate)
        {
            EduHomework old=mapper.selectById(body.getHomeworkId());
            if(old==null||"2".equals(old.getDelFlag())) throw new ServiceException("作业不存在");
            if(!uid.equals(old.getTeacherId())&&!(SecurityUtils.isAdmin(uid) || SecurityUtils.hasRole("admin"))) throw new ServiceException("只能修改自己的作业");
            if("1".equals(old.getArchiveFlag())) throw new ServiceException("已归档作业不能编辑");
            boolean published="1".equals(old.getStatus())||"2".equals(old.getStatus());
            if(published && replaceQuestions && mapper.countSubmissions(old.getHomeworkId())>0) throw new ServiceException("该作业已有家长提交，不能修改题目；可修改标题、说明、截止时间和附件");
            body.setTeacherId(old.getTeacherId()); body.setScheduleId(old.getScheduleId()); body.setUpdateBy(SecurityUtils.getUsername()); body.setUpdateTime(now);
            if(replaceQuestions){ mapper.updateHomework(body); mapper.deleteQuestions(body.getHomeworkId()); }
            else { mapper.updateHomeworkContent(body); }
        }
        else {body.setTeacherId(scheduleTeacher); body.setStatus("0"); body.setArchiveFlag("0"); body.setDelFlag("0"); body.setCreateBy(SecurityUtils.getUsername()); body.setCreateTime(now); mapper.insertHomework(body);}
        if(replaceQuestions)
        {
            int total=0; for(EduHomeworkQuestion q:body.getQuestions()){q.setHomeworkId(body.getHomeworkId()); q.setCreateBy(SecurityUtils.getUsername()); q.setCreateTime(now); mapper.insertQuestion(q); total+=q.getScore()==null?0:q.getScore();}
            body.setTotalScore(total); body.setUpdateBy(SecurityUtils.getUsername()); body.setUpdateTime(now); mapper.updateHomework(body);
        }
        else if(!isUpdate)
        {
            body.setTotalScore(0); body.setUpdateBy(SecurityUtils.getUsername()); body.setUpdateTime(now); mapper.updateHomework(body);
        }
        if(isUpdate) mapper.deleteHomeworkFiles(body.getHomeworkId());
        if(body.getFiles()!=null) for(EduHomeworkFile f:body.getFiles()){if(StringUtils.isBlank(f.getFilePath())) continue; f.setHomeworkId(body.getHomeworkId()); f.setFileType("homework"); f.setCreateBy(SecurityUtils.getUsername()); f.setCreateTime(now); mapper.insertFile(f);}
        return body.getHomeworkId();
    }
    @Transactional(rollbackFor=Exception.class)
    public Long reuse(Long id, HomeworkSaveBody body)
    {
        EduHomework source=mapper.selectById(id);
        if(source==null) throw new ServiceException("作业不存在");
        Long uid=SecurityUtils.getUserId();
        if(!uid.equals(source.getTeacherId())&&!(SecurityUtils.isAdmin(uid) || SecurityUtils.hasRole("admin"))) throw new ServiceException("只能复用自己的作业");
        HomeworkSaveBody copy=body==null?new HomeworkSaveBody():body;
        copy.setScheduleId(copy.getScheduleId()==null?source.getScheduleId():copy.getScheduleId());
        copy.setTitle(StringUtils.isBlank(copy.getTitle())?source.getTitle():copy.getTitle());
        copy.setDescription(copy.getDescription()==null?source.getDescription():copy.getDescription());
        copy.setDeadline(copy.getDeadline()==null?source.getDeadline():copy.getDeadline());
        Long targetTeacher=mapper.selectScheduleTeacher(copy.getScheduleId());
        if(targetTeacher==null||(!uid.equals(targetTeacher)&&!(SecurityUtils.isAdmin(uid) || SecurityUtils.hasRole("admin")))) throw new ServiceException("只能复用到自己负责的班级");
        copy.setHomeworkId(null);
        copy.setQuestions(new ArrayList<>());
        List<EduHomeworkFile> files=mapper.selectHomeworkFiles(id);
        copy.setFiles(files);
        return saveDraft(copy);
    }
    @Transactional(rollbackFor=Exception.class)
    public int publish(Long id,List<Long> scheduleIds)
    {
        EduHomework homework=mapper.selectById(id);
        assertTeacherDraft(id);
        Long uid=SecurityUtils.getUserId();
        List<Long> targets=scheduleIds==null||scheduleIds.isEmpty()?Collections.singletonList(homework.getScheduleId()):scheduleIds;
        for(Long scheduleId:targets)
        {
            Long scheduleTeacher=mapper.selectScheduleTeacher(scheduleId);
            if(scheduleTeacher==null||(!uid.equals(scheduleTeacher)&&!(SecurityUtils.isAdmin(uid) || SecurityUtils.hasRole("admin")))) throw new ServiceException("只能发布给自己负责的班级");
        }
        mapper.deleteTargets(id);
        for(Long scheduleId:targets) mapper.insertTarget(id,scheduleId);
        return mapper.updateStatus(id,"1",SecurityUtils.getUsername());
    }
    public List<EduHomework> teacherList(){return mapper.selectTeacherList(SecurityUtils.getUserId());}
    public List<EduHomeworkSubmission> submissions(Long id){assertTeacher(id); List<EduHomeworkSubmission> list=mapper.selectSubmissions(id,homeworkTeacherScope()); if(list==null) list=new ArrayList<>(); for(EduHomeworkSubmission s:list){List<EduHomeworkAnswer> answers=mapper.selectAnswers(s.getSubmissionId()); List<EduHomeworkFile> files=mapper.selectAnswerFiles(s.getSubmissionId()); for(EduHomeworkAnswer a:answers){List<EduHomeworkFile> own=new ArrayList<>(); for(EduHomeworkFile f:files)if(a.getAnswerId().equals(f.getAnswerId()))own.add(f); a.setFiles(own);} s.setReviewFiles(mapper.selectReviewFiles(s.getSubmissionId())); s.getParams().put("answers",answers); s.getParams().put("files",files);} return list;}
    public List<Map<String,Object>> submissionStats(Long id){assertTeacher(id); return mapper.selectSubmissionStats(id,homeworkTeacherScope());}
    public int review(EduHomeworkSubmission s){if(s==null||s.getSubmissionId()==null) throw new ServiceException("提交记录不能为空"); EduHomeworkSubmission old=mapper.selectSubmissionById(s.getSubmissionId()); if(old==null) throw new ServiceException("提交记录不存在"); assertTeacher(old.getHomeworkId()); s.setReviewerId(SecurityUtils.getUserId()); s.setReviewTime(new Date()); s.setStatus("2"); if(s.getFinalScore()==null) s.setFinalScore((old.getAutoScore()==null?0:old.getAutoScore())+(s.getManualScore()==null?0:s.getManualScore())); int rows=mapper.reviewSubmission(s); if(s.getReviewFiles()!=null){mapper.deleteReviewFiles(s.getSubmissionId()); Date now=new Date(); for(EduHomeworkFile f:s.getReviewFiles()){if(StringUtils.isBlank(f.getFilePath())) continue; f.setHomeworkId(old.getHomeworkId()); f.setSubmissionId(s.getSubmissionId()); f.setAnswerId(null); f.setFileType("review"); f.setCreateBy(SecurityUtils.getUsername()); f.setCreateTime(now); mapper.insertFile(f);}} return rows;}
    public int close(Long id){assertTeacher(id); return mapper.updateStatus(id,"2",SecurityUtils.getUsername());}
    @Transactional(rollbackFor=Exception.class)
    public int updateDeadline(Long id,String deadline)
    {
        assertTeacher(id);
        EduHomework homework=mapper.selectById(id);
        if(!"1".equals(homework.getStatus())&&!"2".equals(homework.getStatus())) throw new ServiceException("仅已发布或已关闭的作业可以调整截止时间");
        if("1".equals(homework.getArchiveFlag())) throw new ServiceException("已归档作业不能调整截止时间");
        if(StringUtils.isBlank(deadline)) throw new ServiceException("请选择截止时间");
        Date parsed=DateUtils.parseDate(deadline.trim());
        if(parsed==null) throw new ServiceException("截止时间格式不正确");
        return mapper.updateDeadline(id,parsed,SecurityUtils.getUsername());
    }
    public List<EduHomework> adminList(EduHomework q){return mapper.selectAdminList(q);}
    public int archive(Long id,boolean archive){return mapper.updateArchive(id,archive?"1":"0",SecurityUtils.getUsername());}
    public int delete(Long id){EduHomework h=mapper.selectById(id); if(h==null) throw new ServiceException("作业不存在"); if(!"1".equals(h.getArchiveFlag())) throw new ServiceException("请先归档作业再删除"); return mapper.deleteHomework(id);}
    private void assertTeacher(Long id){EduHomework h=mapper.selectById(id); Long uid=SecurityUtils.getUserId(); if(h==null||(!uid.equals(h.getTeacherId())&&!(SecurityUtils.isAdmin(uid) || SecurityUtils.hasRole("admin")))) throw new ServiceException("作业不存在或无权操作");}
    private void assertTeacherDraft(Long id){assertTeacher(id); if(!"0".equals(mapper.selectById(id).getStatus())) throw new ServiceException("仅草稿可以发布");}

    private Long homeworkTeacherScope()
    {
        // Existing mapper SQL uses 1 as the cross-teacher scope, not as the acting user identity.
        return SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin") ? 1L : SecurityUtils.getUserId();
    }
}
