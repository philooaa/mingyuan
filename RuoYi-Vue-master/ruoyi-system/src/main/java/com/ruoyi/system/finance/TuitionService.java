package com.ruoyi.system.finance;

import static com.ruoyi.system.finance.FinanceRules.*;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.lesson.LessonFinance;
import com.ruoyi.system.shop.ShopRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TuitionService {
  private final ShopRepository db;
  private final FinancePrivateStorage storage;
  private final TuitionReceiptService receipts;
  private final CouponService coupons;
  private final boolean mockEnabled;
  private final LessonFinance lessons;
  @Autowired(required=false) private ReferralService referrals;

  @Autowired
  public TuitionService(
      ShopRepository db,
      FinancePrivateStorage storage,
      TuitionReceiptService receipts,
      CouponService coupons,
      Environment env) {
    this(
        db,
        storage,
        receipts,
        coupons,
        mockAllowed(env.getActiveProfiles(), env.getProperty("payment.mode", "disabled")));
  }

  public TuitionService(
      ShopRepository db,
      FinancePrivateStorage storage,
      TuitionReceiptService receipts,
      CouponService coupons,
      boolean mockEnabled) {
    this.db = db;
    this.storage = storage;
    this.receipts = receipts;
    this.coupons = coupons;
    this.mockEnabled = mockEnabled;
    this.lessons = new LessonFinance(db);
  }

  private <T> T tx(Supplier<T> op) {
    for (int n = 0; ; n++)
      try {
        return db.transactions().execute(s -> op.get());
      } catch (PessimisticLockingFailureException e) {
        if (n >= 2) throw new ServiceException("操作繁忙，请重试");
      }
  }

  private static String normalizePay(String s) {
    return switch (s) {
      case "0" -> "未支付";
      case "1" -> "已支付";
      case "2" -> "已退款";
      default -> s;
    };
  }

  public Map<String, Object> summary(long actor, Map<String, String> q) {
    tuitionStaff(db, actor);
    String fm = q.getOrDefault("financeMode", "REAL");
    if (fm.isBlank()) fm = "REAL";
    require(
        Set.of("REAL", "MOCK").contains(fm) && (!fm.equals("MOCK") || mockEnabled), "模拟统计仅限本地测试");
    String where = " WHERE e.finance_mode=?";
    List<Object> args = new ArrayList<>(List.of(fm));
    for (var filter :
        Map.of(
                "enrollmentId",
                "e.enrollment_id",
                "parentId",
                "e.parent_id",
                "billingStatus",
                "e.billing_status")
            .entrySet())
      if (!str(q, filter.getKey()).isBlank()) {
        where += " AND " + filter.getValue() + "=?";
        args.add(q.get(filter.getKey()));
      }
    for (var filter :
        Map.of(
                "studentName",
                "e.student_name",
                "parentName",
                FinanceRules.parentSearchSql("u"),
                "campusName",
                "c.campus_name",
                "courseClassName",
                "s.course_class_name",
                "enrollmentCode",
                "e.enrollment_code")
            .entrySet())
      if (!str(q, filter.getKey()).isBlank()) {
        where += " AND " + filter.getValue() + " LIKE ?";
        args.add("%" + q.get(filter.getKey()) + "%");
      }
    if (!str(q, "payStatus").isBlank()) {
      String ps = normalizePay(q.get("payStatus"));
      where += " AND e.pay_status IN (?,?)";
      args.add(ps);
      args.add(
          switch (ps) {
            case "未支付" -> "0";
            case "已支付" -> "1";
            case "已退款" -> "2";
            default -> ps;
          });
    }
    var result =
        db.one(
            "SELECT COALESCE(SUM(received_amount),0)"
                + " received_amount,COALESCE(SUM(refunded_amount),0)"
                + " refunded_amount,COALESCE(SUM(received_amount-refunded_amount),0)"
                + " net_amount,COALESCE(SUM(CASE WHEN billing_status='OPEN' AND success_count=0"
                + " THEN payable_amount ELSE 0 END),0)"
                + " due_amount,COALESCE(SUM(billing_status='HISTORY_PENDING'),0)"
                + " history_pending_count FROM ("
                + BILL_SELECT
                + BILL_FROM
                + where
                + ") ledger",
            args.toArray());
    Timestamp today = Timestamp.valueOf(LocalDateTime.now(ZONE).toLocalDate().atStartOfDay()),
        tomorrow =
            Timestamp.valueOf(LocalDateTime.now(ZONE).toLocalDate().plusDays(1).atStartOfDay());
    List<Object> dates = new ArrayList<>(args);
    dates.add(today);
    dates.add(tomorrow);
    result.put(
        "todayReceivedAmount",
        db.one(
                "SELECT COALESCE(SUM(p.amount),0) amount"
                    + BILL_FROM
                    + " JOIN edu_tuition_payment p ON p.enrollment_id=e.enrollment_id"
                    + where
                    + " AND p.payment_status='SUCCESS' AND p.paid_time>=? AND p.paid_time<?",
                dates.toArray())
            .get("amount"));
    result.put(
        "todayRefundedAmount",
        db.one(
                "SELECT COALESCE(SUM(r.approved_amount),0) amount"
                    + BILL_FROM
                    + " JOIN edu_tuition_payment p ON p.enrollment_id=e.enrollment_id JOIN"
                    + " edu_tuition_refund r ON r.payment_id=p.payment_id"
                    + where
                    + " AND r.refund_status='SUCCESS' AND r.completed_time>=? AND"
                    + " r.completed_time<?",
                dates.toArray())
            .get("amount"));
    result.put(
        "historyPendingCount",
        new BigDecimal(text(result.get("historyPendingCount"))).longValueExact());
    result.put("financeMode", fm);
    return result;
  }

  private static String normalizeEnrollment(String s) {
    return switch (s) {
      case "0" -> "待确认";
      case "1" -> "报名成功";
      case "2" -> "已取消";
      default -> s;
    };
  }

  private void mode(Map<String, Object> e) {
    require(!"MOCK".equals(e.get("financeMode")) || mockEnabled, "MOCK_DISABLED: 正式环境不能处理模拟账单");
  }

  private void actor(long user, boolean admin) {
    if (admin) tuitionStaff(db, user);
    else
      require(
          db.jdbc()
                  .queryForObject(
                      "SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON"
                          + " ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id WHERE"
                          + " u.user_id=? AND u.status='0' AND u.del_flag='0' AND"
                          + " r.role_key='parent' AND r.status='0' AND r.del_flag='0'",
                      Integer.class,
                      user)
              > 0,
          "FORBIDDEN: 家长权限不足");
  }

  private Map<String, Object> enrollment(long enrollment, long actor, boolean admin, boolean lock) {
    var e =
        db.one(
            "SELECT * FROM edu_enrollment WHERE enrollment_id=?"
                + (admin ? "" : " AND parent_id=?")
                + (lock ? " FOR UPDATE" : ""),
            admin ? new Object[] {enrollment} : new Object[] {enrollment, actor});
    mode(e);
    return e;
  }

  /** Lock schedule first for every mutation, including callbacks and cancellation. */
  private Map<String, Object> lockEnrollment(long enrollment, long actor, boolean admin) {
    var e = enrollment(enrollment, actor, admin, false);
    if (CreditPackageService.isPackage(e))
      db.one("SELECT account_id FROM edu_student_lesson_account WHERE account_id=? AND parent_id=? FOR UPDATE",e.get("lessonAccountId"),e.get("parentId"));
    else if (!"LESSON_PACKAGE".equals(e.get("billType")))
      db.one(
          "SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",
          e.get("scheduleId"));
    return enrollment(enrollment, actor, admin, true);
  }

  private Map<String, Object> lockPayment(long payment, long actor, boolean admin) {
    var p = db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", payment);
    lockEnrollment(id(p.get("enrollmentId")), actor, admin);
    return db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=? FOR UPDATE", payment);
  }

  private Map<String, Object> lockRefund(long refund, long actor, boolean admin) {
    var r = db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=?", refund);
    lockPayment(id(r.get("paymentId")), actor, admin);
    return db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=? FOR UPDATE", refund);
  }

  private void version(Map<String, Object> e, Map<String, Object> b) {
    require(
        integer(b.get("financialVersion"), 0, Integer.MAX_VALUE)
            == integer(e.get("financialVersion"), 0, Integer.MAX_VALUE),
        "BILL_VERSION_CONFLICT: 账单已变化，请刷新确认");
  }

  private void updateBill(long id, Map<String, Object> e, Map<String, Object> updates, long actor) {
    updates.put("financial_version", integer(e.get("financialVersion"), 0, Integer.MAX_VALUE) + 1);
    updates.put("update_time", now());
    updates.put("update_by", coupons.username(actor));
    db.update("edu_enrollment", "enrollment_id", id, updates);
  }

  private void update(
      String table,
      String column,
      long id,
      Map<String, Object> old,
      Map<String, Object> changes,
      long actor) {
    changes.put("version", integer(old.get("version"), 0, Integer.MAX_VALUE) + 1);
    changes.put("update_by", coupons.username(actor));
    changes.put("update_time", now());
    db.update(table, column, id, changes);
  }

  private static final String BILL_FROM =
      " FROM edu_enrollment e JOIN sys_user u ON u.user_id=e.parent_id LEFT JOIN"
          + " edu_course_schedule s ON s.schedule_id=e.schedule_id LEFT JOIN edu_classroom c ON"
          + " c.classroom_id=s.classroom_id";
  private static final String BILL_SELECT =
      "SELECT e.*,"
          + FinanceRules.parentNameSql("u")
          + " parent_name,COALESCE(e.fee_title_snapshot,s.course_class_name)"
          + " course_class_name,COALESCE(e.campus_name_snapshot,c.campus_name) campus_name,(SELECT"
          + " COALESCE(SUM(p.amount),0) FROM edu_tuition_payment p WHERE"
          + " p.enrollment_id=e.enrollment_id AND p.payment_status='SUCCESS')"
          + " received_amount,(SELECT COALESCE(SUM(r.approved_amount),0) FROM edu_tuition_refund r"
          + " JOIN edu_tuition_payment p ON p.payment_id=r.payment_id WHERE"
          + " p.enrollment_id=e.enrollment_id AND r.refund_status='SUCCESS')"
          + " refunded_amount,(SELECT COUNT(*) FROM edu_tuition_refund r JOIN edu_tuition_payment p"
          + " ON p.payment_id=r.payment_id WHERE p.enrollment_id=e.enrollment_id AND"
          + " r.refund_status IN"
          + " ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','WAIT_EDU','WAIT_ADMIN'))"
          + " active_refund_count,(SELECT COALESCE(SUM(CASE WHEN r.refund_status='PENDING_REVIEW'"
          + " THEN r.requested_amount ELSE r.approved_amount END),0) FROM edu_tuition_refund r JOIN"
          + " edu_tuition_payment p ON p.payment_id=r.payment_id WHERE"
          + " p.enrollment_id=e.enrollment_id AND r.refund_status IN"
          + " ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','WAIT_ADMIN'))"
          + " reserved_amount,(SELECT COUNT(*) FROM edu_tuition_payment p WHERE"
          + " p.enrollment_id=e.enrollment_id AND p.payment_status='SUCCESS') success_count";

  private static boolean cancelledSingle(Map<String, Object> e) {
    return e.get("classDate") != null
        && Set.of("2", "已取消", "3", "已拒绝").contains(str(e, "enrollmentStatus"));
  }

  private Map<String, Object> amounts(Map<String, Object> e) {
    e.put("mockAllowed", mockEnabled && "MOCK".equals(e.get("financeMode")));
    e.put("mockPricingAllowed", mockEnabled);
    e.put("payStatus", normalizePay(str(e, "payStatus")));
    e.put("enrollmentStatus", normalizeEnrollment(str(e, "enrollmentStatus")));
    if ("HISTORY_PENDING".equals(e.get("billingStatus"))) {
      for (String k :
          List.of("receivedAmount", "refundedAmount", "netAmount", "dueAmount", "refundableAmount"))
        e.put(k, null);
    } else {
      BigDecimal received = money(e.get("receivedAmount")),
          refunded = money(e.get("refundedAmount"));
      e.put("netAmount", received.subtract(refunded).toPlainString());
      e.put(
          "refundableAmount",
          received
              .subtract(refunded)
              .subtract(money(e.get("reservedAmount")))
              .max(BigDecimal.ZERO)
              .setScale(2)
              .toPlainString());
      e.put(
          "dueAmount",
          "OPEN".equals(e.get("billingStatus")) && ((Number) e.get("successCount")).intValue() == 0
              ? e.get("payableAmount")
              : "0.00");
    }
    e.put(
        "canApplyRefund",
        !CreditPackageService.isPackage(e) && "OPEN".equals(e.get("billingStatus"))
            && (!Set.of("已取消", "2", "已拒绝", "3").contains(str(e, "enrollmentStatus")) || cancelledSingle(e))
            && e.get("refundableAmount") != null
            && money(e.get("refundableAmount")).signum() > 0
            && ((Number) e.get("activeRefundCount")).intValue() == 0);
    e.remove("activeRefundCount");
    e.remove("reservedAmount");
    e.remove("successCount");
    e.remove("historyVerificationSnapshot");
    return e;
  }

  public Map<String, Object> listBills(long actor, boolean admin, Map<String, String> q) {
    actor(actor, admin);
    String where = " WHERE 1=1";
    List<Object> a = new ArrayList<>();
    if(!str(q,"accountId").isEmpty()) {
      long account=id(q.get("accountId"));
      if(!admin)checkChildAccount(actor,account);
      where+=" AND e.lesson_account_id=?";a.add(account);
    }
    if (!admin) {
      where += " AND e.parent_id=?";
      a.add(actor);
    }
    if (!mockEnabled) where += " AND e.finance_mode='REAL'";
    if (!str(q, "payStatus").isEmpty()) {
      String status = normalizePay(q.get("payStatus"));
      where += " AND e.pay_status IN (?,?)";
      a.add(status);
      a.add(
          switch (status) {
            case "未支付" -> "0";
            case "已支付" -> "1";
            case "已退款" -> "2";
            default -> status;
          });
    }
    if (admin && !str(q, "parentId").isBlank()) {
      where += " AND e.parent_id=?";
      a.add(id(q.get("parentId")));
    }
    for (var f :
        Map.of(
                "billingStatus",
                "e.billing_status",
                "financeMode",
                "e.finance_mode",
                "enrollmentId",
                "e.enrollment_id")
            .entrySet())
      if (!str(q, f.getKey()).isEmpty()) {
        where += " AND " + f.getValue() + "=?";
        a.add(q.get(f.getKey()));
      }
    for (var f :
        Map.of(
                "studentName",
                "e.student_name",
                "enrollmentCode",
                "e.enrollment_code",
                "parentName",
                FinanceRules.parentSearchSql("u"),
                "courseClassName",
                "COALESCE(e.fee_title_snapshot,s.course_class_name)",
                "campusName",
                "c.campus_name")
            .entrySet())
      if (!str(q, f.getKey()).isEmpty()) {
        where += " AND " + f.getValue() + " LIKE ?";
        a.add("%" + q.get(f.getKey()) + "%");
      }
    Map<String, Object> page =
        CouponService.page(
            db,
            BILL_SELECT + BILL_FROM + where + " ORDER BY e.enrollment_id DESC",
            "SELECT COUNT(*)" + BILL_FROM + where,
            a,
            q);
    for (Object row : (List<?>) page.get("rows")) {
      amounts((Map<String, Object>) row);
      if (!admin) ((Map<?, ?>) row).remove("refundableAmount");
    }
    return page;
  }

  private void checkChildAccount(long actor,long account) {
    db.one("SELECT account_id FROM edu_student_lesson_account WHERE account_id=? AND parent_id=?",account,actor);
  }
  public void checkChild(long enrollment,long actor,long account) {
    actor(actor,false);checkChildAccount(actor,account);
    db.one("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? AND parent_id=? AND lesson_account_id=?",enrollment,actor,account);
  }

  public Map<String, Object> bill(long enrollment, long actor, boolean admin) {
    actor(actor, admin);
    enrollment(enrollment, actor, admin, false);
    var e = amounts(db.one(BILL_SELECT + BILL_FROM + " WHERE e.enrollment_id=?", enrollment));
    if(CreditPackageService.isPackage(e))e.put("packageSnapshot",CreditPackageService.snapshot(e));
    e.put(
        "payments",
        db.rows(
            "SELECT"
                + " payment_id,payment_no,enrollment_id,channel,payment_status,finance_mode,amount,payer_name,provider_trade_no,recorded_by,confirmed_by,confirmed_time,paid_time,expire_time,failure_reason,record_source,create_time"
                + " FROM edu_tuition_payment WHERE enrollment_id=? ORDER BY payment_id DESC",
            enrollment));
    e.put(
        "refunds",
        db.rows(
            "SELECT r.*,p.finance_mode,p.enrollment_id FROM edu_tuition_refund r JOIN"
                + " edu_tuition_payment p ON p.payment_id=r.payment_id WHERE p.enrollment_id=?"
                + " ORDER BY refund_id DESC",
            enrollment));
    e.put(
        "receipts",
        db.rows(
            "SELECT"
                + " r.receipt_id,r.receipt_no,r.payment_id,p.enrollment_id,r.amount,p.finance_mode,r.document_type,r.receipt_status,r.file_status,r.issued_time"
                + " FROM edu_tuition_receipt r JOIN edu_tuition_payment p ON"
                + " p.payment_id=r.payment_id WHERE p.enrollment_id=? ORDER BY r.receipt_id DESC",
            enrollment));
    for (Object refund : (List<?>) e.get("refunds")) {
      refundView((Map<String, Object>) refund, admin);
    }
    if (!admin) {
      e.remove("reservedAmount");
      e.remove("refundableAmount");
    }
    e.put("coupons", coupons.available(e));
    return e;
  }

  public void initializeBill(long enrollment, long actor) {
    tx(
        () -> {
          var e = lockEnrollment(enrollment, actor, true);
          require("PENDING_PRICE".equals(e.get("billingStatus")), "新报名初始化状态不正确");
          var s =
              db.one(
                  "SELECT s.*,c.campus_name FROM edu_course_schedule s JOIN edu_classroom c ON"
                      + " c.classroom_id=s.classroom_id WHERE s.schedule_id=?",
                  e.get("scheduleId"));
          Map<String, Object> v =
              values(
                  "fee_title_snapshot",
                  s.get("courseClassName"),
                  "campus_name_snapshot",
                  s.get("campusName"));
          if (s.get("tuitionPrice") != null) {
            v.putAll(
                values(
                    "billing_status",
                    "OPEN",
                    "original_amount",
                    money(s.get("tuitionPrice")),
                    "discount_amount",
                    money("0"),
                    "payable_amount",
                    money(s.get("tuitionPrice")),
                    "price_confirmed_by",
                    actor,
                    "price_confirmed_time",
                    now()));
          }
          updateBill(enrollment, e, v, actor);
          return null;
        });
  }

  public Map<String, Object> price(long enrollment, long actor, Map<String, Object> b) {
    tuitionStaff(db, actor);
    BigDecimal original = money(b.get("originalAmount"));
    return tx(
        () -> {
          var e = lockEnrollment(enrollment, actor, true);
          require(!CreditPackageService.isPackage(e),"课包按年级和包数自动计价，不能人工改价；请取消未收款订单后重新购买");
          require(!"P".equals(e.get("settlementMethod")), "课包报名通过课次账户结算，不能登记单次现金收款");
          version(e, b);
          require(
              Set.of("PENDING_PRICE", "OPEN").contains(e.get("billingStatus")),
              "HISTORY_REVIEW_REQUIRED: 历史待核验或已关闭账单不能普通核价");
          require(!Set.of("已取消", "2").contains(str(e, "enrollmentStatus")), "已取消报名不能重新核价");
          require(
              db.rows(
                      "SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=? AND"
                          + " payment_status IN ('PENDING','UNKNOWN','SUCCESS')",
                      enrollment)
                  .isEmpty(),
              "PAYMENT_ALREADY_EXISTS: 已有有效付款记录不能调价");
          String fm = str(b, "financeMode");
          if (fm.isEmpty()) fm = str(e, "financeMode");
          require(
              Set.of("REAL", "MOCK").contains(fm) && (!fm.equals("MOCK") || mockEnabled),
              "MOCK_DISABLED: 模拟模式仅限本地测试环境");
          require(
              fm.equals(e.get("financeMode"))
                  || db.rows(
                          "SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=?",
                          enrollment)
                      .isEmpty(),
              "存在缴费历史后不能切换真实／模拟模式");
          var s =
              db.one(
                  "SELECT s.course_class_name,c.campus_name FROM edu_course_schedule s JOIN"
                      + " edu_classroom c ON c.classroom_id=s.classroom_id WHERE s.schedule_id=?",
                  e.get("scheduleId"));
          updateBill(
              enrollment,
              e,
              values(
                  "billing_status",
                  "OPEN",
                  "finance_mode",
                  fm,
                  "original_amount",
                  original,
                  "discount_amount",
                  money("0"),
                  "payable_amount",
                  original,
                  "user_coupon_id",
                  null,
                  "fee_title_snapshot",
                  s.get("courseClassName"),
                  "campus_name_snapshot",
                  s.get("campusName"),
                  "price_confirmed_by",
                  actor,
                  "price_confirmed_time",
                  now()),
              actor);
          return bill(enrollment, actor, true);
        });
  }

  public Map<String, Object> quote(
      long enrollment, long actor, boolean admin, Map<String, Object> b) {
    actor(actor, admin);
    var e = enrollment(enrollment, actor, admin, false);
    require(
        "OPEN".equals(e.get("billingStatus")) && e.get("originalAmount") != null,
        "BILL_NOT_PRICED: 请等待核价或历史核验");
    Long coupon=selectedCoupon(e,b);
    BigDecimal discount = CreditPackageService.bulkDiscount(e).add(
        coupon==null
            ? money("0")
            : coupons.discount(coupon, e, false));
    return values(
        "originalAmount",
        e.get("originalAmount"),
        "discountAmount",
        discount.toPlainString(),
        "payableAmount",
        money(e.get("originalAmount")).subtract(discount).toPlainString(),
        "financialVersion",
        e.get("financialVersion"),
        "userCouponId",
        coupon==null ? null : coupon.toString(),
        "coupons",
        coupons.available(e));
  }

  private Long selectedCoupon(Map<String,Object> e,Map<String,Object> b){
    if(CreditPackageService.isPackage(e)){
      require(str(b,"userCouponId").isEmpty() || str(b,"userCouponId").equals(str(e,"userCouponId")),"课包优惠券随订单锁定，不能在收款时更换");
      return e.get("userCouponId")==null?null:id(e.get("userCouponId"));
    }
    return str(b,"userCouponId").isEmpty()?null:id(b.get("userCouponId"));
  }

  private Map<String, Object> snapshot(Map<String, Object> e) {
    var snap=values(
        "enrollmentId",
        e.get("enrollmentId"),
        "enrollmentCode",
        e.get("enrollmentCode"),
        "parentId",
        e.get("parentId"),
        "studentName",
        e.get("studentName"),
        "courseClassName",
        e.get("feeTitleSnapshot"),
        "campusName",
        e.get("campusNameSnapshot"),
        "originalAmount",
        e.get("originalAmount"),
        "discountAmount",
        e.get("discountAmount"),
        "payableAmount",
        e.get("payableAmount"),
        "financeMode",
        e.get("financeMode"),
        "userCouponId",
        e.get("userCouponId"));
    if(CreditPackageService.isPackage(e)){snap.put("billType",CreditPackageService.TYPE);snap.put("packageSnapshot",CreditPackageService.snapshot(e));}
    return snap;
  }

  private String evidence(Map<String, Object> b, long actor, String type, long business) {
    require(storage != null, "凭证存储不可用");
    return storage.validateEvidence(reason(b, "evidenceKey", 255), actor, type, business);
  }

  public Map<String, Object> createPayment(
      long enrollment, long actor, boolean admin, Map<String, Object> b) {
    require(admin, "FORBIDDEN: 课程费用请联系教务线下缴纳");
    actor(actor, admin);
    String key = key(b),
        hash =
            fingerprint(
                enrollment,
                b,
                "channel",
                "userCouponId",
                "expectedPayableAmount",
                "financialVersion",
                "payerName",
                "providerTradeNo",
                "evidenceKey",
                "paidTime");
    long[] receipt = {0};
    Map<String, Object> result =
        tx(
            () -> {
              var e = lockEnrollment(enrollment, actor, admin);
              db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE", actor);
              var previous =
                  db.rows(
                      "SELECT * FROM edu_tuition_payment WHERE recorded_by=? AND idempotency_key=?",
                      actor,
                      key);
              if (!previous.isEmpty()) {
                require(
                    hash.equals(previous.get(0).get("requestHash")),
                    "IDEMPOTENCY_CONFLICT: 同一请求编号内容不同");
                require(
                    str(previous.get(0), "enrollmentId").equals(Long.toString(enrollment)),
                    "FORBIDDEN");
                return payment(id(previous.get(0).get("paymentId")), actor, admin);
              }
              version(e, b);
              require(
                  "OPEN".equals(e.get("billingStatus")) && e.get("originalAmount") != null,
                  "BILL_NOT_PRICED: 等待核价／历史核验");
              require(!Set.of("2", "已取消", "3", "已拒绝").contains(str(e, "enrollmentStatus")), "报名已取消或拒绝");
              require(
                  db.rows(
                          "SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=? AND"
                              + " payment_status IN ('PENDING','UNKNOWN','SUCCESS')",
                          enrollment)
                      .isEmpty(),
                  "PAYMENT_ALREADY_EXISTS: 已有有效缴费记录");
              Long coupon = selectedCoupon(e,b);
              BigDecimal discount = CreditPackageService.bulkDiscount(e).add(coupon == null ? money("0") : coupons.discount(coupon, e, true)),
                  amount = money(e.get("originalAmount")).subtract(discount);
              require(
                  amount.compareTo(money(b.get("expectedPayableAmount"))) == 0,
                  "BILL_VERSION_CONFLICT: 优惠或应付金额已变化，请重新确认");
              String channel = amount.signum() == 0 ? "FREE" : str(b, "channel");
              if ("MOCK".equals(e.get("financeMode")))
                require(Set.of("MOCK", "FREE").contains(channel) && mockEnabled, "模拟账单只能使用模拟渠道");
              else
                require(
                    admin
                        ? Set.of("CASH", "BANK_TRANSFER", "WECHAT_OFFLINE", "FREE")
                            .contains(channel)
                        : "FREE".equals(channel),
                    "正式在线支付尚未开通，请联系财务线下缴费");
              String payer = str(b, "payerName");
              if (payer.isBlank())
                payer =
                    str(
                        db.one(
                            "SELECT "
                                + FinanceRules.parentNameSql("u")
                                + " parent_name FROM sys_user u WHERE u.user_id=?",
                            e.get("parentId")),
                        "parentName");
              require(!payer.isBlank() && payer.length() <= 64, "付款人名称无效");
              String provider = str(b, "providerTradeNo");
              require(provider.length() <= 64, "流水号过长");
              boolean offline = Set.of("CASH", "BANK_TRANSFER", "WECHAT_OFFLINE").contains(channel);
              String proof = offline ? evidence(b, actor, "PAYMENT", enrollment) : null;
              Timestamp paid = offline ? time(b.get("paidTime")) : null;
              require(paid == null || !paid.after(now()), "收款时间不能晚于当前时间");
              require(!offline || channel.equals("CASH") || !provider.isBlank(), "转账收款必须填写完整流水号");
              Timestamp expire =
                  channel.equals("MOCK")
                      ? Timestamp.valueOf(LocalDateTime.now(ZONE).plusMinutes(30))
                      : null;
              e.put("discountAmount", discount.toPlainString());
              e.put("payableAmount", amount.toPlainString());
              e.put("userCouponId", coupon == null ? null : coupon.toString());
              var snap = snapshot(e);
              if (coupon != null)
                snap.put(
                    "coupon",
                    db.one(
                        "SELECT"
                            + " c.coupon_code,t.coupon_name,t.discount_amount,t.min_spend_amount,t.scope_schedule_id,c.valid_from,c.valid_until"
                            + " FROM edu_user_coupon c JOIN edu_coupon_template t ON"
                            + " t.template_id=c.template_id WHERE c.user_coupon_id=?",
                        coupon));
              long payment =
                  db.insert(
                      "edu_tuition_payment",
                      values(
                          "payment_no",
                          code("TP"),
                          "enrollment_id",
                          enrollment,
                          "channel",
                          channel,
                          "payment_status",
                          "PENDING",
                          "finance_mode",
                          e.get("financeMode"),
                          "amount",
                          amount,
                          "payer_name",
                          payer,
                          "provider_trade_no",
                          provider.isEmpty() ? null : provider,
                          "evidence_key",
                          proof,
                          "paid_time",
                          paid,
                          "bill_snapshot",
                          JSON.toJSONString(snap),
                          "recorded_by",
                          actor,
                          "idempotency_key",
                          key,
                          "request_hash",
                          hash,
                          "expire_time",
                          expire,
                          "create_by",
                          coupons.username(actor),
                          "update_by",
                          coupons.username(actor)));
              updateBill(
                  enrollment,
                  e,
                  values(
                      "discount_amount",
                      discount,
                      "payable_amount",
                      amount,
                      "user_coupon_id",
                      coupon),
                  actor);
              if (coupon != null && !CreditPackageService.isPackage(e))
                require(
                    db.jdbc()
                            .update(
                                "UPDATE edu_user_coupon SET"
                                    + " coupon_status='LOCKED',locked_enrollment_id=?,locked_until=?,version=version+1,update_time=?"
                                    + " WHERE user_coupon_id=? AND coupon_status='AVAILABLE'",
                                enrollment,
                                expire,
                                now(),
                                coupon)
                        == 1,
                    "COUPON_NOT_AVAILABLE: 券已被占用");
              if (channel.equals("FREE"))
                receipt[0] =
                    succeed(
                        db.one(
                            "SELECT * FROM edu_tuition_payment WHERE payment_id=? FOR UPDATE",
                            payment),
                        actor,
                        Map.of());
              return payment(payment, actor, admin);
            });
    generate(receipt[0]);
    return result;
  }

  public Map<String, Object> payment(long payment, long actor, boolean admin) {
    actor(actor, admin);
    var p = db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", payment);
    enrollment(id(p.get("enrollmentId")), actor, admin, false);
    p.remove("evidenceKey");
    p.remove("requestHash");
    return p;
  }

  private long succeed(Map<String, Object> p, long actor, Map<String, Object> b) {
    long payment = id(p.get("paymentId")), enrollment = id(p.get("enrollmentId"));
    var e = enrollment(enrollment, actor, true, true);
    require(
        "PENDING".equals(p.get("paymentStatus")) || "UNKNOWN".equals(p.get("paymentStatus")),
        "付款不能确认");
    require(
        money(p.get("amount")).compareTo(money(e.get("payableAmount"))) == 0
            && str(p, "financeMode").equals(str(e, "financeMode")),
        "账单与收款金额或模式不一致");
    Timestamp paid = p.get("paidTime") == null ? now() : time(p.get("paidTime"));
    String channel = str(p, "channel"),
        proof = str(p, "evidenceKey"),
        provider = str(p, "providerTradeNo");
    if (Set.of("CASH", "BANK_TRANSFER", "WECHAT_OFFLINE").contains(channel)) {
      if (!str(b, "evidenceKey").isEmpty()) proof = evidence(b, actor, "PAYMENT", enrollment);
      require(!proof.isBlank(), "必须有有效收款凭证");
      if (!str(b, "providerTradeNo").isEmpty()) provider = str(b, "providerTradeNo");
      require(
          provider.length() <= 64 && (channel.equals("CASH") || !provider.isBlank()), "请填写完整收款流水号");
      if (b.containsKey("paidTime")) paid = time(b.get("paidTime"));
    }
    require(!paid.after(now()), "收款时间不能晚于当前时间");
    if (channel.equals("MOCK"))
      require(
          p.get("expireTime") != null && !time(p.get("expireTime")).before(now()),
          "模拟支付已过期，请安全关闭后重新发起");
    // Additive immutable receipt evidence for delayed referral reconciliation. No referral
    // tables are required by the original financial transaction.
    var confirmedSnapshot = JSON.parseObject(str(p, "billSnapshot"));
    confirmedSnapshot.put("confirmationEnrollmentActive",
        Set.of("1", "报名成功", "已确认").contains(str(e, "enrollmentStatus"))
            && "0".equals(e.get("status")) && "0".equals(e.get("delFlag")));
    String confirmedSnapshotJson = JSON.toJSONString(confirmedSnapshot);
    update(
        "edu_tuition_payment",
        "payment_id",
        payment,
        p,
        values(
            "payment_status",
            "SUCCESS",
            "bill_snapshot",
            confirmedSnapshotJson,
            "confirmed_by",
            actor,
            "confirmed_time",
            now(),
            "paid_time",
            paid,
            "evidence_key",
            proof.isEmpty() ? null : proof,
            "provider_trade_no",
            provider.isEmpty() ? null : provider),
        actor);
    if (e.get("userCouponId") != null)
      require(
          db.jdbc()
                  .update(
                      "UPDATE edu_user_coupon SET"
                          + " coupon_status='USED',used_enrollment_id=?,used_time=?,locked_enrollment_id=NULL,locked_until=NULL,version=version+1,update_time=?"
                          + " WHERE user_coupon_id=? AND coupon_status='LOCKED' AND"
                          + " locked_enrollment_id=?",
                      enrollment,
                      now(),
                      now(),
                      e.get("userCouponId"),
                      enrollment)
              == 1,
          "锁定优惠券不一致");
    updateBill(
        enrollment,
        e,
        values("pay_status", money(p.get("amount")).signum() == 0 ? "已减免" : "已支付"),
        actor);
    p.put(
        "paidTime",
        paid.toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    p.put("confirmedBy", String.valueOf(actor));
    p.put("billSnapshot", confirmedSnapshotJson);
    lessons.activate(enrollment, actor);
    return createReceipt(p, actor);
  }

  private long createReceipt(Map<String, Object> p, long actor) {
    var existing =
        db.rows(
            "SELECT receipt_id FROM edu_tuition_receipt WHERE payment_id=? AND"
                + " receipt_status='ISSUED'",
            p.get("paymentId"));
    if (!existing.isEmpty()) return id(existing.get(0).get("receiptId"));
    Map<String, Object> snap = JSON.parseObject(str(p, "billSnapshot"));
    String number = code("TR"), type = money(p.get("amount")).signum() == 0 ? "WAIVER" : "RECEIPT";
    var settings =
        db.rows(
            "SELECT config_value FROM sys_config WHERE config_key='finance.institutionTitle' ORDER"
                + " BY config_id LIMIT 1");
    snap.putAll(
        values(
            "institutionTitle",
            settings.isEmpty() ? "名远教育" : settings.get(0).get("configValue"),
            "receiptNo",
            number,
            "paymentNo",
            p.get("paymentNo"),
            "amount",
            p.get("amount"),
            "payerName",
            p.get("payerName"),
            "channel",
            p.get("channel"),
            "paidTime",
            p.get("paidTime"),
            "operatorName",
            coupons.username(actor),
            "documentType",
            type));
    return db.insert(
        "edu_tuition_receipt",
        values(
            "receipt_no",
            number,
            "payment_id",
            p.get("paymentId"),
            "document_type",
            type,
            "receipt_status",
            "ISSUED",
            "amount",
            p.get("amount"),
            "content_snapshot",
            JSON.toJSONString(snap),
            "template_version",
            "tuition-receipt-v1",
            "file_status",
            "PENDING",
            "issued_by",
            actor,
            "issued_time",
            now(),
            "create_by",
            coupons.username(actor),
            "update_by",
            coupons.username(actor)));
  }

  private void generate(long receipt) {
    if (receipt > 0 && receipts != null)
      try {
        receipts.generatePending(receipt);
      } catch (RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("收款已成功，收据保留待重试：{}", receipt);
      }
  }

  public Map<String, Object> confirmPayment(
      long payment, long actor, boolean admin, Map<String, Object> b) {
    require(admin, "FORBIDDEN: 家长不能确认课程收款");
    actor(actor, admin);
    long[] receipt = {0};
    var result =
        tx(
            () -> {
              var p = lockPayment(payment, actor, admin);
              if ("SUCCESS".equals(p.get("paymentStatus"))) return payment(payment, actor, admin);
              receipt[0] = succeed(p, actor, b);
              return payment(payment, actor, admin);
            });
    if(referrals!=null)referrals.afterFinancialChange(id(result.get("enrollmentId")));
    generate(receipt[0]);
    return result;
  }

  private void close(Map<String, Object> p, long actor, String reason) {
    require("PENDING".equals(p.get("paymentStatus")), "结果未知或已成功付款不能直接关闭");
    long enrollment = id(p.get("enrollmentId"));
    update(
        "edu_tuition_payment",
        "payment_id",
        id(p.get("paymentId")),
        p,
        values("payment_status", "CLOSED", "failure_reason", reason),
        actor);
    var e = enrollment(enrollment, actor, true, true);
    if(CreditPackageService.isPackage(e))return; // 关闭一次待核验收款不取消课包，也不释放订单锁券或清空折扣。
    if (e.get("userCouponId") != null)
      db.jdbc()
          .update(
              "UPDATE edu_user_coupon SET"
                  + " coupon_status='AVAILABLE',locked_enrollment_id=NULL,locked_until=NULL,version=version+1,update_time=?"
                  + " WHERE user_coupon_id=? AND coupon_status='LOCKED' AND locked_enrollment_id=?",
              now(),
              e.get("userCouponId"),
              enrollment);
    updateBill(
        enrollment,
        e,
        values(
            "discount_amount",
            money("0"),
            "payable_amount",
            e.get("originalAmount"),
            "user_coupon_id",
            null),
        actor);
  }

  public Map<String, Object> closePayment(long payment, long actor, Map<String, Object> b) {
    coupons.admin(actor);
    String reason = reason(b, "reason", 255);
    return tx(
        () -> {
          var p = lockPayment(payment, actor, true);
          if (!"CLOSED".equals(p.get("paymentStatus"))) close(p, actor, reason);
          return payment(payment, actor, true);
        });
  }

  private BigDecimal refunded(long payment) {
    return money(
        db.one(
                "SELECT COALESCE(SUM(approved_amount),0) amount FROM edu_tuition_refund WHERE"
                    + " payment_id=? AND refund_status='SUCCESS'",
                payment)
            .get("amount"));
  }

  public Map<String, Object> applyRefund(long actor, boolean admin, Map<String, Object> b) {
    actor(actor, admin);
    long payment = id(b.get("paymentId"));
    String key = key(b),
        reason = reason(b, "reason", 500),
        kind = str(b, "refundKind"),
        hash =
            "SESSION".equals(kind)
                ? fingerprint(
                    payment, b, "refundKind", "reason", "classSignInId", "lessonBookingId")
                : fingerprint(payment, b, "refundKind", "reason");
    require(b.get("requestedAmount") == null, "退费金额由教务确认，请勿填写申请金额");
    require(Set.of("PARTIAL", "WITHDRAWAL", "SESSION").contains(kind), "退费类型无效");
    Long signIn = b.get("classSignInId") == null ? null : id(b.get("classSignInId"));
    Long booking = b.get("lessonBookingId") == null ? null : id(b.get("lessonBookingId"));
    require(
        "SESSION".equals(kind)
            ? (signIn == null) != (booking == null)
            : signIn == null && booking == null,
        "单次退款必须且只能指定一个课次；其他退款不能指定课次");
    return tx(
        () -> {
          var p = lockPayment(payment, actor, admin);
          db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE", actor);
          var prior =
              db.rows(
                  "SELECT * FROM edu_tuition_refund WHERE applicant_id=? AND idempotency_key=?",
                  actor,
                  key);
          if (!prior.isEmpty()) {
            require(
                hash.equals(prior.get(0).get("requestHash")), "IDEMPOTENCY_CONFLICT: 同一申请编号内容不同");
            return refund(id(prior.get(0).get("refundId")), actor, admin);
          }
          require(
              "SUCCESS".equals(p.get("paymentStatus")) && money(p.get("amount")).signum() > 0,
              "零元减免或未成功付款不能现金退款");
          require(
              db.rows(
                      "SELECT refund_id FROM edu_tuition_refund WHERE payment_id=? AND"
                          + " refund_status IN"
                          + " ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','WAIT_EDU','WAIT_ADMIN')",
                      payment)
                  .isEmpty(),
              "REFUND_IN_PROGRESS: 当前已有在途退款");
          var e = enrollment(id(p.get("enrollmentId")), actor, admin, true);
          require(!CreditPackageService.isPackage(e),"课包余额退费须先核对逐单剩余次数，暂不能套用课程退费；请联系管理员");
          require(
              "OPEN".equals(e.get("billingStatus"))
                  && (!Set.of("2", "已取消", "3", "已拒绝").contains(str(e, "enrollmentStatus"))
                      || ("WITHDRAWAL".equals(kind) && cancelledSingle(e))),
              "已退课或未核验账单不能再次申请退款");
          require(
              db.rows(
                      "SELECT r.refund_id FROM edu_tuition_refund r JOIN edu_tuition_payment p ON"
                          + " p.payment_id=r.payment_id WHERE p.enrollment_id=? AND r.refund_status"
                          + " IN ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','WAIT_EDU','WAIT_ADMIN')"
                          + " FOR UPDATE",
                      e.get("enrollmentId"))
                  .isEmpty(),
              "REFUND_IN_PROGRESS: 当前报名已有在途退款");
          if ("WITHDRAWAL".equals(kind) && e.get("classDate") != null)
            require(cancelledSingle(e), "一对一须先由教师处理取消申请或拒绝报名，再办理退课退费");
          BigDecimal previous = refunded(payment);
          require(
              money(p.get("amount")).subtract(previous).signum() > 0,
              "REFUND_LIMIT_EXCEEDED: 超过现金可退金额");
          // Lock the existing attendance target before the refund INSERT acquires its FK lock.
          if ("SESSION".equals(kind))
            lessons.validateSessionTarget(id(e.get("enrollmentId")), signIn, booking, actor);
          long refund =
              db.insert(
                  "edu_tuition_refund",
                  values(
                      "refund_no",
                      code("TF"),
                      "payment_id",
                      payment,
                      "refund_kind",
                      kind,
                      "class_sign_in_id",
                      signIn,
                      "lesson_booking_id",
                      booking,
                      "requested_amount",
                      null,
                      "refund_status",
                      "WAIT_EDU",
                      "reason",
                      reason,
                      "calculation_snapshot",
                      JSON.toJSONString(
                          values(
                              "ruleVersion",
                              "tuition-refund-offline-v2",
                              "receivedAmount",
                              p.get("amount"),
                              "refundedAmount",
                              previous.toPlainString(),
                              "remainingAmount",
                              money(p.get("amount")).subtract(previous).toPlainString(),
                              "reason",
                              reason)),
                      "applicant_id",
                      actor,
                      "idempotency_key",
                      key,
                      "request_hash",
                      hash,
                      "create_by",
                      coupons.username(actor),
                      "update_by",
                      coupons.username(actor)));
          if ("SESSION".equals(kind))
            lessons.reserveSession(id(e.get("enrollmentId")), refund, actor);
          if ("WITHDRAWAL".equals(kind)) lessons.freeze(id(e.get("enrollmentId")), refund, actor);
          return refund(refund, actor, admin);
        });
  }

  public Map<String, Object> refund(long refund, long actor, boolean admin) {
    actor(actor, admin);
    var r =
        db.one(
            "SELECT r.*,p.enrollment_id,p.finance_mode,p.channel,p.payment_no,p.amount"
                + " payment_amount,e.student_name,e.enrollment_code,(SELECT nick_name FROM sys_user"
                + " WHERE user_id=r.applicant_id) applicant_name,(SELECT nick_name FROM sys_user"
                + " WHERE user_id=r.reviewer_id) reviewer_name,(SELECT nick_name FROM sys_user"
                + " WHERE user_id=r.executor_id) executor_name FROM edu_tuition_refund r JOIN"
                + " edu_tuition_payment p ON p.payment_id=r.payment_id JOIN edu_enrollment e ON"
                + " e.enrollment_id=p.enrollment_id WHERE r.refund_id=?"
                + (admin ? "" : " AND e.parent_id=?"),
            admin ? new Object[] {refund} : new Object[] {refund, actor});
    mode(r);
    r.remove("evidenceKey");
    r.remove("requestHash");
    r.put("mockAllowed", mockEnabled && "MOCK".equals(r.get("financeMode")));
    r.put("receivedAmount", r.get("paymentAmount"));
    var summary =
        db.one(
            "SELECT COALESCE(SUM(CASE WHEN refund_status='SUCCESS' THEN approved_amount ELSE 0"
                + " END),0) refunded_amount,COALESCE(SUM(CASE WHEN refund_status='PENDING_REVIEW'"
                + " THEN requested_amount WHEN refund_status IN"
                + " ('APPROVED','PROCESSING','UNKNOWN','WAIT_ADMIN') THEN approved_amount ELSE 0"
                + " END),0) reserved_amount FROM edu_tuition_refund WHERE payment_id=?",
            r.get("paymentId"));
    BigDecimal available =
        money(r.get("paymentAmount"))
            .subtract(money(summary.get("refundedAmount")))
            .subtract(money(summary.get("reservedAmount")));
    r.put("refundedAmount", summary.get("refundedAmount"));
    r.put("refundableAmount", available.max(BigDecimal.ZERO).setScale(2).toPlainString());
    r.put(
        "availableForThisRefund",
        money(r.get("paymentAmount"))
            .subtract(money(summary.get("refundedAmount")))
            .toPlainString());
    return refundView(r, admin);
  }

  private boolean offlineRefund(Map<String, Object> r) {
    return "tuition-refund-offline-v2"
        .equals(JSON.parseObject(str(r, "calculationSnapshot")).getString("ruleVersion"));
  }

  private Map<String, Object> refundView(Map<String, Object> r, boolean staff) {
    boolean offline = offlineRefund(r);
    r.put("refundWorkflow", offline ? "OFFLINE_TWO_STAGE" : "LEGACY");
    if (!offline
        && !Set.of("SUCCESS", "REJECTED", "CANCELLED", "FAILED").contains(str(r, "refundStatus")))
      r.put("workflowHint", "历史流程，需工作人员核对");
    r.remove("evidenceKey");
    r.remove("requestHash");
    if (!staff) {
      if ("REJECTED".equals(r.get("refundStatus"))) {
        String rejected =
            JSON.parseObject(str(r, "calculationSnapshot")).getString("adminRejectReason");
        if (rejected != null) r.put("reviewRemark", rejected);
      } else if (!"SUCCESS".equals(r.get("refundStatus"))) r.remove("reviewRemark");
      r.remove("calculationSnapshot");
      if (!"SUCCESS".equals(r.get("refundStatus"))) {
        for (String key :
            List.of(
                "requestedAmount",
                "approvedAmount",
                "reservedAmount",
                "refundableAmount",
                "availableForThisRefund")) r.remove(key);
      }
    }
    return r;
  }

  public Map<String, Object> listRefunds(long actor, boolean admin, Map<String, String> q) {
    actor(actor, admin);
    String
        from =
            " FROM edu_tuition_refund r JOIN edu_tuition_payment p ON p.payment_id=r.payment_id"
                + " JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id",
        where = " WHERE 1=1";
    List<Object> a = new ArrayList<>();
    if(!str(q,"accountId").isEmpty()) {long account=id(q.get("accountId"));if(!admin)checkChildAccount(actor,account);where+=" AND e.lesson_account_id=?";a.add(account);}
    if (!admin) {
      where += " AND e.parent_id=?";
      a.add(actor);
    }
    if (!mockEnabled) where += " AND p.finance_mode='REAL'";
    for (var f :
        Map.of(
                "refundStatus",
                "r.refund_status",
                "enrollmentId",
                "p.enrollment_id",
                "financeMode",
                "p.finance_mode")
            .entrySet())
      if (!str(q, f.getKey()).isBlank()) {
        where += " AND " + f.getValue() + "=?";
        a.add(q.get(f.getKey()));
      }
    for (var f :
        Map.of(
                "refundNo",
                "r.refund_no",
                "studentName",
                "e.student_name",
                "paymentNo",
                "p.payment_no")
            .entrySet())
      if (!str(q, f.getKey()).isBlank()) {
        where += " AND " + f.getValue() + " LIKE ?";
        a.add("%" + q.get(f.getKey()) + "%");
      }
    var result =
        CouponService.page(
            db,
            "SELECT"
                + " r.refund_id,r.refund_no,r.payment_id,p.enrollment_id,p.finance_mode,p.channel,p.payment_no,e.student_name,e.enrollment_code,r.refund_kind,r.requested_amount,r.approved_amount,r.refund_status,r.reason,r.review_remark,r.create_time,r.completed_time,r.calculation_snapshot"
                + from
                + where
                + " ORDER BY r.refund_id DESC",
            "SELECT COUNT(*)" + from + where,
            a,
            q);
    for (Object row : (List<?>) result.get("rows")) refundView((Map<String, Object>) row, admin);
    return result;
  }

  public Map<String, Object> reviewRefund(long refund, long actor, Map<String, Object> b) {
    tuitionStaff(db, actor);
    require(b.get("approved") instanceof Boolean, "请选择通过或驳回");
    return tx(
        () -> {
          var r = lockRefund(refund, actor, true);
          boolean offline = offlineRefund(r);
          if (!offline) coupons.admin(actor);
          if (offline && "WAIT_ADMIN".equals(r.get("refundStatus"))) {
            coupons.admin(actor);
            require(Boolean.FALSE.equals(b.get("approved")), "教务金额已确认，管理员只能登记退款或驳回");
            refundVersion(r, b);
            String rejectReason = reason(b, "reviewRemark", 500);
            Map<String, Object> audit = JSON.parseObject(str(r, "calculationSnapshot"));
            audit.put("adminRejectBy", Long.toString(actor));
            audit.put("adminRejectTime", now().toString());
            audit.put("adminRejectReason", rejectReason);
            update(
                "edu_tuition_refund",
                "refund_id",
                refund,
                r,
                values(
                    "refund_status", "REJECTED", "calculation_snapshot", JSON.toJSONString(audit)),
                actor);
            releaseTeaching(r, actor);
            return refund(refund, actor, true);
          }
          require(
              (offline ? "WAIT_EDU" : "PENDING_REVIEW").equals(r.get("refundStatus")),
              "仅等待教务确认金额的申请允许审核");
          if (offline) refundVersion(r, b);
          var p =
              db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", r.get("paymentId"));
          boolean approved = (Boolean) b.get("approved");
          String remark = str(b, "reviewRemark");
          require(remark.length() <= 500, "审核说明过长");
          BigDecimal amount = approved ? money(b.get("approvedAmount")) : null;
          if (approved) {
            lessons.validateSession(id(p.get("enrollmentId")), refund, actor);
            require(
                amount.signum() > 0
                    && (offline || amount.compareTo(money(r.get("requestedAmount"))) <= 0)
                    && amount.compareTo(refundAvailable(p, refund)) <= 0,
                "REFUND_LIMIT_EXCEEDED: 审核金额超过可退范围");
            if (!offline && amount.compareTo(money(r.get("requestedAmount"))) < 0)
              reason(b, "reviewRemark", 500);
          } else reason(b, "reviewRemark", 500);
          Map<String, Object> calc = JSON.parseObject(str(r, "calculationSnapshot"));
          calc.put("approvedAmount", amount == null ? null : amount.toPlainString());
          calc.put("reviewRemark", remark);
          calc.put(
              "retainedAmount",
              approved
                  ? money(p.get("amount"))
                      .subtract(refunded(id(p.get("paymentId"))))
                      .subtract(amount)
                      .toPlainString()
                  : null);
          update(
              "edu_tuition_refund",
              "refund_id",
              refund,
              r,
              values(
                  "refund_status",
                  approved ? (offline ? "WAIT_ADMIN" : "APPROVED") : "REJECTED",
                  "approved_amount",
                  amount,
                  "reviewer_id",
                  actor,
                  "review_time",
                  now(),
                  "review_remark",
                  remark,
                  "calculation_snapshot",
                  JSON.toJSONString(calc)),
              actor);
          if (!approved) releaseTeaching(r, actor);
          return refund(refund, actor, true);
        });
  }

  public Map<String, Object> cancelRefund(long refund, long actor) {
    return cancelRefund(refund, actor, false);
  }

  private void refundVersion(Map<String, Object> r, Map<String, Object> b) {
    require(
        integer(b.get("version"), 0, Integer.MAX_VALUE)
            == integer(r.get("version"), 0, Integer.MAX_VALUE),
        "退费记录已变化，请刷新");
  }

  private BigDecimal refundAvailable(Map<String, Object> p, long refund) {
    return money(p.get("amount"))
        .subtract(
            money(
                db.one(
                        "SELECT COALESCE(SUM(CASE WHEN refund_status='PENDING_REVIEW' THEN"
                            + " requested_amount ELSE approved_amount END),0) amount FROM"
                            + " edu_tuition_refund WHERE payment_id=? AND refund_id<>? AND"
                            + " refund_status IN"
                            + " ('SUCCESS','PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','WAIT_ADMIN')",
                        p.get("paymentId"),
                        refund)
                    .get("amount")));
  }

  public Map<String, Object> markRefunded(long refund, long actor, Map<String, Object> b) {
    coupons.admin(actor);
    require(b.get("approvedAmount") == null && b.get("requestedAmount") == null, "退款金额须以教务确认记录为准");
    String remark = str(b, "remark");
    require(remark.length() <= 500, "退款说明过长");
    return tx(
        () -> {
          var r = lockRefund(refund, actor, true);
          require(offlineRefund(r), "历史流程，需工作人员核对");
          if ("SUCCESS".equals(r.get("refundStatus"))) {
            require(
                integer(b.get("version"), 0, Integer.MAX_VALUE) + 1
                        == integer(r.get("version"), 0, Integer.MAX_VALUE)
                    && id(r.get("executorId")) == actor
                    && remark.equals(str(r, "remark"))
                    && (str(b, "completedTime").isEmpty()
                        || str(b, "completedTime").equals(str(r, "completedTime"))),
                "已完成退款事实不能修改");
            return refund(refund, actor, true);
          }
          require("WAIT_ADMIN".equals(r.get("refundStatus")), "请先由教务确认金额并已阅");
          refundVersion(r, b);
          var p =
              db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", r.get("paymentId"));
          require(
              money(r.get("approvedAmount")).compareTo(refundAvailable(p, refund)) <= 0,
              "REFUND_LIMIT_EXCEEDED");
          Timestamp completed =
              str(b, "completedTime").isEmpty() ? now() : time(b.get("completedTime"));
          // Save completion metadata in the same transaction; complete owns the single version
          // increment.
          db.jdbc()
              .update("UPDATE edu_tuition_refund SET remark=? WHERE refund_id=?", remark, refund);
          complete(r, p, actor, completed, null, null);
          return refund(refund, actor, true);
        });
  }

  public Map<String, Object> cancelRefund(long refund, long actor, boolean admin) {
    require(admin, "FORBIDDEN: 申请提交后由工作人员处理");
    coupons.admin(actor);
    actor(actor, admin);
    return tx(
        () -> {
          var r = lockRefund(refund, actor, admin);
          require(!offlineRefund(r), "新退费申请不支持撤回");
          // 历史申请仅允许管理员撤回本人创建、尚未审核的记录。
          require(id(r.get("applicantId")) == actor, "只能撤回本人创建的申请");
          require("PENDING_REVIEW".equals(r.get("refundStatus")), "只能撤回尚未审核申请");
          update(
              "edu_tuition_refund",
              "refund_id",
              refund,
              r,
              values("refund_status", "CANCELLED"),
              actor);
          return refund(refund, actor, admin);
        });
  }

  public Map<String, Object> executeRefund(long refund, long actor) {
    coupons.admin(actor);
    return tx(
        () -> {
          var r = lockRefund(refund, actor, true);
          require(!offlineRefund(r), "请使用管理员已退款登记入口");
          if (Set.of("SUCCESS", "PROCESSING", "UNKNOWN").contains(str(r, "refundStatus")))
            return refund(refund, actor, true);
          require("APPROVED".equals(r.get("refundStatus")), "退费必须先审核通过");
          update(
              "edu_tuition_refund",
              "refund_id",
              refund,
              r,
              values("refund_status", "PROCESSING", "executor_id", actor, "processing_time", now()),
              actor);
          var p =
              db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", r.get("paymentId"));
          if ("MOCK".equals(p.get("financeMode"))) {
            require(mockEnabled, "模拟退款已禁用");
            complete(
                db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=?", refund),
                p,
                actor,
                now(),
                null,
                "MOCK-" + r.get("refundNo"));
          }
          return refund(refund, actor, true);
        });
  }

  private void withdraw(long enrollment, Map<String, Object> e, long actor, String reason) {
    if (e.get("classDate") != null) {
      require(Set.of("2", "已取消", "3", "已拒绝").contains(str(e, "enrollmentStatus")),
          "一对一须先由教师处理取消申请或拒绝报名，再办理退课退费");
      updateBill(enrollment, e, values("billing_status", "CLOSED", "billing_closed_time", now(),
          "billing_close_reason", reason), actor);
      return;
    }
    if (Set.of("2", "已取消").contains(str(e, "enrollmentStatus"))) return;
    require(
        db.jdbc()
                .update(
                    "UPDATE edu_enrollment SET"
                        + " enrollment_status='已取消',billing_status='CLOSED',billing_closed_time=?,billing_close_reason=?,cancel_time=?,cancel_reason=?,financial_version=financial_version+1,update_by=?,update_time=?"
                        + " WHERE enrollment_id=? AND enrollment_status NOT IN ('2','已取消')",
                    now(),
                    reason,
                    now(),
                    reason,
                    coupons.username(actor),
                    now(),
                    enrollment)
            == 1,
        "报名状态已变化");
    db.jdbc()
        .update(
            // 报名不按教室容量限制名额，recruit_status 不会被容量自动置为「满班」，
            // 因此退费时也无需按容量回退 recruit_status，只递减 enrolled_count。
            "UPDATE edu_course_schedule s SET"
                + " enrolled_count=GREATEST(COALESCE(enrolled_count,0)-1,0),update_time=? WHERE"
                + " schedule_id=?",
            now(),
            e.get("scheduleId"));
    db.jdbc()
        .update(
            "UPDATE edu_attendance SET attendance_status='2',status='1',update_by=?,update_time=?"
                + " WHERE enrollment_id=? AND del_flag='0' AND attendance_status IN ('0','未上课') AND"
                + " attended_time IS NULL AND confirm_by IS NULL AND IFNULL(confirm_name,'')=''",
            coupons.username(actor),
            now(),
            enrollment);
  }

  private void complete(
      Map<String, Object> r,
      Map<String, Object> p,
      long actor,
      Timestamp completed,
      String proof,
      String provider) {
    require(
        !completed.after(now()) && !completed.before(time(p.get("paidTime"))),
        "退款完成时间不能早于收款或晚于当前时间");
    long refund = id(r.get("refundId")),
        payment = id(p.get("paymentId")),
        enrollment = id(p.get("enrollmentId"));
    require(
        money(r.get("approvedAmount")).compareTo(money(p.get("amount")).subtract(refunded(payment)))
            <= 0,
        "REFUND_LIMIT_EXCEEDED");
    lessons.validateSession(enrollment, refund, actor);
    update(
        "edu_tuition_refund",
        "refund_id",
        refund,
        r,
        values(
            "refund_status",
            "SUCCESS",
            "completed_time",
            completed,
            "executor_id",
            actor,
            "evidence_key",
            proof,
            "provider_channel",
            p.get("channel"),
            "provider_refund_no",
            provider),
        actor);
    BigDecimal refunded = refunded(payment);
    var e = enrollment(enrollment, actor, true, true);
    updateBill(
        enrollment,
        e,
        values("pay_status", refunded.compareTo(money(p.get("amount"))) == 0 ? "已退款" : "部分退款"),
        actor);
    if ("SESSION".equals(r.get("refundKind"))) lessons.reclaimSession(enrollment, refund, actor);
    if ("WITHDRAWAL".equals(r.get("refundKind"))) {
      lessons.close(enrollment, refund, actor);
      withdraw(enrollment, e, actor, str(r, "reason"));
    }
  }

  private void releaseTeaching(Map<String, Object> r, long actor) {
    long enrollment =
        id(
            db.one(
                    "SELECT enrollment_id FROM edu_tuition_payment WHERE payment_id=?",
                    r.get("paymentId"))
                .get("enrollmentId"));
    if ("SESSION".equals(r.get("refundKind")))
      lessons.releaseSession(enrollment, id(r.get("refundId")), actor);
    if ("WITHDRAWAL".equals(r.get("refundKind"))) lessons.unfreeze(enrollment, actor);
  }

  public Map<String, Object> refundTargets(long enrollment, long actor, boolean admin) {
    actor(actor, admin);
    var e = enrollment(enrollment, actor, admin, false);
    return values("rows", lessons.refundTargets(e));
  }

  public Map<String, Object> confirmRefund(long refund, long actor, Map<String, Object> b) {
    coupons.admin(actor);
    return tx(
        () -> {
          var r = lockRefund(refund, actor, true);
          require(!offlineRefund(r), "请使用管理员已退款登记入口");
          if ("SUCCESS".equals(r.get("refundStatus"))) return refund(refund, actor, true);
          require(Set.of("PROCESSING", "UNKNOWN").contains(str(r, "refundStatus")), "退款尚未执行或已结束");
          var p =
              db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", r.get("paymentId"));
          String outcome = str(b, "outcome");
          if (outcome.isEmpty()) outcome = "SUCCESS";
          require(Set.of("SUCCESS", "FAILED", "UNKNOWN").contains(outcome), "退款结果无效");
          if (!outcome.equals("SUCCESS")) {
            update(
                "edu_tuition_refund",
                "refund_id",
                refund,
                r,
                values("refund_status", outcome, "failure_reason", reason(b, "reason", 255)),
                actor);
          } else {
            require(!"MOCK".equals(p.get("financeMode")), "模拟退款使用执行入口");
            String proof = evidence(b, actor, "REFUND", refund),
                provider = str(b, "providerRefundNo");
            require(
                provider.length() <= 64 && (p.get("channel").equals("CASH") || !provider.isEmpty()),
                "必须填写完整退款流水号");
            complete(
                r,
                p,
                actor,
                time(b.get("completedTime")),
                proof,
                provider.isEmpty() ? null : provider);
          }
          return refund(refund, actor, true);
        });
  }

  public Map<String, Object> cancelEnrollment(long enrollment, long actor, boolean admin) {
    return cancelEnrollment(enrollment, actor, admin, "取消报名");
  }

  public Map<String, Object> cancelEnrollment(
      long enrollment, long actor, boolean admin, String reason) {
    actor(actor, admin);
    require(!reason.isBlank() && reason.length() <= 255, "请填写取消原因");
    return tx(
        () -> {
          var e = lockEnrollment(enrollment, actor, admin);
          require(!CreditPackageService.isPackage(e), "请从课包订单取消入口操作");
          if (Set.of("2", "已取消").contains(str(e, "enrollmentStatus")))
            return bill(enrollment, actor, admin);
          require(e.get("classDate") == null, "一对一请提交取消申请，由教师处理；财务不能代替教师取消预约");
          require(
              !"HISTORY_PENDING".equals(e.get("billingStatus")),
              "HISTORY_REVIEW_REQUIRED: 历史报名需先核验");
          var payments =
              db.rows(
                  "SELECT * FROM edu_tuition_payment WHERE enrollment_id=? ORDER BY payment_id FOR"
                      + " UPDATE",
                  enrollment);
          for (var p : payments) {
            if (Set.of("UNKNOWN", "SUCCESS").contains(str(p, "paymentStatus")))
              require(
                  "SUCCESS".equals(p.get("paymentStatus"))
                      && refunded(id(p.get("paymentId"))).compareTo(money(p.get("amount"))) == 0,
                  "已付费或支付结果未知，请进入退课退费流程");
            if ("PENDING".equals(p.get("paymentStatus"))) {
              require("MOCK".equals(p.get("channel")), "线下待核验收款须由财务安全关闭，不能取消");
              close(p, actor, reason);
            }
          }
          withdraw(enrollment, enrollment(enrollment, actor, admin, true), actor, reason);
          return bill(enrollment, actor, admin);
        });
  }

  /** Joins the teacher's reservation transaction; never fabricates a receipt or refund.
   * Outstanding real/unknown payments stay available to the existing finance workflow. */
  public void closeSingleReservation(long enrollment, long teacher, String reason) {
    tx(() -> {
      var e = lockEnrollment(enrollment, teacher, true);
      require(e.get("classDate") != null, "不是具体日期的一对一报名");
      boolean administrator = com.ruoyi.common.utils.SecurityUtils.getUserId().equals(teacher)
          && (com.ruoyi.common.utils.SecurityUtils.isAdmin() || com.ruoyi.common.utils.SecurityUtils.hasRole("admin"));
      require(administrator || !db.rows("SELECT s.schedule_id FROM edu_course_schedule s JOIN sys_user u ON u.user_id=s.teacher_id "
          + "WHERE s.schedule_id=? AND s.teacher_id=? AND u.status='0' AND u.del_flag='0' "
          + "AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id "
          + "WHERE ur.user_id=u.user_id AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0')",
          e.get("scheduleId"), teacher).isEmpty(), "无权处理其他教师课程的财务衔接");
      if ("P".equals(e.get("settlementMethod")) || "HISTORY_PENDING".equals(e.get("billingStatus"))) return null;
      boolean unresolved = false;
      for (var p : db.rows("SELECT * FROM edu_tuition_payment WHERE enrollment_id=? ORDER BY payment_id FOR UPDATE", enrollment)) {
        String state = str(p, "paymentStatus");
        if ("PENDING".equals(state) && "MOCK".equals(p.get("channel"))) close(p, teacher, reason);
        else if (Set.of("PENDING", "UNKNOWN").contains(state)
            || ("SUCCESS".equals(state) && refunded(id(p.get("paymentId"))).compareTo(money(p.get("amount"))) < 0)) unresolved = true;
      }
      if (!unresolved) updateBill(enrollment, enrollment(enrollment, teacher, true, true),
          values("billing_status", "CLOSED", "billing_closed_time", now(), "billing_close_reason", reason), teacher);
      return null;
    });
  }

  @Scheduled(fixedDelay = 60000)
  public void expireMock() {
    if (!mockEnabled) return;
    for (var p :
        db.rows(
            "SELECT payment_id,recorded_by FROM edu_tuition_payment WHERE channel='MOCK' AND"
                + " finance_mode='MOCK' AND payment_status='PENDING' AND expire_time<? ORDER BY"
                + " payment_id LIMIT 100",
            now()))
      try {
        tx(
            () -> {
              var locked = lockPayment(id(p.get("paymentId")), id(p.get("recordedBy")), true);
              if ("PENDING".equals(locked.get("paymentStatus"))
                  && time(locked.get("expireTime")).before(now()))
                close(locked, id(p.get("recordedBy")), "模拟付款超时");
              return null;
            });
      } catch (RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("模拟付款超时关闭待重试：{}", p.get("paymentId"));
      }
  }

  public Map<String, Object> historyVerify(long enrollment, long actor, Map<String, Object> b) {
    coupons.admin(actor);
    BigDecimal original = money(b.get("originalAmount")),
        paid = money(b.get("historyPaidAmount")),
        returned = money(b.get("historyRefundedAmount"));
    require(paid.compareTo(original) <= 0 && returned.compareTo(paid) <= 0, "历史金额不一致");
    String reason = reason(b, "reason", 500);
    long[] receipt = {0};
    var result =
        tx(
            () -> {
              var e = lockEnrollment(enrollment, actor, true);
              require(!"P".equals(e.get("settlementMethod")), "课包报名不能按单次现金账单核验");
              version(e, b);
              require(
                  "HISTORY_PENDING".equals(e.get("billingStatus"))
                      && db.rows(
                              "SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=?",
                              enrollment)
                          .isEmpty(),
                  "仅未核验历史报名可核验");
              String proof = evidence(b, actor, "HISTORY", enrollment);
              Timestamp paidTime = paid.signum() > 0 ? time(b.get("paidTime")) : null,
                  refundedTime = returned.signum() > 0 ? time(b.get("refundedTime")) : null;
              require(paidTime == null || !paidTime.after(now()), "历史收款时间不能晚于当前时间");
              require(
                  refundedTime == null
                      || (!refundedTime.after(now()) && !refundedTime.before(paidTime)),
                  "历史退款时间不能早于收款或晚于当前时间");
              var audit =
                  values(
                      "reason",
                      reason,
                      "evidenceKey",
                      proof,
                      "verifiedBy",
                      String.valueOf(actor),
                      "verifiedTime",
                      now().toString(),
                      "originalAmount",
                      original.toPlainString(),
                      "historyPaidAmount",
                      paid.toPlainString(),
                      "historyRefundedAmount",
                      returned.toPlainString(),
                      "oldPayStatus",
                      e.get("payStatus"),
                      "oldEnrollmentStatus",
                      e.get("enrollmentStatus"));
              var s =
                  db.one(
                      "SELECT s.course_class_name,c.campus_name FROM edu_course_schedule s JOIN"
                          + " edu_classroom c ON c.classroom_id=s.classroom_id WHERE"
                          + " s.schedule_id=?",
                      e.get("scheduleId"));
              boolean canceled = Set.of("2", "已取消").contains(str(e, "enrollmentStatus"));
              updateBill(
                  enrollment,
                  e,
                  values(
                      "billing_status",
                      canceled ? "CLOSED" : "OPEN",
                      "original_amount",
                      original,
                      "discount_amount",
                      paid.signum() > 0 ? original.subtract(paid) : money("0"),
                      "payable_amount",
                      paid.signum() > 0 ? paid : original,
                      "fee_title_snapshot",
                      s.get("courseClassName"),
                      "campus_name_snapshot",
                      s.get("campusName"),
                      "price_confirmed_by",
                      actor,
                      "price_confirmed_time",
                      now(),
                      "history_verification_snapshot",
                      JSON.toJSONString(audit),
                      "pay_status",
                      paid.signum() == 0
                          ? "未支付"
                          : returned.signum() == 0
                              ? "已支付"
                              : returned.compareTo(paid) == 0 ? "已退款" : "部分退款"),
                  actor);
              if (paid.signum() > 0) {
                var updated = enrollment(enrollment, actor, true, true);
                long payment =
                    db.insert(
                        "edu_tuition_payment",
                        values(
                            "payment_no",
                            code("TPH"),
                            "enrollment_id",
                            enrollment,
                            "channel",
                            "CASH",
                            "payment_status",
                            "SUCCESS",
                            "finance_mode",
                            "REAL",
                            "amount",
                            paid,
                            "payer_name",
                            str(
                                db.one(
                                    "SELECT "
                                        + FinanceRules.parentNameSql("u")
                                        + " parent_name FROM sys_user u WHERE u.user_id=?",
                                    e.get("parentId")),
                                "parentName"),
                            "evidence_key",
                            proof,
                            "bill_snapshot",
                            JSON.toJSONString(snapshot(updated)),
                            "recorded_by",
                            actor,
                            "confirmed_by",
                            actor,
                            "confirmed_time",
                            now(),
                            "paid_time",
                            paidTime,
                            "record_source",
                            "HISTORY_VERIFIED",
                            "idempotency_key",
                            "HISTORY-" + enrollment,
                            "request_hash",
                            hash(audit),
                            "remark",
                            reason,
                            "create_by",
                            coupons.username(actor),
                            "update_by",
                            coupons.username(actor)));
                var p = db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", payment);
                receipt[0] = createReceipt(p, actor);
                if (returned.signum() > 0)
                  db.insert(
                      "edu_tuition_refund",
                      values(
                          "refund_no",
                          code("TFH"),
                          "payment_id",
                          payment,
                          "refund_kind",
                          "PARTIAL",
                          "requested_amount",
                          returned,
                          "approved_amount",
                          returned,
                          "refund_status",
                          "SUCCESS",
                          "reason",
                          reason,
                          "calculation_snapshot",
                          JSON.toJSONString(audit),
                          "applicant_id",
                          actor,
                          "reviewer_id",
                          null,
                          "review_time",
                          now(),
                          "review_remark",
                          "历史凭据核验补录",
                          "executor_id",
                          actor,
                          "processing_time",
                          now(),
                          "completed_time",
                          refundedTime,
                          "evidence_key",
                          proof,
                          "idempotency_key",
                          "HISTORY-" + enrollment,
                          "request_hash",
                          hash(audit),
                          "create_by",
                          coupons.username(actor),
                          "update_by",
                          coupons.username(actor)));
              }
              return bill(enrollment, actor, true);
            });
    if(referrals!=null)referrals.afterFinancialChange(enrollment);
    generate(receipt[0]);
    return result;
  }
}
