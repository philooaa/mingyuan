package com.ruoyi.system.service;

import static com.ruoyi.system.finance.FinanceRules.*;
import com.ruoyi.system.shop.ShopRepository;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import org.springframework.stereotype.Service;

/** Staff-only change of one class enrollment. Financial snapshots and attendance are immutable here. */
@Service
public class EnrollmentTransferService {
    private final ShopRepository db;
    public EnrollmentTransferService(ShopRepository db) { this.db=db; }
    /** A transfer must not authorize a new lesson in the former class; only an existing record is editable. */
    public static boolean hasHistoricalSignIn(ShopRepository db,long enrollment,long schedule,String date) {
        return !db.rows("SELECT sign_in_id FROM edu_class_sign_in WHERE enrollment_id=? AND schedule_id=? AND class_date=? AND del_flag='0' LIMIT 1",enrollment,schedule,date).isEmpty();
    }
    public record TransferRequest(Long fromScheduleId, Long targetScheduleId, String reason) {}

    private Map<String,Object> enrollment(long id) {
        var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",id);
        require("0".equals(e.get("delFlag")) && "0".equals(e.get("status"))
            && Set.of("0","1","待确认","已确认","报名成功").contains(text(e.get("enrollmentStatus"))),"只能为有效报名换课");
        require(e.get("scheduleId")!=null && e.get("classDate")==null
            && Set.of("COURSE","COURSE_ITEM").contains(text(e.get("billType"))),"请选择班课报名；单次预约请使用一对一管理");
        return e;
    }
    public List<Map<String,Object>> history(long enrollment,long actor) {
        tuitionStaff(db,actor);
        return db.rows("SELECT transfer_id,from_schedule_id,to_schedule_id,from_course_name,to_course_name,reason,operator_name,create_time FROM edu_enrollment_transfer WHERE enrollment_id=? AND applied_at IS NOT NULL ORDER BY transfer_id DESC",enrollment);
    }
    public Map<String,Object> options(long enrollment,String keyword,int page,long actor) {
        tuitionStaff(db,actor);var e=enrollment(enrollment);
        keyword=text(keyword);require(keyword.length()<=100,"搜索内容最多100字");require(page>=1 && page<=10000,"页码无效");
        String where=" FROM edu_course_schedule s LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id LEFT JOIN sys_user t ON t.user_id=s.teacher_id"
            + " WHERE s.schedule_id<>? AND s.del_flag='0' AND s.status='0' AND s.recruit_status='0' AND COALESCE(s.class_mode,'1')<>'2'"
            + " AND ((s.classroom_id IS NULL AND NULLIF(TRIM(s.campus_name),'') IS NOT NULL) OR c.status='0')"
            + " AND (s.enroll_limit IS NULL OR s.enroll_limit<=0 OR COALESCE(s.enrolled_count,0)<s.enroll_limit)"
            + " AND (s.course_class_name LIKE ? OR s.grade_name LIKE ? OR s.subject_name LIKE ? OR t.user_name LIKE ? OR t.nick_name LIKE ?)";
        String like="%"+keyword+"%";Object[] args={e.get("scheduleId"),like,like,like,like,like};
        long total=db.jdbc().queryForObject("SELECT COUNT(*)"+where,Long.class,args);
        var params=new ArrayList<Object>(Arrays.asList(args));params.add((page-1)*20);
        var rows=db.rows("SELECT s.schedule_id,s.course_class_name,s.grade_name,s.subject_name,s.course_year,s.term_name,s.period_name,s.time_slot,"
            + "TIME_FORMAT(s.start_time,'%H:%i') start_time,TIME_FORMAT(s.end_time,'%H:%i') end_time,DATE_FORMAT(s.start_date,'%Y-%m-%d') start_date,DATE_FORMAT(s.end_date,'%Y-%m-%d') end_date,"
            + "COALESCE(NULLIF(t.user_name,''),NULLIF(t.nick_name,''),s.teacher_name) teacher_name,COALESCE(NULLIF(s.campus_name,''),c.campus_name) campus_name,c.classroom_name"
            + where+" ORDER BY s.course_year DESC,s.grade_name,s.subject_name,s.schedule_id LIMIT 20 OFFSET ?",params.toArray());
        return Map.of("rows",rows,"total",total,"history",history(enrollment,actor));
    }
    public void transfer(long enrollment,long from,long target,String reason,long actor) {
        tuitionStaff(db,actor);
        require(enrollment>0 && from>0 && target>0 && from!=target,"请选择不同的目标课程");
        String explanation=text(reason);require(!explanation.isBlank() && explanation.length()<=255,"请填写255字以内的换课原因");
        db.transactions().executeWithoutResult(tx -> {
            var snapshot=enrollment(enrollment);
            Long bill=snapshot.get("orderEnrollmentId")==null?null:id(snapshot.get("orderEnrollmentId"));
            if(bill!=null) {
                var head=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",bill);
                require("CART_ORDER".equals(head.get("billType")) && Objects.equals(head.get("parentId"),snapshot.get("parentId"))
                    && "0".equals(head.get("delFlag")) && !Set.of("2","已取消").contains(text(head.get("enrollmentStatus")))
                    && !"CLOSED".equals(head.get("billingStatus")),"所属订单已取消或关闭，不能换课");
            }
            // Cart cancellation locks the order before its items; ordinary bills lock schedules first.
            for(long id:new TreeSet<>(List.of(from,target))) db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",id);
            db.one("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",enrollment);
            var e=enrollment(enrollment);require(from==id(e.get("scheduleId")),"所属课程已变化，请刷新后重新选择");
            var old=db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=?",from);
            var next=db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=?",target);
            require(!"2".equals(text(old.get("classMode"))) && !"2".equals(text(next.get("classMode"))),"班课换课不能用于一对一预约");
            require("0".equals(next.get("delFlag")) && "0".equals(next.get("status")) && "0".equals(next.get("recruitStatus")),"目标课程已停用或停止招生");
            var duplicates=db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND parent_id=? AND del_flag='0' AND enrollment_status IN ('0','1','待确认','已确认','报名成功') AND (student_id=? OR lesson_account_id=? OR (student_id IS NULL AND lesson_account_id IS NULL AND student_name=?)) FOR UPDATE",target,e.get("parentId"),e.get("studentId"),e.get("lessonAccountId"),e.get("studentName"));
            require(duplicates.isEmpty(),"该学生已经报名目标课程");
            long billId=bill==null?enrollment:bill;
            require(db.rows("SELECT r.refund_id FROM edu_tuition_refund r JOIN edu_tuition_payment p ON p.payment_id=r.payment_id WHERE p.enrollment_id=? AND r.refund_status NOT IN ('SUCCESS','REJECTED','CLOSED','FAILED') FOR UPDATE",billId).isEmpty(),"存在未完成退费，请先处理退费再换课");
            require(db.rows("SELECT leave_id FROM edu_class_leave_request WHERE enrollment_id=? AND schedule_id=? AND state IN ('PENDING','APPROVED') AND class_date>=CURDATE() FOR UPDATE",enrollment,from).isEmpty(),"原课程有待处理或未来已批准请假，请先撤回或处理后换课");
            String operator=text(db.one("SELECT user_name FROM sys_user WHERE user_id=?",actor).get("userName"));
            int reserved=db.jdbc().update("UPDATE edu_course_schedule s LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id SET s.enrolled_count=COALESCE(s.enrolled_count,0)+1,s.update_by=?,s.update_time=NOW() WHERE s.schedule_id=? AND s.del_flag='0' AND s.status='0' AND s.recruit_status='0' AND ((s.classroom_id IS NULL AND NULLIF(TRIM(s.campus_name),'') IS NOT NULL) OR c.status='0') AND (s.enroll_limit IS NULL OR s.enroll_limit<=0 OR COALESCE(s.enrolled_count,0)<s.enroll_limit)",operator,target);
            require(reserved==1,"目标课程不可报名，可能已满班或教室停用");
            db.jdbc().update("INSERT INTO edu_enrollment_transfer(enrollment_id,from_schedule_id,to_schedule_id,from_course_name,to_course_name,reason,operator_id,operator_name,connection_id) VALUES(?,?,?,?,?,?,?,?,CONNECTION_ID())",enrollment,from,target,text(old.get("courseClassName")),text(next.get("courseClassName")),explanation,actor,operator);
            try {
                db.jdbc().update("UPDATE edu_enrollment SET schedule_id=?,update_by=?,update_time=NOW() WHERE enrollment_id=?",target,operator,enrollment);
            } catch(org.springframework.dao.DuplicateKeyException ex) {throw new ServiceException("目标课程已有该学生的报名记录，请先核对历史报名");}
            db.jdbc().update("UPDATE edu_course_schedule SET enrolled_count=GREATEST(COALESCE(enrolled_count,0)-1,0),update_by=?,update_time=NOW() WHERE schedule_id=?",operator,from);
            // Only move the unused summary placeholder; real and confirmed history stays on the original course.
            db.jdbc().update("UPDATE edu_attendance SET schedule_id=?,update_by=?,update_time=NOW() WHERE enrollment_id=? AND schedule_id=? AND del_flag='0' AND attendance_status IN ('0','未上课') AND attended_time IS NULL AND confirm_by IS NULL AND COALESCE(confirm_name,'')=''",target,operator,enrollment,from);

        });
    }
}
