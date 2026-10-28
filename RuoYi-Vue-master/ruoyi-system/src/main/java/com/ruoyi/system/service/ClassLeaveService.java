package com.ruoyi.system.service;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.mapper.EduCourseScheduleMapper;
import com.ruoyi.system.shop.ShopRepository;

/** 班课请假；申请不改变名额、费用或考勤，点名预填复用原签到流程。 */
@Service
public class ClassLeaveService {
    @Autowired private ShopRepository db;
    @Autowired private EduCourseScheduleMapper schedules;
    @Autowired private ScheduleAvailabilityService calendar;
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
    private static final ObjectMapper JSON = new ObjectMapper();

    public static class Apply {
        public Long enrollmentId;
        public Long accountId;
        public String classDate;
        public String reason;
        public Integer version;
    }
    public static class Decision {
        public Long accountId;
        public String action;
        public String reason;
        public Integer version;
    }
    private void role(boolean teacher) {
        String wanted = teacher ? "teacher" : "parent";
        require(SecurityUtils.getLoginUser().getUser().getRoles().stream().anyMatch(r ->
            wanted.equals(r.getRoleKey()) && "0".equals(r.getStatus())), "请使用对应身份操作");
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new ServiceException(message);
    }
    private static Long id(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private static String text(String value, boolean required) {
        String result = value == null ? "" : value.trim();
        require(!required || !result.isEmpty(), "请填写原因");
        require(result.length() <= 255, "原因不能超过255字");
        return result;
    }
    private static LocalDate date(String value) {
        try {
            require(value != null && value.matches("\\d{4}-\\d{2}-\\d{2}"), "请选择有效课次日期");
            return LocalDate.parse(value);
        } catch (DateTimeException e) { throw new ServiceException("请选择有效课次日期"); }
    }
    private static String childKey(Map<String,Object> e) {
        if (e.get("studentId") != null) return "S:" + e.get("studentId");
        if (e.get("lessonAccountId") != null) return "A:" + e.get("lessonAccountId");
        return "E:" + e.get("enrollmentId"); // 旧数据仅按报名编号区分，绝不按同名猜测。
    }
    private Map<String,Object> enrollment(Long enrollmentId, boolean lock) {
        require(enrollmentId != null, "请选择孩子及班课");
        return db.one("SELECT e.* FROM edu_enrollment e WHERE e.enrollment_id=? AND e.parent_id=?"
            + (lock ? " FOR UPDATE" : ""), enrollmentId, SecurityUtils.getUserId());
    }
    private void active(Map<String,Object> e) {
        require("0".equals(e.get("delFlag")) && "0".equals(e.get("status"))
            && Set.of("1","报名成功","已确认").contains(String.valueOf(e.get("enrollmentStatus")))
            && e.get("classDate") == null, "只能为有效的班课报名申请请假");
    }
    private void classCourse(EduCourseSchedule s) {
        require(s != null && "0".equals(s.getDelFlag()) && "0".equals(s.getStatus())
            && !"2".equals(s.getClassMode()), "仅支持正常班课，不支持一对一");
    }
    private void occurrence(EduCourseSchedule s, LocalDate date, boolean future) {
        classCourse(s);
        require(s.getStartTime() != null && s.getEndTime() != null && s.getEndTime().isAfter(s.getStartTime()),
            "班课上课时间尚未配置，请联系老师");
        require(calendar.dates(s,calendar.adjustments(s.getScheduleId(),false)).contains(date),
            "该日期不是有效班课课次，可能已调课或停课，请刷新");
        if (future) require(LocalDateTime.of(date,s.getStartTime()).isAfter(LocalDateTime.now(clock)),
            "仅开课前可以申请请假");
    }
    private boolean reviewed(Long scheduleId, String classDate) {
        return !db.rows("SELECT sign_in_id FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? AND del_flag='0'"
            + " AND (review_status IN ('1','2') OR review1_admin_id IS NOT NULL OR review2_admin_id IS NOT NULL) LIMIT 1",
            scheduleId,classDate).isEmpty();
    }

    public List<Map<String,Object>> options() { return options(null); }
    public List<Map<String,Object>> options(Long accountId) {
        role(false);
        var rows = db.rows("SELECT e.enrollment_id,e.schedule_id,e.student_id,e.lesson_account_id,e.student_name,"
            + "COALESCE(st.student_name,e.student_name) AS child_name,s.course_class_name,s.teacher_name "
            + "FROM edu_enrollment e JOIN edu_course_schedule s ON s.schedule_id=e.schedule_id "
            + "LEFT JOIN edu_student st ON st.student_id=e.student_id AND st.parent_id=e.parent_id "
            + "WHERE e.parent_id=? AND e.del_flag='0' AND e.status='0' AND e.enrollment_status IN ('1','报名成功','已确认') "
            + "AND e.class_date IS NULL AND s.del_flag='0' AND s.status='0' AND COALESCE(s.class_mode,'1')<>'2' ORDER BY e.enrollment_id",
            SecurityUtils.getUserId());
        if(accountId!=null){ownedAccount(accountId);rows=rows.stream().filter(e->accountId.equals(id(e.get("lessonAccountId")))).toList();}
        rows.forEach(e -> e.put("childKey",childKey(e)));
        return rows;
    }
    public List<Map<String,Object>> lessons(Long enrollmentId) {
        role(false);
        var e = enrollment(enrollmentId,false); active(e);
        var s = schedules.selectEduCourseScheduleByScheduleId(id(e.get("scheduleId"))); classCourse(s);
        if (s.getStartTime() == null || s.getEndTime() == null || !s.getEndTime().isAfter(s.getStartTime())) return List.of();
        var requests = db.rows("SELECT class_date,state,version FROM edu_class_leave_request WHERE schedule_id=? AND (enrollment_id=? OR child_key=?)",
            s.getScheduleId(),enrollmentId,childKey(e));
        Map<LocalDate,Map<String,Object>> states = new HashMap<>();
        requests.forEach(r -> states.put(ScheduleCalendar.date(r.get("classDate")),r));
        List<Map<String,Object>> result = new ArrayList<>();
        for (LocalDate d : calendar.dates(s,calendar.adjustments(s.getScheduleId(),false))) {
            if (!LocalDateTime.of(d,s.getStartTime()).isAfter(LocalDateTime.now(clock)) || reviewed(s.getScheduleId(),d.toString())) continue;
            var r = states.get(d);
            if (r != null && Set.of("PENDING","APPROVED").contains(r.get("state"))) continue;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("classDate",d.toString()); item.put("startTime",s.getStartTime().toString()); item.put("endTime",s.getEndTime().toString());
            item.put("version",r == null ? null : r.get("version")); result.add(item);
        }
        return result;
    }
    public Map<String,Object> list(boolean teacher, String state, int pageNum) { return list(teacher,state,pageNum,null); }
    public Map<String,Object> list(boolean teacher,String state,int pageNum,Long accountId) {
        role(teacher);
        require(state == null || state.isEmpty() || Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN").contains(state),"请假状态无效");
        require(pageNum>0 && pageNum<=100000,"页码无效");
        if(accountId!=null){require(!teacher,"教师列表不接受孩子筛选");ownedAccount(accountId);}
        String scope = teacher ? "s.teacher_id=?" : "l.parent_id=?";
        if(accountId!=null)scope+=" AND EXISTS(SELECT 1 FROM edu_enrollment e WHERE e.enrollment_id=l.enrollment_id AND e.parent_id=l.parent_id AND e.lesson_account_id="+accountId+")";
        String where = " FROM edu_class_leave_request l JOIN edu_course_schedule s ON s.schedule_id=l.schedule_id WHERE " + scope;
        List<Object> args = new ArrayList<>(); args.add(SecurityUtils.getUserId());
        if (state != null && !state.isEmpty()) { where += " AND l.state=?"; args.add(state); }
        long total = db.jdbc().queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
        var pageArgs = new ArrayList<>(args); pageArgs.add((pageNum-1)*30);
        // 历史逐条详情单独查询，列表不携带其他家长资料。
        var rows = db.rows("SELECT l.leave_id,l.enrollment_id,l.student_name,l.course_name,l.class_date,l.start_at,l.end_at,l.reason,l.state,l.version,l.review_reason,l.reviewed_at,l.update_time,s.teacher_name"+where+" ORDER BY l.update_time DESC,l.leave_id DESC LIMIT 30 OFFSET ?",pageArgs.toArray());
        long pending = db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_class_leave_request l JOIN edu_course_schedule s ON s.schedule_id=l.schedule_id WHERE "+scope+" AND l.state='PENDING'",Long.class,SecurityUtils.getUserId());
        return Map.of("rows",rows,"total",total,"pendingCount",pending);
    }
    public Map<String,Object> detail(Long leaveId, boolean teacher) {
        role(teacher);
        return db.one("SELECT l.*,s.teacher_name FROM edu_class_leave_request l JOIN edu_course_schedule s ON s.schedule_id=l.schedule_id WHERE l.leave_id=? AND "
            +(teacher ? "s.teacher_id=?" : "l.parent_id=?"),leaveId,SecurityUtils.getUserId());
    }
    private void ownedAccount(Long accountId){require(accountId>0,"请选择孩子");db.one("SELECT account_id FROM edu_student_lesson_account WHERE account_id=? AND parent_id=?",accountId,SecurityUtils.getUserId());}
    private String event(String action, String reason, int version) {
        try { return JSON.writeValueAsString(Map.of("action",action,"reason",reason,"version",version,
            "actorId",String.valueOf(SecurityUtils.getUserId()),"actorName",SecurityUtils.getUsername(),
            "time",LocalDateTime.now(clock).toString())); }
        catch (JsonProcessingException e) { throw new ServiceException("无法记录操作历史"); }
    }
    @Transactional(rollbackFor=Exception.class)
    public long apply(Apply body) {
        role(false); require(body != null,"请填写申请");
        LocalDate d = date(body.classDate); String reason = text(body.reason,true);
        var preview = enrollment(body.enrollmentId,false);
        if(body.accountId!=null){ownedAccount(body.accountId);require(body.accountId.equals(id(preview.get("lessonAccountId"))),"该报名不属于当前孩子");}
        // 与报名取消、排课/调课、点名及复核共用排课锁；其后才锁报名和申请。
        var s = schedules.lockSchedule(id(preview.get("scheduleId")));
        var e = enrollment(body.enrollmentId,true); active(e); occurrence(s,d,true);
        require(!reviewed(s.getScheduleId(),d.toString()),"该课次已有管理员复核，不可申请请假");
        var old = db.rows("SELECT * FROM edu_class_leave_request WHERE schedule_id=? AND class_date=? AND (child_key=? OR enrollment_id=?) FOR UPDATE",
            s.getScheduleId(),d.toString(),childKey(e),body.enrollmentId);
        if (!old.isEmpty()) {
            var r = old.get(0);
            require(Objects.equals(id(r.get("parentId")),SecurityUtils.getUserId()),"记录不存在或无权访问");
            if ("PENDING".equals(r.get("state")) && reason.equals(r.get("reason"))) return id(r.get("leaveId"));
            require(Set.of("WITHDRAWN","REJECTED").contains(r.get("state")),"同一孩子同一课次已有请假申请，请勿重复提交");
            require(Objects.equals(body.version,((Number)r.get("version")).intValue()),"申请已变化，请刷新课次后重提");
            int next = body.version+1;
            db.jdbc().update("UPDATE edu_class_leave_request SET enrollment_id=?,reason=?,state='PENDING',version=?,start_at=?,end_at=?,reviewed_by=NULL,reviewed_at=NULL,review_reason=NULL,history=JSON_ARRAY_APPEND(history,'$',CAST(? AS JSON)),update_time=CURRENT_TIMESTAMP(3) WHERE leave_id=?",
                body.enrollmentId,reason,next,LocalDateTime.of(d,s.getStartTime()),LocalDateTime.of(d,s.getEndTime()),event("SUBMIT",reason,next),id(r.get("leaveId")));
            return id(r.get("leaveId"));
        }
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("enrollment_id",body.enrollmentId); values.put("schedule_id",s.getScheduleId()); values.put("parent_id",SecurityUtils.getUserId());
        values.put("student_id",e.get("studentId")); values.put("lesson_account_id",e.get("lessonAccountId"));
        values.put("student_name",e.get("studentName")); values.put("course_name",s.getCourseClassName()); values.put("class_date",d.toString());
        values.put("start_at",LocalDateTime.of(d,s.getStartTime())); values.put("end_at",LocalDateTime.of(d,s.getEndTime()));
        values.put("reason",reason); values.put("state","PENDING"); values.put("history","["+event("SUBMIT",reason,1)+"]");
        return db.insert("edu_class_leave_request",values);
    }
    @Transactional(rollbackFor=Exception.class)
    public void decide(Long leaveId, Decision body, boolean teacher) {
        role(teacher); require(body != null && body.version != null,"请刷新后操作");
        String action = body.action;
        require(teacher ? Set.of("APPROVE","REJECT").contains(String.valueOf(action)) : "WITHDRAW".equals(action),"操作无效");
        String reason = text(body.reason,"REJECT".equals(action));
        var preview = detail(leaveId,teacher);
        var s = schedules.lockSchedule(id(preview.get("scheduleId")));
        // 重新核对当前授课老师，不能沿用首次查询时的权限。
        if (teacher) require(Objects.equals(s.getTeacherId(),SecurityUtils.getUserId()),"只能审核本人授课班级");
        var r = db.one("SELECT * FROM edu_class_leave_request WHERE leave_id=? FOR UPDATE",leaveId);
        if (!teacher) require(Objects.equals(id(r.get("parentId")),SecurityUtils.getUserId()),"只能撤回本人申请");
        String next = "APPROVE".equals(action) ? "APPROVED" : "REJECT".equals(action) ? "REJECTED" : "WITHDRAWN";
        int version = ((Number)r.get("version")).intValue();
        if (next.equals(r.get("state")) && version == body.version+1) return; // 相同提交结果查询可安全重试。
        require("PENDING".equals(r.get("state")) && version == body.version,"申请已处理或重新提交，请刷新");
        if ("APPROVE".equals(action)) {
            occurrence(s,ScheduleCalendar.date(r.get("classDate")),false);
            var e = db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=? AND parent_id=? FOR UPDATE",id(r.get("enrollmentId")),id(r.get("parentId"))); active(e);
            LocalDate classDate = ScheduleCalendar.date(r.get("classDate"));
            require(LocalDateTime.of(classDate,s.getStartTime()).equals(LocalDateTime.parse(String.valueOf(r.get("startAt")).replace(' ','T')))
                && LocalDateTime.of(classDate,s.getEndTime()).equals(LocalDateTime.parse(String.valueOf(r.get("endAt")).replace(' ','T'))),"上课时间已调整，请撤回后按新课次申请");
        }
        db.jdbc().update("UPDATE edu_class_leave_request SET state=?,version=?,reviewed_by=?,review_reason=?,reviewed_at=?,history=JSON_ARRAY_APPEND(history,'$',CAST(? AS JSON)),update_time=CURRENT_TIMESTAMP(3) WHERE leave_id=?",
            next,version+1,teacher ? SecurityUtils.getUserId() : null,teacher ? reason : null,
            teacher ? LocalDateTime.now(clock) : null,event(action,reason,version+1),leaveId);
    }
    /** 只返回标识用于预填，不修改已保存的签到或管理员复核。调用方须先校验排课访问权。 */
    public Set<Long> approved(Long scheduleId, String classDate) {
        Set<Long> ids = new HashSet<>();
        for (var r : db.rows("SELECT l.enrollment_id FROM edu_class_leave_request l JOIN edu_course_schedule s ON s.schedule_id=l.schedule_id WHERE l.schedule_id=? AND l.class_date=? AND l.state='APPROVED' AND COALESCE(s.class_mode,'1')<>'2'",scheduleId,classDate))
            ids.add(id(r.get("enrollmentId")));
        return ids;
    }
}
