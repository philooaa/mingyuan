package com.ruoyi.system.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.shop.ShopRepository;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/miniapp/admin/home-stats")
public class MiniAppAdminHomeStatsController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ShopRepository db;

    public MiniAppAdminHomeStatsController(ShopRepository db) { this.db = db; }

    @PreAuthorize("@ss.hasRole('admin')")
    @GetMapping("/attendance")
    public AjaxResult attendance() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        java.math.BigDecimal count = db.jdbc().queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN class_mode='2' THEN 1.5 ELSE 1 END),0) FROM (SELECT DISTINCT r.schedule_id, r.class_date, s.class_mode, "
            + "COALESCE(CONCAT('E',r.enrollment_id),CONCAT('S',r.sign_in_id)) student_key "
            + "FROM edu_class_sign_in r JOIN edu_course_schedule s ON s.schedule_id=r.schedule_id "
            + "WHERE r.class_date=? AND r.del_flag='0' AND r.sign_status IN ('1','4','5') "
            + "AND COALESCE(r.review_status,'0')<>'2' AND TIMESTAMP(r.class_date,s.end_time)<=?) completed",
            java.math.BigDecimal.class, now.toLocalDate(), now);
        return AjaxResult.success(Map.of("completedCount", count == null ? 0 : count));
    }

    @PreAuthorize("@ss.hasRole('admin') and @ss.hasPermi('system:tuition:list')")
    @GetMapping("/finance")
    public AjaxResult finance() {
        LocalDateTime start = LocalDateTime.now(ZONE).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        LocalDateTime end = start.plusWeeks(1);
        var income = db.one("SELECT COALESCE(SUM(p.amount),0) amount FROM edu_tuition_payment p "
            + "WHERE p.finance_mode='REAL' AND p.payment_status='SUCCESS' AND p.paid_time>=? AND p.paid_time<?", start, end);
        var refund = db.one("SELECT COALESCE(SUM(r.approved_amount),0) amount FROM edu_tuition_refund r "
            + "JOIN edu_tuition_payment p ON p.payment_id=r.payment_id WHERE p.finance_mode='REAL' "
            + "AND r.refund_status='SUCCESS' AND r.completed_time>=? AND r.completed_time<?", start, end);
        return AjaxResult.success(Map.of("income", income.get("amount"), "refund", refund.get("amount")));
    }
}
