package com.ruoyi.system.finance;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;
import static org.junit.jupiter.api.Assertions.*;

/** All writes are synthetic fixtures in a random temporary schema, never in the business DB. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreditPackageMysqlTest {
  final String schema="codex_package_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  ShopRepository db; Object packages; TuitionService tuition; CouponService coupons; FinancePrivateStorage storage;
  static void principal(long id,String role){var u=new SysUser();u.setUserId(id);u.setUserName("fixture"+id);u.setNickName("测试用户");var r=new SysRole();r.setRoleKey(role);u.setRoles(List.of(r));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,u,Set.of()),null,List.of()));}
  void migrate()throws Exception{
    String sql=Files.readString(Path.of("../../database/migrations/20261020_credit_package_purchase.sql"));
    db.jdbc().execute("DROP PROCEDURE IF EXISTS credit_package_upgrade");
    db.jdbc().execute(sql.substring(sql.indexOf("CREATE PROCEDURE"),sql.indexOf("END$$")+3));
    db.jdbc().execute("CALL credit_package_upgrade()");db.jdbc().execute("DROP PROCEDURE credit_package_upgrade");
    String scopes=Files.readString(Path.of("../../database/migrations/20261020_coupon_course_scope.sql"));
    db.jdbc().execute(scopes.substring(scopes.indexOf("CREATE PROCEDURE"),scopes.indexOf("END$$")+3));
    db.jdbc().execute("CALL coupon_scope_upgrade()");db.jdbc().execute("DROP PROCEDURE coupon_scope_upgrade");
  }
  @BeforeAll void setup()throws Exception{
    try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){
      c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
      for(String table:List.of("sys_user","sys_role","sys_user_role","sys_config","sys_dict_data","edu_classroom","edu_course_schedule","edu_student","edu_student_lesson_account","edu_lesson_credit_event","edu_enrollment","edu_tuition_payment","edu_tuition_refund","edu_tuition_receipt","edu_coupon_template","edu_user_coupon","edu_tuition_finance_event")){
        // Only copy structures that exist; finance event name differs in older deployments.
        var p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='mingyuaneduminiapp_main_20261013' AND table_name=?");p.setString(1,table);var n=p.executeQuery();n.next();if(n.getInt(1)>0)c.createStatement().execute("CREATE TABLE "+schema+"."+table+" LIKE mingyuaneduminiapp_main_20261013."+table);
      }
    }
    var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root","");db=new ShopRepository(ds);
    assertTrue(Files.exists(Path.of("../../database/migrations/20261020_credit_package_purchase.sql")),"缺少课包增量迁移");migrate();migrate();
    coupons=new CouponService(db);
    try{packages=Class.forName("com.ruoyi.system.finance.CreditPackageService").getConstructor(ShopRepository.class,CouponService.class).newInstance(db,coupons);}catch(ReflectiveOperationException e){throw new AssertionError("缺少课包购买服务",e);}
    var root=Files.createTempDirectory("package-finance-");new com.ruoyi.common.config.RuoYiConfig().setProfile(root.resolve("public").toString());
    storage=new FinancePrivateStorage(root.resolve("private").toString());tuition=new TuitionService(db,storage,new TuitionReceiptService(db,storage),coupons,true);
  }
  Object call(String name,Map<String,Object> b){try{return packages.getClass().getMethod(name,Map.class).invoke(packages,b);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError("缺少课包接口 "+name,e);}}
  @BeforeEach void rows(){
    for(String t:List.of("edu_tuition_receipt","edu_tuition_refund","edu_tuition_payment","edu_lesson_credit_event","edu_user_coupon","edu_coupon_template","edu_enrollment","edu_course_schedule","edu_student_lesson_account","edu_student","sys_user_role","sys_role","sys_user","sys_dict_data"))db.jdbc().update("DELETE FROM "+t);
    db.jdbc().update("INSERT INTO sys_user(user_id,user_name,nick_name) VALUES(20,'fixture20','家长甲'),(21,'fixture21','家长乙'),(30,'fixture30','管理员')");
    db.jdbc().update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(1,'家长','parent',1,'0'),(2,'管理员','admin',2,'0')");db.jdbc().update("INSERT INTO sys_user_role VALUES(20,1),(21,1),(30,2)");
    db.jdbc().update("INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,status) VALUES(5,'五年级','g5','edu_grade','0')");
    db.jdbc().update("INSERT INTO edu_student(student_id,parent_id,student_name,grade_code) VALUES(1,20,'孩子甲','g5'),(2,21,'孩子乙','g5')");
    db.jdbc().update("INSERT INTO edu_student_lesson_account(account_id,parent_id,student_id,student_name,grade_name,remaining_credits,reserved_credits,consumed_credits,request_key,create_by,create_time) VALUES(1,20,1,'孩子甲','五年级',0,0,0,'child-one','fixture20',NOW()),(2,21,2,'孩子乙','五年级',0,0,0,'child-two','fixture21',NOW())");
    principal(20,"parent");
  }
  Map<String,Object> body(int count){var b=new HashMap<String,Object>();b.put("accountId",1L);b.put("packageCount",count);return b;}
  @SuppressWarnings("unchecked") Map<String,Object> quote(Map<String,Object> b){return (Map<String,Object>)call("quote",b);}
  long order(Map<String,Object> b){var q=quote(b);b.put("quoteHash",q.get("quoteHash"));b.put("expectedPayableAmount",q.get("payableAmount"));b.put("idempotencyKey",UUID.randomUUID().toString());return ((Number)call("create",b)).longValue();}
  @Test void pricesUseChildGradeAndThisOrdersQuantity(){
    assertEquals("5700.00",quote(body(1)).get("payableAmount"));assertEquals("11040.00",quote(body(2)).get("payableAmount"));assertEquals("16200.00",quote(body(3)).get("payableAmount"));
    for(int grade:new int[]{1,6,7,8,9,10,11,12}){db.jdbc().update("UPDATE edu_student SET grade_code=? WHERE student_id=1","g"+grade);String unit=grade<=6?"400.00":grade<=8?"500.00":grade==9?"600.00":grade<=11?"700.00":"800.00";assertEquals(unit,quote(body(1)).get("unitPrice"));}
    assertThrows(ServiceException.class,()->quote(body(0)));assertThrows(ServiceException.class,()->quote(body(101)));
  }
  @Test void parentCannotBuyForAnotherChildOrForgeTheFinalPrice(){
    var other=body(1);other.put("accountId",2L);assertThrows(ServiceException.class,()->quote(other));
    var b=body(1);var q=quote(b);b.put("quoteHash",q.get("quoteHash"));b.put("expectedPayableAmount","1.00");b.put("idempotencyKey",UUID.randomUUID().toString());assertThrows(ServiceException.class,()->call("create",b));
    assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment",Integer.class));
  }
  @Test void submittedOrderDoesNotGrantCreditsAndRetryReturnsSameOrder(){
    var b=body(2);long id=order(b);assertEquals(id,((Number)call("create",b)).longValue());
    assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment",Integer.class));
    assertNull(db.one("SELECT schedule_id FROM edu_enrollment WHERE enrollment_id=?",id).get("scheduleId"));
    assertEquals(0,db.jdbc().queryForObject("SELECT remaining_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
    b.put("packageCount",3);assertThrows(ServiceException.class,()->call("create",b));
    principal(30,"admin");assertThrows(ServiceException.class,()->tuition.price(id,30,Map.of("originalAmount","1.00","financialVersion",0)));
  }
  @Test void successfulCollectionGrantsExactlyOnceAndKeepsExistingReservations(){
    long id=order(body(2));db.jdbc().update("UPDATE edu_student_lesson_account SET remaining_credits=5,reserved_credits=2 WHERE account_id=1");
    // A synthetic successful payment exercises the same transaction hook without real cash or uploading a proof.
    principal(30,"admin");assertThrows(ServiceException.class,()->db.transactions().execute(tx->{new com.ruoyi.system.lesson.LessonFinance(db).activate(id,30);return null;}));
    db.jdbc().update("INSERT INTO edu_tuition_payment(payment_no,enrollment_id,channel,payment_status,finance_mode,amount,payer_name,bill_snapshot,recorded_by,confirmed_by,confirmed_time,paid_time,idempotency_key,request_hash) VALUES('TPFIX',?,'CASH','SUCCESS','REAL',11040,'测试家长','{}',30,30,NOW(),NOW(),'fixture-success',REPEAT('a',64))",id);
    db.transactions().execute(tx->{var hooks=new com.ruoyi.system.lesson.LessonFinance(db);hooks.activate(id,30);hooks.activate(id,30);return null;});
    assertEquals(35,db.jdbc().queryForObject("SELECT remaining_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
    assertEquals(2,db.jdbc().queryForObject("SELECT reserved_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
    assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_lesson_credit_event WHERE request_key=?",Integer.class,"PURCHASE:"+id));
  }
  void coupon(){
    db.jdbc().update("INSERT INTO edu_coupon_template(template_id,template_code,coupon_name,discount_amount,min_spend_amount,valid_from,valid_until,total_quantity,issued_quantity,template_status) VALUES(1,'FIX','通用券',100,5600,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),10,1,'ACTIVE')");
    db.jdbc().update("INSERT INTO edu_user_coupon(user_coupon_id,coupon_code,template_id,parent_id,coupon_status,valid_from,valid_until,granted_by,grant_request_key,grant_request_hash,grant_reason) VALUES(1,'FIX-C',1,20,'AVAILABLE',DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY),30,'FIX-G',REPEAT('a',64),'测试')");
  }
  @Test void couponCourseTypesGatePackageQuotesAndKeepLegacyRestrictions(){
    coupon();var b=body(1);b.put("userCouponId",1L);
    db.jdbc().update("UPDATE edu_coupon_template SET course_scope='CLASS' WHERE template_id=1");
    assertThrows(ServiceException.class,()->quote(b));assertTrue(((List<?>)quote(body(1)).get("coupons")).isEmpty());
    for(String scope:List.of("ONE_TO_ONE","ALL","LEGACY")){
      db.jdbc().update("UPDATE edu_coupon_template SET course_scope=? WHERE template_id=1",scope);
      assertEquals("5600.00",quote(b).get("payableAmount"));assertEquals(1,((List<?>)quote(body(1)).get("coupons")).size());
    }
    db.jdbc().update("UPDATE edu_coupon_template SET scope_schedule_id=999 WHERE template_id=1");
    assertThrows(ServiceException.class,()->quote(b));
  }
  @Test void courseCouponsAreIsolatedByScheduleTypeAndIssuedScopeCannotChange(){
    coupon();db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,schedule_code,classroom_id,term_name,period_name,time_slot,grade_name,subject_name,course_class_name,class_mode) VALUES(1,'FIX-CLASS',1,'秋季','周日','上午','五年级','数学','测试班课','1')");
    var bill=FinanceRules.values("scheduleId",1L,"parentId",20L,"originalAmount","6000.00");
    db.jdbc().update("UPDATE edu_coupon_template SET course_scope='ONE_TO_ONE' WHERE template_id=1");
    assertThrows(ServiceException.class,()->coupons.discount(1,bill,false));assertTrue(coupons.available(bill).isEmpty());
    db.jdbc().update("UPDATE edu_course_schedule SET class_mode='2' WHERE schedule_id=1");
    assertEquals("100.00",coupons.discount(1,bill,false).toPlainString());assertEquals(1,coupons.available(bill).size());
    db.jdbc().update("UPDATE edu_coupon_template SET course_scope='CLASS' WHERE template_id=1");
    assertThrows(ServiceException.class,()->coupons.discount(1,bill,false));
    db.jdbc().update("UPDATE edu_course_schedule SET class_mode='1' WHERE schedule_id=1");
    assertEquals("100.00",coupons.discount(1,bill,false).toPlainString());
    principal(30,"admin");assertThrows(ServiceException.class,()->coupons.saveTemplate(30,1L,Map.of("courseScope","ALL")));
    assertEquals("CLASS",coupons.saveTemplate(30,1L,Map.of("templateStatus","PAUSED")).get("courseScope"));
  }
  @Test void couponCollectionUsesFinalPriceAndCreditsOnlyAtConfirmation()throws Exception{
    coupon();var b=body(1);b.put("userCouponId",1L);assertEquals("5600.00",quote(b).get("payableAmount"));long id=order(b);
    assertEquals("LOCKED",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=1").get("couponStatus"));
    principal(30,"admin");String proof=storage.uploadEvidence(30,"PAYMENT",id,new FinanceFilesTest.FilePart("proof.png",FinanceFilesTest.png())).get("evidenceKey").toString();
    var payment=tuition.createPayment(id,30,true,Map.of("channel","CASH","payerName","测试家长","evidenceKey",proof,"paidTime",FinanceRules.now().toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),"expectedPayableAmount","5600.00","financialVersion",0,"idempotencyKey","FIX-PAY"));
    long pid=FinanceRules.id(payment.get("paymentId"));
    assertEquals(0,db.jdbc().queryForObject("SELECT remaining_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
    db.jdbc().update("UPDATE edu_student_lesson_account SET remaining_credits=10000000 WHERE account_id=1");
    assertThrows(ServiceException.class,()->tuition.confirmPayment(pid,30,true,Map.of()));
    assertEquals("PENDING",db.one("SELECT payment_status FROM edu_tuition_payment WHERE payment_id=?",pid).get("paymentStatus"));
    assertEquals("LOCKED",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=1").get("couponStatus"));
    assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_lesson_credit_event",Integer.class));
    db.jdbc().update("UPDATE edu_student_lesson_account SET remaining_credits=0 WHERE account_id=1");
    tuition.confirmPayment(pid,30,true,Map.of());tuition.confirmPayment(pid,30,true,Map.of());
    assertEquals(15,db.jdbc().queryForObject("SELECT remaining_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
    assertEquals("USED",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=1").get("couponStatus"));
    assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_tuition_receipt",Integer.class));
  }
  @Test void cancellationReleasesCouponButSubjectCouponCannotBuyUniversalCredits(){
    coupon();var b=body(1);b.put("userCouponId",1L);long id=order(b);
    assertThrows(ServiceException.class,()->tuition.cancelEnrollment(id,20,false));
    ((CreditPackageService)packages).cancel(id,1L);((CreditPackageService)packages).cancel(id,1L);
    assertEquals("AVAILABLE",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=1").get("couponStatus"));
    db.jdbc().update("UPDATE edu_coupon_template SET subject_names='[\"数学\"]' WHERE template_id=1");
    assertThrows(ServiceException.class,()->quote(b));
    assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_lesson_credit_event",Integer.class));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @AfterAll void cleanup()throws Exception{if(!schema.matches("codex_package_test_[a-f0-9]{12}"))throw new IllegalStateException();try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("DROP DATABASE IF EXISTS "+schema);}}
}
