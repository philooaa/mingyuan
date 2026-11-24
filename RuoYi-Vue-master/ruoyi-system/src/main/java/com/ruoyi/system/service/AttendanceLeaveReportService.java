package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.shop.ShopRepository;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, confirmed leave occurrences and explicitly linked, confirmed makeup attendance. */
@Service
public class AttendanceLeaveReportService {
    private final ShopRepository db;
    @org.springframework.beans.factory.annotation.Autowired private ParentAccountService parents;
    public AttendanceLeaveReportService(ShopRepository db) { this.db=db; }
    private static String text(Object value) { return value==null?"":String.valueOf(value); }
    private static int number(Object value) { return value==null?0:new java.math.BigDecimal(value.toString()).intValueExact(); }
    private boolean teacherOnly(String scope) {
        long user=SecurityUtils.getUserId();
        var roles=db.rows("SELECT r.role_key FROM sys_user u LEFT JOIN sys_user_role ur ON ur.user_id=u.user_id LEFT JOIN sys_role r ON r.role_id=ur.role_id AND r.status='0' AND r.del_flag='0' WHERE u.user_id=? AND u.status='0' AND u.del_flag='0'",user);
        boolean staff=user==1&&!roles.isEmpty()||roles.stream().anyMatch(r->Set.of("admin","education","business_admin").contains(text(r.get("roleKey"))));
        if(!staff&&roles.stream().noneMatch(r->"teacher".equals(text(r.get("roleKey")))))throw new ServiceException("仅教师、教务或管理员可查看请假课时表");
        boolean teacher=roles.stream().anyMatch(r->"teacher".equals(text(r.get("roleKey"))));
        if("teacher".equals(scope)&&!teacher)throw new ServiceException("当前账号没有有效教师身份");
        return !staff||"teacher".equals(scope);
    }
    private static int page(Map<String,String> query,String key,int fallback,int max) {
        try {int n=Integer.parseInt(query.getOrDefault(key,String.valueOf(fallback)));if(n<1||n>max)throw new IllegalArgumentException();return n;}
        catch(Exception e) {throw new ServiceException("分页参数无效");}
    }
    private List<Map<String,Object>> collect(Map<String,String> query,boolean teacherScope,Long childStudent) {
        String from=query.getOrDefault("from",""),to=query.getOrDefault("to","");
        try {if(!from.isBlank())LocalDate.parse(from);if(!to.isBlank())LocalDate.parse(to);if(!from.isBlank()&&!to.isBlank()&&from.compareTo(to)>0)throw new IllegalArgumentException();}
        catch(Exception e){throw new ServiceException("请选择有效的起止日期");}
        String state=query.getOrDefault("state","");
        if(!Set.of("","pending","scheduled","failed","completed").contains(state))throw new ServiceException("处理状态无效");
        StringBuilder where=new StringBuilder(" WHERE r.del_flag='0' AND r.sign_status='3' AND r.review_status='1' AND (r.review_stage IS NULL OR r.review_stage='' OR r.review_stage='FINAL') AND c.del_flag='0' AND NOT (COALESCE(r.source_type,'1')='3' AND EXISTS(SELECT 1 FROM edu_attendance_makeup child_plan WHERE child_plan.target_lesson_id=l.lesson_id AND child_plan.student_id=COALESCE(r.student_id,e.student_id)))");
        var args=new ArrayList<Object>();
        if(childStudent!=null){where.append(" AND st.student_id=? AND st.parent_id=?");args.add(childStudent);args.add(SecurityUtils.getUserId());}
        if(teacherScope){where.append(" AND COALESCE(l.teacher_id,c.teacher_id)=?");args.add(SecurityUtils.getUserId());}
        for(var field:Map.of("year","COALESCE(l.course_year,c.course_year)","termName","COALESCE(l.term_name,c.term_name)","teacherName","COALESCE(NULLIF(t.user_name,''),c.teacher_name)","gradeName","c.grade_name").entrySet()) {
            String value=query.getOrDefault(field.getKey(),"").trim();if(value.length()>100)throw new ServiceException("筛选内容过长");
            if(!value.isEmpty()){where.append(" AND ").append(field.getValue()).append("=?");args.add(value);}
        }
        if(!from.isBlank()){where.append(" AND r.class_date>=?");args.add(from);}if(!to.isBlank()){where.append(" AND r.class_date<=?");args.add(to);}
        String keyword=query.getOrDefault("keyword","").trim();if(keyword.length()>100)throw new ServiceException("搜索词过长");
        if(!keyword.isEmpty()) {
            where.append(" AND (COALESCE(NULLIF(st.student_name,''),NULLIF(e.student_name,''),r.student_name) LIKE ? OR COALESCE(l.course_name,c.course_class_name) LIKE ? OR COALESCE(NULLIF(t.user_name,''),c.teacher_name) LIKE ? OR COALESCE(NULLIF(e.contact_phone,''),p.phonenumber,'') LIKE ?)");
            for(int i=0;i<4;i++)args.add("%"+keyword+"%");
        }
        var records=db.rows("SELECT COALESCE(l.teacher_id,c.teacher_id) source_teacher_id,r.sign_in_id,r.schedule_id,r.enrollment_id,st.student_id,COALESCE(NULLIF(st.student_name,''),NULLIF(e.student_name,''),r.student_name) student_name,COALESCE(NULLIF(e.contact_phone,''),p.phonenumber,'') contact_phone,DATE_FORMAT(r.class_date,'%Y-%m-%d') class_date,l.lesson_id,l.lesson_no,COALESCE(l.course_name,c.course_class_name) course_name,COALESCE(l.course_year,c.course_year) year,COALESCE(l.term_name,c.term_name) term_name,c.grade_name,c.subject_name,COALESCE(NULLIF(t.user_name,''),c.teacher_name) teacher_name,"
            +"(SELECT COUNT(*) FROM edu_class_sign_in x LEFT JOIN edu_enrollment xe ON xe.enrollment_id=x.enrollment_id WHERE x.schedule_id=r.schedule_id AND x.class_date=r.class_date AND x.del_flag='0' AND (x.sign_in_id=r.sign_in_id OR (COALESCE(r.student_id,e.student_id) IS NOT NULL AND COALESCE(x.student_id,xe.student_id)=COALESCE(r.student_id,e.student_id)) OR (r.enrollment_id IS NOT NULL AND x.enrollment_id=r.enrollment_id))) record_count "
            +"FROM edu_class_sign_in r JOIN edu_course_schedule c ON c.schedule_id=r.schedule_id LEFT JOIN edu_attendance_lesson l ON l.lesson_id=(SELECT MIN(z.lesson_id) FROM edu_attendance_lesson z WHERE z.schedule_id=r.schedule_id AND z.class_date=r.class_date) LEFT JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id LEFT JOIN edu_student st ON st.student_id=COALESCE(r.student_id,e.student_id) AND (r.student_id IS NOT NULL OR st.parent_id=e.parent_id) LEFT JOIN sys_user p ON p.user_id=COALESCE(st.parent_id,e.parent_id) LEFT JOIN sys_user t ON t.user_id=COALESCE(l.teacher_id,c.teacher_id)"
            +where+" ORDER BY r.class_date DESC,r.schedule_id,r.sign_in_id LIMIT 5001",args.toArray());
        if(records.size()>5000)throw new ServiceException("请假记录较多，请缩小筛选范围后查询");
        // Makeup attendance is checked outside the original leave date window.
        Map<String,Map<String,Object>> linked=new HashMap<>(),completed=new HashMap<>();var makeupConflicts=new HashSet<String>();
        if(!records.isEmpty()) {
            var ids=new LinkedHashSet<String>();for(var r:records)if(r.get("lessonId")!=null&&r.get("studentId")!=null)ids.add(text(r.get("lessonId")));
            if(!ids.isEmpty()) {
                var makeup=db.rows("SELECT g.source_lesson_id,g.student_id,g.guest_id,mp.plan_id,mp.arrange_type,l.schedule_id,l.lesson_no,DATE_FORMAT(l.class_date,'%Y-%m-%d') class_date,COALESCE(l.course_name,mc.course_class_name) course_name,l.cancelled,l.locked,mc.status target_status,mc.del_flag target_deleted,(SELECT COUNT(*) FROM edu_class_sign_in sheet WHERE sheet.schedule_id=l.schedule_id AND sheet.class_date=l.class_date AND sheet.del_flag='0') sheet_count,COUNT(a.sign_in_id) record_count,SUM(a.review_status='1' AND (a.review_stage IS NULL OR a.review_stage='' OR a.review_stage='FINAL')) final_count,SUM(a.review_status='1' AND (a.review_stage IS NULL OR a.review_stage='' OR a.review_stage='FINAL') AND a.sign_status IN ('1','5') AND a.source_type='3') confirmed FROM edu_attendance_lesson_guest g JOIN edu_attendance_lesson l ON l.lesson_id=g.lesson_id LEFT JOIN edu_course_schedule mc ON mc.schedule_id=l.schedule_id LEFT JOIN edu_attendance_makeup mp ON mp.guest_id=g.guest_id AND mp.cancelled=0 LEFT JOIN edu_class_sign_in a ON a.schedule_id=l.schedule_id AND a.class_date=l.class_date AND a.del_flag='0' AND COALESCE(a.student_id,(SELECT ae.student_id FROM edu_enrollment ae WHERE ae.enrollment_id=a.enrollment_id))=g.student_id WHERE g.source='TRANSFER' AND g.source_lesson_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") GROUP BY g.guest_id,g.source_lesson_id,g.student_id,mp.plan_id,mp.arrange_type,l.schedule_id,l.lesson_no,l.class_date,l.course_name,mc.course_class_name,l.cancelled,l.locked,mc.status,mc.del_flag",ids.toArray());
                for(var m:makeup) {
                    String key=m.get("sourceLessonId")+":"+m.get("studentId");
                    linked.put(key,m);
                    if(number(m.get("recordCount"))>1)makeupConflicts.add(key);
                    else if(number(m.get("recordCount"))==1&&number(m.get("confirmed"))==1&&number(m.get("cancelled"))==0)completed.put(key,m);
                }
            }
        }
        var groups=new LinkedHashMap<String,Map<String,Object>>();var seen=new HashSet<String>();
        for(var r:records) {
            String student=r.get("studentId")!=null?"S:"+r.get("studentId"):r.get("enrollmentId")!=null?"E:"+r.get("enrollmentId"):"R:"+r.get("signInId");
            String key=student+":"+r.get("scheduleId"),occurrence=key+":"+r.get("classDate");if(!seen.add(occurrence))continue;
            var group=groups.computeIfAbsent(key,k->{var copy=new LinkedHashMap<String,Object>(r);copy.remove("recordCount");copy.remove("signInId");copy.put("studentKey",student);copy.put("rowKey",key);copy.put("leaveCount",0);copy.put("makeupCount",0);copy.put("pendingCount",0);copy.put("scheduledCount",0);copy.put("failedCount",0);copy.put("conflictCount",0);copy.put("lessons",new ArrayList<Map<String,Object>>());return copy;});
            String originKey=r.get("lessonId")+":"+r.get("studentId");
            boolean conflict=number(r.get("recordCount"))!=1 || makeupConflicts.contains(originKey);
            var makeup=linked.get(originKey);
            boolean unavailable=makeup!=null&&(number(makeup.get("cancelled"))==1||!"0".equals(text(makeup.get("targetStatus")))||!"0".equals(text(makeup.get("targetDeleted"))));
            String lessonState=conflict?"conflict":completed.containsKey(originKey)?"completed":makeup==null?"pending":unavailable||number(makeup.get("finalCount"))==1?"failed":"scheduled";
            boolean canCancel=makeup!=null&&makeup.get("planId")!=null&&!conflict&&(number(makeup.get("recordCount"))==1&&number(makeup.get("finalCount"))==1&&!completed.containsKey(originKey)||number(makeup.get("sheetCount"))==0&&(unavailable||number(makeup.get("locked"))==0));
            boolean stable=r.get("studentId")!=null&&r.get("lessonId")!=null;
            var lesson=new LinkedHashMap<String,Object>();for(String f:List.of("signInId","lessonId","lessonNo","classDate"))lesson.put(f,r.get(f));
            lesson.put("state",lessonState);lesson.put("makeup",conflict?null:makeup);
            boolean canManage=childStudent==null&&teacherScope&&text(r.get("sourceTeacherId")).equals(text(SecurityUtils.getUserId()));
            lesson.put("canCancel",canManage&&canCancel);lesson.put("canArrange",canManage&&stable&&!conflict&&("pending".equals(lessonState)||"failed".equals(lessonState)&&canCancel));
            lesson.put("arrangeBlockedReason",!stable?"请先关联学员档案并完善原课次":conflict?"请先核对重复出勤记录":"");
            @SuppressWarnings("unchecked") var lessons=(List<Map<String,Object>>)group.get("lessons");lessons.add(lesson);
            if(conflict)group.put("conflictCount",number(group.get("conflictCount"))+1);
            else {
                group.put("leaveCount",number(group.get("leaveCount"))+1);
                String counter="completed".equals(lessonState)?"makeupCount":"pendingCount";group.put(counter,number(group.get(counter))+1);
                if("scheduled".equals(lessonState))group.put("scheduledCount",number(group.get("scheduledCount"))+1);
                if("failed".equals(lessonState))group.put("failedCount",number(group.get("failedCount"))+1);
            }
        }
        return groups.values().stream().filter(r->state.isEmpty()||switch(state){case "pending"->number(r.get("pendingCount"))+number(r.get("conflictCount"))>0;case "scheduled"->number(r.get("scheduledCount"))>0;case "failed"->number(r.get("failedCount"))>0;default->number(r.get("pendingCount"))==0&&number(r.get("conflictCount"))==0;}).toList();
    }
    private Map<String,Object> parentLesson(Object lessonId) {
        if(lessonId==null)return Map.of();
        return db.one("SELECT l.lesson_no,DATE_FORMAT(l.class_date,'%Y-%m-%d') class_date,COALESCE(l.course_name,c.course_class_name) course_name,TIME_FORMAT(l.start_time,'%H:%i') start_time,TIME_FORMAT(l.end_time,'%H:%i') end_time,COALESCE(NULLIF(u.nick_name,''),u.user_name,c.teacher_name) teacher_name,COALESCE(NULLIF(c.lesson_location,''),NULLIF(CONCAT_WS(' · ',room.campus_name,room.classroom_name),''),'待确认') place_text,l.cancelled FROM edu_attendance_lesson l JOIN edu_course_schedule c ON c.schedule_id=l.schedule_id LEFT JOIN sys_user u ON u.user_id=COALESCE(l.teacher_id,c.teacher_id) LEFT JOIN edu_classroom room ON room.classroom_id=c.classroom_id WHERE l.lesson_id=?",lessonId);
    }
    /** Read-only projection: ownership comes from the bound child, never from names or phone numbers. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @SuppressWarnings("unchecked")
    public Map<String,Object> parentReport(Long accountId) {
        if(db.rows("SELECT u.user_id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' AND r.role_key='parent' AND r.status='0' AND r.del_flag='0'",SecurityUtils.getUserId()).isEmpty())throw new ServiceException("请使用家长账号查看");
        var child=parents.ownedChild(accountId);long student=Long.parseLong(text(child.get("studentId")));
        var groups=collect(Map.of(),false,student);var rows=new ArrayList<Map<String,Object>>();int completed=0;
        for(var group:groups)for(var lesson:(List<Map<String,Object>>)group.get("lessons")) {
            var row=new LinkedHashMap<String,Object>();
            row.put("rowKey",text(lesson.get("signInId")));row.put("studentName",child.get("studentName"));
            row.put("courseName",group.get("courseName"));row.put("lessonNo",lesson.get("lessonNo"));row.put("classDate",lesson.get("classDate"));
            String state=text(lesson.get("state"));String label=switch(state){case "completed"->"已完成补课";case "scheduled"->"待补课";case "failed"->"补课未完成 · 待重新安排";case "conflict"->"出勤核对中";default->"已请假 · 待安排补课";};
            var linked=(Map<String,Object>)lesson.get("makeup");
            if(linked!=null){
                if("scheduled".equals(state)&&number(linked.get("recordCount"))>0)label="补课出勤确认中";
                var target=db.one("SELECT lesson_id FROM edu_attendance_lesson WHERE schedule_id=? AND lesson_no=?",linked.get("scheduleId"),linked.get("lessonNo"));
                row.put("makeup",parentLesson(target.get("lessonId")));
            }
            var plans=db.rows("SELECT p.plan_id,p.target_lesson_id,p.cancelled,DATE_FORMAT(p.create_time,'%Y-%m-%d %H:%i') arranged_at FROM edu_attendance_makeup p WHERE p.sign_in_id=? AND p.student_id=? ORDER BY p.plan_id DESC LIMIT 20",lesson.get("signInId"),student);
            var history=new ArrayList<Map<String,Object>>();
            for(var plan:plans){var item=new LinkedHashMap<String,Object>(parentLesson(plan.get("targetLessonId")));item.put("rowKey",text(plan.get("planId")));item.put("cancelled",number(plan.get("cancelled"))==1);item.put("arrangedAt",plan.get("arrangedAt"));history.add(item);}
            if("pending".equals(state)&&!plans.isEmpty())label="已请假 · 待重新安排";
            row.put("state",state);row.put("stateText",label);row.put("history",history);rows.add(row);if("completed".equals(state))completed++;
        }
        return Map.of("rows",rows,"leaveCount",rows.size(),"makeupCount",completed,"pendingCount",rows.size()-completed);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> report(Map<String,String> query) {
        int page=page(query,"pageNum",1,100000),size=page(query,"pageSize",20,100);
        boolean teacherScope=teacherOnly(query.getOrDefault("scope",""));
        var all=collect(query,teacherScope,null);int start=Math.min((page-1)*size,all.size());
        var result=new LinkedHashMap<String,Object>();result.put("rows",all.subList(start,Math.min(start+size,all.size())));result.put("total",all.size());result.put("readOnly",!teacherScope);result.put("canManageMakeup",teacherScope);
        for(String k:List.of("leaveCount","makeupCount","pendingCount","scheduledCount","failedCount","conflictCount"))result.put(k,all.stream().mapToInt(r->number(r.get(k))).sum());
        var students=new HashSet<String>();for(var r:all)students.add(text(r.get("studentKey")));result.put("studentCount",students.size());return result;
    }
}
