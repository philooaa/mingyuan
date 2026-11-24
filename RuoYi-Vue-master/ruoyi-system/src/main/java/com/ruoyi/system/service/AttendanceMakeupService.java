package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.shop.ShopRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A roster-only makeup plan: original attendance, enrollment and money are never edited. */
@Service
public class AttendanceMakeupService {
    private final ShopRepository db;
    private final AttendanceLessonRosterService rosters;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public AttendanceMakeupService(ShopRepository db,AttendanceLessonRosterService rosters) { this.db=db;this.rosters=rosters; }
    public record ArrangeRequest(Long signInId,Long targetLessonId,String remark,String mode,String classDate,String startTime,String endTime,String location) {
        public ArrangeRequest(Long signInId,Long targetLessonId,String remark) { this(signInId,targetLessonId,remark,"CLASS",null,null,null,null); }
    }
    public record CancelRequest(Long planId,String reason) {}
    private static String text(Object v) { return v==null?"":String.valueOf(v); }
    private static long id(Object v) { return Long.parseLong(text(v)); }
    private static boolean yes(Object v) { return "1".equals(text(v))||Boolean.TRUE.equals(v); }
    private static void require(boolean ok,String message) { if(!ok)throw new ServiceException(message); }
    private static boolean finalReview(Map<String,Object> row) {
        return "1".equals(text(row.get("reviewStatus")))&&Set.of("","FINAL").contains(text(row.get("reviewStage")));
    }
    private Set<String> roles() {
        long actor=SecurityUtils.getUserId();
        var rows=db.rows("SELECT r.role_key FROM sys_user u LEFT JOIN sys_user_role ur ON ur.user_id=u.user_id LEFT JOIN sys_role r ON r.role_id=ur.role_id AND r.status='0' AND r.del_flag='0' WHERE u.user_id=? AND u.status='0' AND u.del_flag='0'",actor);
        var result=new HashSet<String>();for(var row:rows)result.add(text(row.get("roleKey")));
        if(actor==1&&!rows.isEmpty())result.add("admin");return result;
    }
    private boolean authorized(long signIn,boolean write) {
        var assigned=roles();boolean teacher=assigned.contains("teacher");
        boolean staff=assigned.stream().anyMatch(r->Set.of("admin","education","business_admin").contains(r));
        require(write?teacher:teacher||staff,write?"仅原任课教师可维护补课":"仅教师、教务或管理员可查看补课");
        var owner=db.one("SELECT COALESCE(l.teacher_id,c.teacher_id) teacher_id FROM edu_class_sign_in r JOIN edu_course_schedule c ON c.schedule_id=r.schedule_id LEFT JOIN edu_attendance_lesson l ON l.schedule_id=r.schedule_id AND l.class_date=r.class_date WHERE r.sign_in_id=? ORDER BY l.lesson_id LIMIT 1",signIn);
        boolean own=teacher&&Objects.equals(text(owner.get("teacherId")),text(SecurityUtils.getUserId()));
        require(!write&&staff||own,"只能维护本人原授课讲次的补课");return own;
    }
    private void recheckOwner(long signIn) {
        require(!db.rows("SELECT u.user_id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role role ON role.role_id=ur.role_id WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' AND role.role_key='teacher' AND role.status='0' AND role.del_flag='0' FOR SHARE",SecurityUtils.getUserId()).isEmpty(),"仅原任课教师可维护补课");
        var owner=db.one("SELECT COALESCE(l.teacher_id,c.teacher_id) teacher_id FROM edu_class_sign_in r JOIN edu_course_schedule c ON c.schedule_id=r.schedule_id LEFT JOIN edu_attendance_lesson l ON l.schedule_id=r.schedule_id AND l.class_date=r.class_date WHERE r.sign_in_id=? ORDER BY l.lesson_id LIMIT 1 FOR UPDATE",signIn);
        require(Objects.equals(text(owner.get("teacherId")),text(SecurityUtils.getUserId())),"原讲次教师已变更，请刷新后重试");
    }
    private Map<String,Object> source(long signIn) {
        var row=db.one("SELECT r.*,e.student_id enrollment_student_id,e.parent_id enrollment_parent_id,e.schedule_id enrollment_schedule_id,e.del_flag enrollment_deleted,COALESCE(r.student_id,e.student_id) stable_student_id FROM edu_class_sign_in r LEFT JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id WHERE r.sign_in_id=?",signIn);
        require("0".equals(text(row.get("delFlag")))&&"3".equals(text(row.get("signStatus")))&&finalReview(row),"只能安排已终审的请假记录");
        require(row.get("stableStudentId")!=null,"请先关联稳定学生档案");
        long student=id(row.get("stableStudentId"));
        var profile=db.one("SELECT student_id,student_name,parent_id FROM edu_student WHERE student_id=? AND del_flag='0' FOR UPDATE",student);
        if(row.get("enrollmentId")!=null) {
            require("0".equals(text(row.get("enrollmentDeleted")))&&Objects.equals(text(row.get("scheduleId")),text(row.get("enrollmentScheduleId")))&&Objects.equals(text(row.get("enrollmentStudentId")),text(student)),"请假记录与报名档案不一致，请先核对");
            if(row.get("studentId")==null)require(Objects.equals(text(profile.get("parentId")),text(row.get("enrollmentParentId"))),"请假记录与家长档案不一致");
        }
        // Lock both the original sheet and its identity range, including duplicate records.
        var locked=db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=? FOR UPDATE",signIn);
        require("0".equals(text(locked.get("delFlag")))&&"3".equals(text(locked.get("signStatus")))&&finalReview(locked)
                &&Objects.equals(text(locked.get("studentId")),text(row.get("studentId")))&&Objects.equals(text(locked.get("enrollmentId")),text(row.get("enrollmentId")))
                &&Objects.equals(text(locked.get("classDate")),text(row.get("classDate")))&&Objects.equals(text(locked.get("scheduleId")),text(row.get("scheduleId"))),"原请假记录已变更，请刷新后重试");
        rosters.makeupLessons(id(row.get("scheduleId")));
        var lessons=db.rows("SELECT * FROM edu_attendance_lesson WHERE schedule_id=? AND class_date=? FOR UPDATE",row.get("scheduleId"),row.get("classDate"));
        require(lessons.size()==1,"原请假讲次不明确，请先核对排课");
        var lesson=lessons.get(0);
        var identities=db.rows("SELECT r.sign_in_id FROM edu_class_sign_in r LEFT JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id WHERE r.schedule_id=? AND r.class_date=? AND r.del_flag='0' AND (COALESCE(r.student_id,e.student_id)=? OR (? IS NOT NULL AND r.enrollment_id=?)) FOR UPDATE",row.get("scheduleId"),row.get("classDate"),student,row.get("enrollmentId"),row.get("enrollmentId"));
        require(identities.size()==1,"原请假签到身份重复，请先核对");
        require(!"3".equals(text(row.get("sourceType")))||db.rows("SELECT plan_id FROM edu_attendance_makeup WHERE target_lesson_id=? AND student_id=? LIMIT 1",lesson.get("lessonId"),student).isEmpty(),"补课产生的请假须从原请假记录重新安排");
        row.put("sourceLessonId",lesson.get("lessonId"));row.put("lessonNo",lesson.get("lessonNo"));
        row.put("studentName",profile.get("studentName"));row.put("courseName",lesson.get("courseName"));
        return row;
    }
    private List<Map<String,Object>> submissions(Map<String,Object> lesson) {
        // This range lock also prevents a teacher submitting an empty target while arranging/cancelling.
        return db.rows("SELECT r.*,COALESCE(r.student_id,e.student_id) stable_student_id FROM edu_class_sign_in r LEFT JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id WHERE r.schedule_id=? AND r.class_date=? AND r.del_flag='0' FOR UPDATE",lesson.get("scheduleId"),lesson.get("classDate"));
    }
    private Map<String,Object> lesson(long lessonId) {
        var initial=db.one("SELECT l.schedule_id,c.status target_status,c.del_flag target_deleted FROM edu_attendance_lesson l JOIN edu_course_schedule c ON c.schedule_id=l.schedule_id WHERE l.lesson_id=?",lessonId);
        if("0".equals(text(initial.get("targetStatus")))&&"0".equals(text(initial.get("targetDeleted"))))rosters.makeupLessons(id(initial.get("scheduleId")));
        return db.one("SELECT l.*,c.status target_status,c.del_flag target_deleted FROM edu_attendance_lesson l JOIN edu_course_schedule c ON c.schedule_id=l.schedule_id WHERE l.lesson_id=? FOR UPDATE",lessonId);
    }
    private boolean futureEditable(Map<String,Object> lesson) {
        if(yes(lesson.get("cancelled"))||yes(lesson.get("locked"))||lesson.get("classDate")==null||lesson.get("startTime")==null||lesson.get("endTime")==null)return false;
        LocalTime start=LocalTime.parse(text(lesson.get("startTime"))),end=LocalTime.parse(text(lesson.get("endTime")));
        return end.isAfter(start)&&LocalDateTime.of(ScheduleCalendar.date(lesson.get("classDate")),start).isAfter(LocalDateTime.now(ZONE))
                &&db.rows("SELECT sign_in_id FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0' LIMIT 1",lesson.get("scheduleId"),lesson.get("classDate")).isEmpty();
    }
    private boolean contains(Map<String,Object> lesson,long student) {
        return rosters.makeupRoster(lesson).stream().anyMatch(r->text(student).equals(text(r.get("studentId"))));
    }
    private void syncStudentCourses(long student) {
        var courses=db.rows("SELECT c.schedule_id FROM edu_course_schedule c WHERE c.del_flag='0' AND c.status='0' AND COALESCE(c.class_mode,'1')<>'2' AND (EXISTS(SELECT 1 FROM edu_enrollment e WHERE e.schedule_id=c.schedule_id AND e.student_id=? AND e.del_flag='0' AND e.enrollment_status IN ('0','1','待确认','已确认','报名成功')) OR EXISTS(SELECT 1 FROM edu_attendance_membership m WHERE m.schedule_id=c.schedule_id AND m.student_id=?) OR EXISTS(SELECT 1 FROM edu_attendance_lesson_guest g JOIN edu_attendance_lesson l ON l.lesson_id=g.lesson_id WHERE l.schedule_id=c.schedule_id AND g.student_id=?)) ORDER BY c.schedule_id",student,student,student);
        for(var course:courses)rosters.makeupLessons(id(course.get("scheduleId")));
    }
    private boolean overlap(Map<String,Object> target,long student,long sourceLesson) {
        var overlaps=db.rows("SELECT l.* FROM edu_attendance_lesson l JOIN edu_course_schedule c ON c.schedule_id=l.schedule_id WHERE l.class_date=? AND l.cancelled=0 AND l.lesson_id<>? AND l.lesson_id<>? AND c.del_flag='0' AND c.status='0' AND (l.start_time IS NULL OR l.end_time IS NULL OR (l.start_time<? AND l.end_time>?)) ORDER BY l.lesson_id",target.get("classDate"),target.get("lessonId"),sourceLesson,target.get("endTime"),target.get("startTime"));
        for(var other:overlaps)if(contains(other,student))return true;
        return reservationConflict(target.get("classDate"),target.get("startTime"),target.get("endTime"),null,student);
    }
    private List<Map<String,Object>> history(long signIn) {
        return db.rows("SELECT p.plan_id,p.arrange_type,l.course_name,l.lesson_no,TIME_FORMAT(l.start_time,'%H:%i') start_time,TIME_FORMAT(l.end_time,'%H:%i') end_time,c.lesson_location location,DATE_FORMAT(l.class_date,'%Y-%m-%d') class_date,p.cancelled,p.remark,p.cancel_reason,p.create_time,COALESCE(NULLIF(u.nick_name,''),u.user_name) operator_name FROM edu_attendance_makeup p JOIN edu_attendance_lesson l ON l.lesson_id=p.target_lesson_id JOIN edu_course_schedule c ON c.schedule_id=l.schedule_id LEFT JOIN sys_user u ON u.user_id=p.operator_id WHERE p.sign_in_id=? ORDER BY p.plan_id DESC",signIn);
    }
    @Transactional public Map<String,Object> options(long signIn,Long schedule,String keyword) {
        boolean canManage=authorized(signIn,false);require(text(keyword).length()<=100,"搜索词过长");var source=source(signIn);
        long student=id(source.get("stableStudentId")),origin=id(source.get("sourceLessonId"));
        var courses=db.rows("SELECT c.schedule_id,c.course_class_name course_name,COALESCE(NULLIF(u.nick_name,''),u.user_name) teacher_name,c.course_year year,c.term_name,CONCAT(TIME_FORMAT(c.start_time,'%H:%i'),'-',TIME_FORMAT(c.end_time,'%H:%i')) time_slot FROM edu_course_schedule c LEFT JOIN sys_user u ON u.user_id=c.teacher_id WHERE c.del_flag='0' AND c.status='0' AND COALESCE(c.class_mode,'1')<>'2' AND NOT EXISTS(SELECT 1 FROM edu_attendance_makeup_session ms WHERE ms.schedule_id=c.schedule_id) AND (c.course_class_name LIKE ? OR u.nick_name LIKE ? OR u.user_name LIKE ?) ORDER BY c.course_year DESC,c.schedule_id DESC","%"+text(keyword).trim()+"%","%"+text(keyword).trim()+"%","%"+text(keyword).trim()+"%");
        var choices=new ArrayList<Map<String,Object>>();
        if(canManage&&schedule!=null&&courses.stream().anyMatch(c->id(c.get("scheduleId"))==schedule)) {
            syncStudentCourses(student);
            for(var l:rosters.makeupLessons(schedule))if(futureEditable(l)&&id(l.get("lessonId"))!=origin&&!contains(l,student)&&!overlap(l,student,origin)) {
                var choice=new LinkedHashMap<String,Object>();for(String field:List.of("lessonId","lessonNo","classDate","startTime","endTime"))choice.put(field,l.get(field));
                choice.put("label","第"+l.get("lessonNo")+"讲 · "+l.get("classDate")+" "+l.get("startTime")+"-"+l.get("endTime"));choices.add(choice);
            }
        }
        var brief=new LinkedHashMap<String,Object>();for(String f:List.of("studentName","courseName","lessonNo","classDate"))brief.put(f,source.get(f));
        return Map.of("source",brief,"courses",canManage?courses:List.of(),"lessons",choices,"history",history(signIn),"canManageMakeup",canManage);
    }
    private boolean failed(Map<String,Object> plan,Map<String,Object> target,List<Map<String,Object>> sheet) {
        require(sheet.stream().allMatch(AttendanceMakeupService::finalReview),"补课考勤正在审核，暂不能取消或重排");
        var own=sheet.stream().filter(r->Objects.equals(text(r.get("stableStudentId")),text(plan.get("studentId")))).toList();
        require(own.size()<=1,"补课签到重复，请先核对");
        require(own.stream().noneMatch(r->Set.of("1","4","5").contains(text(r.get("signStatus")))),"补课已经终审到课，不能取消或重排");
        require(own.stream().allMatch(r->Set.of("2","3","6").contains(text(r.get("signStatus")))),"补课签到状态不明确，请先核对");
        if(yes(target.get("cancelled"))||!"0".equals(text(target.get("targetStatus")))||!"0".equals(text(target.get("targetDeleted"))))return true;
        if(sheet.isEmpty())return false;
        require(own.size()==1,"补课考勤缺少学生记录，请先核对");
        return true;
    }
    private void retire(Map<String,Object> plan,String reason) {
        db.jdbc().update("UPDATE edu_attendance_makeup SET cancelled=1,cancel_by=?,cancel_reason=?,cancel_time=NOW() WHERE plan_id=? AND cancelled=0",SecurityUtils.getUserId(),reason,plan.get("planId"));
        db.jdbc().update("DELETE FROM edu_attendance_lesson_guest WHERE guest_id=? AND lesson_id=? AND student_id=? AND source_lesson_id=?",plan.get("guestId"),plan.get("targetLessonId"),plan.get("studentId"),plan.get("sourceLessonId"));
        db.jdbc().update("UPDATE edu_attendance_lesson l JOIN edu_attendance_makeup_session ms ON ms.schedule_id=l.schedule_id SET l.cancelled=1 WHERE l.lesson_id=? AND l.locked=0 AND NOT EXISTS(SELECT 1 FROM edu_attendance_lesson_guest g WHERE g.lesson_id=l.lesson_id) AND NOT EXISTS(SELECT 1 FROM edu_class_sign_in r WHERE r.schedule_id=l.schedule_id AND r.class_date=l.class_date AND r.del_flag='0')",plan.get("targetLessonId"));
        db.jdbc().update("UPDATE edu_course_schedule c JOIN edu_attendance_makeup_session ms ON ms.schedule_id=c.schedule_id JOIN edu_attendance_lesson l ON l.schedule_id=c.schedule_id SET c.status='1' WHERE l.lesson_id=? AND l.cancelled=1",plan.get("targetLessonId"));
    }
    private void validateCustom(ArrangeRequest input) {
        try {
            require(text(input.classDate()).matches("\\d{4}-\\d{2}-\\d{2}")&&text(input.startTime()).matches("\\d{2}:\\d{2}")&&text(input.endTime()).matches("\\d{2}:\\d{2}"),"请输入有效日期和起止时间");
            var day=LocalDate.parse(input.classDate());var start=LocalTime.parse(input.startTime());var end=LocalTime.parse(input.endTime());
            require(day.getYear()<=2100&&end.isAfter(start)&&day.atTime(start).isAfter(LocalDateTime.now(ZONE)),"请选择未来有效的起止时间");
        } catch(java.time.DateTimeException ex) { throw new ServiceException("请输入有效日期和起止时间"); }
        require(!text(input.location()).trim().isEmpty()&&text(input.location()).length()<=200,"请输入200字以内的上课地点");
    }
    private boolean sameCustom(Map<String,Object> old,ArrangeRequest input) {
        if(!"CUSTOM".equals(text(old.get("arrangeType"))))return false;
        var row=db.one("SELECT DATE_FORMAT(l.class_date,'%Y-%m-%d') class_date,TIME_FORMAT(l.start_time,'%H:%i') start_time,TIME_FORMAT(l.end_time,'%H:%i') end_time,ms.location FROM edu_attendance_lesson l JOIN edu_attendance_makeup_session ms ON ms.schedule_id=l.schedule_id WHERE l.lesson_id=?",old.get("targetLessonId"));
        return input.classDate().equals(text(row.get("classDate")))&&input.startTime().equals(text(row.get("startTime")))&&input.endTime().equals(text(row.get("endTime")))&&input.location().trim().equals(text(row.get("location")));
    }
    private Map<String,Object> customLesson(ArrangeRequest input,Map<String,Object> source) {
        long actor=SecurityUtils.getUserId();
        var shared=db.rows("SELECT l.lesson_id FROM edu_attendance_makeup_session ms JOIN edu_attendance_lesson l ON l.schedule_id=ms.schedule_id JOIN edu_course_schedule c ON c.schedule_id=ms.schedule_id WHERE ms.source_lesson_id=? AND ms.teacher_id=? AND ms.location=? AND l.class_date=? AND l.start_time=? AND l.end_time=? AND l.cancelled=0 AND l.locked=0 AND c.status='0' AND c.del_flag='0' ORDER BY l.lesson_id FOR UPDATE",source.get("sourceLessonId"),actor,input.location().trim(),input.classDate(),input.startTime(),input.endTime());
        for(var candidate:shared){var existing=lesson(id(candidate.get("lessonId")));if(futureEditable(existing))return existing;}
        // Materialize the teacher's ordinary calendars before comparing stable occurrences.
        for(var course:db.rows("SELECT schedule_id FROM edu_course_schedule WHERE teacher_id=? AND status='0' AND del_flag='0' AND COALESCE(class_mode,'1')<>'2' ORDER BY schedule_id",actor))rosters.makeupLessons(id(course.get("scheduleId")));
        require(db.rows("SELECT l.lesson_id FROM edu_attendance_lesson l JOIN edu_course_schedule c ON c.schedule_id=l.schedule_id WHERE COALESCE(l.teacher_id,c.teacher_id)=? AND l.cancelled=0 AND c.status='0' AND c.del_flag='0' AND l.class_date=? AND (l.start_time IS NULL OR l.end_time IS NULL OR (l.start_time<? AND l.end_time>?))",actor,input.classDate(),input.endTime(),input.startTime()).isEmpty(),"补课时间与教师其他课程冲突");
        require(!reservationConflict(input.classDate(),input.startTime(),input.endTime(),actor,null),"补课时间与教师预约冲突");
        var origin=db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=?",source.get("scheduleId"));
        var values=new LinkedHashMap<String,Object>();
        values.put("schedule_code","MK"+UUID.randomUUID().toString().replace("-","").substring(0,28));String name="自定义补课 · "+text(source.get("courseName"));values.put("course_class_name",name.substring(0,Math.min(100,name.length())));
        for(String[] field:new String[][]{{"grade_name","gradeName"},{"subject_name","subjectName"},{"term_name","termName"}})values.put(field[0],text(origin.get(field[1])));
        values.put("course_year",LocalDate.parse(input.classDate()).getYear());values.put("period_name","补课");values.put("time_slot",input.startTime()+"-"+input.endTime());values.put("class_pattern","DAILY");values.put("start_date",input.classDate());values.put("end_date",input.classDate());values.put("start_time",input.startTime());values.put("end_time",input.endTime());values.put("teacher_id",actor);values.put("class_mode","1");values.put("recruit_status","1");values.put("enrolled_count",0);values.put("lesson_location",input.location().trim());
        long schedule=db.insert("edu_course_schedule",values);
        db.jdbc().update("INSERT INTO edu_attendance_makeup_session(schedule_id,source_lesson_id,teacher_id,location) VALUES(?,?,?,?)",schedule,source.get("sourceLessonId"),actor,input.location().trim());
        var lesson=new LinkedHashMap<String,Object>();lesson.put("schedule_id",schedule);lesson.put("lesson_no",1);lesson.put("original_date",input.classDate());lesson.put("class_date",input.classDate());lesson.put("course_year",values.get("course_year"));lesson.put("term_name",values.get("term_name"));lesson.put("teacher_id",actor);lesson.put("course_name",values.get("course_class_name"));lesson.put("start_time",input.startTime());lesson.put("end_time",input.endTime());
        long lessonId=db.insert("edu_attendance_lesson",lesson);return lesson(lessonId);
    }
    private boolean reservationConflict(Object date,Object start,Object end,Long teacher,Long student) {
        return !db.rows("SELECT e.enrollment_id FROM edu_enrollment e JOIN edu_course_schedule c ON c.schedule_id=e.schedule_id WHERE c.status='0' AND c.del_flag='0' AND c.class_mode='2' AND e.del_flag='0' AND e.enrollment_status IN ('0','1','待确认','已确认','报名成功') AND e.class_date=? AND (? IS NULL OR c.teacher_id=?) AND (? IS NULL OR e.student_id=?) AND (c.start_time IS NULL OR c.end_time IS NULL OR (c.start_time<? AND c.end_time>?))",date,teacher,teacher,student,student,end,start).isEmpty();
    }
    @Transactional public Map<String,Object> arrange(ArrangeRequest input) {
        require(input!=null&&input.signInId()!=null,"请选择原请假记录");authorized(input.signInId(),true);
        String mode=text(input.mode()).isBlank()?"CLASS":input.mode();require(Set.of("CLASS","CUSTOM").contains(mode),"请选择有效补课方式");
        boolean custom="CUSTOM".equals(mode);
        require(custom?input.targetLessonId()==null:input.targetLessonId()!=null,"请选择补课讲次，自定义补课不能同时选择班级");
        if(custom)validateCustom(input);
        // Match course-planning lock order: teacher first, then stable student, source and targets.
        db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",SecurityUtils.getUserId());
        require(text(input.remark()).length()<=500,"备注不能超过500字");var source=source(input.signInId());recheckOwner(input.signInId());
        long student=id(source.get("stableStudentId")),origin=id(source.get("sourceLessonId"));
        var active=db.rows("SELECT * FROM edu_attendance_makeup WHERE sign_in_id=? AND cancelled=0 FOR UPDATE",input.signInId());
        if(!active.isEmpty()) {
            var old=active.get(0);if(custom?sameCustom(old,input):"CLASS".equals(text(old.get("arrangeType")))&&id(old.get("targetLessonId"))==input.targetLessonId())return old;
            var target=lesson(id(old.get("targetLessonId")));
            require(failed(old,target,submissions(target)),"已有有效补课安排，请先取消后重新安排");retire(old,"原补课未完成，重新安排");
        }
        require(custom||input.targetLessonId()!=origin,"补课讲次不能是原请假讲次");
        syncStudentCourses(student);var target=custom?customLesson(input,source):lesson(input.targetLessonId());
        var course=db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=? AND del_flag='0'",target.get("scheduleId"));
        require(custom||db.rows("SELECT schedule_id FROM edu_attendance_makeup_session WHERE schedule_id=?",target.get("scheduleId")).isEmpty(),"自定义补课不能作为插班目标");
        require("0".equals(text(course.get("status")))&&!"2".equals(text(course.get("classMode"))),"请选择启用的班课");
        require(submissions(target).isEmpty()&&futureEditable(target),"请选择未来未提交、未锁定且未取消的补课讲次");
        require(!contains(target,student),"学生已在目标讲次名单中");
        require(!overlap(target,student,origin),"补课时间与学生其他课程冲突");
        require(db.rows("SELECT guest_id FROM edu_attendance_lesson_guest WHERE source_lesson_id=? AND student_id=? FOR UPDATE",origin,student).isEmpty(),"原讲次已有临时调课安排，请先核对");
        require(db.rows("SELECT guest_id FROM edu_attendance_lesson_guest WHERE source_lesson_id=? AND student_id=? FOR UPDATE",target.get("lessonId"),student).isEmpty(),"学生已从目标讲次调出，请先取消原调课");
        long guest=db.insert("edu_attendance_lesson_guest",Map.of("lesson_id",target.get("lessonId"),"student_id",student,"source","TRANSFER","source_lesson_id",origin,"operator_id",SecurityUtils.getUserId()));
        long plan=db.insert("edu_attendance_makeup",Map.of("sign_in_id",input.signInId(),"source_lesson_id",origin,"student_id",student,"target_lesson_id",target.get("lessonId"),"arrange_type",mode,"guest_id",guest,"remark",text(input.remark()).trim(),"operator_id",SecurityUtils.getUserId()));
        return db.one("SELECT * FROM edu_attendance_makeup WHERE plan_id=?",plan);
    }
    @Transactional public Map<String,Object> cancel(CancelRequest input) {
        require(input!=null&&input.planId()!=null,"请选择补课安排");require(text(input.reason()).length()<=500,"取消原因不能超过500字");
        var initial=db.one("SELECT * FROM edu_attendance_makeup WHERE plan_id=?",input.planId());
        authorized(id(initial.get("signInId")),true);
        db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",SecurityUtils.getUserId());
        db.one("SELECT student_id FROM edu_student WHERE student_id=? FOR UPDATE",initial.get("studentId"));
        db.one("SELECT sign_in_id FROM edu_class_sign_in WHERE sign_in_id=? FOR UPDATE",initial.get("signInId"));
        var plan=db.one("SELECT * FROM edu_attendance_makeup WHERE plan_id=? FOR UPDATE",input.planId());
        recheckOwner(id(plan.get("signInId")));
        if(yes(plan.get("cancelled")))return plan;
        var target=lesson(id(plan.get("targetLessonId")));var sheet=submissions(target);
        boolean failed=failed(plan,target,sheet);
        require(failed||(!yes(target.get("locked"))&&sheet.isEmpty()),"目标讲次已锁定，不能取消");
        retire(plan,text(input.reason()).isBlank()?"取消补课安排":text(input.reason()).trim());
        return db.one("SELECT * FROM edu_attendance_makeup WHERE plan_id=?",input.planId());
    }
}
