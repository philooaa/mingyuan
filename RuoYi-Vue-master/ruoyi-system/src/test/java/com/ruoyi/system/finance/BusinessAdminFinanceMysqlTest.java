package com.ruoyi.system.finance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
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
