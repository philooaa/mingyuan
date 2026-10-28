package com.ruoyi.system.finance;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Receipt identity/snapshot commits before rendering; CAS versions fence retries and voiding. */
@Service
public class TuitionReceiptService {
  private final ShopRepository db;
  private final FinancePrivateStorage storage;
  private final boolean mockEnabled;

  @Autowired
  public TuitionReceiptService(ShopRepository db, FinancePrivateStorage storage, Environment env) {
    this(
        db,
        storage,
        FinanceRules.mockAllowed(
            env.getActiveProfiles(), env.getProperty("payment.mode", "disabled")));
  }

  public TuitionReceiptService(ShopRepository db, FinancePrivateStorage storage) {
    this(db, storage, true);
  }

  public TuitionReceiptService(
      ShopRepository db, FinancePrivateStorage storage, boolean mockEnabled) {
    this.db = db;
    this.storage = storage;
    this.mockEnabled = mockEnabled;
  }

  private static final String JOIN =
      " FROM edu_tuition_receipt r JOIN edu_tuition_payment p ON p.payment_id=r.payment_id JOIN"
          + " edu_enrollment e ON e.enrollment_id=p.enrollment_id LEFT JOIN sys_user u ON"
          + " u.user_id=e.parent_id ";
  private static final String COLUMNS =
      "SELECT"
          + " r.*,e.enrollment_id,e.enrollment_code,e.student_name,e.contact_phone,p.finance_mode,p.payment_no,"
          + FinanceRules.parentNameSql("u")
          + " AS parent_name";

  public Map<String, Object> list(long actor, boolean admin, Map<String, String> q) {
    if (admin) FinanceRules.tuitionStaff(db, actor);
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (q.get("accountId") != null && !q.get("accountId").isBlank()) {
      long account = FinanceRules.id(q.get("accountId"));
      if (!admin) db.one("SELECT account_id FROM edu_student_lesson_account WHERE account_id=? AND parent_id=?", account, actor);
      where.append(" AND e.lesson_account_id=?");
      args.add(account);
    }
    if (!admin) {
      where.append(" AND e.parent_id=?");
      args.add(actor);
    }
    if (!mockEnabled) where.append(" AND p.finance_mode='REAL'");
    for (var key : List.of("receiptStatus", "fileStatus", "enrollmentId", "financeMode")) {
      String v = q.get(key);
      if (v != null && !v.isBlank()) {
        where
            .append(" AND ")
            .append(
                switch (key) {
                  case "receiptStatus" -> "r.receipt_status";
                  case "fileStatus" -> "r.file_status";
                  case "enrollmentId" -> "e.enrollment_id";
                  default -> "p.finance_mode";
                })
            .append("=?");
        args.add(v);
      }
    }
    String search = q.getOrDefault("search", q.getOrDefault("receiptNo", ""));
    if (!search.isBlank()) {
      where.append(
          " AND (r.receipt_no LIKE ? OR e.student_name LIKE ? OR e.enrollment_code LIKE ?)");
      for (int i = 0; i < 3; i++) args.add("%" + search + "%");
    }
    long total =
        db.jdbc().queryForObject("SELECT COUNT(*)" + JOIN + where, Long.class, args.toArray());
    int page = number(q.get("pageNum"), 1, 100000), size = number(q.get("pageSize"), 20, 100);
    args.add(size);
    args.add((page - 1) * size);
    var rows =
        db.rows(
            COLUMNS + JOIN + where + " ORDER BY r.receipt_id DESC LIMIT ? OFFSET ?",
            args.toArray());
    rows.forEach(TuitionReceiptService::publicInfo);
    return Map.of("rows", rows, "total", total);
  }

  private static int number(String s, int fallback, int max) {
    try {
      return Math.min(max, Math.max(1, Integer.parseInt(s)));
    } catch (Exception e) {
      return fallback;
    }
  }

  private static void publicInfo(Map<String, Object> row) {
    row.remove("fileKey");
    row.remove("fileSha256");
    row.remove("contentSnapshot");
  }

  public Map<String, Object> receipt(long id, long actor, boolean admin) {
    if (admin) FinanceRules.tuitionStaff(db, actor);
    var row =
        db.one(
            COLUMNS
                + JOIN
                + " WHERE r.receipt_id=?"
                + (admin ? "" : " AND e.parent_id=?")
                + (mockEnabled ? "" : " AND p.finance_mode='REAL'"),
            admin ? new Object[] {id} : new Object[] {id, actor});
    publicInfo(row);
    return row;
  }

  private void lockBillForPayment(long paymentId) {
    var p =
        db.one(
            "SELECT e.schedule_id,e.enrollment_id FROM edu_tuition_payment p JOIN edu_enrollment e"
                + " ON e.enrollment_id=p.enrollment_id WHERE p.payment_id=?",
            paymentId);
    db.one(
        "SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",
        p.get("scheduleId"));
    db.one(
        "SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",
        p.get("enrollmentId"));
    db.one("SELECT payment_id FROM edu_tuition_payment WHERE payment_id=? FOR UPDATE", paymentId);
  }

  public Map<String, Object> issue(long paymentId, long actor) {
    new CouponService(db).admin(actor);
    long id =
        db.transactions()
            .execute(
                tx -> {
                  lockBillForPayment(paymentId);
                  var p = db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", paymentId);
                  if (!mockEnabled && "MOCK".equals(p.get("financeMode")))
                    throw new ServiceException("正式环境不能处理模拟收据");
                  if (!"SUCCESS".equals(p.get("paymentStatus")))
                    throw new ServiceException("只有已确认收款才可开具收据");
                  var rows =
                      db.rows(
                          "SELECT receipt_id FROM edu_tuition_receipt WHERE payment_id=? AND"
                              + " receipt_status='ISSUED' FOR UPDATE",
                          paymentId);
                  if (!rows.isEmpty())
                    return Long.parseLong(rows.get(0).get("receiptId").toString());
                  return create(p, actor, null, null);
                });
    generatePending(id);
    return receipt(id, actor, true);
  }

  private long create(
      Map<String, Object> p, long actor, Map<String, Object> previous, String reason) {
    String code = "RC" + UUID.randomUUID().toString().replace("-", "");
    Map<String, Object> snapshot =
        previous == null
            ? new LinkedHashMap<>(JSON.parseObject(p.get("billSnapshot").toString()))
            : new LinkedHashMap<>(JSON.parseObject(previous.get("contentSnapshot").toString()));
    snapshot.put("receiptNo", code);
    snapshot.put("paymentNo", p.get("paymentNo"));
    snapshot.put("amount", p.get("amount"));
    snapshot.put("payerName", p.get("payerName"));
    snapshot.put("channel", p.get("channel"));
    snapshot.put("paidTime", p.get("paidTime"));
    snapshot.put("financeMode", p.get("financeMode"));
    if (previous != null || !snapshot.containsKey("institutionTitle")) {
      var cfg =
          db.rows(
              "SELECT config_value FROM sys_config WHERE config_key='finance.institutionTitle'"
                  + " LIMIT 1");
      snapshot.put("institutionTitle", cfg.isEmpty() ? "名远教育" : cfg.get(0).get("configValue"));
    }
    String type = "FREE".equals(p.get("channel")) ? "WAIVER" : "RECEIPT";
    snapshot.put("documentType", type);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("receipt_no", code);
    row.put("payment_id", p.get("paymentId"));
    row.put("document_type", type);
    row.put("receipt_status", "ISSUED");
    row.put("amount", p.get("amount"));
    row.put("content_snapshot", JSON.toJSONString(snapshot));
    row.put("template_version", "1.0");
    row.put("file_status", "PENDING");
    row.put("issued_by", actor);
    row.put("issued_time", now());
    row.put("create_by", Long.toString(actor));
    row.put("update_by", Long.toString(actor));
    if (previous != null) {
      row.put("replaces_receipt_id", previous.get("receiptId"));
      row.put("remark", reason);
    }
    return db.insert("edu_tuition_receipt", row);
  }

  public Map<String, Object> retry(long id, long actor) {
    new CouponService(db).admin(actor);
    receipt(id, actor, true);
    generatePending(id);
    return receipt(id, actor, true);
  }

  public Map<String, Object> replace(long id, long actor, String reason) {
    new CouponService(db).admin(actor);
    if (reason == null || reason.isBlank() || reason.length() > 255)
      throw new ServiceException("请填写255字以内的作废补开原因");
    long replacement =
        db.transactions()
            .execute(
                tx -> {
                  var info = receipt(id, actor, true);
                  long paymentId = Long.parseLong(info.get("paymentId").toString());
                  lockBillForPayment(paymentId);
                  var old =
                      db.one("SELECT * FROM edu_tuition_receipt WHERE receipt_id=? FOR UPDATE", id);
                  if (!"ISSUED".equals(old.get("receiptStatus"))) {
                    var rows =
                        db.rows(
                            "SELECT receipt_id FROM edu_tuition_receipt WHERE"
                                + " replaces_receipt_id=?",
                            id);
                    if (!rows.isEmpty() && reason.equals(old.get("voidReason")))
                      return Long.parseLong(rows.get(0).get("receiptId").toString());
                    throw new ServiceException("该收据已作废，请查看关联补开收据");
                  }
                  db.jdbc()
                      .update(
                          "UPDATE edu_tuition_receipt SET"
                              + " receipt_status='VOID',void_by=?,void_time=?,void_reason=?,version=version+1,update_by=?,update_time=?"
                              + " WHERE receipt_id=? AND receipt_status='ISSUED'",
                          actor,
                          now(),
                          reason,
                          Long.toString(actor),
                          now(),
                          id);
                  var payment =
                      db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?", paymentId);
                  return create(payment, actor, old, reason);
                });
    generatePending(replacement);
    return receipt(replacement, actor, true);
  }

  public void generatePending(long id) {
    var claimed =
        db.transactions()
            .execute(
                tx -> {
                  var rows =
                      db.rows(
                          "SELECT * FROM edu_tuition_receipt WHERE receipt_id=? FOR UPDATE", id);
                  if (rows.isEmpty()) return null;
                  var row = rows.get(0);
                  if (!mockEnabled
                      && "MOCK"
                          .equals(
                              db.one(
                                      "SELECT finance_mode FROM edu_tuition_payment WHERE"
                                          + " payment_id=?",
                                      row.get("paymentId"))
                                  .get("financeMode"))) return null;
                  if (!"ISSUED".equals(row.get("receiptStatus"))
                      || "READY".equals(row.get("fileStatus"))) return null;
                  if ("GENERATING".equals(row.get("fileStatus"))
                      && LocalDateTime.parse(
                              row.get("updateTime").toString(),
                              java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                          .isAfter(LocalDateTime.now(FinanceRules.ZONE).minusMinutes(5)))
                    return null;
                  db.jdbc()
                      .update(
                          "UPDATE edu_tuition_receipt SET"
                              + " file_status='GENERATING',generation_error=NULL,version=version+1,update_time=?"
                              + " WHERE receipt_id=?",
                          now(),
                          id);
                  return db.one("SELECT * FROM edu_tuition_receipt WHERE receipt_id=?", id);
                });
    if (claimed == null) return;
    Object version = claimed.get("version");
    String key = null;
    try {
      byte[] pdf =
          FinanceReceiptPdf.render(JSON.parseObject(claimed.get("contentSnapshot").toString()));
      key = storage.publishReceipt(pdf);
      String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
      int updated =
          db.jdbc()
              .update(
                  "UPDATE edu_tuition_receipt SET"
                      + " file_status='READY',file_key=?,file_sha256=?,generation_error=NULL,update_time=?"
                      + " WHERE receipt_id=? AND receipt_status='ISSUED' AND"
                      + " file_status='GENERATING' AND version=?",
                  key,
                  sha,
                  now(),
                  id,
                  version);
      if (updated == 0) storage.discardReceipt(key);
    } catch (Exception failure) {
      if (key != null) storage.discardReceipt(key);
      db.jdbc()
          .update(
              "UPDATE edu_tuition_receipt SET file_status='FAILED',generation_error=?,update_time=?"
                  + " WHERE receipt_id=? AND receipt_status='ISSUED' AND file_status='GENERATING'"
                  + " AND version=?",
              "收据文件生成失败，请重试或检查私有目录配置",
              now(),
              id,
              version);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("收据文件生成失败，付款事实不回滚，receiptId={}", id, failure);
    }
  }

  public Path download(long id, long actor, boolean admin) {
    var row = receipt(id, actor, admin);
    if (!"ISSUED".equals(row.get("receiptStatus"))) throw new ServiceException("该收据已作废，请查看最新补开收据");
    if (!"READY".equals(row.get("fileStatus"))) throw new ServiceException("收据文件尚未就绪，请刷新或由管理员重试");
    return storage.resolve(
        Objects.toString(
            db.one(
                    "SELECT file_key FROM edu_tuition_receipt WHERE receipt_id=? AND"
                        + " receipt_status='ISSUED' AND file_status='READY'",
                    id)
                .get("fileKey"),
            ""));
  }

  private static Timestamp now() {
    return FinanceRules.now();
  }
}
