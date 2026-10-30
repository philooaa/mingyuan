package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.util.Map;

/** Read-only parent projection. Payment facts stay authoritative; no financial details are exposed. */
public final class ParentTuitionProgress {
    private ParentTuitionProgress() {}

    public static void decorate(Map<String, Object> course) {
        String state = state(course);
        course.keySet().removeIf(key -> key.startsWith("tuitionSource"));
        course.put("tuitionProgress", state);
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get("tuitionSource" + field);
        return value == null ? "" : value.toString();
    }

    private static BigDecimal amount(Map<String, Object> row, String field) {
        String value = text(row, field);
        return value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String state(Map<String, Object> row) {
        if ("P".equals(text(row, "Settlement"))) {
            return text(row, "Completed").isEmpty() ? "CREDIT_RESERVED" : "CREDIT_USED";
        }
        if (text(row, "BillId").isEmpty()) return "UNAVAILABLE";
        String billing = text(row, "BillingStatus");
        if ("HISTORY_PENDING".equals(billing)) return "HISTORY_PENDING";
        String refund = text(row, "RefundStatus");
        if ("UNKNOWN".equals(refund)) return "REFUND_UNKNOWN";
        if (!refund.isEmpty()) return "REFUND_PENDING";
        BigDecimal refunded = amount(row, "RefundedAmount");
        if (refunded.signum() > 0) {
            return refunded.compareTo(amount(row, "PaymentAmount")) >= 0 ? "REFUNDED" : "PARTIAL_REFUND";
        }
        String payment = text(row, "PaymentStatus");
        if ("SUCCESS".equals(payment)) {
            return "FREE".equals(text(row, "Channel")) ? "WAIVED" : "PAID";
        }
        if ("UNKNOWN".equals(payment)) return "PAYMENT_UNKNOWN";
        if ("CLOSED".equals(billing)) return "CLOSED";
        if ("PENDING".equals(payment)) return "WAIT_CONFIRM";
        // An old payment label alone cannot prove that the finance service confirmed a receipt.
        if (java.util.Set.of("1", "2", "已支付", "已退款", "部分退款", "已减免")
                .contains(text(row, "PayStatus"))) return "HISTORY_PENDING";
        if ("PENDING_PRICE".equals(billing)) return "WAIT_PRICE";
        if ("OPEN".equals(billing)) return "WAIT_PAYMENT";
        return "UNAVAILABLE";
    }
}
