package com.ruoyi.system.service;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.shop.ShopRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stable scheduled occurrences and effective membership, independent of tuition accounting. */
@Service
public class AttendanceLessonRosterService {
    private final ShopRepository db;
    public AttendanceLessonRosterService(ShopRepository db) { this.db=db; }
    private static long id(Object value) { return Long.parseLong(String.valueOf(value)); }
    private static String text(Object value) { return value==null?"":String.valueOf(value); }
    private static void require(boolean condition,String message) { if(!condition) throw new ServiceException(message); }
    private static boolean yes(Object value) { return "1".equals(text(value)) || Boolean.TRUE.equals(value); }
    private Set<String> roles() {
        long actor=SecurityUtils.getUserId();
        var user=db.rows("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'",actor);
        require(!user.isEmpty(),"账号不可用");
        var result=new HashSet<String>();
        for(var r:db.rows("SELECT r.role_key FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0'",actor))result.add(text(r.get("roleKey")));
        if(actor==1)result.add("admin");
        return result;
    }
    private void staff() { var r=roles();require(r.contains("admin")||r.contains("business_admin")||r.contains("education"),"仅教务或管理员可维护名单"); }
    private Map<String,Object> course(long schedule) { return db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=? AND del_flag='0'",schedule); }
    public List<Map<String,Object>> courses(String keyword) {
        staff();String like="%"+text(keyword)+"%";
        return db.rows("SELECT c.schedule_id,c.course_class_name course_name,c.course_year year,c.term_name,c.grade_name,c.subject_name,COALESCE(NULLIF(TRIM(u.nick_name),''),u.user_name) teacher_name FROM edu_course_schedule c LEFT JOIN sys_user u ON u.user_id=c.teacher_id WHERE c.del_flag='0' AND COALESCE(c.class_mode,'1')<>'2' AND c.course_class_name LIKE ? ORDER BY c.course_year DESC,c.schedule_id DESC",like);
    }
    public List<Map<String,Object>> students(String keyword) {
        staff();
        String key=text(keyword).trim();require(key.length()<=100,"搜索内容过长");
        // A bounded, minimal picker: no account, credit or payment data.
        String like="%"+key+"%";
        return db.rows("SELECT st.student_id,st.student_name,st.parent_id,u.phonenumber contact_phone FROM edu_student st LEFT JOIN sys_user u ON u.user_id=st.parent_id WHERE st.del_flag='0' AND (st.parent_id IS NULL OR u.del_flag='0') AND (st.student_name LIKE ? OR u.phonenumber LIKE ?) ORDER BY st.student_id DESC LIMIT 50",like,like);
    }
    /** Called inside a transaction; locking the course serializes materialization and roster edits. */
    private List<Map<String,Object>> sync(long schedule) {
        db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",schedule);
        var c=course(schedule);
        if("2".equals(text(c.get("classMode"))))return List.of();
        var originals=ScheduleCalendar.dates(ScheduleCalendar.date(c.get("startDate")),ScheduleCalendar.date(c.get("endDate")),text(c.get("classPattern")),text(c.get("periodName")),List.of());
        var adjustments=db.rows("SELECT a.original_date,a.adjusted_date FROM edu_schedule_adjustment a WHERE a.schedule_id=? OR (a.schedule_id=0 AND a.term_name=? AND a.period_name=? AND NOT EXISTS(SELECT 1 FROM edu_schedule_adjustment x WHERE x.schedule_id=? AND x.original_date=a.original_date)) ORDER BY a.original_date",schedule,c.get("termName"),c.get("periodName"),schedule);
        var moved=new HashMap<LocalDate,LocalDate>();
        for(var a:adjustments){LocalDate original=ScheduleCalendar.date(a.get("originalDate"));require(original!=null&&!moved.containsKey(original),"调课日期冲突，请先完善排课");moved.put(original,ScheduleCalendar.date(a.get("adjustedDate")));}
        var existing=db.rows("SELECT * FROM edu_attendance_lesson WHERE schedule_id=? ORDER BY lesson_no",schedule);
        int next=existing.stream().mapToInt(r->((Number)r.get("lessonNo")).intValue()).max().orElse(0);
        var byDate=new HashMap<LocalDate,Map<String,Object>>();for(var row:existing)byDate.put(ScheduleCalendar.date(row.get("originalDate")),row);
        // Validate the entire resulting calendar before mutating or adopting any attendance sheet.
        var occupied=new HashSet<LocalDate>();
        for(LocalDate original:originals) {
            var old=byDate.get(original);boolean frozen=old!=null&&(yes(old.get("locked"))||hasSubmission(schedule,text(old.get("classDate"))));
            LocalDate actual=frozen?ScheduleCalendar.date(old.get("classDate")):moved.containsKey(original)?moved.get(original):original;
            if(actual!=null&&!(frozen&&yes(old.get("cancelled"))))require(occupied.add(actual),"调课后多个讲次在同一日期，请先修正排课");
        }
        for(var old:existing)if(!originals.contains(ScheduleCalendar.date(old.get("originalDate")))&&(yes(old.get("locked"))||hasSubmission(schedule,text(old.get("classDate"))))&&!yes(old.get("cancelled"))) {
            LocalDate actual=ScheduleCalendar.date(old.get("classDate"));if(actual!=null)require(occupied.add(actual),"调课日期与已提交讲次冲突，请先修正排课");
        }
        for(LocalDate original:originals) {
            LocalDate actual=moved.containsKey(original)?moved.get(original):original;
            var row=byDate.get(original);
            if(row==null) {
                var values=new LinkedHashMap<String,Object>();values.put("schedule_id",schedule);values.put("lesson_no",++next);values.put("original_date",original);values.put("class_date",actual);values.put("course_year",c.get("courseYear"));values.put("term_name",c.get("termName"));values.put("teacher_id",c.get("teacherId"));values.put("course_name",c.get("courseClassName"));values.put("start_time",c.get("startTime"));values.put("end_time",c.get("endTime"));values.put("cancelled",actual==null?1:0);db.insert("edu_attendance_lesson",values);
            } else if(!yes(row.get("locked")) && !hasSubmission(schedule,text(row.get("classDate")))) {
                db.jdbc().update("UPDATE edu_attendance_lesson SET class_date=?,cancelled=?,teacher_id=?,start_time=?,end_time=? WHERE lesson_id=? AND locked=0",actual,actual==null?1:0,c.get("teacherId"),c.get("startTime"),c.get("endTime"),row.get("lessonId"));
            }
        }
        for(var row:existing)if(!originals.contains(ScheduleCalendar.date(row.get("originalDate")))&&!yes(row.get("locked"))&&!hasSubmission(schedule,text(row.get("classDate"))))db.jdbc().update("UPDATE edu_attendance_lesson SET cancelled=1 WHERE lesson_id=?",row.get("lessonId"));
        // Adopt old submitted sheets before membership edits can affect their reconstructed roster.
        for(var legacy:db.rows("SELECT lesson_id,class_date FROM edu_attendance_lesson WHERE schedule_id=? AND locked=0",schedule)) {
            if(!hasSubmission(schedule,text(legacy.get("classDate"))))continue;
            var snapshot=db.rows("SELECT r.enrollment_id,COALESCE(r.student_id,e.student_id) student_id,r.student_name,e.contact_phone,1 start_lesson,NULL end_lesson,CASE r.source_type WHEN '2' THEN 'TRIAL' WHEN '3' THEN 'TRANSFER' ELSE 'ENROLLED' END source,CASE WHEN r.enrollment_id IS NULL THEN 'HISTORICAL' ELSE 'ENROLLMENT' END payment_status,NULL membership_id FROM edu_class_sign_in r LEFT JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id WHERE r.schedule_id=? AND r.class_date=? AND r.del_flag='0' ORDER BY r.sign_in_id",schedule,legacy.get("classDate"));
            db.jdbc().update("UPDATE edu_attendance_lesson SET locked=1,roster_snapshot=? WHERE lesson_id=? AND locked=0",JSON.toJSONString(snapshot),legacy.get("lessonId"));
        }
        return db.rows("SELECT lesson_id,schedule_id,lesson_no,DATE_FORMAT(original_date,'%Y-%m-%d') original_date,DATE_FORMAT(class_date,'%Y-%m-%d') class_date,course_year year,term_name,teacher_id,course_name,TIME_FORMAT(start_time,'%H:%i') start_time,TIME_FORMAT(end_time,'%H:%i') end_time,cancelled,locked FROM edu_attendance_lesson WHERE schedule_id=? ORDER BY lesson_no",schedule);
    }
    private boolean hasSubmission(long schedule,String date) { if(date==null||date.isBlank())return false;return !db.rows("SELECT sign_in_id FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0' LIMIT 1",schedule,date).isEmpty(); }
    @Transactional public List<Map<String,Object>> lessons(long schedule) { staff();var rows=sync(schedule);for(var row:rows)row.put("expectedCount",yes(row.get("cancelled"))?0:rosterFor(db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",row.get("lessonId"))).size());return rows; }
    private Map<String,Object> lesson(long schedule,long lessonId) { sync(schedule);return db.one("SELECT * FROM edu_attendance_lesson WHERE schedule_id=? AND lesson_id=?",schedule,lessonId); }
    private Map<String,Object> forDate(long schedule,String date) {
        sync(schedule);var rows=db.rows("SELECT * FROM edu_attendance_lesson WHERE schedule_id=? AND class_date=? AND cancelled=0",schedule,date);
        require(rows.size()==1,"课次日期不明确，请先完善排课");return rows.get(0);
    }
    @Transactional public List<Map<String,Object>> roster(long schedule,long lessonId) { staff();return rosterFor(lesson(schedule,lessonId)); }
    private List<Map<String,Object>> rosterFor(Map<String,Object> lesson) {
        if(lesson.get("rosterSnapshot")!=null)return JSON.parseObject(text(lesson.get("rosterSnapshot")),new com.alibaba.fastjson2.TypeReference<List<Map<String,Object>>>(){});
        long schedule=id(lesson.get("scheduleId"));int no=((Number)lesson.get("lessonNo")).intValue();
        // Legacy registrations without an explicit range retain their original eligibility.
        var legacy=db.rows("SELECT e.enrollment_id,e.student_id,e.student_name,e.contact_phone,1 start_lesson,NULL end_lesson,'ENROLLED' source,'ENROLLMENT' payment_status,NULL membership_id FROM edu_enrollment e WHERE e.schedule_id=? AND e.del_flag='0' AND e.enrollment_status IN ('0','1','待确认','已确认','报名成功') AND (e.class_date IS NULL OR e.class_date=?) AND NOT EXISTS(SELECT 1 FROM edu_attendance_membership m WHERE m.schedule_id=e.schedule_id AND (m.enrollment_id=e.enrollment_id OR (e.student_id IS NOT NULL AND m.student_id=e.student_id))) ORDER BY e.enrollment_id",schedule,lesson.get("classDate"));
        var result=new ArrayList<Map<String,Object>>(legacy);
        result.addAll(db.rows("SELECT m.membership_id,m.student_id,m.enrollment_id,st.student_name,st.parent_id,u.phonenumber contact_phone,m.start_lesson,m.end_lesson,m.source,m.payment_status FROM edu_attendance_membership m JOIN edu_student st ON st.student_id=m.student_id LEFT JOIN sys_user u ON u.user_id=st.parent_id WHERE m.schedule_id=? AND m.start_lesson<=? AND (m.end_lesson IS NULL OR m.end_lesson>=?) ORDER BY m.membership_id",schedule,no,no));
        return result;
    }
    @Transactional public List<Map<String,Object>> teacherRoster(Long schedule,String date) {
        var r=roles();var lesson=forDate(schedule,date);require(r.contains("admin")||(r.contains("teacher")&&Objects.equals(text(lesson.get("teacherId")),text(SecurityUtils.getUserId()))),"只能查看自己课程名单");
        return rosterFor(lesson);
    }
    public record MembershipRequest(Long scheduleId,Long studentId,Long enrollmentId,Integer startLesson,Integer endLesson,String source,String paymentStatus,String reason) {}
    public List<Map<String,Object>> pendingParentBindings() {
        staff();
        var rows = db.rows("SELECT st.student_id,st.student_name,m.schedule_id,m.course_year,m.term_name,"
                + "m.start_lesson,m.end_lesson,c.course_class_name course_name,c.period_name,"
                + "TIME_FORMAT(c.start_time,'%H:%i') start_time,TIME_FORMAT(c.end_time,'%H:%i') end_time,"
                + "COALESCE(NULLIF(TRIM(u.nick_name),''),u.user_name) teacher_name "
                + "FROM edu_student st JOIN edu_attendance_membership m ON m.student_id=st.student_id "
                + "JOIN edu_course_schedule c ON c.schedule_id=m.schedule_id "
                + "LEFT JOIN sys_user u ON u.user_id=c.teacher_id "
                + "WHERE st.parent_id IS NULL AND st.del_flag='0' AND m.source='OFFLINE' "
                + "AND (m.end_lesson IS NULL OR m.end_lesson>=m.start_lesson) "
                + "ORDER BY st.student_id DESC,m.membership_id");
        var students = new LinkedHashMap<Object,Map<String,Object>>();
        var courses = new LinkedHashMap<Object,List<Map<String,Object>>>();
        for (var row : rows) {
            Object studentId = row.get("studentId");
            var studentCourses = courses.computeIfAbsent(studentId, key -> new ArrayList<>());
            students.putIfAbsent(studentId, Map.of("studentId",studentId,
                    "studentName",row.get("studentName"),"courses",studentCourses));
            row.remove("studentId");
            row.remove("studentName");
            studentCourses.add(row);
        }
        return new ArrayList<>(students.values());
    }

    public record OfflineStudentRequest(Long scheduleId, String studentName, Integer startLesson) {}
    public record ParentBindingRequest(String phone) {}

    @Transactional
    public Map<String, Long> addOfflineStudent(OfflineStudentRequest input) {
        staff();
        require(input != null && input.scheduleId() != null && input.startLesson() != null,
                "请选择课程及起始讲次");
        String name = text(input.studentName()).strip();
        require(!name.isEmpty() && name.length() <= 30, "请输入30字以内的学生姓名");
        long schedule = input.scheduleId();
        db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? AND del_flag='0' AND COALESCE(class_mode,'1')<>'2' FOR UPDATE", schedule);
        require(db.rows("SELECT m.membership_id FROM edu_attendance_membership m JOIN edu_student st "
                + "ON st.student_id=m.student_id WHERE m.schedule_id=? AND st.student_name=?", schedule, name).isEmpty()
                && db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND student_name=? "
                + "AND del_flag='0' AND enrollment_status IN ('0','1','待确认','已确认','报名成功')", schedule, name).isEmpty(),
                "本班已有同名学员，请先核对名单和学生档案");
        long student = db.insert("edu_student", Map.of("student_name", name, "create_by", SecurityUtils.getUsername()));
        long membership = add(new MembershipRequest(schedule, student, null, input.startLesson(), null,
                "OFFLINE", "UNKNOWN", "补录线下在读学员"));
        return Map.of("studentId", student, "membershipId", membership);
    }

    @Transactional
    public void bindOfflineStudent(long studentId, ParentBindingRequest input) {
        staff();
        String phone = input == null ? "" : text(input.phone()).strip();
        require(phone.matches("1[3-9][0-9]{9}"), "请输入家长已注册的11位手机号");
        var student = db.one("SELECT student_id,parent_id,student_name FROM edu_student WHERE student_id=? "
                + "AND del_flag='0' FOR UPDATE", studentId);
        require(student.get("parentId") == null, "该学员已有绑定账号");
        var parents = db.rows("SELECT u.user_id FROM sys_user u WHERE u.phonenumber=? AND u.status='0' "
                + "AND u.del_flag='0' AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r "
                + "ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='parent' "
                + "AND r.status='0' AND r.del_flag='0') FOR UPDATE", phone);
        require(parents.size() == 1, "手机号须对应唯一可用的家长账号");
        Object parent = parents.get(0).get("userId");
        require(db.rows("SELECT student_id FROM edu_student WHERE parent_id=? AND student_name="
                + "(SELECT student_name FROM edu_student WHERE student_id=?) AND del_flag='0'",
                parent, studentId).isEmpty(), "该家长已有同名孩子档案，请先核对");
        var membership = db.one("SELECT c.grade_name FROM edu_attendance_membership m "
                + "JOIN edu_course_schedule c ON c.schedule_id=m.schedule_id "
                + "WHERE m.student_id=? AND m.source='OFFLINE' ORDER BY m.membership_id LIMIT 1", studentId);
        String grade = text(membership.get("gradeName"));
        var grades = db.rows("SELECT dict_value FROM sys_dict_data WHERE dict_type='edu_grade' "
                + "AND dict_label=? AND status='0'", grade);
        require(grades.size() == 1, "请先完善补录班次的年级");
        String actor = SecurityUtils.getUsername();
        db.jdbc().update("UPDATE edu_student SET parent_id=?,grade_code=?,update_by=?,update_time=CURRENT_TIMESTAMP(3) "
                + "WHERE student_id=? AND parent_id IS NULL", parent, grades.get(0).get("dictValue"), actor, studentId);
        db.insert("edu_student_lesson_account", Map.of("parent_id", parent, "student_id", studentId,
                "student_name", student.get("studentName"), "grade_name", grade, "request_key", "OFFLINE:" + studentId,
                "create_by", actor, "create_time", LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
    }

    public record RemovalRequest(Long scheduleId,Integer effectiveLesson,String reason) {}
    private String reason(String value){require(value!=null&&!value.isBlank()&&value.length()<=255,"请填写255字以内的名单变更原因");return value.trim();}
    private Map<String,Object> numbered(long schedule,int no) { return db.one("SELECT * FROM edu_attendance_lesson WHERE schedule_id=? AND lesson_no=?",schedule,no); }
    private void mutableFrom(long schedule,int no) {
        var l=numbered(schedule,no);require(!yes(l.get("cancelled")),"请选择有效课次");
        LocalDate day=ScheduleCalendar.date(l.get("classDate"));require(day!=null&&!day.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))),"名单变更只能从当前或未来课次生效");
        require(db.rows("SELECT lesson_id FROM edu_attendance_lesson l WHERE schedule_id=? AND lesson_no>=? AND (locked=1 OR EXISTS(SELECT 1 FROM edu_class_sign_in r WHERE r.schedule_id=l.schedule_id AND r.class_date=l.class_date AND r.del_flag='0'))",schedule,no).isEmpty(),"生效范围包含已提交课次，请选择更后的讲次");
    }
    @Transactional public long add(MembershipRequest input) {
        staff();require(input!=null&&input.scheduleId()!=null&&input.studentId()!=null&&input.startLesson()!=null,"请选择课程、学生及起始讲次");reason(input.reason());long schedule=input.scheduleId();sync(schedule);mutableFrom(schedule,input.startLesson());
        var quarter=numbered(schedule,input.startLesson());require(quarter.get("courseYear")!=null&&!text(quarter.get("termName")).isBlank(),"请先完善课程年份和季度");
        if(input.endLesson()!=null){require(input.endLesson()>=input.startLesson(),"结束讲次不能早于开始讲次");numbered(schedule,input.endLesson());}
        var student=db.one("SELECT student_id,parent_id FROM edu_student WHERE student_id=? AND del_flag='0'",input.studentId());
        require(Set.of("ENROLLED","OFFLINE","TRIAL","TRANSFER").contains(text(input.source())),"请选择明确的名单来源");
        Long enrollment=input.enrollmentId();
        if(enrollment==null&&"ENROLLED".equals(input.source())) {
            var found=db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND student_id=? AND parent_id=? AND del_flag='0' AND enrollment_status IN ('0','1','待确认','已确认','报名成功')",schedule,input.studentId(),student.get("parentId"));
            require(found.size()==1,"请选择唯一有效报名；线下或试听请选择相应来源");enrollment=id(found.get(0).get("enrollmentId"));
        }
        if(enrollment!=null)require(!db.rows("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? AND schedule_id=? AND student_id=? AND parent_id=? AND del_flag='0' AND enrollment_status IN ('0','1','待确认','已确认','报名成功')",enrollment,schedule,input.studentId(),student.get("parentId")).isEmpty(),"报名与课程或学生不匹配");
        require(enrollment!=null?"ENROLLMENT".equals(input.paymentStatus()):Set.of("UNPAID","HISTORICAL","UNKNOWN").contains(text(input.paymentStatus())),"请明确收费状态；名单录入不会生成缴费记录");
        require(db.rows("SELECT membership_id FROM edu_attendance_membership WHERE schedule_id=? AND student_id=?",schedule,input.studentId()).isEmpty(),"该学生已有生效范围，请先核对名单");
        var c=course(schedule);var values=new LinkedHashMap<String,Object>();values.put("schedule_id",schedule);values.put("student_id",input.studentId());values.put("enrollment_id",enrollment);values.put("course_year",quarter.get("courseYear"));values.put("term_name",quarter.get("termName"));values.put("start_lesson",input.startLesson());values.put("end_lesson",input.endLesson());values.put("source",input.source());values.put("payment_status",input.paymentStatus());values.put("reason",input.reason());values.put("operator_id",SecurityUtils.getUserId());long membership=db.insert("edu_attendance_membership",values);audit(membership,"ADD",null,values,input.reason());return membership;
    }
    @Transactional public void remove(long membership,RemovalRequest input) {
        staff();require(input!=null&&input.effectiveLesson()!=null,"请选择移出生效讲次");reason(input.reason());long schedule=id(db.one("SELECT schedule_id FROM edu_attendance_membership WHERE membership_id=?",membership).get("scheduleId"));sync(schedule);var before=db.one("SELECT * FROM edu_attendance_membership WHERE membership_id=? FOR UPDATE",membership);mutableFrom(schedule,input.effectiveLesson());require(input.effectiveLesson()>=((Number)before.get("startLesson")).intValue(),"移出讲次不能早于加入讲次");
        require(before.get("endLesson")==null||input.effectiveLesson()<=((Number)before.get("endLesson")).intValue()+1,"该学生在此讲次前已移出");
        db.jdbc().update("UPDATE edu_attendance_membership SET end_lesson=?,reason=?,operator_id=?,update_time=NOW() WHERE membership_id=?",input.effectiveLesson()-1,input.reason(),SecurityUtils.getUserId(),membership);audit(membership,"REMOVE",before,db.one("SELECT * FROM edu_attendance_membership WHERE membership_id=?",membership),input.reason());
    }
    @Transactional public void removeEnrollment(long enrollment,RemovalRequest input) {
        staff();require(input!=null&&input.scheduleId()!=null&&input.effectiveLesson()!=null,"请选择课程及移出生效讲次");sync(input.scheduleId());var matches=db.rows("SELECT membership_id FROM edu_attendance_membership WHERE schedule_id=? AND enrollment_id=?",input.scheduleId(),enrollment);
        if(!matches.isEmpty()){remove(id(matches.get(0).get("membershipId")),input);return;}
        var e=db.one("SELECT student_id FROM edu_enrollment WHERE enrollment_id=? AND schedule_id=? AND del_flag='0'",enrollment,input.scheduleId());require(e.get("studentId")!=null,"历史报名缺少学生档案，请先补全关联");
        // Import its original open range, then close it; this never changes its financial registration.
        mutableFrom(input.scheduleId(),input.effectiveLesson());reason(input.reason());var c=course(input.scheduleId());var values=new LinkedHashMap<String,Object>();values.put("schedule_id",input.scheduleId());values.put("student_id",e.get("studentId"));values.put("enrollment_id",enrollment);values.put("course_year",c.get("courseYear"));values.put("term_name",c.get("termName"));values.put("start_lesson",1);values.put("end_lesson",input.effectiveLesson()-1);values.put("source","ENROLLED");values.put("payment_status","ENROLLMENT");values.put("reason",input.reason());values.put("operator_id",SecurityUtils.getUserId());long membership=db.insert("edu_attendance_membership",values);audit(membership,"REMOVE",null,values,input.reason());
    }
    private void audit(long membership,String action,Object before,Object after,String explanation) {
        var row=new LinkedHashMap<String,Object>();row.put("membership_id",membership);row.put("action",action);row.put("before_snapshot",before==null?null:JSON.toJSONString(before));row.put("after_snapshot",JSON.toJSONString(after));row.put("operator_id",SecurityUtils.getUserId());row.put("reason",explanation);db.insert("edu_attendance_membership_history",row);
    }
    public void validateSubmission(MiniAppSignSubmitBody body) {
        var accountRoles=roles();var schedule=course(body.getScheduleId());
        if("2".equals(text(schedule.get("classMode")))) {require(accountRoles.contains("admin")||(accountRoles.contains("teacher")&&text(schedule.get("teacherId")).equals(text(SecurityUtils.getUserId()))),"只能提交自己课程的点名");return;}
        var lesson=forDate(body.getScheduleId(),body.getClassDate());
        require(accountRoles.contains("admin")||(accountRoles.contains("teacher")&&text(lesson.get("teacherId")).equals(text(SecurityUtils.getUserId()))),"只能提交自己课程的点名");
        var roster=rosterFor(lesson);var enrollments=new HashMap<Long,Map<String,Object>>();var students=new HashMap<Long,Map<String,Object>>();
        for(var row:roster){if(row.get("enrollmentId")!=null)enrollments.put(id(row.get("enrollmentId")),row);if(row.get("studentId")!=null)students.put(id(row.get("studentId")),row);}
        var seen=new HashSet<String>();
        require(body.getDetails()!=null&&!body.getDetails().isEmpty(),"签到明细不能为空");
        for(var d:body.getDetails()) {
            require(d!=null,"签到明细不能为空");
            Map<String,Object> row;
            if(d.getEnrollmentId()!=null) {
                row=enrollments.get(d.getEnrollmentId());require(row!=null,"学生不在本讲有效名单内");
                if(d.getStudentId()!=null)require(row.get("studentId")!=null&&d.getStudentId()==id(row.get("studentId")),"学生档案与报名不匹配");
            } else {
                require(d.getStudentId()!=null,"试听或调课学生必须关联学生档案");row=students.get(d.getStudentId());
                require(row!=null&&row.get("enrollmentId")==null,"请使用本讲名单的正式报名或由教务加入临时学员");
            }
            String canonical=row.get("studentId")!=null?"S:"+row.get("studentId"):"E:"+row.get("enrollmentId");
            require(seen.add(canonical),"同一学生重复点名");
            d.setStudentId(row.get("studentId")==null?null:id(row.get("studentId")));
            d.setStudentName(text(row.get("studentName")));
            d.setSourceType("TRIAL".equals(row.get("source"))?"2":"TRANSFER".equals(row.get("source"))?"3":"1");
        }
        for(var row:roster)require(seen.contains(row.get("studentId")!=null?"S:"+row.get("studentId"):"E:"+row.get("enrollmentId")),"请完成本讲全部有效学员的点名");
    }

    public void lockLesson(long schedule,String date,Long teacher) {
        if("2".equals(text(course(schedule).get("classMode"))))return;
        var candidates=db.rows("SELECT * FROM edu_attendance_lesson WHERE schedule_id=? AND class_date=? AND cancelled=0",schedule,date);require(candidates.size()==1,"课次日期不明确");var lesson=candidates.get(0);if(yes(lesson.get("locked")))return;var snapshot=rosterFor(lesson);
        db.jdbc().update("UPDATE edu_attendance_lesson SET locked=1,teacher_id=?,roster_snapshot=? WHERE lesson_id=? AND locked=0",teacher,JSON.toJSONString(snapshot),lesson.get("lessonId"));
    }
    private String reviewStage(long schedule,Object date) {
        var review=db.rows("SELECT COALESCE(review_stage,CASE WHEN review_status='1' THEN 'FINAL' WHEN review_status='2' THEN 'RETURNED' ELSE 'PENDING_FIRST' END) stage FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0' LIMIT 1",schedule,date);
        return review.isEmpty()?"UNSUBMITTED":text(review.get(0).get("stage"));
    }
    /** All planned valid occurrences, including unsubmitted lessons, within the existing date scope. */
    @Transactional public boolean hasLessonInRange(long schedule,String from,String to) {
        staff();LocalDate first=from==null||from.isBlank()?null:ScheduleCalendar.date(from),last=to==null||to.isBlank()?null:ScheduleCalendar.date(to);
        require((from==null||from.isBlank()||first!=null)&&(to==null||to.isBlank()||last!=null)&&(first==null||last==null||!first.isAfter(last)),"日期范围无效");
        for(var row:sync(schedule)) {
            LocalDate day=ScheduleCalendar.date(row.get("classDate"));
            if(!yes(row.get("cancelled"))&&day!=null&&(first==null||!day.isBefore(first))&&(last==null||!day.isAfter(last)))return true;
        }
        return !db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND del_flag='0' AND enrollment_status='1' AND class_date IS NOT NULL AND (? IS NULL OR class_date>=?) AND (? IS NULL OR class_date<=?) LIMIT 1",schedule,first,first,last,last).isEmpty();
    }
    @Transactional public List<Map<String,Object>> courseOverviewLessons(long schedule,String from,String to) {
        staff();LocalDate first=from==null||from.isBlank()?null:ScheduleCalendar.date(from),last=to==null||to.isBlank()?null:ScheduleCalendar.date(to);
        require((from==null||from.isBlank()||first!=null)&&(to==null||to.isBlank()||last!=null)&&(first==null||last==null||!first.isAfter(last)),"日期范围无效");
        var result=new ArrayList<Map<String,Object>>();
        for(var row:sync(schedule)) {
            LocalDate day=ScheduleCalendar.date(row.get("classDate"));if(yes(row.get("cancelled"))||day==null||(first!=null&&day.isBefore(first))||(last!=null&&day.isAfter(last)))continue;
            row.put("expectedCount",rosterFor(db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",row.get("lessonId"))).size());row.put("reviewStage",reviewStage(schedule,row.get("classDate")));result.add(row);
        }
        return result;
    }
    @Transactional public Map<String,Object> summary(long schedule,String from,String to) {
        var rows=courseOverviewLessons(schedule,from,to);long expected=rows.stream().mapToLong(r->((Number)r.get("expectedCount")).longValue()).sum();
        long formalExpected=rows.stream().filter(r->"FINAL".equals(r.get("reviewStage"))).mapToLong(r->((Number)r.get("expectedCount")).longValue()).sum();
        return Map.of("lessons",rows,"expectedPersonOccurrences",expected,"formalExpectedPersonOccurrences",formalExpected);
    }
    @Transactional public Map<String,Object> todos(String role) {
        var roles=roles();if("admin".equals(role)&&!roles.contains("admin")&&roles.contains("business_admin"))role="business_admin";require(Set.of("teacher","education","business_admin","admin").contains(role),"请选择有效身份");require(roles.contains("admin")||roles.contains(role),"当前身份无权查看待办");
        var items=new ArrayList<Map<String,Object>>();var now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var candidates=new ArrayList<Map<String,Object>>();
        var courses=db.rows("SELECT schedule_id,status FROM edu_course_schedule WHERE del_flag='0' AND COALESCE(class_mode,'1')<>'2'"+("teacher".equals(role)?" AND teacher_id=?":""),"teacher".equals(role)?new Object[]{SecurityUtils.getUserId()}:new Object[]{});
        for(var c:courses)for(var l:sync(id(c.get("scheduleId")))) { l.put("scheduleStatus",c.get("status"));candidates.add(l); }
        // Existing sheets, including pre-migration dates and single lessons, remain actionable.
        String teacherFilter="teacher".equals(role)?" AND COALESCE(l.teacher_id,c.teacher_id)=?":"";
        var historical=db.rows("SELECT DISTINCT c.schedule_id,DATE_FORMAT(r.class_date,'%Y-%m-%d') class_date,COALESCE(l.course_name,c.course_class_name) course_name,TIME_FORMAT(COALESCE(l.start_time,c.start_time),'%H:%i') start_time,TIME_FORMAT(COALESCE(l.end_time,c.end_time),'%H:%i') end_time,c.status schedule_status,COALESCE(l.teacher_id,c.teacher_id) teacher_id FROM edu_class_sign_in r JOIN edu_course_schedule c ON c.schedule_id=r.schedule_id LEFT JOIN edu_attendance_lesson l ON l.schedule_id=r.schedule_id AND l.class_date=r.class_date WHERE r.del_flag='0'"+teacherFilter,"teacher".equals(role)?new Object[]{SecurityUtils.getUserId()}:new Object[]{});
        // A one-to-one reservation keeps its established booking/credit behavior and still gets a local reminder.
        var bookings=db.rows("SELECT DISTINCT c.schedule_id,DATE_FORMAT(e.class_date,'%Y-%m-%d') class_date,c.course_class_name course_name,TIME_FORMAT(c.start_time,'%H:%i') start_time,TIME_FORMAT(c.end_time,'%H:%i') end_time,c.status schedule_status,c.teacher_id FROM edu_enrollment e JOIN edu_course_schedule c ON c.schedule_id=e.schedule_id WHERE c.del_flag='0' AND c.status='0' AND c.class_mode='2' AND e.del_flag='0' AND e.enrollment_status='1' AND e.class_date IS NOT NULL"+("teacher".equals(role)?" AND c.teacher_id=?":""),"teacher".equals(role)?new Object[]{SecurityUtils.getUserId()}:new Object[]{});
        historical.addAll(bookings);
        var keys=new HashSet<String>();for(var l:candidates)keys.add(l.get("scheduleId")+":"+l.get("classDate"));
        for(var l:historical)if(keys.add(l.get("scheduleId")+":"+l.get("classDate")))candidates.add(l);
        // Metadata supports filters without sending unrelated rosters or user records to the client.
        var courseMetadata=new HashMap<String,Map<String,Object>>();
        for(var c:db.rows("SELECT schedule_id,grade_name,subject_name,teacher_id FROM edu_course_schedule"))courseMetadata.put(text(c.get("scheduleId")),c);
        var teacherNames=new HashMap<String,String>();
        for(var t:db.rows("SELECT user_id,COALESCE(NULLIF(TRIM(nick_name),''),user_name) teacher_name FROM sys_user"))teacherNames.put(text(t.get("userId")),text(t.get("teacherName")));
        for(var l:candidates) {
            if(yes(l.get("cancelled"))||l.get("classDate")==null)continue;
            var review=db.rows("SELECT COALESCE(review_stage,CASE WHEN review_status='1' THEN 'FINAL' WHEN review_status='2' THEN 'RETURNED' ELSE 'PENDING_FIRST' END) stage FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0' LIMIT 1",l.get("scheduleId"),l.get("classDate"));
            String stage=review.isEmpty()?"UNSUBMITTED":text(review.get(0).get("stage"));boolean overdue=false;
            if("UNSUBMITTED".equals(stage)&&!"0".equals(text(l.get("scheduleStatus"))))continue;
            if("teacher".equals(role)) {
                if(!Set.of("UNSUBMITTED","RETURNED").contains(stage))continue;
                LocalTime start=parseTime(l.get("startTime"));if(start==null||now.isBefore(LocalDate.parse(text(l.get("classDate"))).atTime(start)))continue;
                LocalTime end=parseTime(l.get("endTime"));overdue=end!=null&&now.isAfter(LocalDate.parse(text(l.get("classDate"))).atTime(end));
            } else if("education".equals(role)?!"PENDING_FIRST".equals(stage):"business_admin".equals(role)?!"PENDING_FINAL".equals(stage):!Set.of("PENDING_FIRST","PENDING_FINAL").contains(stage))continue;
            var item=new LinkedHashMap<String,Object>(l);if(l.get("lessonId")!=null)item.put("expectedCount",rosterFor(db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",l.get("lessonId"))).size());else if("UNSUBMITTED".equals(stage))item.put("expectedCount",db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment WHERE schedule_id=? AND class_date=? AND enrollment_status='1' AND del_flag='0'",Long.class,l.get("scheduleId"),l.get("classDate")));else item.put("expectedCount",db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0'",Long.class,l.get("scheduleId"),l.get("classDate")));var metadata=courseMetadata.getOrDefault(text(l.get("scheduleId")),Map.of());
            item.put("gradeName",metadata.get("gradeName"));item.put("subjectName",metadata.get("subjectName"));
            Object lessonTeacher=l.get("teacherId")!=null?l.get("teacherId"):metadata.get("teacherId");
            item.put("teacherName",teacherNames.getOrDefault(text(lessonTeacher),""));
            item.put("stage",stage);item.put("overdue",overdue);items.add(item);
        }
        items.sort(Comparator.comparing(r->text(r.get("classDate"))));return Map.of("count",items.size(),"overdueCount",items.stream().filter(r->Boolean.TRUE.equals(r.get("overdue"))).count(),"items",items);
    }
    private static LocalTime parseTime(Object value){try{return LocalTime.parse(text(value));}catch(RuntimeException ex){return null;}}
}
