package com.ruoyi.system.finance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/** Uses a private disposable database, never the application database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessAdminFinanceMysqlTest {
  private TuitionMysqlTest fixture;
  private CouponService coupons;
  @BeforeAll void setup() throws Exception {
    fixture = new TuitionMysqlTest(); fixture.setup();
    var db = fixture.db;
    db.jdbc().update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(80,'business','业务管理员','0','0')");
    db.jdbc().update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(80,'业务管理员','business_admin',4,'0')");
    db.jdbc().update("INSERT INTO sys_user_role VALUES(80,80)");
    db.jdbc().execute("CREATE TABLE edu_referral_record(review_status varchar(20))");
    db.jdbc().execute("CREATE TABLE edu_referral_anomaly(state varchar(20))");
    coupons = new CouponService(db);
  }
  @AfterAll void cleanup() throws Exception { if (fixture != null) fixture.cleanup(); }
  @Test void businessAdminCanReadCouponOptionsAndReferralCountsAndPassTuitionGuard() {
    assertDoesNotThrow(() -> coupons.admin(80));
    var options = coupons.options(80);
    assertEquals(2, ((List<?>) options.get("subjects")).size());
    assertEquals(1, ((List<?>) options.get("schedules")).size());
    assertEquals(0L, new ReferralService(fixture.db,coupons,false).counts(80).get("pending"));
    assertDoesNotThrow(() -> FinanceRules.tuitionStaff(fixture.db,80));
  }
  @Test void ordinaryParentCannotUseAdministratorGuards() {
    assertThrows(Exception.class, () -> coupons.admin(2));
    assertThrows(Exception.class, () -> FinanceRules.tuitionStaff(fixture.db,2));
  }
  @Test void confirmationAndReceiptActionsAllowBusinessAdministratorsButDenyEducation() {
    var service = new TuitionService(fixture.db, fixture.storage, fixture.receiptService, coupons, false);
    List<java.util.function.LongConsumer> actions = List.of(
        actor -> service.confirmPayment(-1, actor, true, Map.of()),
        actor -> service.closePayment(-1, actor, Map.of()),
        actor -> service.historyVerify(-1, actor, Map.of()),
        actor -> fixture.receiptService.issue(-1, actor),
        actor -> fixture.receiptService.retry(-1, actor),
        actor -> fixture.receiptService.replace(-1, actor, ""));
    for (var action : actions) {
      // Deliberately invalid requests must reach validation, without writing financial data.
      var businessError = assertThrows(RuntimeException.class, () -> action.accept(80));
      assertFalse(String.valueOf(businessError.getMessage()).contains("FORBIDDEN"), businessError::getMessage);
      var educationError = assertThrows(RuntimeException.class, () -> action.accept(5));
      assertTrue(String.valueOf(educationError.getMessage()).contains("FORBIDDEN"));
    }
  }
  @Test void educationPreparesPaymentButBusinessAdministratorConfirmsIt() {
    var service = new TuitionService(fixture.db, fixture.storage, fixture.receiptService, coupons, true);
    long enrollment=fixture.fresh();
    assertThrows(RuntimeException.class,()->service.price(enrollment,80,fixture.priceBody("MOCK")));
    service.price(enrollment,5,fixture.priceBody("MOCK"));
    assertThrows(RuntimeException.class,()->service.createPayment(enrollment,80,true,fixture.payBody(1,"ADMIN_DENIED"+enrollment)));
    long before=service.pendingConfirmationCount(80);
    var payment=service.createPayment(enrollment,5,true,fixture.payBody(1,"EDU_PREPARE"+enrollment));
    assertEquals(before+1,service.pendingConfirmationCount(80));
    assertThrows(RuntimeException.class,()->service.pendingConfirmationCount(5));
    long paymentId=FinanceRules.id(payment.get("paymentId"));
    assertEquals("PENDING",payment.get("paymentStatus"));
    assertThrows(RuntimeException.class,()->service.confirmPayment(paymentId,5,true,Map.of()));
    var pending=service.listBills(80,true,Map.of("paymentStage","WAIT_ADMIN"));
    assertTrue(((List<Map<String,Object>>)pending.get("rows")).stream().anyMatch(row->FinanceRules.id(row.get("enrollmentId"))==enrollment));
    assertEquals("SUCCESS",service.confirmPayment(paymentId,80,true,Map.of()).get("paymentStatus"));
    assertEquals(before,service.pendingConfirmationCount(80));
    var waiting=service.listBills(80,true,Map.of("paymentStage","WAIT_ADMIN"));
    assertFalse(((List<Map<String,Object>>)waiting.get("rows")).stream().anyMatch(row->FinanceRules.id(row.get("enrollmentId"))==enrollment));
  }
  @Test void staffBillShowsCurrentCourseDetailsForSingleAndCartOrders() {
    var db=fixture.db;
    db.jdbc().execute("ALTER TABLE edu_enrollment ADD order_enrollment_id bigint");
    db.jdbc().execute("ALTER TABLE edu_course_schedule ADD unit_price decimal(10,2) DEFAULT 0, ADD lesson_count int");
    var service=new TuitionService(db,fixture.storage,fixture.receiptService,coupons,true);
    long enrollment=fixture.fresh();
    try {
      db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=80,start_time='08:30:00',end_time='10:00:00',start_date='2026-11-01',end_date='2026-12-01' WHERE schedule_id=1");
      for(long actor:List.of(5L,80L)) {
        var detail=service.bill(enrollment,actor,true);
        var courses=(List<Map<String,Object>>)detail.get("courses");
        assertEquals(1,courses.size());assertEquals("08:30",courses.get(0).get("startTime"));
        assertEquals("2026-11-01",courses.get(0).get("startDate"));
        assertEquals("业务管理员",courses.get(0).get("teacherName"));
        assertEquals("数学",courses.get(0).get("subjectName"));
      }
      long child=fixture.fresh(),foreign=fixture.fresh();
      db.jdbc().update("UPDATE edu_enrollment SET bill_type='CART_ORDER' WHERE enrollment_id=?",enrollment);
      db.jdbc().update("UPDATE edu_enrollment SET bill_type='COURSE_ITEM',order_enrollment_id=? WHERE enrollment_id IN (?,?)",enrollment,child,foreign);
      db.jdbc().update("UPDATE edu_enrollment SET parent_id=3 WHERE enrollment_id=?",foreign);
      var courses=(List<Map<String,Object>>)service.bill(enrollment,80,true).get("courses");
      assertEquals(1,courses.size());assertEquals(child,FinanceRules.id(courses.get(0).get("enrollmentId")));
    } finally {
      db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=NULL,start_time=NULL,end_time=NULL,start_date=NULL,end_date=NULL WHERE schedule_id=1");
    }
  }
  @Test void disabledRoleOrAccountCannotUseAdministratorGuards() {
    try {
      fixture.db.jdbc().update("UPDATE sys_role SET status='1' WHERE role_id=80");
      assertThrows(Exception.class, () -> coupons.admin(80));
      assertThrows(Exception.class, () -> FinanceRules.tuitionStaff(fixture.db,80));
      fixture.db.jdbc().update("UPDATE sys_role SET status='0' WHERE role_id=80");
      fixture.db.jdbc().update("UPDATE sys_user SET status='1' WHERE user_id=80");
      assertThrows(Exception.class, () -> coupons.admin(80));
      assertThrows(Exception.class, () -> FinanceRules.tuitionStaff(fixture.db,80));
    } finally {
      fixture.db.jdbc().update("UPDATE sys_role SET status='0' WHERE role_id=80");
      fixture.db.jdbc().update("UPDATE sys_user SET status='0' WHERE user_id=80");
    }
  }
}
