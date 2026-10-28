package com.ruoyi.system.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.domain.model.MiniAppSignReviewBody;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.Objects;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.ruoyi.system.domain.EduClassSignIn;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.mapper.EduClassSignInMapper;
import com.ruoyi.system.mapper.EduCourseScheduleMapper;
import com.ruoyi.system.service.IEduClassSignInService;

/**
 * 课次签到 服务实现
 *
 * @author ruoyi
 * @date 2026-08-30
 */
@Service
public class EduClassSignInServiceImpl implements IEduClassSignInService
{
    /** 计入实际到课人数的签到结果：到课/试听到课/调课到课 */
    private static final Set<String> ATTENDED_STATUSES = new LinkedHashSet<>(Arrays.asList("1", "4", "5"));

    private static final Set<String> VALID_SIGN_STATUSES = new LinkedHashSet<>(Arrays.asList("1", "2", "3", "4", "5", "6"));

    private static final Set<String> VALID_SOURCE_TYPES = new LinkedHashSet<>(Arrays.asList("1", "2", "3"));

    @Autowired
    private EduClassSignInMapper signInMapper;

    @Autowired
    private EduCourseScheduleMapper scheduleMapper;
    @Autowired private com.ruoyi.system.shop.ShopRepository singleDb;
    @Autowired private com.ruoyi.system.service.ClassLeaveService classLeaves;

    @Override
    public List<EduClassSignIn> selectTeacherSchedules(Long teacherId)
    {
        var courses=signInMapper.selectScheduleListForTeacher(teacherId);
        var corrections=singleDb.rows("SELECT r.schedule_id,DATE_FORMAT(r.class_date,'%Y-%m-%d') class_date,MAX(r.review_reason) review_reason FROM edu_class_sign_in r JOIN edu_course_schedule s ON s.schedule_id=r.schedule_id WHERE s.teacher_id=? AND s.del_flag='0' AND r.del_flag='0' AND r.review_status='2' GROUP BY r.schedule_id,r.class_date ORDER BY MAX(r.update_time) DESC,r.class_date DESC",teacherId);
        for(var course:courses)course.setCorrections(corrections.stream().filter(r->course.getScheduleId().toString().equals(r.get("scheduleId").toString())).toList());
        return courses;
    }

    @Override
    public List<Map<String, Object>> selectEnrolledStudents(Long scheduleId)
    {
        return signInMapper.selectEnrolledStudents(scheduleId);
    }

    @Override
    public Map<String, Object> selectDetail(Long scheduleId, String classDate)
    {
        Map<String, Object> info = signInMapper.selectReviewInfo(scheduleId, classDate);
        List<EduClassSignIn> details = signInMapper.selectDetailList(scheduleId, classDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scheduleId", scheduleId);
        result.put("classDate", classDate);
        result.put("details", details);
        result.put("review", formatReviewInfo(info));
        result.put("stats", attendanceStats(details));
        result.put("submissionVersion", submissionVersion(details));
        if (!details.isEmpty()) {
            var first = details.get(0);
            result.put("teacherName", first.getTeacherName());
            result.put("timeSlot", first.getTimeSlot());
            result.put("submittedBy", first.getCreateBy());
            result.put("submittedTime", formatTime(first.getCreateTime()));
        }
        boolean reviewed = info != null && (Set.of("1", "2").contains(String.valueOf(info.get("review_status")))
            || info.get("review1_admin_id") != null || info.get("review2_admin_id") != null);
        result.put("locked", reviewed);
        result.put("approvedLeaveEnrollmentIds", reviewed ? java.util.Set.of() : classLeaves.approved(scheduleId,classDate));
        return result;
    }

    /**
     * 复核信息：统一转驼峰键，时间格式化为字符串，便于小程序展示
     */
    private Map<String, Object> formatReviewInfo(Map<String, Object> info)
    {
        Map<String, Object> review = new LinkedHashMap<>();
        if (info == null)
        {
            review.put("reviewStatus", "0");
            review.put("reviewVersion", 0L);
            review.put("history", java.util.List.of());
            review.put("confirmedCount", 0);
            return review;
        }
        review.put("reviewStatus", str(info.get("review_status")));
        review.put("reviewReason", str(info.get("review_reason")));
        review.put("reviewVersion", version(info));
        review.put("history", history(info));
        review.put("review1AdminId", info.get("review1_admin_id"));
        review.put("review1AdminName", info.get("review1_admin_name"));
        review.put("review1Images", str(info.get("review1_images")));
        review.put("review1Time", formatTime(info.get("review1_time")));
        review.put("review2AdminId", info.get("review2_admin_id"));
        review.put("review2AdminName", info.get("review2_admin_name"));
        review.put("review2Images", str(info.get("review2_images")));
        review.put("review2Time", formatTime(info.get("review2_time")));
        review.put("totalCount", info.get("total_count"));
        review.put("actualCount", info.get("actual_count"));
        int confirmed = 0;
        if (info.get("review1_admin_id") != null)
        {
            confirmed++;
        }
        if (info.get("review2_admin_id") != null)
        {
            confirmed++;
        }
        review.put("confirmedCount", confirmed);
        return review;
    }

    private String str(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private String formatTime(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Date)
        {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format((Date) value);
        }
        return String.valueOf(value);
    }

    @Override
    @Transactional
    public Map<String, Object> submitSignIn(MiniAppSignSubmitBody body, String operator)
    {
        Long scheduleId = body.getScheduleId();
        String classDate = body.getClassDate();
        List<MiniAppSignSubmitBody.Detail> details = body.getDetails();
        if (scheduleId == null || StringUtils.isEmpty(classDate) || details == null || details.isEmpty())
        {
            throw new ServiceException("缺少排课、课次日期或签到明细");
        }
        EduCourseSchedule schedule = scheduleMapper.lockSchedule(scheduleId);
        if (schedule == null || "2".equals(schedule.getDelFlag()))
        {
            throw new ServiceException("排课不存在或已删除");
        }
        if ("2".equals(schedule.getClassMode()) && body.getDetails() != null) {
            if(!singleDb.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND class_date=? AND lesson_completed_time IS NOT NULL FOR UPDATE",scheduleId,classDate).isEmpty())
                throw new ServiceException("本次一对一已确认完成，不能覆盖原签到或改变消课历史，请联系管理员核对");
            for (var detail : body.getDetails()) {
                if (detail.getEnrollmentId() == null) throw new ServiceException("一对一签到需关联已确认的单次报名");
                var rows = singleDb.rows("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? AND schedule_id=? AND class_date=? AND enrollment_status='1' AND del_flag='0' FOR UPDATE",detail.getEnrollmentId(),body.getScheduleId(),body.getClassDate());
                if (rows.isEmpty()) throw new ServiceException("单次报名日期或确认状态已变化，请刷新");
            }
        }
        if (!Objects.equals(schedule.getTeacherId(), SecurityUtils.getUserId()))
        {
            throw new ServiceException("排课教师已变更，无权提交该课程签到");
        }
        if (!classDate.matches("\\d{4}-\\d{2}-\\d{2}"))
        {
            throw new ServiceException("课次日期格式应为 yyyy-MM-dd");
        }

        // 已复核完成的课次锁定，不允许修改签到
        Map<String, Object> info = signInMapper.selectReviewInfo(scheduleId, classDate);
        if (info != null && (Set.of("1", "2").contains(String.valueOf(info.get("review_status")))
            || info.get("review1_admin_id") != null || info.get("review2_admin_id") != null))
        {
            throw new ServiceException("该课次已进入教务审核，异常记录请由教务或管理员更正，教师不能覆盖签到");
        }
        if(!"0".equals(schedule.getStatus()) && signInMapper.countByScheduleAndDate(scheduleId,classDate)==0)
            throw new ServiceException("课程已停用，只能更正原有课次");

        checkVersion(body.getReviewVersion(), info);
        LocalDate.parse(classDate);

        // 报名学员按稳定报名编号去重，同名孩子不能互相覆盖；自由添加的学员仍按姓名检查。
        List<EduClassSignIn> rows = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Set<Long> enrollmentIds = new LinkedHashSet<>();
        Set<String> freeNames = new LinkedHashSet<>();
        for (MiniAppSignSubmitBody.Detail d : details)
        {
            if (d == null || StringUtils.isEmpty(d.getStudentName()))
            {
                throw new ServiceException("签到明细缺少学生姓名");
            }
            String status = d.getSignStatus();
            String source = d.getSourceType();
            if (!VALID_SIGN_STATUSES.contains(status))
            {
                throw new ServiceException("学生「" + d.getStudentName() + "」的签到结果无效");
            }
            if (!VALID_SOURCE_TYPES.contains(source))
            {
                throw new ServiceException("学生「" + d.getStudentName() + "」的学员来源无效");
            }
            String name = d.getStudentName().trim();
            if(name.length()>30 || (d.getRemark()!=null && d.getRemark().length()>500)) throw new ServiceException("姓名或备注过长");
            if (d.getEnrollmentId()!=null) singleDb.one("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? AND schedule_id=?",d.getEnrollmentId(),scheduleId);
            boolean duplicate = d.getEnrollmentId() == null
                ? !freeNames.add(name) || names.contains(name)
                : !enrollmentIds.add(d.getEnrollmentId()) || freeNames.contains(name);
            if (duplicate)
            {
                throw new ServiceException("学生「" + d.getStudentName() + "」重复出现");
            }
            names.add(name);
            EduClassSignIn row = new EduClassSignIn();
            row.setScheduleId(scheduleId);
            row.setClassDate(java.sql.Date.valueOf(classDate));
            row.setEnrollmentId(d.getEnrollmentId());
            row.setStudentName(d.getStudentName().trim());
            row.setSourceType(source);
            row.setSignStatus(status);
            row.setCreateBy(operator);
            row.setRemark(d.getRemark());
            rows.add(row);
        }

        // 保留稳定签到ID及更正前快照；不再物理删除明细，避免财务引用失效。
        var previous=singleDb.rows("SELECT * FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? FOR UPDATE",scheduleId,classDate);
        var before=snapshot(scheduleId,classDate);
        singleDb.jdbc().update("UPDATE edu_class_sign_in SET del_flag='2' WHERE schedule_id=? AND class_date=?",scheduleId,classDate);
        for(var row:rows) {
            String identity=row.getEnrollmentId()==null?"N:"+row.getStudentName():"E:"+row.getEnrollmentId();
            var existing=previous.stream().filter(x->identity.equals(x.get("enrollmentId")==null?"N:"+x.get("studentName"):"E:"+x.get("enrollmentId"))).findFirst().orElse(null);
            var values=new LinkedHashMap<String,Object>();values.put("schedule_id",scheduleId);values.put("class_date",classDate);values.put("enrollment_id",row.getEnrollmentId());values.put("student_name",row.getStudentName());values.put("source_type",row.getSourceType());values.put("sign_status",row.getSignStatus());values.put("remark",row.getRemark());values.put("del_flag","0");values.put("review_status","0");values.put("review_reason",null);values.put("update_by",operator);values.put("update_time",LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            if(existing==null){values.put("create_by",operator);values.put("create_time",LocalDateTime.now(ZoneId.of("Asia/Shanghai")));singleDb.insert("edu_class_sign_in",values);}
            else singleDb.update("edu_class_sign_in","sign_in_id",Long.parseLong(existing.get("signInId").toString()),values);
        }
        appendHistory(scheduleId,classDate,info,"SUBMIT",null,before,snapshot(scheduleId,classDate),List.of());

        long actual = rows.stream().filter(r -> ATTENDED_STATUSES.contains(r.getSignStatus())).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", rows.size());
        result.put("actualCount", actual);
        result.put("attended", rows.stream().filter(r -> "1".equals(r.getSignStatus())).count());
        result.put("recorded", rows.stream().filter(r -> "2".equals(r.getSignStatus())).count());
        result.put("leave", rows.stream().filter(r -> "3".equals(r.getSignStatus())).count());
        result.put("audited", rows.stream().filter(r -> "4".equals(r.getSignStatus())).count());
        result.put("transferred", rows.stream().filter(r -> "5".equals(r.getSignStatus())).count());
        return result;
    }

    private static final String ATTENDANCE_JOINS=" FROM edu_class_sign_in r JOIN edu_course_schedule c ON c.schedule_id=r.schedule_id LEFT JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id AND e.schedule_id=r.schedule_id LEFT JOIN edu_student st ON st.student_id=e.student_id AND st.parent_id=e.parent_id LEFT JOIN sys_user p ON p.user_id=e.parent_id LEFT JOIN sys_user t ON t.user_id=c.teacher_id ";
    private static final String ATTENDANCE_FIELDS="r.sign_in_id,r.schedule_id,DATE_FORMAT(r.class_date,'%Y-%m-%d') class_date,e.enrollment_id,r.student_name recorded_name,r.sign_status,r.source_type,COALESCE(r.review_status,'0') review_status,st.student_id,st.parent_id,st.student_name current_name,e.student_name enrolled_name,p.phonenumber parent_phone,c.course_class_name,COALESCE(NULLIF(TRIM(t.user_name),''),c.teacher_name) teacher_name,c.grade_name,c.subject_name";
    private static final Map<String,String> SIGN_LABELS=Map.of("1","到课","2","录播","3","请假","4","试听到课","5","调课到课","6","未到课");
    private void attendanceAdmin() {if(!SecurityUtils.isAdmin() && !SecurityUtils.hasRole("admin") && !SecurityUtils.hasRole("education"))throw new ServiceException("只有教务或管理员可以查看学生出勤总览");}
    private void reviewer(Long actor) {attendanceAdmin();if(!Objects.equals(actor,SecurityUtils.getUserId()))throw new ServiceException("复核身份不一致");}
    private void attendanceRange(String from,String to) {
        try {if(from!=null&&!from.isBlank())LocalDate.parse(from);if(to!=null&&!to.isBlank())LocalDate.parse(to);if(from!=null&&!from.isBlank()&&to!=null&&!to.isBlank()&&from.compareTo(to)>0)throw new IllegalArgumentException();}
        catch(Exception e){throw new ServiceException("请选择有效的起止日期，开始日期不能晚于结束日期");}
    }
    private String rangeSql(String from,String to,List<Object> args) {
        String sql="";if(from!=null&&!from.isBlank()){sql+=" AND r.class_date>=?";args.add(from);}if(to!=null&&!to.isBlank()){sql+=" AND r.class_date<=?";args.add(to);}return sql;
    }
    private Map<String,Object> attendanceCourse(Long id) {
        if(id==null||id<=0)throw new ServiceException("请选择课程");
        return singleDb.one("SELECT c.schedule_id,c.course_class_name,c.grade_name,c.subject_name,COALESCE(NULLIF(TRIM(t.user_name),''),c.teacher_name) teacher_name,c.period_name,c.term_name,c.time_slot,c.course_year,c.status FROM edu_course_schedule c LEFT JOIN sys_user t ON t.user_id=c.teacher_id WHERE c.schedule_id=? AND c.del_flag='0'",id);
    }
    @Override public List<Map<String,Object>> attendanceCourses(String keyword) {
        attendanceAdmin();if(keyword!=null&&keyword.length()>100)throw new ServiceException("搜索词过长");String like="%"+(keyword==null?"":keyword.trim())+"%";
        return singleDb.rows("SELECT c.schedule_id,c.course_class_name,c.grade_name,c.subject_name,COALESCE(NULLIF(TRIM(t.user_name),''),c.teacher_name) teacher_name,c.course_year,c.term_name,c.period_name,c.time_slot,(SELECT COUNT(DISTINCT r.class_date) FROM edu_class_sign_in r WHERE r.schedule_id=c.schedule_id AND r.del_flag='0') recorded_count FROM edu_course_schedule c LEFT JOIN sys_user t ON t.user_id=c.teacher_id WHERE c.del_flag='0' AND (c.course_class_name LIKE ? OR COALESCE(t.user_name,c.teacher_name) LIKE ? OR c.grade_name LIKE ? OR c.subject_name LIKE ?) ORDER BY recorded_count DESC,c.course_year DESC,c.schedule_id DESC",like,like,like,like);
    }
    private String studentKey(Map<String,Object> r) {
        return r.get("studentId")!=null?"S:"+r.get("studentId"):r.get("enrollmentId")!=null?"E:"+r.get("enrollmentId"):"R:"+r.get("signInId");
    }
    private String displayName(Map<String,Object> r) {
        for(String key:List.of("currentName","enrolledName","recordedName"))if(r.get(key)!=null&&!r.get(key).toString().isBlank())return r.get(key).toString();return "未填写姓名";
    }
    private Map<String,Object> studentRow(Map<String,Object> source) {
        var row=new LinkedHashMap<String,Object>();row.put("studentKey",studentKey(source));row.put("studentName",displayName(source));row.put("parentPhone",source.get("parentPhone"));row.put("linked",source.get("studentId")!=null);row.put("temporary",source.get("enrollmentId")==null);row.put("stats",emptyStats());return row;
    }
    private Map<String,Long> emptyStats() {
        var stats=new LinkedHashMap<String,Long>();for(String key:List.of("recorded","attended","normal","video","leave","trial","transfer","absent","approved","pending","returned","conflicts"))stats.put(key,0L);return stats;
    }
    private void increment(Map<String,Long> stats,String key) {stats.put(key,stats.get(key)+1);}
    private void countCell(Map<String,Long> stats,List<Map<String,Object>> records) {
        if(records.isEmpty())return;increment(stats,"recorded");if(records.size()!=1){increment(stats,"conflicts");return;}
        var r=records.get(0);String sign=String.valueOf(r.get("signStatus")),review=String.valueOf(r.get("reviewStatus"));
        if(ATTENDED_STATUSES.contains(sign))increment(stats,"attended");String kind=Map.of("1","normal","2","video","3","leave","4","trial","5","transfer","6","absent").get(sign);if(kind!=null)increment(stats,kind);
        increment(stats,"1".equals(review)?"approved":"2".equals(review)?"returned":"pending");
    }
    private List<Map<String,Object>> overviewRecords(String condition,List<Object> args,String from,String to) {
        String range=rangeSql(from,to,args);var rows=singleDb.rows("SELECT "+ATTENDANCE_FIELDS+ATTENDANCE_JOINS+" WHERE r.del_flag='0' AND c.del_flag='0' AND "+condition+range+" ORDER BY r.class_date DESC,r.schedule_id,r.sign_in_id LIMIT 5001",args.toArray());
        if(rows.size()>5000)throw new ServiceException("记录较多，请缩小日期范围后查看");return rows;
    }
    @Override @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @SuppressWarnings("unchecked") public Map<String,Object> attendanceOverview(Long scheduleId,String from,String to) {
        attendanceAdmin();attendanceRange(from,to);var course=attendanceCourse(scheduleId);var args=new ArrayList<Object>();args.add(scheduleId);var records=overviewRecords("r.schedule_id=?",args,from,to);
        var dates=records.stream().map(r->r.get("classDate").toString()).distinct().sorted().toList();if(dates.size()>120)throw new ServiceException("课次超过120天，请选择日期范围");
        var roster=singleDb.rows("SELECT e.enrollment_id,st.student_id,st.parent_id,st.student_name current_name,e.student_name enrolled_name,p.phonenumber parent_phone FROM edu_enrollment e LEFT JOIN edu_student st ON st.student_id=e.student_id AND st.parent_id=e.parent_id LEFT JOIN sys_user p ON p.user_id=e.parent_id WHERE e.schedule_id=? AND (e.del_flag='0' OR EXISTS(SELECT 1 FROM edu_class_sign_in r WHERE r.enrollment_id=e.enrollment_id AND r.schedule_id=e.schedule_id AND r.del_flag='0')) ORDER BY e.enrollment_id",scheduleId);
        var students=new LinkedHashMap<String,Map<String,Object>>();for(var r:roster)students.putIfAbsent(studentKey(r),studentRow(r));for(var r:records)students.putIfAbsent(studentKey(r),studentRow(r));
        if((long)students.size()*dates.size()>8000)throw new ServiceException("总览较大，请缩小日期范围后查看");
        var grouped=new LinkedHashMap<String,Map<String,List<Map<String,Object>>>>();for(var r:records)grouped.computeIfAbsent(studentKey(r),key->new LinkedHashMap<>()).computeIfAbsent(r.get("classDate").toString(),key->new ArrayList<>()).add(r);
        var totals=emptyStats();for(var entry:students.entrySet()) {
            var cells=new ArrayList<Map<String,Object>>();var stats=(Map<String,Long>)entry.getValue().get("stats");
            for(String date:dates){var same=grouped.getOrDefault(entry.getKey(),Map.of()).getOrDefault(date,List.of());var cell=new LinkedHashMap<String,Object>();cell.put("recorded",!same.isEmpty());
                if(same.size()==1){var r=same.get(0);cell.put("text",SIGN_LABELS.getOrDefault(String.valueOf(r.get("signStatus")),"未识别"));cell.put("status",r.get("signStatus"));cell.put("review",r.get("reviewStatus"));}
                else {cell.put("text",same.isEmpty()?"—":"待核对");cell.put("status",same.isEmpty()?"empty":"conflict");cell.put("review","");}
                countCell(stats,same);countCell(totals,same);cells.add(cell);}
            entry.getValue().put("cells",cells);
        }
        var result=new LinkedHashMap<String,Object>();result.put("course",course);result.put("dates",dates);result.put("rows",new ArrayList<>(students.values()));result.put("stats",totals);result.put("occurrenceCount",dates.size());return result;
    }
    @Override @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> attendanceStudent(Long scheduleId,String key,boolean allCourses,String from,String to,int pageNum) {
        attendanceAdmin();attendanceRange(from,to);attendanceCourse(scheduleId);if(key==null||!key.matches("[SER]:[1-9][0-9]{0,18}")||pageNum<1||pageNum>100000)throw new ServiceException("请选择有效学生");
        String field=key.startsWith("S:")?"st.student_id":key.startsWith("E:")?"e.enrollment_id":"r.sign_in_id";Long id;try{id=Long.valueOf(key.substring(2));}catch(Exception e){throw new ServiceException("学生编号无效");}
        var found=singleDb.rows("SELECT "+ATTENDANCE_FIELDS+ATTENDANCE_JOINS+" WHERE r.schedule_id=? AND "+field+"=? AND r.del_flag='0' AND c.del_flag='0' ORDER BY r.class_date DESC,r.sign_in_id DESC LIMIT 1",scheduleId,id);
        Map<String,Object> student;
        if(found.isEmpty()&&!key.startsWith("R:")) {
            var enrolled=singleDb.rows("SELECT e.enrollment_id,st.student_id,st.parent_id,st.student_name current_name,e.student_name enrolled_name,p.phonenumber parent_phone FROM edu_enrollment e LEFT JOIN edu_student st ON st.student_id=e.student_id AND st.parent_id=e.parent_id LEFT JOIN sys_user p ON p.user_id=e.parent_id WHERE e.schedule_id=? AND "+(key.startsWith("S:")?"st.student_id":"e.enrollment_id")+"=? AND e.del_flag='0' LIMIT 1",scheduleId,id);
            if(enrolled.isEmpty())throw new ServiceException("学生不属于所选课程");student=studentRow(enrolled.get(0));
        } else {if(found.isEmpty())throw new ServiceException("学生记录不属于所选课程");student=studentRow(found.get(0));}
        if(!key.equals(student.get("studentKey")))throw new ServiceException("学生身份已关联，请刷新总览");
        if(allCourses&&!key.startsWith("S:"))throw new ServiceException("历史或临时记录尚未关联孩子，不能按姓名合并跨课程统计");
        var args=new ArrayList<Object>();args.add(id);String condition=field+"=?";if(!allCourses){condition+=" AND r.schedule_id=?";args.add(scheduleId);}var records=overviewRecords(condition,args,from,to);
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();for(var r:records)grouped.computeIfAbsent(r.get("scheduleId")+":"+r.get("classDate"),x->new ArrayList<>()).add(r);
        var stats=emptyStats();var lessons=new ArrayList<Map<String,Object>>();for(var same:grouped.values()) {
            countCell(stats,same);var r=same.get(0);var lesson=new LinkedHashMap<String,Object>();for(String f:List.of("scheduleId","classDate","courseClassName","teacherName","recordedName"))lesson.put(f,r.get(f));lesson.put("statusText",same.size()==1?SIGN_LABELS.getOrDefault(String.valueOf(r.get("signStatus")),"未识别"):"多条记录待核对");lesson.put("reviewStatus",same.size()==1?r.get("reviewStatus"):null);lesson.put("conflict",same.size()>1);lessons.add(lesson);
        }
        int start=Math.min(lessons.size(),(pageNum-1)*30);return Map.of("student",student,"stats",stats,"rows",lessons.subList(start,Math.min(start+30,lessons.size())),"total",lessons.size(),"allCourses",allCourses);
    }

    @Override
    public List<EduClassSignIn> selectReviewList(String classDate)
    {
        return signInMapper.selectReviewList(classDate);
    }

    private Map<String, Object> attendanceStats(List<EduClassSignIn> rows)
    {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCount", rows.size());
        stats.put("actualCount", rows.stream().filter(r -> ATTENDED_STATUSES.contains(r.getSignStatus())).count());
        stats.put("attended", rows.stream().filter(r -> "1".equals(r.getSignStatus())).count());
        stats.put("recorded", rows.stream().filter(r -> "2".equals(r.getSignStatus())).count());
        stats.put("leave", rows.stream().filter(r -> "3".equals(r.getSignStatus())).count());
        stats.put("audited", rows.stream().filter(r -> "4".equals(r.getSignStatus())).count());
        stats.put("transferred", rows.stream().filter(r -> "5".equals(r.getSignStatus())).count());
        stats.put("absent", rows.stream().filter(r -> "6".equals(r.getSignStatus())).count());
        return stats;
    }

    /** Replacing any submitted row invalidates the sheet being reviewed; no duplicate snapshot table. */
    private String submissionVersion(List<EduClassSignIn> rows)
    {
        var sheet = rows.stream().sorted(java.util.Comparator.comparing(EduClassSignIn::getSignInId))
            .map(r -> Arrays.asList(r.getSignInId(), r.getEnrollmentId(), r.getStudentName(),
                r.getSourceType(), r.getSignStatus(), r.getRemark(), r.getCreateBy(),
                r.getTeacherName(), r.getTimeSlot(), r.getCourseClassName(),
                r.getCampusName(), r.getClassroomName(), r.getReviewStatus(),
                r.getReviewReason(), r.getReviewHistory())).toList();
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(com.alibaba.fastjson2.JSON.toJSONString(sheet).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public Map<String, Object> review(MiniAppSignReviewBody body, Long adminId, String adminName)
    {
        reviewer(adminId);
        if (body == null || body.getScheduleId() == null || StringUtils.isEmpty(body.getClassDate()))
            throw new ServiceException("缺少排课或课次日期");
        String action=body.getAction()==null?"APPROVE":body.getAction();
        if(!Set.of("APPROVE","RETURN").contains(action))throw new ServiceException("复核操作无效");
        if("RETURN".equals(action)){reject(body,adminId,adminName);return reviewResult(freshReviewInfo(body.getScheduleId(),body.getClassDate()));}
        Long scheduleId = body.getScheduleId();
        String classDate = body.getClassDate();
        try {
            if (!java.time.LocalDate.parse(classDate).toString().equals(classDate))
                throw new java.time.DateTimeException("Invalid date");
        } catch (java.time.DateTimeException e) {
            throw new ServiceException("课次日期格式应为 yyyy-MM-dd");
        }
        var schedule = scheduleMapper.lockSchedule(scheduleId);
        if (schedule == null || !"0".equals(schedule.getDelFlag()))
            throw new ServiceException("排课不存在或已删除");
        var details = signInMapper.selectDetailList(scheduleId, classDate);
        if (details.isEmpty()) throw new ServiceException("该课次尚未提交点名，不能审核");
        var info = signInMapper.selectReviewInfo(scheduleId, classDate);
        if (info != null && "1".equals(String.valueOf(info.get("review_status"))))
            return reviewResult(info); // Never overwrite the original auditor, time or images.
        checkSheetVersion(body,details,info);
        if ("2".equals(str(info.get("review_status"))) && !hasCorrection(details))
            throw new ServiceException("请先更正异常学员出勤，再重新审核确认");
        var images = body.getImages() == null ? List.<String>of() : body.getImages();
        if (images.size() > 9 || images.stream().anyMatch(Objects::isNull))
            throw new ServiceException("课堂图片最多9张");
        if(images.stream().anyMatch(x->!x.startsWith("/profile/") || x.contains(",") || x.contains("..")))throw new ServiceException("课堂图片路径无效");
        String imageList = String.join(",", images);
        if (imageList.length() > 2000) throw new ServiceException("课堂图片信息过长");
        int updated = info != null && info.get("review1_admin_id") != null
            ? signInMapper.completeLegacyReview(scheduleId, classDate, adminId, adminName, imageList)
            : signInMapper.updateReview1(scheduleId, classDate, adminId, adminName, imageList);
        if (updated != details.size()) throw new ServiceException("点名状态已变化，请刷新后再审核");
        appendHistory(scheduleId,classDate,info,"APPROVE",null,List.of(),snapshot(scheduleId,classDate),images);
        return reviewResult(freshReviewInfo(scheduleId, classDate));
    }

    private boolean hasCorrection(List<EduClassSignIn> rows)
    {
        return rows.stream().anyMatch(r -> {
            var history = com.alibaba.fastjson2.JSON.parseArray(r.getReviewHistory());
            return history != null && !history.isEmpty()
                && "CORRECT".equals(history.getJSONObject(history.size() - 1).getString("action"));
        });
    }

    /** Use the existing sheet and schedule lock; never recreate identities or touch money/credits. */
    private List<EduClassSignIn> exceptionSheet(MiniAppSignReviewBody body)
    {
        if (body == null || body.getScheduleId() == null || body.getClassDate() == null)
            throw new ServiceException("请选择课次");
        try {
            if (!java.time.LocalDate.parse(body.getClassDate()).toString().equals(body.getClassDate()))
                throw new java.time.DateTimeException("Invalid date");
        } catch (java.time.DateTimeException e) { throw new ServiceException("课次日期格式应为 yyyy-MM-dd"); }
        var schedule = scheduleMapper.lockSchedule(body.getScheduleId());
        if (schedule == null || !"0".equals(schedule.getDelFlag())) throw new ServiceException("排课不存在或已删除");
        var rows = signInMapper.selectDetailList(body.getScheduleId(), body.getClassDate());
        if (rows.isEmpty()) throw new ServiceException("该课次尚未提交点名");
        if (rows.stream().anyMatch(r -> "1".equals(r.getReviewStatus())))
            throw new ServiceException("已确认课次已锁定，不可直接更正");
        checkSheetVersion(body,rows,signInMapper.selectReviewInfo(body.getScheduleId(),body.getClassDate()));
        if (!singleDb.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND class_date=? AND lesson_completed_time IS NOT NULL FOR UPDATE",
                body.getScheduleId(), body.getClassDate()).isEmpty())
            throw new ServiceException("本课次已完成消课，不可直接更正，请先核对消课历史");
        if (!singleDb.rows("SELECT record_id FROM edu_teacher_salary_record WHERE teacher_id=? AND salary_month=? AND confirm_status='1' AND del_flag='0' FOR UPDATE",
                schedule.getTeacherId(), body.getClassDate().substring(0,7)).isEmpty())
            throw new ServiceException("该教师本月薪资已结算，不可直接更正出勤");
        return rows;
    }

    private String correctionReason(String value)
    {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new ServiceException("请填写1至500字的异常或更正原因");
        return reason;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reject(MiniAppSignReviewBody body, Long adminId, String adminName)
    {
        reviewer(adminId);
        var rows = exceptionSheet(body);
        body.setReason(correctionReason(body.getReason()));
        int updated = singleDb.jdbc().update("UPDATE edu_class_sign_in SET review_history=JSON_ARRAY_APPEND(COALESCE(review_history,JSON_ARRAY()),'$',"
            + "JSON_OBJECT('action','REJECT','beforeStatus',sign_status,'afterStatus',sign_status,'actorId',CAST(? AS CHAR),'actorName',?,'time',DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'),'reason',?)),"
            + "review_reason=?,review_status='2',review_version=review_version+1,update_by=?,update_time=NOW() WHERE schedule_id=? AND class_date=? AND del_flag='0' AND review_status IN ('0','2')",
            adminId,adminName,body.getReason(),body.getReason(),adminName,body.getScheduleId(),body.getClassDate());
        if (updated != rows.size()) throw new ServiceException("课次已变化，请刷新后操作");
        return selectDetail(body.getScheduleId(), body.getClassDate());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> correct(MiniAppSignReviewBody body, Long adminId, String adminName)
    {
        reviewer(adminId);
        var rows = exceptionSheet(body);
        if (rows.stream().anyMatch(r -> !"2".equals(r.getReviewStatus()))) throw new ServiceException("请先标记审核异常，再更正出勤");
        if (body.getCorrections() != null && (body.getSignInId() != null || body.getSignStatus() != null))
            throw new ServiceException("不能混用单人和批量更正参数");
        var changes = body.getCorrections() == null
            ? List.of(new MiniAppSignReviewBody.Correction(body.getSignInId(), body.getSignStatus(), body.getReason())) : body.getCorrections();
        if (changes.isEmpty() || changes.size() > rows.size()) throw new ServiceException("请选择本课次需要更正的学员");
        var identities = new java.util.HashMap<Long, EduClassSignIn>();
        rows.forEach(row -> identities.put(row.getSignInId(), row));
        var seen = new java.util.HashSet<Long>();
        // Validate the entire batch before writing; the transaction also rolls back any SQL failure.
        for (var change : changes) {
            var row = change == null ? null : identities.get(change.getSignInId());
            if (row == null) throw new ServiceException("该学员不属于当前课次");
            if (!seen.add(row.getSignInId())) throw new ServiceException("同一学员不能重复更正");
            var allowed = "2".equals(row.getSourceType()) ? Set.of("3","4","6")
                : "3".equals(row.getSourceType()) ? Set.of("3","5","6") : Set.of("1","2","3","6");
            if (change.getSignStatus() == null || !allowed.contains(change.getSignStatus()))
                throw new ServiceException("请选择符合学员来源的出勤状态");
            if (change.getSignStatus().equals(row.getSignStatus())) throw new ServiceException("学生「" + row.getStudentName() + "」的出勤状态未改变");
            change.setReason(correctionReason(change.getReason()));
        }
        for (var change : changes) {
            int updated = singleDb.jdbc().update("UPDATE edu_class_sign_in SET review_history=JSON_ARRAY_APPEND(COALESCE(review_history,JSON_ARRAY()),'$',"
                + "JSON_OBJECT('action','CORRECT','beforeStatus',sign_status,'afterStatus',?,'actorId',CAST(? AS CHAR),'actorName',?,'time',DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'),'reason',?)),"
                + "sign_status=?,update_by=?,update_time=NOW() WHERE sign_in_id=? AND schedule_id=? AND class_date=? AND del_flag='0' AND review_status='2'",
                change.getSignStatus(),adminId,adminName,change.getReason(),change.getSignStatus(),adminName,change.getSignInId(),body.getScheduleId(),body.getClassDate());
            if (updated != 1) throw new ServiceException("出勤已变化，请刷新后操作");
        }
        long nextVersion=version(freshReviewInfo(body.getScheduleId(),body.getClassDate()))+1;
        singleDb.jdbc().update("UPDATE edu_class_sign_in SET review_version=? WHERE schedule_id=? AND class_date=? AND del_flag='0'",nextVersion,body.getScheduleId(),body.getClassDate());
        return selectDetail(body.getScheduleId(), body.getClassDate());
    }

    private Map<String, Object> reviewResult(Map<String, Object> info)
    {
        Map<String, Object> result = formatReviewInfo(info);
        result.put("requiredConfirmations", 1);
        return result;
    }
    // JDBC 写入不会清除 MyBatis 会话缓存，操作后的状态须从数据库重新读取。
    private Map<String,Object> freshReviewInfo(Long scheduleId,String date) {
        return singleDb.jdbc().queryForMap("SELECT MAX(review_status) review_status,MAX(review_reason) review_reason,MAX(review_version) review_version,MAX(CAST(review_history AS CHAR)) review_history,MAX(review1_admin_id) review1_admin_id,MAX(review1_admin_name) review1_admin_name,MAX(review1_images) review1_images,MAX(review1_time) review1_time,MAX(review2_admin_id) review2_admin_id,MAX(review2_admin_name) review2_admin_name,MAX(review2_images) review2_images,MAX(review2_time) review2_time,COUNT(*) total_count,SUM(sign_status IN('1','4','5')) actual_count FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0'",scheduleId,date);
    }
    private boolean locked(Map<String,Object> info) {
        return info!=null && ("1".equals(str(info.get("review_status"))) || (info.get("review_history")==null && (info.get("review1_admin_id")!=null || info.get("review2_admin_id")!=null)));
    }
    private long version(Map<String,Object> info) {return info==null || info.get("review_version")==null?0:Long.parseLong(info.get("review_version").toString());}
    private void checkVersion(Long expected,Map<String,Object> info) {
        if(!Objects.equals(expected==null?0L:expected,version(info)))throw new ServiceException("该课次已更新，请重新加载后操作");
    }
    private void checkSheetVersion(MiniAppSignReviewBody body,List<EduClassSignIn> rows,Map<String,Object> info) {
        if(body.getSubmissionVersion()!=null && !body.getSubmissionVersion().isBlank()) {
            if(!submissionVersion(rows).equals(body.getSubmissionVersion()))throw new ServiceException("点名表已变化或版本缺失，请刷新核对后再确认");
        } else {
            if(body.getReviewVersion()==null)throw new ServiceException("点名表版本缺失，请刷新后操作");
            checkVersion(body.getReviewVersion(),info);
        }
    }
    private JSONArray history(Map<String,Object> info) {
        return info==null || info.get("review_history")==null?new JSONArray():JSON.parseArray(info.get("review_history").toString());
    }
    private List<Map<String,Object>> snapshot(Long scheduleId,String date) {
        return singleDb.rows("SELECT sign_in_id,enrollment_id,student_name,source_type,sign_status,remark,review_status,review1_admin_id,review1_admin_name,review1_time,review1_images,review2_admin_id,review2_admin_name,review2_time,review2_images FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0' ORDER BY sign_in_id",scheduleId,date);
    }
    private void appendHistory(Long scheduleId,String date,Map<String,Object> info,String action,String reason,List<Map<String,Object>> before,List<Map<String,Object>> after,List<String> images) {
        var event=new LinkedHashMap<String,Object>();event.put("action",action);event.put("version",version(info)+1);event.put("reason",reason);event.put("actorId",SecurityUtils.getUserId().toString());event.put("actorName",SecurityUtils.getUsername());event.put("time",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());event.put("before",before);event.put("after",after);
        event.put("images",images);
        singleDb.jdbc().update("UPDATE edu_class_sign_in SET review_history=JSON_ARRAY_APPEND(COALESCE(review_history,JSON_ARRAY()),'$',CAST(? AS JSON)),review_version=? WHERE schedule_id=? AND class_date=?",JSON.toJSONString(event),version(info)+1,scheduleId,date);
    }
}
