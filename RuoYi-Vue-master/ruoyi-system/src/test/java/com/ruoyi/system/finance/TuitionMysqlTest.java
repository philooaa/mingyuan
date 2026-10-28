package com.ruoyi.system.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.ruoyi.system.shop.ShopRepository;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TuitionMysqlTest {
  static final String SCHEMA = "codex_tuition_finance_test_" + UUID.randomUUID().toString().replace("-", "");
  ShopRepository db;
  Object tuition, coupons;
  Path privateRoot;
  FinancePrivateStorage storage;
  TuitionReceiptService receiptService;

  @BeforeAll
  void setup() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("tuition.mysql"),
        "Enable isolated MySQL suite with -Dtuition.mysql=true");
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:3306/?serverTimezone=Asia/Shanghai", "root", System.getenv().getOrDefault("MYSQL_PWD", ""))) {
      c.createStatement().execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4");
    }
    db =
        new ShopRepository(
            new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/" + SCHEMA + "?serverTimezone=Asia/Shanghai",
                "root",
                System.getenv().getOrDefault("MYSQL_PWD", "")));
    db.jdbc()
        .execute(
            "CREATE TABLE sys_user(user_id bigint PRIMARY KEY,user_name varchar(64),nick_name"
                + " varchar(64),status char(1) DEFAULT '0',del_flag char(1) DEFAULT '0')");
    db.jdbc()
        .execute(
            "INSERT INTO sys_user"
                + " VALUES(1,'admin','经办','0','0'),(2,'parent','家长','0','0'),(3,'other','其他家长','0','0'),(4,'checker','复核','0','0')");
    String schema = Files.readString(Path.of("../../database/schema.sql"));
    for (String table :
        List.of(
            "sys_role",
            "sys_role_menu",
            "sys_menu",
            "sys_config",
            "edu_classroom",
            "edu_course_schedule",
            "edu_enrollment",
            "edu_class_sign_in",
            "edu_attendance")) {
      Matcher m =
          Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=.*?;", Pattern.DOTALL)
              .matcher(schema);
      assertTrue(m.find());
      db.jdbc().execute(m.group());
    }
    db.jdbc().execute("CREATE TABLE sys_user_role(user_id bigint,role_id bigint)");
    db.jdbc()
        .execute(
            "INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status)"
                + " VALUES(1,'管理员','admin',1,'0'),(2,'家长','parent',2,'0')");
    db.jdbc().execute("INSERT INTO sys_user_role VALUES(1,1),(4,1),(2,2),(3,2)");
    db.jdbc()
        .execute(
            "INSERT INTO edu_classroom(classroom_id,classroom_code,campus_name,classroom_name)"
                + " VALUES(1,'TEST','测试校区','A')");
    db.jdbc()
        .execute(
            "INSERT INTO"
                + " edu_course_schedule(schedule_id,schedule_code,classroom_id,term_name,period_name,time_slot,grade_name,subject_name,course_class_name,enrolled_count)"
                + " VALUES(1,'TEST',1,'秋季','周日','上午','五年级','数学','测试课程',100)");
    db.jdbc()
        .execute(
            "INSERT INTO"
                + " edu_enrollment(enrollment_code,schedule_id,parent_id,student_name,pay_status)"
                + " VALUES('HISTORY',1,2,'历史学生','已支付')");
    // Simulate interruption immediately after the first bill-status ALTER.
    db.jdbc()
        .execute(
            "ALTER TABLE edu_enrollment ADD billing_status varchar(20) NOT NULL DEFAULT"
                + " 'HISTORY_PENDING'");
    assertEquals(
        "HISTORY_PENDING",
        db.one("SELECT billing_status FROM edu_enrollment WHERE enrollment_code='HISTORY'")
            .get("billingStatus"));
    migrateLegacy();
    migrate();
    migrate();
    assertEquals(
        0,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id"
                    + " WHERE r.role_key='education'",
                Integer.class));
    db.jdbc().update("INSERT INTO sys_user VALUES(5,'education','教务','0','0')");
    db.jdbc()
        .update(
            "INSERT INTO sys_user_role SELECT 5,role_id FROM sys_role WHERE role_key='education'");
    runMigration("20261012_tuition_session_unified.sql");
    runMigration("20261012_tuition_session_unified.sql");
    db.jdbc().execute("ALTER TABLE sys_user ADD phonenumber varchar(11) DEFAULT ''");
    db.jdbc().execute("UPDATE sys_user SET phonenumber=CASE user_id WHEN 2 THEN '11111111111' WHEN 3 THEN '22222222222' ELSE '' END");
    db.jdbc().execute("CREATE TABLE sys_dict_data(dict_code bigint AUTO_INCREMENT PRIMARY KEY,dict_type varchar(100),dict_label varchar(100),dict_value varchar(100),dict_sort int,status char(1))");
    db.jdbc().execute("INSERT INTO sys_dict_data(dict_type,dict_label,dict_value,dict_sort,status) VALUES('edu_subject','数学','math',1,'0'),('edu_subject','化学','chemistry',2,'0')");
    runMigration("20261014_coupon_subject_scope.sql");runMigration("20261014_coupon_subject_scope.sql");
    privateRoot = Files.createTempDirectory("codex-tuition-finance-test-");
    new com.ruoyi.common.config.RuoYiConfig().setProfile(privateRoot.resolve("public").toString());
    storage = new FinancePrivateStorage(privateRoot.resolve("private").toString());
    receiptService = new TuitionReceiptService(db, storage);
  }

  void migrate() throws Exception {
    boolean session =
        db.jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='edu_tuition_refund' AND column_name='class_sign_in_id'",
                    Integer.class)
            > 0;
    runMigration("20261010_tuition_offline_two_stage.sql");
    runMigration("20261020_coupon_course_scope.sql");
    if (session) runMigration("20261012_tuition_session_unified.sql");
  }

  void migrateLegacy() throws Exception {
    runMigration("20261007_tuition_finance.sql", "20261010_tuition_refund_single_admin.sql");
  }

  void runMigration(String... files) throws Exception {
    Process p =
        new ProcessBuilder(
                System.getenv().getOrDefault("MYSQL_CLIENT", "mysql"), "--protocol=TCP", "-h127.0.0.1", "--default-character-set=utf8mb4", "-uroot", SCHEMA)
            .redirectErrorStream(true)
            .start();
    try (var out = p.getOutputStream()) {
      for (String file : files)
        out.write(Files.readAllBytes(Path.of("../../database/migrations/" + file)));
    }
    String output = new String(p.getInputStream().readAllBytes());
    assertEquals(0, p.waitFor(), output);
  }

  @AfterAll
  void cleanup() throws Exception {
    if (db != null)
      try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/", "root", System.getenv().getOrDefault("MYSQL_PWD", ""))) {
        c.createStatement().execute("DROP DATABASE IF EXISTS " + SCHEMA);
      }
    if (privateRoot != null)
      try (var paths = Files.walk(privateRoot)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
  }

  Object service(String name) throws Exception {
    try {
      Class<?> c = Class.forName("com.ruoyi.system.finance." + name);
      if (name.equals("CouponService"))
        return c.getConstructor(ShopRepository.class).newInstance(db);
      return Arrays.stream(c.getConstructors())
          .filter(x -> x.getParameterCount() == 5 && x.getParameterTypes()[4] == boolean.class)
          .findFirst()
          .orElseThrow()
          .newInstance(db, storage, receiptService, service("CouponService"), true);
    } catch (ClassNotFoundException e) {
      fail("Tuition payment/refund/coupon workflow is not implemented");
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> call(Object s, String name, Object... args) throws Exception {
    Method method =
        Arrays.stream(s.getClass().getMethods())
            .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
            .findFirst()
            .orElseThrow();
    try {
      return (Map<String, Object>) method.invoke(s, args);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  long fresh() {
    String code = UUID.randomUUID().toString().substring(0, 30);
    return db.insert(
        "edu_enrollment",
        Map.of(
            "enrollment_code",
            code,
            "schedule_id",
            1,
            "parent_id",
            2,
            "student_name",
            code,
            "enrollment_status",
            "报名成功"));
  }

  @Test
  void sessionRefundPreservesOtherDatesAndChecksCurrentLeave() throws Exception {
    var s = (TuitionService) service("TuitionService");
    long bill = fresh();
    s.price(bill, 1, priceBody("MOCK"));
    long payment = lid(s.createPayment(bill, 1, true, payBody(1, "SESSION" + bill)), "paymentId");
    s.confirmPayment(payment, 1, true, Map.of());
    long first = signIn(bill, "2026-10-01"), second = signIn(bill, "2026-10-02");
    assertEquals(true, s.bill(bill, 2, false).get("canApplyRefund"));
    var r =
        assertDoesNotThrow(
            () ->
                s.applyRefund(
                    2,
                    false,
                    Map.of(
                        "paymentId",
                        payment,
                        "refundKind",
                        "SESSION",
                        "classSignInId",
                        first,
                        "reason",
                        "请假退费",
                        "idempotencyKey",
                        "S" + bill)));
    assertEquals(false, s.bill(bill, 2, false).get("canApplyRefund"));
    long refund = lid(r, "refundId");
    db.jdbc().update("UPDATE edu_class_sign_in SET sign_status='1' WHERE sign_in_id=?", first);
    assertThrows(
        Exception.class,
        () ->
            s.reviewRefund(
                refund, 1, Map.of("approved", true, "approvedAmount", "37.19", "version", 0)));
    assertEquals("WAIT_EDU", s.refund(refund, 1, true).get("refundStatus"));
    db.jdbc().update("UPDATE edu_class_sign_in SET sign_status='3' WHERE sign_in_id=?", first);
    s.reviewRefund(refund, 1, Map.of("approved", true, "approvedAmount", "37.19", "version", 0));
    db.jdbc().update("UPDATE edu_class_sign_in SET del_flag='2' WHERE sign_in_id=?", first);
    assertThrows(Exception.class, () -> s.markRefunded(refund, 1, Map.of("version", 1)));
    assertEquals("WAIT_ADMIN", s.refund(refund, 1, true).get("refundStatus"));
    db.jdbc().update("UPDATE edu_class_sign_in SET del_flag='0' WHERE sign_in_id=?", first);
    s.markRefunded(refund, 1, Map.of("version", 1));
    s.markRefunded(refund, 1, Map.of("version", 1));
    assertEquals("37.19", s.refund(refund, 2, false).get("approvedAmount"));
    assertEquals(
        "报名成功",
        db.one("SELECT enrollment_status FROM edu_enrollment WHERE enrollment_id=?", bill)
            .get("enrollmentStatus"));
    assertEquals(
        "3",
        db.one("SELECT sign_status FROM edu_class_sign_in WHERE sign_in_id=?", second)
            .get("signStatus"));
    assertEquals(true, s.bill(bill, 2, false).get("canApplyRefund"));
  }

  long signIn(long bill, String date) {
    return db.insert(
        "edu_class_sign_in",
        Map.of(
            "schedule_id",
            1,
            "class_date",
            date,
            "enrollment_id",
            bill,
            "student_name",
            "S" + bill,
            "sign_status",
            "3",
            "review_status",
            "1"));
  }

  @Test
  void existingOfflinePartialRequestRetainsItsIdempotencyFingerprint() throws Exception {
    var s = (TuitionService) service("TuitionService");
    long bill = fresh();
    s.price(bill, 1, priceBody("MOCK"));
    long p = lid(s.createPayment(bill, 1, true, payBody(1, "OLDKEY" + bill)), "paymentId");
    s.confirmPayment(p, 1, true, Map.of());
    var request =
        Map.<String, Object>of(
            "paymentId",
            p,
            "refundKind",
            "PARTIAL",
            "reason",
            "旧版本请求",
            "idempotencyKey",
            "OLD-R" + bill);
    var r = s.applyRefund(2, false, request);
    db.jdbc()
        .update(
            "UPDATE edu_tuition_refund SET request_hash=? WHERE refund_id=?",
            FinanceRules.fingerprint(p, request, "refundKind", "reason"),
            r.get("refundId"));
    assertEquals(
        r.get("refundId"),
        assertDoesNotThrow(() -> s.applyRefund(2, false, request)).get("refundId"));
  }

  @Test
  void sessionTargetsValidateOwnerReviewExactIdsAndGlobalUniqueness() throws Exception {
    var s = (TuitionService) service("TuitionService");
    long bill = fresh();
    s.price(bill, 1, priceBody("MOCK"));
    long p = lid(s.createPayment(bill, 1, true, payBody(1, "TARGET" + bill)), "paymentId");
    s.confirmPayment(p, 1, true, Map.of());
    long target = signIn(bill, "2026-09-01"), wrong = signIn(fresh(), "2026-09-01");
    db.jdbc()
        .update(
            "UPDATE edu_class_sign_in SET sign_in_id=9007199254740993 WHERE sign_in_id=?", target);
    target = 9007199254740993L;
    assertThrows(Exception.class, () -> s.refundTargets(bill, 3, false));
    db.jdbc().update("UPDATE edu_course_schedule SET teacher_name='授课教师' WHERE schedule_id=1");
    var targets = (List<Map<String, Object>>) s.refundTargets(bill, 2, false).get("rows");
    assertEquals("2026-09-01 · 测试课程 · 授课教师 · 已返0次", targets.get(0).get("label"));
    assertEquals("9007199254740993", targets.get(0).get("targetId"));
    assertEquals("CLASS_SIGN_IN", targets.get(0).get("targetType"));
    for (var body :
        List.of(
            Map.of("refundKind", "SESSION"),
            Map.of("refundKind", "SESSION", "classSignInId", target, "lessonBookingId", 1L),
            Map.of("refundKind", "PARTIAL", "classSignInId", target),
            Map.of("refundKind", "SESSION", "classSignInId", wrong))) {
      var request = new HashMap<String, Object>(body);
      request.putAll(
          Map.of("paymentId", p, "reason", "核对", "idempotencyKey", UUID.randomUUID().toString()));
      assertThrows(Exception.class, () -> s.applyRefund(2, false, request));
    }
    var request =
        Map.<String, Object>of(
            "paymentId",
            p,
            "refundKind",
            "SESSION",
            "classSignInId",
            target,
            "reason",
            "请假",
            "idempotencyKey",
            "TARGET-R" + bill);
    db.jdbc().update("UPDATE edu_class_sign_in SET review_status='0' WHERE sign_in_id=?", target);
    assertTrue(((List<?>) s.refundTargets(bill, 2, false).get("rows")).isEmpty());
    assertThrows(Exception.class, () -> s.applyRefund(2, false, request));
    db.jdbc().update("UPDATE edu_class_sign_in SET review_status='1' WHERE sign_in_id=?", target);
    var r = s.applyRefund(2, false, request);
    long refund = lid(r, "refundId");
    assertEquals(r.get("refundId"), s.applyRefund(2, false, request).get("refundId"));
    assertTrue(((List<?>) s.refundTargets(bill, 2, false).get("rows")).isEmpty());
    var row =
        ((List<Map<String, Object>>)
                s.listBills(2, false, Map.of("enrollmentId", String.valueOf(bill))).get("rows"))
            .get(0);
    assertEquals(false, row.get("canApplyRefund"));
    assertFalse(row.containsKey("refundableAmount"));
    assertFalse(row.containsKey("reservedAmount"));
    s.reviewRefund(refund, 1, Map.of("approved", true, "approvedAmount", "18.09", "version", 0));
    s.markRefunded(refund, 1, Map.of("version", 1));
    long secondPayment =
        db.insert(
            "edu_tuition_payment",
            FinanceRules.values(
                "payment_no",
                FinanceRules.code("CLOSED"),
                "enrollment_id",
                bill,
                "channel",
                "MOCK",
                "payment_status",
                "CLOSED",
                "finance_mode",
                "MOCK",
                "amount",
                "1000",
                "payer_name",
                "家长",
                "bill_snapshot",
                "{}",
                "recorded_by",
                1,
                "idempotency_key",
                UUID.randomUUID().toString(),
                "request_hash",
                "b".repeat(64)));
    long exactTarget = target;
    assertThrows(
        Exception.class,
        () ->
            db.insert(
                "edu_tuition_refund",
                FinanceRules.values(
                    "refund_no",
                    FinanceRules.code("DUP"),
                    "payment_id",
                    secondPayment,
                    "refund_kind",
                    "SESSION",
                    "class_sign_in_id",
                    exactTarget,
                    "requested_amount",
                    null,
                    "refund_status",
                    "WAIT_EDU",
                    "reason",
                    "重复目标",
                    "calculation_snapshot",
                    "{\"ruleVersion\":\"tuition-refund-offline-v2\"}",
                    "applicant_id",
                    2,
                    "idempotency_key",
                    UUID.randomUUID().toString(),
                    "request_hash",
                    "c".repeat(64))));
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update(
                    "UPDATE edu_tuition_refund SET class_sign_in_id=? WHERE refund_id=?",
                    wrong,
                    refund));
    assertEquals(
        true,
        ((List<Map<String, Object>>)
                s.listBills(2, false, Map.of("enrollmentId", String.valueOf(bill))).get("rows"))
            .get(0)
            .get("canApplyRefund"));
  }

  Map<String, Object> priceBody(String mode) {
    return Map.of("originalAmount", "1000.00", "financialVersion", 0, "financeMode", mode);
  }

  Map<String, Object> payBody(int version, String key) {
    return Map.of(
        "channel",
        "MOCK",
        "expectedPayableAmount",
        "1000.00",
        "financialVersion",
        version,
        "idempotencyKey",
        key);
  }

  long lid(Map<String, Object> row, String key) {
    return Long.parseLong(String.valueOf(row.get(key)));
  }

  String proof(long actor, String type, long business) throws Exception {
    return storage
        .uploadEvidence(
            actor,
            type,
            business,
            new FinanceFilesTest.FilePart("proof.png", FinanceFilesTest.png()))
        .get("evidenceKey")
        .toString();
  }

  Map<String, Object> legacyRefund(long payment, long actor) throws Exception {
    long id =
        db.insert(
            "edu_tuition_refund",
            FinanceRules.values(
                "refund_no",
                FinanceRules.code("OLD"),
                "payment_id",
                payment,
                "refund_kind",
                "PARTIAL",
                "requested_amount",
                "300",
                "refund_status",
                "PENDING_REVIEW",
                "reason",
                "历史申请",
                "calculation_snapshot",
                "{\"ruleVersion\":\"tuition-refund-v1\"}",
                "applicant_id",
                actor,
                "idempotency_key",
                UUID.randomUUID().toString(),
                "request_hash",
                "a".repeat(64)));
    return call(service("TuitionService"), "refund", id, 1L, true);
  }

  Map<String, Object> templateBody(int quantity) {
    return Map.of(
        "couponName",
        "测试券",
        "discountAmount",
        "100",
        "minSpendAmount",
        "500",
        "validFrom",
        "2020-01-01 00:00:00",
        "validUntil",
        "2099-01-01 00:00:00",
        "totalQuantity",
        quantity,
        "templateStatus",
        "ACTIVE");
  }

  @Test
  void parentCannotCreateOrConfirmCoursePaymentsEvenMockOrFree() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    assertThrows(
        Exception.class, () -> call(s, "createPayment", id, 2L, false, payBody(1, "DENY" + id)));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "STAFF" + id));
    assertThrows(
        Exception.class, () -> call(s, "confirmPayment", lid(p, "paymentId"), 2L, false, Map.of()));
  }

  @Test
  void offlineRefundRequiresTwoStagesAndHidesProposedMoneyFromParent() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "OFF" + id));
    long payment = lid(p, "paymentId");
    call(s, "confirmPayment", payment, 1L, true, Map.of());
    var body =
        Map.<String, Object>of(
            "paymentId",
            payment,
            "refundKind",
            "PARTIAL",
            "reason",
            "费用调整",
            "idempotencyKey",
            "OFFR" + id);
    var r = call(s, "applyRefund", 2L, false, body);
    assertEquals("WAIT_EDU", r.get("refundStatus"));
    assertNull(r.get("requestedAmount"));
    long refund = lid(r, "refundId");
    assertThrows(Exception.class, () -> call(s, "markRefunded", refund, 1L, Map.of("version", 0)));
    assertThrows(Exception.class, () -> call(s, "cancelRefund", refund, 2L));
    for (String amount : List.of("0", "-1", "1000.01"))
      assertThrows(
          Exception.class,
          () ->
              call(
                  s,
                  "reviewRefund",
                  refund,
                  1L,
                  Map.of("approved", true, "approvedAmount", amount, "version", 0)));
    call(
        s,
        "reviewRefund",
        refund,
        1L,
        Map.of("approved", true, "approvedAmount", "300", "version", 0));
    var pending = call(s, "refund", refund, 2L, false);
    assertEquals("WAIT_ADMIN", pending.get("refundStatus"));
    assertNull(pending.get("approvedAmount"));
    assertNull(pending.get("calculationSnapshot"));
    Map<?, ?> listed =
        (Map<?, ?>)
            ((List<?>)
                    call(s, "listRefunds", 2L, false, Map.of("enrollmentId", "" + id)).get("rows"))
                .get(0);
    assertNull(listed.get("approvedAmount"));
    assertNull(listed.get("calculationSnapshot"));
    Map<?, ?> billRefund =
        (Map<?, ?>) ((List<?>) call(s, "bill", id, 2L, false).get("refunds")).get(0);
    assertNull(billRefund.get("approvedAmount"));
    assertNull(billRefund.get("calculationSnapshot"));
    Map<?, ?> listedBill =
        (Map<?, ?>)
            ((List<?>) call(s, "listBills", 2L, false, Map.of("enrollmentId", "" + id)).get("rows"))
                .get(0);
    assertNull(listedBill.get("refundableAmount"), "可退余额不能反推未完成退款金额");
    assertEquals("OFFLINE_TWO_STAGE", pending.get("refundWorkflow"));
    assertEquals("300.00", call(s, "refund", refund, 1L, true).get("approvedAmount"));
    assertThrows(Exception.class, () -> call(s, "executeRefund", refund, 1L));
    assertThrows(Exception.class, () -> call(s, "confirmRefund", refund, 1L, Map.of()));
    assertThrows(Exception.class, () -> call(s, "markRefunded", refund, 1L, Map.of("version", 0)));
    var done = call(s, "markRefunded", refund, 1L, Map.of("version", 1));
    assertEquals("SUCCESS", done.get("refundStatus"));
    call(s, "markRefunded", refund, 1L, Map.of("version", 1));
    assertEquals("300.00", call(s, "refund", refund, 2L, false).get("approvedAmount"));
    assertEquals("700.00", call(s, "bill", id, 2L, false).get("netAmount"));
    assertEquals("报名成功", call(s, "bill", id, 2L, false).get("enrollmentStatus"));
    assertThrows(
        Exception.class,
        () -> call(s, "markRefunded", refund, 1L, Map.of("version", 1, "approvedAmount", "1")));
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "markRefunded",
                refund,
                1L,
                Map.of("version", 1, "completedTime", "2020-01-01 00:00:00")));
    var rest =
        call(
            s,
            "applyRefund",
            2L,
            false,
            Map.of(
                "paymentId",
                payment,
                "refundKind",
                "PARTIAL",
                "reason",
                "退剩余",
                "idempotencyKey",
                "REST" + id));
    long restId = lid(rest, "refundId");
    call(
        s,
        "reviewRefund",
        restId,
        1L,
        Map.of("approved", true, "approvedAmount", "700", "version", 0));
    assertEquals(
        2,
        race(
            () -> {
              call(s, "markRefunded", restId, 1L, Map.of("version", 1));
              return true;
            },
            () -> {
              call(s, "markRefunded", restId, 1L, Map.of("version", 1));
              return true;
            }));
    assertEquals("0.00", call(s, "bill", id, 2L, false).get("netAmount"));
    assertEquals("报名成功", call(s, "bill", id, 2L, false).get("enrollmentStatus"));
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "applyRefund",
                2L,
                false,
                Map.of(
                    "paymentId",
                    payment,
                    "refundKind",
                    "PARTIAL",
                    "reason",
                    "已退完",
                    "idempotencyKey",
                    "EMPTY" + id)));
  }

  @Test
  void educationCollectsAndReviewsButOnlyAdminCompletesOrRejectsReviewedRefund() throws Exception {
    var s = service("TuitionService");
    // This account exists only in the isolated fixture, above JavaScript's exact integer range.
    long rejectingAdmin = 9007199254740993L;
    db.jdbc()
        .update(
            "INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag)"
                + " VALUES(?,'large-admin','大ID管理员','0','0')",
            rejectingAdmin);
    db.jdbc().update("INSERT INTO sys_user_role VALUES(?,1)", rejectingAdmin);
    long id = fresh(), other = fresh();
    call(s, "price", id, 5L, priceBody("REAL"));
    var p =
        call(
            s,
            "createPayment",
            id,
            5L,
            true,
            Map.of(
                "channel",
                "CASH",
                "expectedPayableAmount",
                "1000.00",
                "financialVersion",
                1,
                "idempotencyKey",
                "EDU" + id,
                "paidTime",
                "2025-01-01 00:00:00",
                "evidenceKey",
                proof(5, "PAYMENT", id)));
    call(s, "confirmPayment", lid(p, "paymentId"), 5L, true, Map.of());
    assertEquals(
        1,
        ((List<?>) receiptService.list(5, true, Map.of("enrollmentId", "" + id)).get("rows"))
            .size());
    assertThrows(Exception.class, () -> new CouponService(db).admin(5));
    long receipt =
        lid(
            db.one(
                "SELECT receipt_id FROM edu_tuition_receipt WHERE payment_id=?",
                p.get("paymentId")),
            "receiptId");
    assertNotNull(receiptService.download(receipt, 5, true));
    assertThrows(Exception.class, () -> receiptService.replace(receipt, 5, "无权"));
    var body =
        new HashMap<String, Object>(
            Map.of(
                "paymentId",
                p.get("paymentId"),
                "refundKind",
                "WITHDRAWAL",
                "reason",
                "退一门课",
                "idempotencyKey",
                "EDUR" + id));
    body.put("requestedAmount", "300");
    assertThrows(Exception.class, () -> call(s, "applyRefund", 2L, false, body));
    body.remove("requestedAmount");
    var r = call(s, "applyRefund", 2L, false, body);
    long refund = lid(r, "refundId");
    assertEquals(refund, lid(call(s, "applyRefund", 2L, false, body), "refundId"));
    body.put("reason", "更改申请");
    assertThrows(Exception.class, () -> call(s, "applyRefund", 2L, false, body));
    var reviewed =
        call(
            s,
            "reviewRefund",
            refund,
            5L,
            Map.of("approved", true, "approvedAmount", "300", "reviewRemark", "核准", "version", 0));
    assertThrows(Exception.class, () -> call(s, "markRefunded", refund, 5L, Map.of("version", 1)));
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "reviewRefund",
                refund,
                5L,
                Map.of("approved", false, "reviewRemark", "驳回", "version", 1)));
    var rejected =
        call(
            s,
            "reviewRefund",
            refund,
            rejectingAdmin,
            Map.of("approved", false, "reviewRemark", "需重新核实", "version", 1));
    assertEquals("REJECTED", rejected.get("refundStatus"));
    assertEquals(reviewed.get("reviewerId"), rejected.get("reviewerId"));
    assertEquals(reviewed.get("reviewTime"), rejected.get("reviewTime"));
    assertEquals("核准", rejected.get("reviewRemark"));
    assertEquals("300.00", rejected.get("approvedAmount"));
    assertEquals("需重新核实", call(s, "refund", refund, 2L, false).get("reviewRemark"));
    assertTrue(rejected.get("calculationSnapshot").toString().contains("adminRejectReason"));
    assertEquals(
        "9007199254740993",
        com.alibaba
            .fastjson2
            .JSON
            .parseObject(rejected.get("calculationSnapshot").toString())
            .get("adminRejectBy"));
    assertEquals(
        "STRING",
        db.one(
                "SELECT JSON_TYPE(JSON_EXTRACT(calculation_snapshot,'$.adminRejectBy')) id_type"
                    + " FROM edu_tuition_refund WHERE refund_id=?",
                refund)
            .get("idType"));
    assertNull(rejected.get("executorId"));
    body.put("idempotencyKey", "EDUR2" + id);
    r = call(s, "applyRefund", 2L, false, body);
    long next = lid(r, "refundId");
    call(
        s,
        "reviewRefund",
        next,
        1L,
        Map.of("approved", true, "approvedAmount", "1000", "version", 0));
    call(s, "markRefunded", next, 1L, Map.of("version", 1));
    assertEquals("已取消", call(s, "bill", id, 2L, false).get("enrollmentStatus"));
    assertEquals("报名成功", call(s, "bill", other, 2L, false).get("enrollmentStatus"));
    body.put("idempotencyKey", "EDUR3" + id);
    assertThrows(Exception.class, () -> call(s, "applyRefund", 2L, false, body));
    var before = db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=?", next);
    migrate();
    assertEquals(before, db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=?", next));
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update(
                    "UPDATE edu_tuition_refund SET calculation_snapshot='{}' WHERE refund_id=?",
                    next));
  }

  @Test
  void databaseRestrictsNullMoneyToNewWorkflowAndRequiresBothStageFacts() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "CHECK" + id));
    long payment = lid(p, "paymentId");
    call(s, "confirmPayment", payment, 1L, true, Map.of());
    var r =
        call(
            s,
            "applyRefund",
            2L,
            false,
            Map.of(
                "paymentId",
                payment,
                "refundKind",
                "PARTIAL",
                "reason",
                "约束校验",
                "idempotencyKey",
                "CHECKR" + id));
    long refund = lid(r, "refundId");
    for (String invalid :
        List.of(
            "requested_amount=1",
            "calculation_snapshot='{}'",
            "refund_status='WAIT_ADMIN'",
            "refund_status='SUCCESS',approved_amount=1,completed_time=NOW(3)",
            "approved_amount=0")) {
      assertThrows(
          Exception.class,
          () ->
              db.jdbc()
                  .update(
                      "UPDATE edu_tuition_refund SET " + invalid + " WHERE refund_id=?", refund));
    }
    call(
        s,
        "reviewRefund",
        refund,
        1L,
        Map.of("approved", false, "reviewRemark", "测试驳回", "version", 0));
    var legacy = legacyRefund(payment, 1L);
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update(
                    "UPDATE edu_tuition_refund SET requested_amount=NULL WHERE refund_id=?",
                    legacy.get("refundId")));
    assertEquals(
        0,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id JOIN"
                    + " sys_menu m ON m.menu_id=rm.menu_id WHERE r.role_key='education' AND m.perms"
                    + " IN ('system:tuitionRefund:markRefunded','system:tuitionRefund:execute','system:tuition:historyVerify','system:tuitionReceipt:replace','system:coupon:grant')",
                Integer.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void couponRecipientsMatchOneActiveEnrollmentAndGrantRechecksFrozenFilters() {
    db.jdbc()
        .update(
            "INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag)"
                + " VALUES(51,'coupon-a','家长A','0','0'),(52,'coupon-b','家长B','0','0'),(53,'coupon-c','取消家长','0','0'),(54,'coupon-d','非家长','0','0')");
    db.jdbc().update("INSERT INTO sys_user_role VALUES(51,2),(52,2),(53,2)");
    for (int i = 51; i <= 54; i++)
      db.insert(
          "edu_course_schedule",
          Map.of(
              "schedule_id",
              i,
              "schedule_code",
              "CF" + i,
              "classroom_id",
              1,
              "term_name",
              "优惠券测试",
              "period_name",
              "周日",
              "time_slot",
              "时段" + i,
              "grade_name",
              i == 52 ? "六年级" : "五年级",
              "subject_name",
              i == 51 ? "数学" : "英语",
              "course_class_name",
              "筛选测试"));
    for (Object[] row :
        List.of(
            new Object[] {51, 51, "孩子甲", "报名成功"}, new Object[] {51, 52, "孩子乙", "报名成功"},
            new Object[] {52, 53, "孩子丙", "报名成功"}, new Object[] {52, 54, "孩子丙", "报名成功"},
            new Object[] {53, 53, "孩子丁", "已取消"}, new Object[] {54, 53, "孩子戊", "报名成功"}))
      db.insert(
          "edu_enrollment",
          Map.of(
              "enrollment_code",
              UUID.randomUUID().toString().substring(0, 30),
              "parent_id",
              row[0],
              "schedule_id",
              row[1],
              "student_name",
              row[2],
              "enrollment_status",
              row[3]));
    var c = new CouponService(db);
    var filters = Map.of("gradeName", "五年级", "subjectName", "英语");
    var page = c.parents(1L, filters);
    assertEquals(1L, ((Number) page.get("total")).longValue());
    var rows = (List<Map<String, Object>>) page.get("rows");
    assertEquals("家长B", rows.get(0).get("parentName"));
    assertEquals(
        Set.of("parentId", "parentName", "userName", "parentPhone", "matchedChildren"), rows.get(0).keySet());
    var children = (List<Map<String, Object>>) rows.get(0).get("matchedChildren");
    assertEquals(2, children.size());
    assertEquals("孩子丙", children.get(0).get("studentName"));
    assertEquals("五年级", children.get(0).get("gradeName"));
    assertEquals("英语", children.get(0).get("subjectName"));
    assertInstanceOf(String.class, children.get(0).get("enrollmentId"));
    assertEquals(
        0L,
        ((Number)
                c.parents(1L, Map.of("gradeName", "五年级", "subjectName", "英语", "search", "coupon-a"))
                    .get("total"))
            .longValue());
    assertThrows(Exception.class, () -> c.parents(1L, Map.of("gradeName", "长".repeat(21))));
    assertThrows(Exception.class, () -> c.parents(1L, Map.of("subjectName", "长".repeat(21))));
    assertThrows(Exception.class, () -> c.parents(1L, Map.of("search", "长".repeat(65))));
    long template = lid(c.saveTemplate(1L, null, templateBody(10)), "templateId");
    var body =
        new HashMap<String, Object>(
            Map.of(
                "parentIds",
                List.of("51", "52"),
                "reason",
                "筛选活动",
                "idempotencyKey",
                "filtered-grant"));
    body.putAll(filters);
    assertThrows(Exception.class, () -> c.issue(template, 1L, body));
    assertEquals(
        0,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_user_coupon WHERE template_id=?",
                Integer.class,
                template));
    assertEquals(
        0,
        db.jdbc()
            .queryForObject(
                "SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",
                Integer.class,
                template));
    body.put("parentIds", List.of("52"));
    db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='已取消' WHERE parent_id=52");
    assertThrows(Exception.class, () -> c.issue(template, 1L, body));
    db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='报名成功' WHERE parent_id=52");
    assertEquals(1, c.issue(template, 1L, body).get("total"));
    db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='已取消' WHERE parent_id=52");
    assertEquals(1, c.issue(template, 1L, body).get("total"));
    body.put("gradeName", "六年级");
    assertThrows(Exception.class, () -> c.issue(template, 1L, body));
    assertEquals(
        1,
        c.issue(
                template,
                1L,
                Map.of(
                    "parentIds",
                    List.of("51"),
                    "reason",
                    "无筛选兼容",
                    "idempotencyKey",
                    "legacy-grant"))
            .get("total"));
    assertEquals(
        2,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_user_coupon WHERE template_id=?",
                Integer.class,
                template));
  }

  @Test
  void historyIsUnknownAndCannotBeCollectedWithoutDedicatedVerification() throws Exception {
    var history = db.one("SELECT * FROM edu_enrollment WHERE enrollment_code='HISTORY'");
    assertEquals("HISTORY_PENDING", history.get("billingStatus"));
    assertNull(history.get("originalAmount"));
    assertEquals("已支付", history.get("payStatus"));
    var s = service("TuitionService");
    assertThrows(
        Exception.class,
        () -> call(s, "price", lid(history, "enrollmentId"), 1L, priceBody("REAL")));
  }

  @Test
  void interruptedMigrationKeepsOldPaidUnknownAndOnlyNewRowsDefaultToPendingPrice() {
    var old = db.one("SELECT * FROM edu_enrollment WHERE enrollment_code='HISTORY'");
    assertEquals("已支付", old.get("payStatus"));
    assertEquals("HISTORY_PENDING", old.get("billingStatus"));
    assertNull(old.get("originalAmount"));
    assertNull(old.get("discountAmount"));
    assertNull(old.get("payableAmount"));
    assertEquals(
        "PENDING_PRICE",
        db.one("SELECT billing_status FROM edu_enrollment WHERE enrollment_id=?", fresh())
            .get("billingStatus"));
  }

  @Test
  void successfulPaymentIsIdempotentImmutableAndOwned() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "createPayment",
                id,
                1L,
                true,
                Map.of(
                    "channel",
                    "MOCK",
                    "expectedPayableAmount",
                    "1.00",
                    "financialVersion",
                    1,
                    "idempotencyKey",
                    "bad" + id)));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "PAY" + id));
    assertEquals(
        p.get("paymentId"),
        call(s, "createPayment", id, 1L, true, payBody(1, "PAY" + id)).get("paymentId"));
    call(s, "confirmPayment", lid(p, "paymentId"), 1L, true, Map.of());
    call(s, "confirmPayment", lid(p, "paymentId"), 1L, true, Map.of());
    assertEquals(
        1,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_tuition_receipt WHERE payment_id=?",
                Integer.class,
                lid(p, "paymentId")));
    assertEquals("1000.00", call(s, "bill", id, 2L, false).get("receivedAmount"));
    assertThrows(Exception.class, () -> call(s, "bill", id, 3L, false));
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "price",
                id,
                1L,
                Map.of("originalAmount", "500", "financialVersion", 2, "financeMode", "REAL")));
  }

  @Test
  void partialRefundsReserveUnknownAndReleaseWithdrawalCapacityExactlyOnce() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "PR" + id));
    long payment = lid(p, "paymentId");
    call(s, "confirmPayment", payment, 1L, true, Map.of());
    var r =
        call(
            s,
            "applyRefund",
            2L,
            false,
            Map.of(
                "paymentId",
                payment,
                "refundKind",
                "PARTIAL",
                "reason",
                "补偿",
                "idempotencyKey",
                "R1" + id));
    long rid = lid(r, "refundId");
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "applyRefund",
                2L,
                false,
                Map.of(
                    "paymentId",
                    payment,
                    "refundKind",
                    "PARTIAL",
                    "reason",
                    "重复",
                    "idempotencyKey",
                    "R2" + id)));
    final long firstRefund = rid;
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "reviewRefund",
                firstRefund,
                2L,
                Map.of("approved", true, "approvedAmount", "300")));
    call(
        s,
        "reviewRefund",
        rid,
        4L,
        Map.of("approved", true, "approvedAmount", "300", "version", 0));
    call(s, "markRefunded", rid, 1L, Map.of("version", 1));
    var bill = call(s, "bill", id, 2L, false);
    assertEquals("700.00", bill.get("netAmount"));
    assertEquals("0.00", bill.get("dueAmount"));
    r =
        call(
            s,
            "applyRefund",
            2L,
            false,
            Map.of(
                "paymentId",
                payment,
                "refundKind",
                "WITHDRAWAL",
                "reason",
                "退课",
                "idempotencyKey",
                "R3" + id));
    rid = lid(r, "refundId");
    call(
        s,
        "reviewRefund",
        rid,
        4L,
        Map.of("approved", true, "approvedAmount", "700", "version", 0));
    int before =
        db.jdbc()
            .queryForObject(
                "SELECT enrolled_count FROM edu_course_schedule WHERE schedule_id=1",
                Integer.class);
    call(s, "markRefunded", rid, 1L, Map.of("version", 1));
    call(s, "markRefunded", rid, 1L, Map.of("version", 1));
    assertEquals(
        before - 1,
        db.jdbc()
            .queryForObject(
                "SELECT enrolled_count FROM edu_course_schedule WHERE schedule_id=1",
                Integer.class));
    assertEquals("已取消", call(s, "bill", id, 2L, false).get("enrollmentStatus"));
  }

  @Test
  void cancellationInvalidatesOnlyUnusedAttendance() throws Exception {
    var s = service("TuitionService");
    long unused = fresh(), attended = fresh();
    db.jdbc()
        .update(
            "INSERT INTO"
                + " edu_attendance(attendance_code,schedule_id,enrollment_id,parent_id,student_name)"
                + " VALUES(?,1,?,2,'学生')",
            "unused" + unused,
            unused);
    db.jdbc()
        .update(
            "INSERT INTO"
                + " edu_attendance(attendance_code,schedule_id,enrollment_id,parent_id,student_name,attendance_status,attended_time,confirm_by,confirm_name)"
                + " VALUES(?,1,?,2,'学生','1',NOW(),1,'经办')",
            "attended" + attended,
            attended);
    int before =
        db.jdbc()
            .queryForObject(
                "SELECT enrolled_count FROM edu_course_schedule WHERE schedule_id=1",
                Integer.class);
    call(s, "cancelEnrollment", unused, 2L, false);
    call(s, "cancelEnrollment", unused, 2L, false);
    call(s, "cancelEnrollment", attended, 2L, false);
    assertEquals(
        "2",
        db.one("SELECT attendance_status FROM edu_attendance WHERE enrollment_id=?", unused)
            .get("attendanceStatus"));
    assertEquals(
        "1",
        db.one("SELECT attendance_status FROM edu_attendance WHERE enrollment_id=?", attended)
            .get("attendanceStatus"));
    assertEquals(
        before - 2,
        db.jdbc()
            .queryForObject(
                "SELECT enrolled_count FROM edu_course_schedule WHERE schedule_id=1",
                Integer.class));
  }

  @Test
  void concurrentPaymentCreationAllowsOnlyOneEffectiveAttempt() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "C" + i + id;
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    call(s, "createPayment", id, 1L, true, payBody(1, key));
                    return true;
                  } catch (Exception e) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int successes = 0;
      for (var j : jobs) if (j.get()) successes++;
      assertEquals(1, successes);
      assertEquals(
          1,
          db.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM edu_tuition_payment WHERE enrollment_id=?",
                  Integer.class,
                  id));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void offlineMoneyStaysPendingUntilEvidenceConfirmationAndUnknownRefundKeepsReservation()
      throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("REAL"));
    String evidence = proof(1, "PAYMENT", id);
    var b =
        new HashMap<String, Object>(
            Map.of(
                "channel",
                "BANK_TRANSFER",
                "expectedPayableAmount",
                "1000.00",
                "financialVersion",
                1,
                "idempotencyKey",
                "OFF" + id,
                "payerName",
                "测试付款人",
                "providerTradeNo",
                "BANK-" + id,
                "paidTime",
                "2025-10-07 12:00:00"));
    assertThrows(Exception.class, () -> call(s, "createPayment", id, 2L, false, b));
    assertThrows(Exception.class, () -> call(s, "createPayment", id, 1L, true, b));
    b.put("evidenceKey", evidence);
    var p = call(s, "createPayment", id, 1L, true, b);
    long payment = lid(p, "paymentId");
    assertEquals("PENDING", p.get("paymentStatus"));
    assertEquals("0.00", call(s, "bill", id, 2L, false).get("receivedAmount"));
    assertThrows(Exception.class, () -> call(s, "cancelEnrollment", id, 2L, false));
    assertThrows(Exception.class, () -> call(s, "confirmPayment", payment, 2L, false, Map.of()));
    call(s, "confirmPayment", payment, 4L, true, Map.of());
    var r = legacyRefund(payment, 1L);
    long refund = lid(r, "refundId");
    migrate();
    assertEquals("PENDING_REVIEW", call(s, "refund", refund, 1L, true).get("refundStatus"));
    var reviewed =
        call(s, "reviewRefund", refund, 1L, Map.of("approved", true, "approvedAmount", "300"));
    assertEquals("APPROVED", reviewed.get("refundStatus"));
    assertEquals(1L, lid(reviewed, "reviewerId"));
    assertThrows(Exception.class, () -> call(s, "cancelRefund", refund, 2L));
    call(s, "executeRefund", refund, 1L);
    assertThrows(Exception.class, () -> call(s, "cancelRefund", refund, 1L, true));
    call(s, "confirmRefund", refund, 1L, Map.of("outcome", "UNKNOWN", "reason", "转账结果待查"));
    assertThrows(Exception.class, () -> call(s, "cancelRefund", refund, 2L));
    assertEquals("700.00", call(s, "bill", id, 1L, true).get("refundableAmount"));
    assertEquals("1000.00", call(s, "bill", id, 2L, false).get("netAmount"));
    String rp = proof(1, "REFUND", refund);
    call(
        s,
        "confirmRefund",
        refund,
        1L,
        Map.of(
            "evidenceKey",
            rp,
            "providerRefundNo",
            "BANK-R-" + refund,
            "completedTime",
            "2025-10-07 13:00:00"));
    assertEquals("700.00", call(s, "bill", id, 2L, false).get("netAmount"));
    assertThrows(Exception.class, () -> call(s, "cancelRefund", refund, 1L, true));
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update(
                    "UPDATE edu_tuition_refund SET approved_amount=1 WHERE refund_id=?", refund));
    var legacyFact = db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=?", refund);
    migrate();
    assertEquals(legacyFact, db.one("SELECT * FROM edu_tuition_refund WHERE refund_id=?", refund));
  }

  @Test
  void onlyAdminCanWithdrawOwnLegacyPendingApplication() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var payment = call(s, "createPayment", id, 1L, true, payBody(1, "CANCEL" + id));
    long paymentId = lid(payment, "paymentId");
    call(s, "confirmPayment", paymentId, 1L, true, Map.of());
    {
      var refund = legacyRefund(paymentId, 1L);
      long refundId = lid(refund, "refundId");
      assertThrows(Exception.class, () -> call(s, "cancelRefund", refundId, 3L));
      assertThrows(Exception.class, () -> call(s, "cancelRefund", refundId, 4L, true));
      assertThrows(Exception.class, () -> call(s, "cancelRefund", refundId, 2L));
      var cancelled = call(s, "cancelRefund", refundId, 1L, true);
      assertEquals("CANCELLED", cancelled.get("refundStatus"));
      assertEquals(1L, lid(cancelled, "applicantId"));
      assertEquals("1000.00", call(s, "bill", id, 1L, true).get("refundableAmount"));
      assertThrows(
          Exception.class,
          () ->
              call(
                  s,
                  "reviewRefund",
                  refundId,
                  1L,
                  Map.of("approved", true, "approvedAmount", "300")));
    }
  }

  @Test
  void couponBatchIsAtomicIdempotentFrozenAndOneCouponCannotLockTwoBills() throws Exception {
    var c = service("CouponService");
    long template = lid(call(c, "saveTemplate", 1L, null, templateBody(2)), "templateId");
    assertThrows(
        Exception.class,
        () ->
            call(
                c,
                "issue",
                template,
                1L,
                Map.of(
                    "parentIds",
                    List.of(2, 999),
                    "reason",
                    "活动",
                    "idempotencyKey",
                    "invalid" + template)));
    assertEquals(
        0,
        db.jdbc()
            .queryForObject(
                "SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",
                Integer.class,
                template));
    Map<String, Object> grant =
        Map.of("parentIds", List.of(2, 3), "reason", "活动", "idempotencyKey", "batch" + template);
    var issued = call(c, "issue", template, 1L, grant);
    call(c, "issue", template, 1L, grant);
    assertEquals(
        2,
        db.jdbc()
            .queryForObject(
                "SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",
                Integer.class,
                template));
    assertThrows(
        Exception.class,
        () ->
            call(
                c,
                "issue",
                template,
                1L,
                Map.of(
                    "parentIds",
                    List.of(2),
                    "reason",
                    "活动",
                    "idempotencyKey",
                    "batch" + template)));
    assertThrows(
        Exception.class,
        () -> call(c, "saveTemplate", 1L, template, Map.of("discountAmount", "999")));
    call(c, "saveTemplate", 1L, template, Map.of("templateStatus", "PAUSED"));
    long coupon =
        Long.parseLong(
            db.one(
                    "SELECT user_coupon_id FROM edu_user_coupon WHERE template_id=? AND"
                        + " parent_id=2",
                    template)
                .get("userCouponId")
                .toString());
    var s = service("TuitionService");
    long first = fresh(), second = fresh();
    call(s, "price", first, 1L, priceBody("MOCK"));
    call(s, "price", second, 1L, priceBody("MOCK"));
    assertEquals(
        "900.00",
        call(s, "quote", first, 2L, false, Map.of("userCouponId", coupon)).get("payableAmount"));
    var pay =
        Map.of(
            "channel",
            "MOCK",
            "userCouponId",
            coupon,
            "expectedPayableAmount",
            "900.00",
            "financialVersion",
            1,
            "idempotencyKey",
            "coupon" + first);
    var payment = call(s, "createPayment", first, 1L, true, pay);
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "createPayment",
                second,
                1L,
                true,
                Map.of(
                    "channel",
                    "MOCK",
                    "userCouponId",
                    coupon,
                    "expectedPayableAmount",
                    "900.00",
                    "financialVersion",
                    1,
                    "idempotencyKey",
                    "coupon" + second)));
    call(s, "closePayment", lid(payment, "paymentId"), 1L, Map.of("reason", "付款取消"));
    assertEquals(
        "AVAILABLE",
        db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=?", coupon)
            .get("couponStatus"));
    assertEquals(
        2,
        ((Number)
                call(c, "grants", 1L, true, Map.of("templateId", String.valueOf(template)))
                    .get("total"))
            .intValue());
  }

  @Test
  void zeroCouponWaiverCannotRefundCashAndCanSafelyWithdraw() throws Exception {
    var c = service("CouponService");
    var rules = new HashMap<String, Object>(templateBody(1));
    rules.put("discountAmount", "1500");
    long template = lid(call(c, "saveTemplate", 1L, null, rules), "templateId");
    call(
        c,
        "issue",
        template,
        1L,
        Map.of("parentIds", List.of(2), "reason", "减免", "idempotencyKey", "free" + template));
    long coupon =
        Long.parseLong(
            db.one("SELECT user_coupon_id FROM edu_user_coupon WHERE template_id=?", template)
                .get("userCouponId")
                .toString());
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("REAL"));
    var p =
        call(
            s,
            "createPayment",
            id,
            1L,
            true,
            Map.of(
                "channel",
                "FREE",
                "userCouponId",
                coupon,
                "expectedPayableAmount",
                "0.00",
                "financialVersion",
                1,
                "idempotencyKey",
                "free" + id));
    assertEquals("SUCCESS", p.get("paymentStatus"));
    assertEquals("已减免", call(s, "bill", id, 2L, false).get("payStatus"));
    assertThrows(
        Exception.class,
        () ->
            call(
                s,
                "applyRefund",
                2L,
                false,
                Map.of(
                    "paymentId",
                    p.get("paymentId"),
                    "refundKind",
                    "PARTIAL",
                    "reason",
                    "不能现金退款",
                    "idempotencyKey",
                    "free-refund" + id)));
    call(s, "cancelEnrollment", id, 2L, false);
    assertEquals(
        "USED",
        db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=?", coupon)
            .get("couponStatus"));
  }

  @Test
  void receiptFailurePreservesPaymentAndRetryUsesSameSnapshotAndNumber() throws Exception {
    var failing =
        new FinancePrivateStorage(privateRoot.resolve("private").toString()) {
          @Override
          public String publishReceipt(byte[] bytes) throws java.io.IOException {
            throw new java.io.IOException("test disk failure");
          }
        };
    var rs = new TuitionReceiptService(db, failing);
    var s = new TuitionService(db, storage, rs, new CouponService(db), true);
    long id = fresh();
    s.price(id, 1, priceBody("MOCK"));
    var p = s.createPayment(id, 1, true, payBody(1, "PDF" + id));
    s.confirmPayment(lid(p, "paymentId"), 1, true, Map.of());
    var r = db.one("SELECT * FROM edu_tuition_receipt WHERE payment_id=?", p.get("paymentId"));
    assertEquals("FAILED", r.get("fileStatus"));
    assertEquals("SUCCESS", s.payment(lid(p, "paymentId"), 2, false).get("paymentStatus"));
    long receipt = lid(r, "receiptId");
    receiptService.retry(receipt, 1);
    var ready = receiptService.receipt(receipt, 2, false);
    assertEquals("READY", ready.get("fileStatus"));
    assertEquals(r.get("receiptNo"), ready.get("receiptNo"));
    assertEquals(
        r.get("contentSnapshot"),
        db.one("SELECT content_snapshot FROM edu_tuition_receipt WHERE receipt_id=?", receipt)
            .get("contentSnapshot"));
    assertThrows(Exception.class, () -> receiptService.download(receipt, 3, false));
    assertTrue(Files.isRegularFile(receiptService.download(receipt, 2, false)));
    String originalTitle =
        com.alibaba
            .fastjson2
            .JSON
            .parseObject(String.valueOf(r.get("contentSnapshot")))
            .getString("institutionTitle");
    db.jdbc()
        .update(
            "INSERT INTO sys_config(config_name,config_key,config_value)"
                + " VALUES('测试机构','finance.institutionTitle','测试新抬头')");
    var replacement = receiptService.replace(receipt, 1, "测试更正");
    assertEquals(
        "测试新抬头",
        com.alibaba
            .fastjson2
            .JSON
            .parseObject(
                String.valueOf(
                    db.one(
                            "SELECT content_snapshot FROM edu_tuition_receipt WHERE receipt_id=?",
                            replacement.get("receiptId"))
                        .get("contentSnapshot")))
            .getString("institutionTitle"));
    assertEquals(
        originalTitle,
        com.alibaba
            .fastjson2
            .JSON
            .parseObject(
                String.valueOf(
                    db.one(
                            "SELECT content_snapshot FROM edu_tuition_receipt WHERE receipt_id=?",
                            receipt)
                        .get("contentSnapshot")))
            .getString("institutionTitle"));
    db.jdbc().update("DELETE FROM sys_config WHERE config_key='finance.institutionTitle'");
    assertEquals("1000.00", replacement.get("amount"));
    assertThrows(Exception.class, () -> receiptService.download(receipt, 2, false));
    assertEquals("1000.00", s.bill(id, 2, false).get("receivedAmount"));
  }

  @Test
  void productionRejectsExistingMockBillsAndRefundDetailsExposeReservation() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var prod = new TuitionService(db, storage, receiptService, new CouponService(db), false);
    assertThrows(Exception.class, () -> prod.bill(id, 2, false));
    assertThrows(Exception.class, () -> prod.createPayment(id, 2, false, payBody(1, "PROD" + id)));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "MD" + id));
    call(s, "confirmPayment", lid(p, "paymentId"), 1L, true, Map.of());
    var r =
        call(
            s,
            "applyRefund",
            2L,
            false,
            Map.of(
                "paymentId",
                p.get("paymentId"),
                "refundKind",
                "PARTIAL",
                "reason",
                "审核展示",
                "idempotencyKey",
                "MD-r" + id));
    assertEquals(true, r.get("mockAllowed"));
    assertNull(r.get("refundableAmount"));
    assertEquals(
        "1000.00", call(s, "refund", lid(r, "refundId"), 1L, true).get("refundableAmount"));
  }

  @Test
  void legacyStatesAreNormalizedAndHistoryVerificationRequiresChronologicalKnownMoney()
      throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    db.jdbc()
        .update(
            "UPDATE edu_enrollment SET enrollment_status='1',pay_status='0' WHERE enrollment_id=?",
            id);
    assertEquals("报名成功", call(s, "bill", id, 2L, false).get("enrollmentStatus"));
    assertEquals("未支付", call(s, "bill", id, 2L, false).get("payStatus"));
    long history = fresh();
    db.jdbc()
        .update(
            "UPDATE edu_enrollment SET billing_status='HISTORY_PENDING',pay_status='已退款' WHERE"
                + " enrollment_id=?",
            history);
    String evidence = proof(1, "HISTORY", history);
    var b =
        new HashMap<String, Object>(
            Map.of(
                "originalAmount",
                "1000",
                "historyPaidAmount",
                "900",
                "historyRefundedAmount",
                "300",
                "paidTime",
                "2025-10-07 12:00:00",
                "refundedTime",
                "2025-10-07 11:00:00",
                "financialVersion",
                0,
                "reason",
                "历史收退核验",
                "evidenceKey",
                evidence));
    assertThrows(Exception.class, () -> call(s, "historyVerify", history, 1L, b));
    b.put("refundedTime", "2025-10-07 13:00:00");
    var verified = call(s, "historyVerify", history, 1L, b);
    assertEquals("900.00", verified.get("receivedAmount"));
    assertEquals("300.00", verified.get("refundedAmount"));
    assertEquals("0.00", verified.get("dueAmount"));
    assertEquals("600.00", verified.get("refundableAmount"));
    assertEquals(
        "HISTORY_VERIFIED",
        db.one("SELECT record_source FROM edu_tuition_payment WHERE enrollment_id=?", history)
            .get("recordSource"));
    assertThrows(Exception.class, () -> call(s, "historyVerify", history, 1L, b));
    long unpaid = fresh();
    db.jdbc()
        .update(
            "UPDATE edu_enrollment SET billing_status='HISTORY_PENDING' WHERE enrollment_id=?",
            unpaid);
    var ub =
        Map.of(
            "originalAmount",
            "800",
            "historyPaidAmount",
            "0",
            "historyRefundedAmount",
            "0",
            "financialVersion",
            0,
            "reason",
            "凭据明确未支付",
            "evidenceKey",
            proof(1, "HISTORY", unpaid));
    assertEquals("800.00", call(s, "historyVerify", unpaid, 1L, ub).get("dueAmount"));
    assertEquals(
        0,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_tuition_payment WHERE enrollment_id=?",
                Integer.class,
                unpaid));
    assertNotNull(
        db.one(
                "SELECT history_verification_snapshot FROM edu_enrollment WHERE enrollment_id=?",
                unpaid)
            .get("historyVerificationSnapshot"));
  }

  @Test
  void databaseRejectsMutationOfSuccessfulCashFactsAndMigrationConflictingTypes() throws Exception {
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "IMM" + id));
    call(s, "confirmPayment", lid(p, "paymentId"), 1L, true, Map.of());
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update(
                    "UPDATE edu_tuition_payment SET amount=1 WHERE payment_id=?",
                    p.get("paymentId")));
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update(
                    "UPDATE edu_tuition_receipt SET content_snapshot=JSON_OBJECT('amount','1.00')"
                        + " WHERE payment_id=?",
                    p.get("paymentId")));
    assertThrows(
        Exception.class,
        () ->
            db.jdbc()
                .update("UPDATE edu_enrollment SET student_name='换学生' WHERE enrollment_id=?", id));
    db.jdbc().update("UPDATE sys_role SET role_name='冲突角色' WHERE role_key='education'");
    try {
      assertThrows(AssertionError.class, this::migrate);
    } finally {
      db.jdbc().update("UPDATE sys_role SET role_name='教务' WHERE role_key='education'");
    }
    migrate();
  }

  @Test
  void summaryDefaultsToRealAndUsesCashOccurrenceDatesInsteadOfEnrollmentCreation()
      throws Exception {
    var s = service("TuitionService");
    assertTrue(
        Arrays.stream(s.getClass().getMethods()).anyMatch(m -> m.getName().equals("summary")),
        "Finance summary is not implemented");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("REAL"));
    var summaryBefore = call(s, "summary", 1L, Map.of("enrollmentId", String.valueOf(id)));
    assertEquals("1000.00", summaryBefore.get("dueAmount"));
    String evidence = proof(1, "PAYMENT", id);
    var p =
        call(
            s,
            "createPayment",
            id,
            1L,
            true,
            Map.of(
                "channel",
                "CASH",
                "expectedPayableAmount",
                "1000.00",
                "financialVersion",
                1,
                "idempotencyKey",
                "SUM" + id,
                "paidTime",
                "2025-01-01 12:00:00",
                "evidenceKey",
                evidence));
    call(s, "confirmPayment", lid(p, "paymentId"), 1L, true, Map.of());
    var summary = call(s, "summary", 1L, Map.of("enrollmentId", String.valueOf(id)));
    assertEquals("1000.00", summary.get("receivedAmount"));
    assertEquals("0.00", summary.get("todayReceivedAmount"));
    assertEquals("0.00", summary.get("dueAmount"));
    long mock = fresh();
    call(s, "price", mock, 1L, priceBody("MOCK"));
    var mp = call(s, "createPayment", mock, 1L, true, payBody(1, "SM" + mock));
    call(s, "confirmPayment", lid(mp, "paymentId"), 1L, true, Map.of());
    assertEquals(
        "0.00",
        call(s, "summary", 1L, Map.of("enrollmentId", String.valueOf(mock))).get("receivedAmount"));
    assertEquals(
        "1000.00",
        call(s, "summary", 1L, Map.of("enrollmentId", String.valueOf(mock), "financeMode", "MOCK"))
            .get("todayReceivedAmount"));
  }

  @Test
  void replayStopsOnMissingSafetyIndexOrForeignKeyOrChangedGeneratedGuard() throws Exception {
    db.jdbc().execute("ALTER TABLE edu_tuition_payment DROP INDEX uk_tp_effective");
    try {
      assertThrows(AssertionError.class, this::migrate);
    } finally {
      db.jdbc()
          .execute(
              "ALTER TABLE edu_tuition_payment ADD UNIQUE INDEX"
                  + " uk_tp_effective(effective_enrollment_id)");
    }
    db.jdbc().execute("ALTER TABLE edu_tuition_payment DROP FOREIGN KEY fk_tp_recorder");
    try {
      assertThrows(AssertionError.class, this::migrate);
    } finally {
      db.jdbc()
          .execute(
              "ALTER TABLE edu_tuition_payment ADD CONSTRAINT fk_tp_recorder FOREIGN"
                  + " KEY(recorded_by) REFERENCES sys_user(user_id)");
    }
    db.jdbc()
        .execute(
            "ALTER TABLE edu_tuition_payment MODIFY effective_enrollment_id bigint GENERATED ALWAYS"
                + " AS(CASE WHEN payment_status='SUCCESS' THEN enrollment_id END) STORED");
    try {
      assertThrows(AssertionError.class, this::migrate);
    } finally {
      db.jdbc()
          .execute(
              "ALTER TABLE edu_tuition_payment MODIFY effective_enrollment_id bigint GENERATED"
                  + " ALWAYS AS(CASE WHEN payment_status IN ('PENDING','UNKNOWN','SUCCESS') THEN"
                  + " enrollment_id END) STORED");
    }
    migrate();
  }

  int race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                ready.await();
                return first.call();
              });
      var b =
          pool.submit(
              () -> {
                ready.await();
                return second.call();
              });
      ready.countDown();
      return (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentFinalCouponCapacityAndRefundApplicationsHaveOneWinner() throws Exception {
    var c = service("CouponService");
    long template = lid(call(c, "saveTemplate", 1L, null, templateBody(1)), "templateId");
    int granted =
        race(
            () -> {
              try {
                call(
                    c,
                    "issue",
                    template,
                    1L,
                    Map.of(
                        "parentIds",
                        List.of(2),
                        "reason",
                        "最后一张",
                        "idempotencyKey",
                        "LAST-A" + template));
                return true;
              } catch (Exception e) {
                return false;
              }
            },
            () -> {
              try {
                call(
                    c,
                    "issue",
                    template,
                    4L,
                    Map.of(
                        "parentIds",
                        List.of(3),
                        "reason",
                        "最后一张",
                        "idempotencyKey",
                        "LAST-B" + template));
                return true;
              } catch (Exception e) {
                return false;
              }
            });
    assertEquals(1, granted);
    assertEquals(
        1,
        db.jdbc()
            .queryForObject(
                "SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",
                Integer.class,
                template));
    assertEquals(
        1,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_user_coupon WHERE template_id=?",
                Integer.class,
                template));
    var s = service("TuitionService");
    long id = fresh();
    call(s, "price", id, 1L, priceBody("MOCK"));
    var p = call(s, "createPayment", id, 1L, true, payBody(1, "RACE-R" + id));
    long payment = lid(p, "paymentId");
    assertEquals(
        2,
        race(
            () -> {
              call(s, "confirmPayment", payment, 1L, true, Map.of());
              return true;
            },
            () -> {
              call(s, "confirmPayment", payment, 1L, true, Map.of());
              return true;
            }));
    assertEquals(
        1,
        db.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM edu_tuition_receipt WHERE payment_id=?",
                Integer.class,
                payment));
    int applied =
        race(
            () -> {
              try {
                call(
                    s,
                    "applyRefund",
                    2L,
                    false,
                    Map.of(
                        "paymentId",
                        payment,
                        "refundKind",
                        "PARTIAL",
                        "reason",
                        "并发申请",
                        "idempotencyKey",
                        "RA" + payment));
                return true;
              } catch (Exception e) {
                return false;
              }
            },
            () -> {
              try {
                call(
                    s,
                    "applyRefund",
                    1L,
                    true,
                    Map.of(
                        "paymentId",
                        payment,
                        "refundKind",
                        "PARTIAL",
                        "reason",
                        "并发申请",
                        "idempotencyKey",
                        "RB" + payment));
                return true;
              } catch (Exception e) {
                return false;
              }
            });
    assertEquals(1, applied);
    assertEquals("1000.00", call(s, "bill", id, 1L, true).get("refundableAmount"));
  }
  @Test
  void subjectScopeFiltersAvailableCouponsAndRejectsForgedUse() throws Exception {
    var c=new CouponService(db); long enrollment=fresh();
    var tuition=(TuitionService)service("TuitionService"); tuition.price(enrollment,1,priceBody("MOCK"));
    var body=new HashMap<>(templateBody(10));body.put("subjectNames",List.of("化学"));
    var template=c.saveTemplate(1,null,body);long tid=lid(template,"templateId");
    c.issue(tid,1,Map.of("parentIds",List.of(2),"reason","学科验证","idempotencyKey","SUBJECT-"+tid));
    long coupon=lid(db.one("SELECT * FROM edu_user_coupon WHERE template_id=?",tid),"userCouponId");
    var bill=tuition.bill(enrollment,2,false);
    assertTrue(c.available(bill).stream().noneMatch(x->strId(x,"userCouponId").equals(String.valueOf(coupon))));
    assertEquals(0L,c.grants(2,false,Map.of("enrollmentId",String.valueOf(enrollment),"templateId",String.valueOf(tid))).get("total"));
    assertThrows(Exception.class,()->c.discount(coupon,bill,true));
    var pay=new HashMap<>(payBody(1,"SUBJECT-PAY-"+enrollment));pay.put("userCouponId",coupon);pay.put("expectedPayableAmount","900.00");
    assertThrows(Exception.class,()->tuition.createPayment(enrollment,1,true,pay));
    assertEquals("AVAILABLE",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=?",coupon).get("couponStatus"));
    assertThrows(Exception.class,()->c.saveTemplate(1,tid,Map.of("subjectNames",List.of("数学"))));
    c.saveTemplate(1,tid,Map.of("templateStatus","PAUSED"));
  }
  private static String strId(Map<String,Object> row,String field){return String.valueOf(row.get(field));}

  @Test
  void multiSubjectAndUnrestrictedCouponsKeepOrdinaryCompatibility() throws Exception {
    var c=new CouponService(db);long enrollment=fresh();var tuition=(TuitionService)service("TuitionService");tuition.price(enrollment,1,priceBody("MOCK"));var bill=tuition.bill(enrollment,2,false);
    for(var scope:List.of(List.of("数学"),List.of("数学","化学"),List.<String>of())){
      var body=new HashMap<>(templateBody(10));body.put("subjectNames",scope);
      var template=c.saveTemplate(1,null,body);long tid=lid(template,"templateId");
      var grant=Map.<String,Object>of("parentIds",List.of(2),"reason","兼容验证","idempotencyKey","SCOPES-"+tid);
      var first=c.issue(tid,1,grant);assertEquals(first,c.issue(tid,1,grant));
      long coupon=lid(db.one("SELECT * FROM edu_user_coupon WHERE template_id=?",tid),"userCouponId");
      assertEquals(new java.math.BigDecimal("100.00"),c.discount(coupon,bill,false));
      assertTrue(c.available(bill).stream().anyMatch(x->strId(x,"userCouponId").equals(String.valueOf(coupon))));
      assertEquals(1L,c.grants(2,false,Map.of("enrollmentId",String.valueOf(enrollment),"templateId",String.valueOf(tid))).get("total"));
      assertThrows(Exception.class,()->c.issue(tid,1,Map.of("parentIds",List.of(2),"reason","重复拒绝","idempotencyKey","DUP-"+tid)));
    }
  }

  @Test
  void invalidSubjectsAndContradictoryCourseScopeCannotBeSaved() throws Exception {
    var c=new CouponService(db);var body=new HashMap<>(templateBody(10));body.put("subjectNames",List.of("无效学科"));assertThrows(Exception.class,()->c.saveTemplate(1,null,body));
    body.put("subjectNames",List.of("化学"));body.put("scopeScheduleId",1);assertThrows(Exception.class,()->c.saveTemplate(1,null,body));
    body.put("subjectNames",List.of("数学","化学","数学"));var saved=c.saveTemplate(1,null,body);assertEquals(List.of("化学","数学"),saved.get("subjectNames"));
    assertThrows(Exception.class,()->c.options(2));assertFalse(((List<?>)c.options(1).get("subjects")).isEmpty());
  }

  @Test
  void previewRuleHashRejectsChangedRulesButAllowsStockIncrementsAndRetry() throws Exception {
    var c=new CouponService(db);var template=c.saveTemplate(1,null,templateBody(10));long tid=lid(template,"templateId");
    var grant=new HashMap<String,Object>(Map.of("parentIds",List.of(2),"reason","规则预览","idempotencyKey","HASH-"+tid,"templateRulesHash",template.get("templateRulesHash")));
    var updated=c.saveTemplate(1,tid,Map.of("subjectNames",List.of("数学")));
    assertThrows(Exception.class,()->c.issue(tid,1,grant));assertEquals(0,db.jdbc().queryForObject("SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",Integer.class,tid));
    grant.put("templateRulesHash",updated.get("templateRulesHash"));var first=c.issue(tid,1,grant);assertEquals(first,c.issue(tid,1,grant));
    grant.put("parentIds",List.of(3));grant.put("idempotencyKey","HASH-SECOND-"+tid);c.issue(tid,1,grant);
    assertEquals(2,db.jdbc().queryForObject("SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",Integer.class,tid));
  }

  @Test
  void phoneSearchFindsParentsWithoutChangingLegacyNameAndOwnershipFilters() throws Exception {
    var c=new CouponService(db);assertEquals(1L,c.parents(1,Map.of("parentPhone","11111111111")).get("total"));assertEquals(1L,c.parents(1,Map.of("parentPhone","222")).get("total"));
    assertEquals(1L,c.parents(1,Map.of("q","其他家长")).get("total"));assertThrows(Exception.class,()->c.parents(1,Map.of("parentPhone","11%")));
    var t=c.saveTemplate(1,null,templateBody(5));long tid=lid(t,"templateId");c.issue(tid,1,Map.of("parentIds",List.of(2,3),"reason","手机号验证","idempotencyKey","PHONE-"+tid));
    assertEquals(1L,c.grants(1,true,Map.of("templateId",String.valueOf(tid),"parentPhone","111")).get("total"));
    assertEquals(1L,c.grants(2,false,Map.of("templateId",String.valueOf(tid),"parentId","3","parentPhone","222")).get("total"));
  }

}
