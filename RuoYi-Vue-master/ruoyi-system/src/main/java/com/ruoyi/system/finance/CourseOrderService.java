package com.ruoyi.system.finance;

import static com.ruoyi.system.finance.FinanceRules.*;

import com.ruoyi.common.core.domain.model.MiniAppCartOrderBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.system.service.LessonCreditService;
import com.ruoyi.system.shop.ShopRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程报名购物车：一个购物车 → 一个订单 → 一次收款 → 一张收据。
 *
 * <p>订单本身是一条 {@code edu_enrollment} 记录（{@code bill_type='CART_ORDER'}，{@code schedule_id} 为空），
 * 因此核价、优惠、收款、退款、收据全部复用既有的学费财务链路，不新增一套资金表。
 *
 * <p>订单里的每门课程也是一条 {@code edu_enrollment} 记录（{@code bill_type='COURSE_ITEM'}），
 * 通过 {@code order_enrollment_id} 指向订单，单独不允许收款，避免同一笔钱被收两次。
 * 这样名额、考勤、我的课表、老师端名单都还能按课程维度正常工作。
 */
@Service
public class CourseOrderService {
  private final ShopRepository db;
  private final LessonCreditService credits;

  public CourseOrderService(ShopRepository db, LessonCreditService credits) {
    this.db = db;
    this.credits = credits;
  }

  private <T> T tx(java.util.function.Supplier<T> op) {
    return db.transactions().execute(status -> op.get());
  }

  /** 购物车下单：一次提交多门课程，生成一个订单。 */
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> createOrder(long parentId, MiniAppCartOrderBody body) {
    require(body != null && body.getScheduleIds() != null, "请先选择要报名的课程");
    List<Long> ids =
        body.getScheduleIds().stream().filter(Objects::nonNull).distinct().toList();
    require(!ids.isEmpty(), "请先选择要报名的课程");
    require(ids.size() <= 10, "一个订单最多报名 10 门课程");

    String idem = text(body.getIdempotencyKey());
    if (!idem.isEmpty()) {
      require(idem.matches("[A-Za-z0-9_-]{1,64}"), "下单请求编号无效");
      var exists =
          db.rows(
              "SELECT enrollment_id FROM edu_enrollment WHERE parent_id=? AND package_request_key=?",
              parentId,
              idem);
      if (!exists.isEmpty()) return detail(id(exists.get(0).get("enrollmentId")), parentId, true);
    }

    String studentName = text(body.getStudentName());
    require(!studentName.isBlank(), "请输入学生姓名");
    require(studentName.length() <= 30, "学生姓名过长");

    return tx(
        () -> {
          var first =
              db.one(
                  "SELECT schedule_id,course_class_name,grade_name,class_mode,del_flag,status FROM edu_course_schedule WHERE schedule_id=?",
                  ids.get(0));
          require("0".equals(first.get("delFlag")) && "0".equals(first.get("status")), "课程已下架，请刷新后重试");
          require(!"2".equals(str(first, "classMode")), "一对一课程请单独预约");

          String contactPhone =
              text(body.getContactPhone()).isBlank()
                  ? SecurityUtils.getLoginUser().getUser().getPhonenumber()
                  : text(body.getContactPhone());
          var child =
              credits.enrollmentChild(
                  parentId,
                  body.getLessonAccountId(),
                  studentName,
                  str(first, "gradeName"),
                  contactPhone,
                  body.getStudentRequestKey());
          String name = str(child, "studentName");
          long accountId = id(child.get("accountId"));

          BigDecimal total = BigDecimal.ZERO.setScale(2);
          boolean priced = true;
          List<Map<String, Object>> items = new ArrayList<>();

          for (Long scheduleId : ids) {
            var s =
                db.one(
                    "SELECT s.schedule_id,s.course_class_name,s.grade_name,s.subject_name,s.term_name,"
                        + "s.period_name,s.unit_price,s.tuition_price,s.lesson_count,s.class_mode,s.recruit_status,"
                        + "COALESCE(NULLIF(s.campus_name,''),c.campus_name) campus_name,"
                        + "COALESCE(NULLIF(s.campus_name,''),c.campus_name) campus "
                        + "FROM edu_course_schedule s LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id "
                        + "WHERE s.schedule_id=? AND s.del_flag='0' AND s.status='0' FOR UPDATE",
                    scheduleId);
            require(!"2".equals(str(s, "classMode")), "一对一课程请单独预约");
            require("0".equals(str(s, "recruitStatus")), "「" + str(s, "courseClassName") + "」已停止招生");

            Integer dup =
                db.jdbc()
                    .queryForObject(
                        "SELECT COUNT(*) FROM edu_enrollment WHERE schedule_id=? AND parent_id=? AND lesson_account_id=?"
                            + " AND del_flag='0' AND enrollment_status NOT IN ('2','已取消')",
                        Integer.class,
                        scheduleId,
                        parentId,
                        accountId);
            require(dup == null || dup == 0, "「" + str(s, "courseClassName") + "」已经报过名了");

            int seat =
                db.jdbc()
                    .update(
                        "UPDATE edu_course_schedule s LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id"
                            + " SET s.enrolled_count=IFNULL(s.enrolled_count,0)+1,s.update_by='miniapp',s.update_time=NOW()"
                            + " WHERE s.schedule_id=? AND s.del_flag='0' AND s.status='0' AND s.recruit_status='0'"
                            + " AND ((s.classroom_id IS NULL AND NULLIF(TRIM(s.campus_name),'') IS NOT NULL) OR c.status='0')"
                            + " AND (s.enroll_limit IS NULL OR s.enroll_limit<=0 OR IFNULL(s.enrolled_count,0)<s.enroll_limit)",
                        scheduleId);
            require(seat == 1, "「" + str(s, "courseClassName") + "」名额已满");

            BigDecimal courseTotal =
                s.get("tuitionPrice") == null ? null : money(s.get("tuitionPrice"));
            if (courseTotal == null || courseTotal.signum() <= 0) {
              courseTotal = BigDecimal.ZERO.setScale(2);
              priced = false;
            }
            total = total.add(courseTotal);
            items.add(
                values(
                    "scheduleId", scheduleId,
                    "courseClassName", str(s, "courseClassName"),
                    "gradeName", str(s, "gradeName"),
                    "subjectName", str(s, "subjectName"),
                    "termName", str(s, "termName"),
                    "periodName", str(s, "periodName"),
                    "unitPrice", s.get("unitPrice"),
                    "lessonCount", s.get("lessonCount"),
                    "amount", courseTotal.toPlainString(),
                    "campusName", str(s, "campusName")));
          }

          String code = "ORD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
              + IdUtils.fastSimpleUUID().substring(0, 6).toUpperCase();
          String campus = items.isEmpty() ? "" : text(items.get(0).get("campusName"));

          long orderId =
              db.insert(
                  "edu_enrollment",
                  values(
                      "enrollment_code", code,
                      "schedule_id", null,
                      "parent_id", parentId,
                      "student_id", child.get("studentId"),
                      "lesson_account_id", accountId,
                      "student_name", name,
                      "student_phone", text(body.getStudentPhone()),
                      "contact_phone", contactPhone,
                      "enrollment_status", "报名成功",
                      "pay_status", "未支付",
                      "bill_type", "CART_ORDER",
                      // 订单总价已按课程定价汇总，但优惠与应收金额要等教务核价后才生效。
                      "billing_status", "PENDING_PRICE",
                      "finance_mode", "REAL",
                      "settlement_method", "C",
                      "original_amount", total,
                      "discount_amount", BigDecimal.ZERO.setScale(2),
                      "payable_amount", total,
                      "fee_title_snapshot", "课程报名订单 · " + items.size() + " 门课程",
                      "campus_name_snapshot", campus.isBlank() ? null : campus,
                      "package_request_key", idem.isEmpty() ? null : idem,
                      "status", "0",
                      "del_flag", "0",
                      "create_by", SecurityUtils.getUsername(),
                      "create_time", now()));

          for (Map<String, Object> item : items) {
            long scheduleId = Long.parseLong(String.valueOf(item.get("scheduleId")));
            String itemCode =
                "ENR"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + IdUtils.fastSimpleUUID().substring(0, 6).toUpperCase();
            long enrollmentId =
                db.insert(
                    "edu_enrollment",
                    values(
                        "enrollment_code", itemCode,
                        "schedule_id", scheduleId,
                        "parent_id", parentId,
                        "student_id", child.get("studentId"),
                        "lesson_account_id", accountId,
                        "student_name", name,
                        "student_phone", text(body.getStudentPhone()),
                        "contact_phone", contactPhone,
                        "enrollment_status", "报名成功",
                        "pay_status", "未支付",
                        "bill_type", "COURSE_ITEM",
                        "billing_status", "ORDER_BOUND",
                        "finance_mode", "REAL",
                        "settlement_method", "C",
                        "order_enrollment_id", orderId,
                        "original_amount", money(item.get("amount")),
                        "discount_amount", BigDecimal.ZERO.setScale(2),
                        "payable_amount", money(item.get("amount")),
                        "fee_title_snapshot", item.get("courseClassName"),
                        "campus_name_snapshot", item.get("campusName"),
                        "status", "0",
                        "del_flag", "0",
                        "create_by", SecurityUtils.getUsername(),
                        "create_time", now()));
            db.insert(
                "edu_attendance",
                values(
                    "attendance_code",
                    "ATT"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        + IdUtils.fastSimpleUUID().substring(0, 6).toUpperCase(),
                    "schedule_id", scheduleId,
                    "enrollment_id", enrollmentId,
                    "parent_id", parentId,
                    "student_name", name,
                    "attendance_status", "0",
                    "status", "0",
                    "del_flag", "0",
                    "create_by", SecurityUtils.getUsername(),
                    "create_time", now()));
          }

          return detail(orderId, parentId, true);
        });
  }

  /** 订单详情：主账单 + 课程明细。 */
  public Map<String, Object> detail(long orderId, long actor, boolean admin) {
    var e =
        db.one(
            "SELECT e.* FROM edu_enrollment e WHERE e.enrollment_id=?"
                + (admin ? "" : " AND e.parent_id=?"),
            admin ? new Object[] {orderId} : new Object[] {orderId, actor});
    require("CART_ORDER".equals(str(e, "billType")), "这不是购物车订单");
    Map<String, Object> out = new LinkedHashMap<>(e);
    out.put("items", items(orderId));
    return out;
  }

  /** 订单课程明细。 */
  public List<Map<String, Object>> items(long orderId) {
    return db.rows(
        "SELECT e.enrollment_id,e.enrollment_code,e.schedule_id,e.student_name,e.enrollment_status,e.pay_status,"
            + "e.original_amount,e.payable_amount,e.fee_title_snapshot,e.create_time,"
            + "s.course_class_name,s.grade_name,s.subject_name,s.term_name,s.period_name,"
            + "s.start_time,s.end_time,s.unit_price,s.lesson_count,"
            + "COALESCE(NULLIF(s.campus_name,''),c.campus_name) campus_name,c.classroom_name,"
            + "coalesce(nullif(trim(t.user_name),''),nullif(trim(t.nick_name),''),s.teacher_name) teacher_name "
            + "FROM edu_enrollment e LEFT JOIN edu_course_schedule s ON s.schedule_id=e.schedule_id "
            + "LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id "
            + "LEFT JOIN sys_user t ON t.user_id=s.teacher_id "
            + "WHERE e.order_enrollment_id=? AND e.del_flag='0' ORDER BY e.enrollment_id",
        orderId);
  }

  /** 家长订单列表（不含课程明细，明细按需拉取）。 */
  public List<Map<String, Object>> myOrders(long parentId, Long accountId) {
    String sql =
        "SELECT e.enrollment_id,e.enrollment_code,e.student_name,e.enrollment_status,e.pay_status,"
            + "e.billing_status,e.original_amount,e.discount_amount,e.payable_amount,e.fee_title_snapshot,"
            + "e.create_time,e.price_confirmed_time,"
            + "(SELECT COALESCE(SUM(p.amount),0) FROM edu_tuition_payment p WHERE p.enrollment_id=e.enrollment_id"
            + "   AND p.payment_status='SUCCESS') received_amount,"
            + "(SELECT COUNT(*) FROM edu_enrollment i WHERE i.order_enrollment_id=e.enrollment_id AND i.del_flag='0') item_count "
            + "FROM edu_enrollment e WHERE e.parent_id=? AND e.bill_type='CART_ORDER' AND e.del_flag='0'"
            + (accountId == null ? "" : " AND e.lesson_account_id=?")
            + " ORDER BY e.enrollment_id DESC";
    List<Map<String, Object>> rows =
        accountId == null ? db.rows(sql, parentId) : db.rows(sql, parentId, accountId);
    for (var row : rows) {
      row.put("items", items(id(row.get("enrollmentId"))));
      row.put(
          "dueAmount",
          "OPEN".equals(row.get("billingStatus")) ? row.get("payableAmount") : "0.00");
    }
    return rows;
  }

  /** 取消订单：连带取消订单内全部课程报名，并释放名额。 */
  @Transactional(rollbackFor = Exception.class)
  public void cancelOrder(long orderId, long actor, boolean admin, String reason) {
    var e =
        db.one(
            "SELECT * FROM edu_enrollment WHERE enrollment_id=?"
                + (admin ? "" : " AND parent_id=?")
                + " FOR UPDATE",
            admin ? new Object[] {orderId} : new Object[] {orderId, actor});
    require("CART_ORDER".equals(str(e, "billType")), "这不是购物车订单");
    require(!"CLOSED".equals(str(e, "billingStatus")), "订单已关闭");
    int paid =
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_tuition_payment WHERE enrollment_id=? AND payment_status='SUCCESS'",
                Integer.class,
                orderId);
    require(paid == 0, "订单已收款，请先办理退费再取消");

    for (var item : items(orderId)) {
      long enrollmentId = id(item.get("enrollmentId"));
      long scheduleId = id(item.get("scheduleId"));
      db.jdbc()
          .update(
              "UPDATE edu_enrollment SET enrollment_status='已取消',cancel_time=NOW(),cancel_reason=?,"
                  + "billing_status='CLOSED',billing_closed_time=NOW(),billing_close_reason=?,update_time=NOW()"
                  + " WHERE enrollment_id=?",
              reason,
              "订单取消：" + reason,
              enrollmentId);
      db.jdbc()
          .update(
              "UPDATE edu_course_schedule SET enrolled_count=GREATEST(IFNULL(enrolled_count,0)-1,0) WHERE schedule_id=?",
              scheduleId);
    }
    db.jdbc()
        .update(
            "UPDATE edu_enrollment SET enrollment_status='已取消',pay_status='未支付',cancel_time=NOW(),"
                + "cancel_reason=?,billing_status='CLOSED',billing_closed_time=NOW(),billing_close_reason=?,"
                + "financial_version=financial_version+1,update_time=NOW() WHERE enrollment_id=?",
            reason,
            "订单取消：" + reason,
            orderId);
  }

  /** 收款成功后把订单内课程同步为已支付，家长端与老师端状态才一致。 */
  public void markItemsPaid(long orderId, String payStatus, long actor) {
    db.jdbc()
        .update(
            "UPDATE edu_enrollment SET pay_status=?,update_time=NOW(),update_by=? "
                + "WHERE order_enrollment_id=? AND del_flag='0'",
            payStatus,
            FinanceRules.text(db.one("SELECT user_name FROM sys_user WHERE user_id=?", actor).get("userName")),
            orderId);
  }
}
