package com.ruoyi.system.finance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.*;

/** Real transactions in a disposable database; no writes to the application database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrollmentTransferMysqlTest {
  TuitionMysqlTest fixture;
  Object service;
  long target;
  @BeforeAll void setup() throws Exception {
    fixture=new TuitionMysqlTest(); fixture.setup();
    fixture.db.jdbc().execute("ALTER TABLE edu_enrollment ADD order_enrollment_id bigint");
    fixture.db.jdbc().execute("ALTER TABLE edu_course_schedule ADD enroll_limit int DEFAULT NULL");
    fixture.db.jdbc().execute("CREATE TABLE edu_class_leave_request(leave_id bigint PRIMARY KEY,enrollment_id bigint,schedule_id bigint,state varchar(20),class_date date)");
    fixture.db.jdbc().update("INSERT INTO sys_user(user_id,user_name,status,del_flag) VALUES(80,'business','0','0'),(81,'teacher','0','0')");
    fixture.db.jdbc().update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(80,'业务管理员','business_admin',4,'0'),(81,'教师','teacher',5,'0')");
    fixture.db.jdbc().update("INSERT INTO sys_user_role VALUES(80,80),(81,81)");
    // Reflection lets the first run fail for the missing feature, rather than a compilation error.
    Class<?> type=Class.forName("com.ruoyi.system.service.EnrollmentTransferService");
    service=type.getConstructor(com.ruoyi.system.shop.ShopRepository.class).newInstance(fixture.db);
    fixture.runMigration("20261103_enrollment_transfer.sql");
    fixture.db.jdbc().update("INSERT INTO edu_course_schedule(schedule_code,classroom_id,course_class_name,class_mode,campus_name,enrolled_count,status,del_flag,recruit_status,term_name,period_name,time_slot,grade_name,subject_name) VALUES('TRANSFER_TARGET',1,'新数学班','1','测试校区',0,'0','0','0','秋季','周日','下午','五年级','数学')");
    target=Long.parseLong(fixture.db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_code='TRANSFER_TARGET'").get("scheduleId").toString());
  }
  @AfterAll void cleanup() throws Exception {if(fixture!=null)fixture.cleanup();}
  Object call(String name,Class<?>[] types,Object... args) {
    try{return service.getClass().getMethod(name,types).invoke(service,args);}
    catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new RuntimeException(e.getCause());}
    catch(Exception e){throw new RuntimeException(e);}
  }
  void transfer(long id,long from,long to,long actor) {call("transfer",new Class<?>[]{long.class,long.class,long.class,String.class,long.class},id,from,to,"家长申请换班",actor);}
  long fresh() {long id=fixture.fresh();fixture.db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='1' WHERE enrollment_id=?",id);return id;}
  long count(long schedule){return Long.parseLong(fixture.db.one("SELECT enrolled_count FROM edu_course_schedule WHERE schedule_id=?",schedule).get("enrolledCount").toString());}
  @Test void paidEnrollmentKeepsMoneyPaymentAndHistoricalAttendance() {
    long id=fresh();var db=fixture.db;
    var tuition=new TuitionService(db,fixture.storage,fixture.receiptService,new CouponService(db),true);
    tuition.price(id,1,fixture.priceBody("MOCK"));
    var payment=tuition.createPayment(id,1,true,fixture.payBody(1,"TRANSFER"+id));
    tuition.confirmPayment(FinanceRules.id(payment.get("paymentId")),1,true,Map.of());
    db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,enrollment_id,student_name,sign_status,review_status) SELECT schedule_id,'2026-10-01',enrollment_id,student_name,'1','1' FROM edu_enrollment WHERE enrollment_id=?",id);
    db.jdbc().update("INSERT INTO edu_attendance(attendance_code,schedule_id,enrollment_id,parent_id,student_name,attendance_status,attended_time,confirm_by,confirm_name) SELECT CONCAT('ATT',enrollment_id),schedule_id,enrollment_id,parent_id,student_name,'1',NOW(),1,'管理员' FROM edu_enrollment WHERE enrollment_id=?",id);
    var receiptRows=db.rows("SELECT * FROM edu_tuition_receipt WHERE payment_id=?",payment.get("paymentId"));
    var before=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",id);
    var payments=db.rows("SELECT * FROM edu_tuition_payment WHERE enrollment_id=?",id);
    var signs=db.rows("SELECT * FROM edu_class_sign_in WHERE enrollment_id=?",id);
    long old=count(1),next=count(target);
    assertThrows(RuntimeException.class,()->db.jdbc().update("UPDATE edu_enrollment SET schedule_id=? WHERE enrollment_id=?",target,id));
    transfer(id,1,target,5);
    assertThrows(RuntimeException.class,()->db.jdbc().update("UPDATE edu_enrollment SET payable_amount=0 WHERE enrollment_id=?",id));
    var after=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",id);
    assertEquals(String.valueOf(target),after.get("scheduleId"));
    for(String key:before.keySet())if(!Set.of("scheduleId","updateBy","updateTime").contains(key))assertEquals(before.get(key),after.get(key),key);
    assertEquals(payments,db.rows("SELECT * FROM edu_tuition_payment WHERE enrollment_id=?",id));
    assertEquals(signs,db.rows("SELECT * FROM edu_class_sign_in WHERE enrollment_id=?",id));
    assertEquals(old-1,count(1));assertEquals(next+1,count(target));
    assertEquals(receiptRows,db.rows("SELECT * FROM edu_tuition_receipt WHERE payment_id=?",payment.get("paymentId")));
    try {
      var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(db.jdbc().getDataSource());
      factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/system/MiniAppParentMapper.xml"));
      var mapper=new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(com.ruoyi.system.mapper.MiniAppParentMapper.class);
      assertTrue(mapper.selectParentAttendanceDetails(2L,"测试课程").stream().anyMatch(row->String.valueOf(id).equals(String.valueOf(row.get("enrollmentId")))));
    } catch(Exception ex) {throw new RuntimeException(ex);}
    assertEquals(1,db.rows("SELECT * FROM edu_enrollment_transfer WHERE enrollment_id=?",id).size());
    assertThrows(RuntimeException.class,()->transfer(id,1,target,5)); // stale or duplicate confirmation
    assertEquals(next+1,count(target));
  }
  @Test void cartItemAndUnpaidEnrollmentsCanMoveWithoutRepricing() {
    for(long actor:List.of(1L,5L,80L)) {
      long id=fresh(),bill=fresh();fixture.db.jdbc().update("UPDATE edu_enrollment SET bill_type='CART_ORDER' WHERE enrollment_id=?",bill);fixture.db.jdbc().update("UPDATE edu_enrollment SET bill_type='COURSE_ITEM',order_enrollment_id=? WHERE enrollment_id=?",bill,id);
      transfer(id,1,target,actor);
      assertEquals(String.valueOf(bill),fixture.db.one("SELECT order_enrollment_id FROM edu_enrollment WHERE enrollment_id=?",id).get("orderEnrollmentId"));
    }
  }
  @Test void fullOrDuplicateTargetRollsBackAllWrites() {
    long id=fresh(),before=count(1),next=count(target);
    fixture.db.jdbc().update("UPDATE edu_course_schedule SET enroll_limit=enrolled_count WHERE schedule_id=?",target);
    // Ensure a positive capacity even when tests execute in a different order.
    fixture.db.jdbc().update("UPDATE edu_course_schedule SET enroll_limit=1,enrolled_count=1 WHERE schedule_id=?",target);
    assertThrows(RuntimeException.class,()->transfer(id,1,target,5));
    assertEquals(before,count(1));assertEquals("1",fixture.db.one("SELECT schedule_id FROM edu_enrollment WHERE enrollment_id=?",id).get("scheduleId"));
    assertTrue(fixture.db.rows("SELECT * FROM edu_enrollment_transfer WHERE enrollment_id=?",id).isEmpty());
    fixture.db.jdbc().update("UPDATE edu_course_schedule SET enroll_limit=NULL,enrolled_count=? WHERE schedule_id=?",next,target);
    var e=fixture.db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",id);
    long duplicate=fixture.db.insert("edu_enrollment",Map.of("enrollment_code","DUP"+id,"schedule_id",target,"parent_id",e.get("parentId"),"student_name",e.get("studentName"),"enrollment_status","1"));
    assertThrows(RuntimeException.class,()->transfer(id,1,target,5));
    fixture.db.jdbc().update("DELETE FROM edu_enrollment WHERE enrollment_id=?",duplicate);
    assertEquals(before,count(1));
  }
  @Test void ordinaryRolesCancelledAndSingleAppointmentsCannotTransfer() {
    long id=fresh();for(long actor:List.of(2L,81L))assertThrows(RuntimeException.class,()->transfer(id,1,target,actor));
    fixture.db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='2' WHERE enrollment_id=?",id);
    assertThrows(RuntimeException.class,()->transfer(id,1,target,5));
    fixture.db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='1',class_date='2026-12-01' WHERE enrollment_id=?",id);
    assertThrows(RuntimeException.class,()->transfer(id,1,target,5));
  }
}
