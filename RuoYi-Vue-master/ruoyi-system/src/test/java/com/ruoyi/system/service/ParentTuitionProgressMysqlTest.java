package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.ruoyi.system.mapper.MiniAppParentMapper;
import com.ruoyi.system.service.impl.MiniAppParentServiceImpl;

/** Explicit clone-only regression against the actual schema, never the business database. */
@EnabledIfEnvironmentVariable(named = "MINGYUAN_TEST_DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3306/mingyuan_single_test_.*")
class ParentTuitionProgressMysqlTest extends ChildCenterMysqlTest {
    private MiniAppParentServiceImpl classes;

    private Map<String, Object> course(long enrollment) {
        ScheduleCrudTest.principal(parent, "parent");
        var row = classes.selectMyTimetable().stream()
                .filter(e -> String.valueOf(enrollment).equals(String.valueOf(e.get("enrollmentId"))))
                .findFirst().orElseThrow();
        assertTrue(row.keySet().stream().noneMatch(k -> k.startsWith("tuitionSource")));
        assertFalse(row.containsKey("evidenceKey"));
        assertFalse(row.containsKey("payerName"));
        return row;
    }

    private void expect(long enrollment, String state) {
        assertEquals(state, course(enrollment).get("tuitionProgress"));
    }

    private void price(long enrollment) {
        ScheduleCrudTest.principal(1, "admin");
        payments.price(enrollment, 1, Map.of("originalAmount", "100", "financeMode", "MOCK",
                "financialVersion", db.one("SELECT financial_version FROM edu_enrollment WHERE enrollment_id=?", enrollment).get("financialVersion")));
    }

    private long collect(long enrollment) {
        ScheduleCrudTest.principal(1, "admin");
        return id(payments.createPayment(enrollment, 1, true, Map.of("channel", "MOCK",
                "expectedPayableAmount", "100", "financialVersion",
                db.one("SELECT financial_version FROM edu_enrollment WHERE enrollment_id=?", enrollment).get("financialVersion"),
                "idempotencyKey", UUID.randomUUID().toString())), "paymentId");
    }

    @Test void projectsRealFinanceStagesOrdersRefundsAndOwnershipWithoutWriting() throws Exception {
        setup(); // Inherited guard rejects every database except a disposable random clone.
        classes = new MiniAppParentServiceImpl();
        inject(classes, "tuition", payments); inject(classes, "db", db);
        inject(classes, "singleService", single); inject(classes, "credits", credits);
        inject(classes, "schedules", session.getMapper(com.ruoyi.system.mapper.EduCourseScheduleMapper.class));
        inject(classes, "miniAppParentMapper", session.getMapper(MiniAppParentMapper.class));
        var personal = new ParentAccountService(); inject(personal, "db", db);
        var target = db.one("SELECT s.schedule_id,s.classroom_id FROM edu_course_schedule s JOIN edu_classroom c ON c.classroom_id=s.classroom_id WHERE s.class_mode='1' AND s.del_flag='0' AND s.status='0' AND s.recruit_status='0' AND c.status='0' ORDER BY s.schedule_id LIMIT 1");
        long schedule = id(target, "scheduleId");
        db.jdbc().update("UPDATE edu_course_schedule SET enrolled_count=0,tuition_price=NULL,unit_price=NULL WHERE schedule_id=?", schedule);
        long a = account(parent, "同名进度学生"), b = account(parent, "同名进度学生"), foreign = account(otherParent, "同名进度学生");
        ScheduleCrudTest.principal(parent, "parent");
        long ea = classEnroll(classes, schedule, a), eb = classEnroll(classes, schedule, b);
        expect(ea, "WAIT_PRICE"); expect(eb, "WAIT_PRICE");
        price(ea); expect(ea, "WAIT_PAYMENT");
        long payment = collect(ea); expect(ea, "WAIT_CONFIRM"); expect(eb, "WAIT_PRICE");
        ScheduleCrudTest.principal(1, "admin"); payments.confirmPayment(payment, 1, true, Map.of());
        expect(ea, "PAID"); expect(eb, "WAIT_PRICE");
        assertEquals(List.of(ea), personal.filterChild(classes.selectMyTimetable(), a).stream()
                .map(e -> Long.parseLong(e.get("enrollmentId").toString())).toList());
        assertThrows(Exception.class, () -> personal.filterChild(classes.selectMyTimetable(), foreign));
        ScheduleCrudTest.principal(otherParent, "parent");
        assertTrue(classes.selectMyTimetable().stream().noneMatch(e -> Set.of(ea, eb).contains(Long.parseLong(e.get("enrollmentId").toString()))));

        // A COURSE_ITEM reads the order's bill, not its own ORDER_BOUND / unpaid labels.
        long order = db.insert("edu_enrollment", Map.ofEntries(
                Map.entry("enrollment_code", UUID.randomUUID().toString().replace("-", "")),
                Map.entry("parent_id", parent), Map.entry("student_name", "同名进度学生"),
                Map.entry("student_id", db.one("SELECT student_id FROM edu_student_lesson_account WHERE account_id=?", b).get("studentId")),
                Map.entry("lesson_account_id", b), Map.entry("bill_type", "CART_ORDER"),
                Map.entry("billing_status", "PENDING_PRICE"), Map.entry("enrollment_status", "报名成功"),
                Map.entry("pay_status", "未支付"), Map.entry("finance_mode", "MOCK"),
                Map.entry("original_amount", "100.00"), Map.entry("discount_amount", "0.00"),
                Map.entry("payable_amount", "100.00")));
        db.jdbc().update("UPDATE edu_enrollment SET bill_type='COURSE_ITEM',billing_status='ORDER_BOUND',order_enrollment_id=? WHERE enrollment_id=?", order, eb);
        expect(eb, "WAIT_PRICE"); price(order); expect(eb, "WAIT_PAYMENT");
        long orderPayment = collect(order); expect(eb, "WAIT_CONFIRM");
        ScheduleCrudTest.principal(1, "admin"); payments.confirmPayment(orderPayment, 1, true, Map.of());
        expect(eb, "PAID");
        assertThrows(Exception.class, () -> db.jdbc().update("UPDATE edu_enrollment SET parent_id=? WHERE enrollment_id=?", otherParent, order));
        long differentChild = account(parent, "同名进度学生");
        ScheduleCrudTest.principal(parent, "parent");
        long mismatched = classEnroll(classes, schedule, differentChild);
        db.jdbc().update("UPDATE edu_enrollment SET bill_type='COURSE_ITEM',billing_status='ORDER_BOUND',order_enrollment_id=? WHERE enrollment_id=?", order, mismatched);
        expect(mismatched, "UNAVAILABLE");

        long refund = db.insert("edu_tuition_refund", Map.ofEntries(
                Map.entry("refund_no", UUID.randomUUID().toString()), Map.entry("payment_id", orderPayment),
                Map.entry("refund_kind", "PARTIAL"), Map.entry("refund_status", "PENDING_REVIEW"),
                Map.entry("requested_amount", "30.00"),
                Map.entry("reason", "isolated progress check"), Map.entry("calculation_snapshot", "{}"),
                Map.entry("applicant_id", parent), Map.entry("idempotency_key", UUID.randomUUID().toString()),
                Map.entry("request_hash", "a".repeat(64))));
        expect(eb, "REFUND_PENDING");
        db.jdbc().update("UPDATE edu_tuition_refund SET refund_status='UNKNOWN',approved_amount=30 WHERE refund_id=?", refund);
        expect(eb, "REFUND_UNKNOWN");
        db.jdbc().update("UPDATE edu_tuition_refund SET refund_status='SUCCESS',completed_time=NOW(3) WHERE refund_id=?", refund);
        expect(eb, "PARTIAL_REFUND");
        db.insert("edu_tuition_refund", Map.ofEntries(
                Map.entry("refund_no", UUID.randomUUID().toString()), Map.entry("payment_id", orderPayment),
                Map.entry("refund_kind", "PARTIAL"), Map.entry("refund_status", "SUCCESS"),
                Map.entry("requested_amount", "70.00"), Map.entry("approved_amount", "70.00"),
                Map.entry("completed_time", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())),
                Map.entry("reason", "isolated final refund check"),
                Map.entry("calculation_snapshot", "{}"), Map.entry("applicant_id", parent),
                Map.entry("idempotency_key", UUID.randomUUID().toString()), Map.entry("request_hash", "b".repeat(64))));
        expect(eb, "REFUNDED");

        var enrollmentBefore = db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id");
        var paymentBefore = db.rows("SELECT * FROM edu_tuition_payment ORDER BY payment_id");
        var refundBefore = db.rows("SELECT * FROM edu_tuition_refund ORDER BY refund_id");
        expect(ea, "PAID"); expect(eb, "REFUNDED");
        assertEquals(enrollmentBefore, db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
        assertEquals(paymentBefore, db.rows("SELECT * FROM edu_tuition_payment ORDER BY payment_id"));
        assertEquals(refundBefore, db.rows("SELECT * FROM edu_tuition_refund ORDER BY refund_id"));
    }
}
