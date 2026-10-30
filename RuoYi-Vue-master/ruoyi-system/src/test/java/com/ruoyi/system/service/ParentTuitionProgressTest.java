package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParentTuitionProgressTest {
    private Map<String, Object> course(String billing, String payment) {
        var row = new HashMap<String, Object>();
        row.put("enrollmentId", 12L);
        row.put("payStatus", "未支付");
        row.put("tuitionSourceBillId", 99L);
        row.put("tuitionSourceBillingStatus", billing);
        row.put("tuitionSourcePaymentStatus", payment);
        return row;
    }

    private void expect(String expected, Map<String, Object> row) {
        var enrollment = row.get("enrollmentId");
        ParentTuitionProgress.decorate(row);
        assertEquals(expected, row.get("tuitionProgress"));
        assertEquals(enrollment, row.get("enrollmentId"));
        assertTrue(row.keySet().stream().noneMatch(k -> k.startsWith("tuitionSource")));
    }

    @Test void stepsFollowConfirmedFinanceFacts() {
        expect("WAIT_PRICE", course("PENDING_PRICE", ""));
        expect("WAIT_PAYMENT", course("OPEN", ""));
        expect("WAIT_CONFIRM", course("OPEN", "PENDING"));
        expect("PAID", course("OPEN", "SUCCESS"));
        var free = course("OPEN", "SUCCESS");
        free.put("tuitionSourceChannel", "FREE");
        expect("WAIVED", free);
    }

    @Test void oldPaymentLabelsNeverSubstituteForAConfirmedPayment() {
        for (String label : java.util.List.of("1", "2", "已支付", "已退款", "部分退款", "已减免")) {
            var row = course("OPEN", "");
            row.put("tuitionSourcePayStatus", label);
            expect("HISTORY_PENDING", row);
        }
        expect("HISTORY_PENDING", course("HISTORY_PENDING", "SUCCESS"));
    }

    @Test void unknownAndClosedDoNotPretendToBeUnpaid() {
        expect("PAYMENT_UNKNOWN", course("OPEN", "UNKNOWN"));
        expect("CLOSED", course("CLOSED", ""));
        expect("UNAVAILABLE", course("UNRECOGNIZED", ""));
        var missing = course("OPEN", "SUCCESS");
        missing.remove("tuitionSourceBillId");
        expect("UNAVAILABLE", missing);
    }

    @Test void pendingAndSuccessfulRefundsOverrideThePaidLabel() {
        for (String state : java.util.List.of("WAIT_EDU", "WAIT_ADMIN", "PENDING_REVIEW", "APPROVED", "PROCESSING")) {
            var row = course("OPEN", "SUCCESS");
            row.put("tuitionSourceRefundStatus", state);
            expect("REFUND_PENDING", row);
        }
        var unknown = course("OPEN", "SUCCESS");
        unknown.put("tuitionSourceRefundStatus", "UNKNOWN");
        expect("REFUND_UNKNOWN", unknown);
        var partial = course("OPEN", "SUCCESS");
        partial.put("tuitionSourceRefundedAmount", "30.00");
        partial.put("tuitionSourcePaymentAmount", "100.00");
        expect("PARTIAL_REFUND", partial);
        var full = course("OPEN", "SUCCESS");
        full.put("tuitionSourceRefundedAmount", "100.00");
        full.put("tuitionSourcePaymentAmount", "100.00");
        expect("REFUNDED", full);
    }

    @Test void lessonCreditSettlementDoesNotEnterTheCashPaymentSteps() {
        var reserved = course("PENDING_PRICE", "");
        reserved.put("tuitionSourceSettlement", "P");
        expect("CREDIT_RESERVED", reserved);
        var used = course("PENDING_PRICE", "");
        used.put("tuitionSourceSettlement", "P");
        used.put("tuitionSourceCompleted", "2030-01-01 12:00:00");
        expect("CREDIT_USED", used);
    }
}
