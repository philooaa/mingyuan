package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit clone-only regression against the actual schema, never the business database. */
@EnabledIfEnvironmentVariable(named = "MINGYUAN_TEST_DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3306/mingyuan_single_test_.*")
class CartOrderCancelMysqlTest extends LessonCreditMysqlTest {

    private List<Long> schedules() {
        return db.rows("SELECT schedule_id FROM edu_course_schedule WHERE class_mode='1'"
                + " AND del_flag='0' AND status='0' ORDER BY schedule_id LIMIT 2")
                .stream().map(row -> id(row, "scheduleId")).toList();
    }

    private long cartOrder(long account) {
        return db.insert("edu_enrollment", Map.ofEntries(
                Map.entry("enrollment_code", UUID.randomUUID().toString().replace("-", "").substring(0, 30)),
                Map.entry("parent_id", parent), Map.entry("student_name", "整单取消学生"),
                Map.entry("student_id", db.one(
                        "SELECT student_id FROM edu_student_lesson_account WHERE account_id=?", account).get("studentId")),
                Map.entry("lesson_account_id", account), Map.entry("settlement_method", "C"),
                Map.entry("enrollment_status", "报名成功"), Map.entry("pay_status", "未支付"),
                Map.entry("bill_type", "CART_ORDER"), Map.entry("billing_status", "PENDING_PRICE"),
                Map.entry("finance_mode", "MOCK"), Map.entry("original_amount", "200.00"),
                Map.entry("discount_amount", "0.00"), Map.entry("payable_amount", "200.00")));
    }

    private long cartItem(long schedule, long order, long account) {
        return db.insert("edu_enrollment", Map.ofEntries(
                Map.entry("enrollment_code", UUID.randomUUID().toString().replace("-", "").substring(0, 30)),
                Map.entry("schedule_id", schedule), Map.entry("parent_id", parent),
                Map.entry("student_id", db.one(
                        "SELECT student_id FROM edu_student_lesson_account WHERE account_id=?", account).get("studentId")),
                Map.entry("lesson_account_id", account), Map.entry("settlement_method", "C"),
                Map.entry("student_name", "整单取消学生"), Map.entry("enrollment_status", "报名成功"),
                Map.entry("pay_status", "未支付"), Map.entry("bill_type", "COURSE_ITEM"),
                Map.entry("billing_status", "ORDER_BOUND"), Map.entry("order_enrollment_id", order),
                Map.entry("finance_mode", "MOCK")));
    }

    private int enrolled(long schedule) {
        return db.jdbc().queryForObject(
                "SELECT enrolled_count FROM edu_course_schedule WHERE schedule_id=?", Integer.class, schedule);
    }

    private String status(long enrollment) {
        return String.valueOf(db.one(
                "SELECT enrollment_status FROM edu_enrollment WHERE enrollment_id=?", enrollment).get("enrollmentStatus"));
    }

    @Test
    void cancellingOneCartItemCancelsTheWholeOrderOnce() throws Exception {
        setup();
        var schedules = schedules();
        assertEquals(2, schedules.size());
        long one = schedules.get(0), two = schedules.get(1);
        int beforeOne = enrolled(one), beforeTwo = enrolled(two);
        long account = account(parent, "整单取消学生");
        long order = cartOrder(account);
        long first = cartItem(one, order, account), second = cartItem(two, order, account);
        db.jdbc().update("UPDATE edu_course_schedule SET enrolled_count=enrolled_count+1 WHERE schedule_id IN (?,?)", one, two);

        ScheduleCrudTest.principal(parent, "parent");
        // 取消购物车订单里的一门课：返回整单账单，而不是报「请在订单主账单里核价收款」
        var bill = payments.cancelEnrollment(first, parent, false, "家长取消购物车课程");
        assertEquals("CART_ORDER", bill.get("billType"));
        assertEquals(2, ((List<?>) bill.get("items")).size());
        for (long enrollment : List.of(order, first, second)) assertEquals("已取消", status(enrollment));
        assertEquals("CLOSED", String.valueOf(db.one(
                "SELECT billing_status FROM edu_enrollment WHERE enrollment_id=?", order).get("billingStatus")));
        assertEquals(beforeOne, enrolled(one));
        assertEquals(beforeTwo, enrolled(two));

        // 同一单里另一门再取消一次：幂等，不重复释放名额
        payments.cancelEnrollment(second, parent, false, "家长取消购物车课程");
        assertEquals(beforeOne, enrolled(one));
        assertEquals(beforeTwo, enrolled(two));
    }

    @Test
    void cancellingTheOrderItselfStillCancelsEveryItem() throws Exception {
        setup();
        var schedules = schedules();
        long one = schedules.get(0), two = schedules.get(1);
        int beforeOne = enrolled(one), beforeTwo = enrolled(two);
        long account = account(parent, "整单取消学生");
        long order = cartOrder(account);
        long first = cartItem(one, order, account), second = cartItem(two, order, account);
        db.jdbc().update("UPDATE edu_course_schedule SET enrolled_count=enrolled_count+1 WHERE schedule_id IN (?,?)", one, two);

        ScheduleCrudTest.principal(parent, "parent");
        var bill = payments.cancelEnrollment(order, parent, false, "家长取消购物车订单");
        assertEquals("CART_ORDER", bill.get("billType"));
        for (long enrollment : List.of(order, first, second)) assertEquals("已取消", status(enrollment));
        assertEquals(beforeOne, enrolled(one));
        assertEquals(beforeTwo, enrolled(two));
    }
}
