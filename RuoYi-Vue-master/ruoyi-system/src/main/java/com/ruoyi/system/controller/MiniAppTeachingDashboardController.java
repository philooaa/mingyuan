package com.ruoyi.system.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.shop.ShopRepository;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/miniapp/admin/teaching-dashboard")
@PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('system:enrollment:list') and @ss.hasPermi('system:tuition:list')")
public class MiniAppTeachingDashboardController {
    private final ShopRepository db;
    public MiniAppTeachingDashboardController(ShopRepository db) { this.db = db; }

    @GetMapping("/lesson-details")
    public AjaxResult lessonDetails(@RequestParam Long scheduleId, @RequestParam String date) {
        LocalDate day=LocalDate.parse(date);
        return AjaxResult.success(db.rows("SELECT DISTINCT COALESCE(CONCAT('E',r.enrollment_id),CONCAT('S',r.sign_in_id)) record_key,r.student_name,s.course_class_name,DATE_FORMAT(r.class_date,'%Y-%m-%d') class_date,s.time_slot,CASE WHEN s.class_mode='2' THEN 1.5 ELSE 1 END consumption_count FROM edu_class_sign_in r JOIN edu_course_schedule s ON s.schedule_id=r.schedule_id WHERE r.schedule_id=? AND r.class_date=? AND r.del_flag='0' AND r.sign_status IN ('1','4','5') AND r.review_status='1' AND TIMESTAMP(r.class_date,s.end_time)<=? ORDER BY r.student_name,record_key",scheduleId,day,LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
    }

    @GetMapping("/weekly-history")
    public AjaxResult weeklyHistory(@RequestParam(defaultValue="0") int page) {
        if(page<0 || page>520) throw new com.ruoyi.common.exception.ServiceException("页码无效");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate end=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((long) page * 12);
        LocalDate start=end.minusWeeks(12);
        var rows=db.rows("SELECT DATE_FORMAT(DATE_SUB(class_date,INTERVAL WEEKDAY(class_date) DAY),'%Y-%m-%d') week_start,COUNT(*) actual_count,SUM(CASE WHEN class_mode='2' THEN 1.5 ELSE 1 END) consumption_count FROM (SELECT DISTINCT r.schedule_id,r.class_date,s.class_mode,COALESCE(CONCAT('E',r.enrollment_id),CONCAT('S',r.sign_in_id)) record_key FROM edu_class_sign_in r JOIN edu_course_schedule s ON s.schedule_id=r.schedule_id WHERE r.class_date>=? AND r.class_date<? AND r.del_flag='0' AND r.sign_status IN ('1','4','5') AND r.review_status='1' AND TIMESTAMP(r.class_date,s.end_time)<=?) completed GROUP BY week_start ORDER BY week_start DESC",start,end,LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        return AjaxResult.success(rows);
    }

    @GetMapping
    public AjaxResult dashboard(@RequestParam(required=false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : LocalDate.parse(date);
        LocalDate start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusWeeks(1);
        var lessons = db.rows("SELECT DATE_FORMAT(r.class_date,'%Y-%m-%d') class_date,s.schedule_id,s.course_class_name,s.teacher_name,s.time_slot,s.course_year,s.term_name,COUNT(DISTINCT COALESCE(CONCAT('E',r.enrollment_id),CONCAT('S',r.sign_in_id))) actual_count,COUNT(DISTINCT COALESCE(CONCAT('E',r.enrollment_id),CONCAT('S',r.sign_in_id)))*CASE WHEN s.class_mode='2' THEN 1.5 ELSE 1 END consumption_count FROM edu_class_sign_in r JOIN edu_course_schedule s ON s.schedule_id=r.schedule_id WHERE r.class_date>=? AND r.class_date<? AND r.del_flag='0' AND r.sign_status IN ('1','4','5') AND r.review_status='1' AND TIMESTAMP(r.class_date,s.end_time)<=? GROUP BY r.class_date,s.schedule_id,s.course_class_name,s.teacher_name,s.time_slot,s.course_year,s.term_name,s.class_mode ORDER BY r.class_date DESC,s.time_slot,s.schedule_id", start, end, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        var refunds = db.rows("SELECT r.refund_id,e.student_name,r.approved_amount,DATE_FORMAT(r.completed_time,'%Y-%m-%d %H:%i') completed_time FROM edu_tuition_refund r JOIN edu_tuition_payment p ON p.payment_id=r.payment_id JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE p.finance_mode='REAL' AND r.refund_status='SUCCESS' AND r.completed_time>=? AND r.completed_time<? ORDER BY r.completed_time DESC,r.refund_id DESC", start.atStartOfDay(), end.atStartOfDay());
        var teachers = db.rows("SELECT s.teacher_id,COALESCE(NULLIF(TRIM(u.user_name),''),NULLIF(s.teacher_name,''),'教师待配置') teacher_name,s.schedule_id,s.course_class_name,s.time_slot,s.course_year,s.term_name,COUNT(DISTINCT COALESCE(CONCAT('S',COALESCE(e.student_id,a.student_id)),CONCAT('A',e.lesson_account_id),CONCAT('E',e.enrollment_id))) student_count FROM edu_enrollment e JOIN edu_course_schedule s ON s.schedule_id=e.schedule_id LEFT JOIN sys_user u ON u.user_id=s.teacher_id LEFT JOIN edu_student_lesson_account a ON a.account_id=e.lesson_account_id AND a.parent_id=e.parent_id WHERE e.del_flag='0' AND COALESCE(e.bill_type,'')<>'CART_ORDER' AND COALESCE(e.enrollment_status,'') NOT IN ('2','3','已取消','已拒绝','老师已拒绝') GROUP BY s.teacher_id,u.user_name,s.teacher_name,s.schedule_id,s.course_class_name,s.time_slot,s.course_year,s.term_name ORDER BY teacher_name,s.course_class_name,s.schedule_id");
        return AjaxResult.success(Map.of("weekStart",start.toString(),"weekEnd",end.minusDays(1).toString(),"lessons",lessons,"refunds",refunds,"teachers",teachers));
    }
}
