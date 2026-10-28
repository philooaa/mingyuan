package com.ruoyi.system.finance;

import static com.ruoyi.system.finance.FinanceRules.*;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.system.shop.ShopRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

/** Opt-in isolated MySQL tests. Uses the original finance fixture and original public services. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferralMysqlTest {
  TuitionMysqlTest fixture;
  ShopRepository db;
  CouponService coupons;
  TuitionService tuition;
  ReferralService referrals;
  AtomicLong users=new AtomicLong(1000);
  @BeforeAll void setup() throws Exception {
    fixture=new TuitionMysqlTest();fixture.setup();db=fixture.db;
    fixture.runMigration("20261014_referral_rewards.sql");fixture.runMigration("20261014_referral_rewards.sql");
    coupons=new CouponService(db);tuition=(TuitionService)fixture.service("TuitionService");
  }
  @BeforeEach void freshService() throws Exception {if(db!=null){referrals=new ReferralService(db,coupons,true);var f=TuitionService.class.getDeclaredField("referrals");f.setAccessible(true);f.set(tuition,referrals);}}
  @AfterAll void cleanup() throws Exception {if(fixture!=null)fixture.cleanup();}
  long parent(){long id=users.incrementAndGet();db.jdbc().update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(?,?,?,'0','0')",id,"p"+id,"测试家长"+id);db.jdbc().update("INSERT INTO sys_user_role VALUES(?,2)",id);return id;}
  long bill(long parent){return db.insert("edu_enrollment",values("enrollment_code",code("RF").substring(0,30),"schedule_id",1,"parent_id",parent,"student_name",code("S").substring(0,30),"enrollment_status","报名成功"));}
  long payment(long parent){long e=bill(parent);tuition.price(e,1,fixture.priceBody("MOCK"));var p=tuition.createPayment(e,1,true,fixture.payBody(1,UUID.randomUUID().toString()));long id=id(p.get("paymentId"));tuition.confirmPayment(id,1,true,Map.of());return id;}
  String paidCode(long parent){payment(parent);return str(referrals.home(parent),"referralCode");}
  long record(long invitee){return db.jdbc().queryForObject("SELECT r.record_id FROM edu_referral_record r JOIN edu_referral_binding b ON b.binding_id=r.binding_id WHERE b.invitee_id=?",Long.class,invitee);}
  long qualified(long old,String code){long newcomer=parent();referrals.bind(newcomer,Map.of("code",code));payment(newcomer);return record(newcomer);}
  long template(int stock){return id(coupons.saveTemplate(1,null,fixture.templateBody(stock)).get("templateId"));}
  Map<String,Object> grantBody(long template,List<Long> records,String role){return new HashMap<>(Map.of("templateId",template,"recordIds",records,"recipientRole",role,"reason","核验推荐奖励","idempotencyKey",UUID.randomUUID().toString()));}
  Map<String,Object> preview(Map<String,Object> body){var p=referrals.previewReward(1,body);body.put("previewHash",p.get("previewHash"));return p;}
  void clock(LocalDateTime time) throws Exception {var f=ReferralService.class.getDeclaredField("clock");f.setAccessible(true);f.set(referrals,Clock.fixed(time.atZone(ZONE).toInstant(),ZONE));}
  int count(String table){return db.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

  @Test void bindBeforePayGeneratesOnlyOneRecordAndKeepsFinancialApi() {
    long old=parent(),newcomer=parent();String code=paidCode(old);
    var binding=referrals.bind(newcomer,Map.of("code",code));assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record WHERE binding_id=?",Integer.class,binding.get("bindingId")));
    long payment=payment(newcomer);assertEquals("SUCCESS",tuition.payment(payment,1,true).get("paymentStatus"));
    long record=record(newcomer);assertTrue(record>0);referrals.refresh(newcomer);referrals.bind(newcomer,Map.of("code",code));
    assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record WHERE binding_id=?",Integer.class,binding.get("bindingId")));
    assertNotNull(((Map<?,?>)referrals.home(newcomer).get("binding")).get("lockedAt"));
    assertThrows(Exception.class,()->referrals.counts(newcomer));
    assertEquals(0L,referrals.list(parent(),false,Map.of("kind","rewards")).get("total"));
  }
  @Test void paidFirstDeadlineUsesConfirmationMillisecondsAndNeverRestarts() throws Exception {
    long old=parent();String code=paidCode(old);long fresh=parent();long p=payment(fresh);
    Timestamp first=db.jdbc().queryForObject("SELECT confirmed_time FROM edu_tuition_payment WHERE payment_id=?",Timestamp.class,p);
    clock(first.toLocalDateTime().plusDays(7));assertDoesNotThrow(()->referrals.bind(fresh,Map.of("code",code)));
    long expired=parent();long expiredPayment=payment(expired);Timestamp time=db.jdbc().queryForObject("SELECT confirmed_time FROM edu_tuition_payment WHERE payment_id=?",Timestamp.class,expiredPayment);
    payment(expired);clock(time.toLocalDateTime().plusDays(7).plusNanos(1_000_000));assertThrows(Exception.class,()->referrals.bind(expired,Map.of("code",code)));
    assertEquals(expiredPayment,id(db.one("SELECT first_payment_id FROM edu_referral_profile WHERE parent_id=?",expired).get("firstPaymentId")));
    assertFalse((Boolean)referrals.home(expired).get("canBind"));
  }
  @Test void historicalCustomerSelfCycleAndDisabledAccountCannotBind() {
    long old=parent();String code=paidCode(old);assertThrows(Exception.class,()->referrals.bind(old,Map.of("code",code)));
    assertThrows(Exception.class,()->referrals.bind(2,Map.of("code",code))); // fixture's real historical paid evidence
    payment(2);assertFalse((Boolean)referrals.home(2).get("canBind"));
    long a=parent(),b=parent();String ca=paidCode(a),cb=paidCode(b);referrals.bind(a,Map.of("code",cb));assertThrows(Exception.class,()->referrals.bind(b,Map.of("code",ca)));
    long disabled=parent();db.jdbc().update("UPDATE sys_user SET status='1' WHERE user_id=?",disabled);assertThrows(Exception.class,()->referrals.home(disabled));
    assertThrows(Exception.class,()->referrals.home(1));
  }
  @Test void administratorCorrectionKeepsHistoryAndLocksAtFirstReceipt() {
    long old=parent(),other=parent(),fresh=parent();String a=paidCode(old),b=paidCode(other);long binding=id(referrals.bind(fresh,Map.of("code",a)).get("bindingId"));
    assertThrows(Exception.class,()->referrals.correct(fresh,binding,Map.of("code",b,"version",1,"reason","越权")));
    referrals.correct(1,binding,Map.of("code",b,"version",1,"reason","首次收款前核对纠错"));
    assertEquals(2,db.jdbc().queryForObject("SELECT JSON_LENGTH(history) FROM edu_referral_binding WHERE binding_id=?",Integer.class,binding));
    assertThrows(Exception.class,()->referrals.correct(1,binding,Map.of("code",a,"version",1,"reason","过期页面")));
    payment(fresh);assertThrows(Exception.class,()->referrals.correct(1,binding,Map.of("code",a,"version",2,"reason","已锁定")));
  }
  @Test void concurrentBindingAndQualificationAreIdempotent() throws Exception {
    long old=parent(),fresh=parent();String code=paidCode(old);var pool=Executors.newFixedThreadPool(4);
    try {var jobs=new ArrayList<Future<?>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->referrals.bind(fresh,Map.of("code",code))));for(var j:jobs)j.get();payment(fresh);jobs.clear();for(int i=0;i<8;i++)jobs.add(pool.submit(()->referrals.refresh(fresh)));for(var j:jobs)j.get();}
    finally{pool.shutdownNow();}
    assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_binding WHERE invitee_id=?",Integer.class,fresh));
    assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record r JOIN edu_referral_binding b ON b.binding_id=r.binding_id WHERE b.invitee_id=?",Integer.class,fresh));
  }
  @Test void crossRelationshipSameTemplateUsesCouponQuantityAndStableBatchRetry() {
    long old=parent();String code=paidCode(old);long a=qualified(old,code),b=qualified(old,code),t=template(8);
    var body=grantBody(t,List.of(a,b),"OLD");var p=preview(body);assertEquals(1,p.get("recipientCount"));assertEquals(2,p.get("couponCount"));
    var grant=referrals.grant(1,body);assertEquals(2,grant.get("total"));assertEquals(grant,referrals.grant(1,body));
    assertEquals(2,id(db.one("SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",t).get("issuedQuantity")));
    assertThrows(Exception.class,()->referrals.previewReward(4,grantBody(t,List.of(a,b),"OLD")));
    var newUsers=grantBody(t,List.of(a,b),"NEW");preview(newUsers);assertEquals(2,referrals.grant(1,newUsers).get("total"));
    assertEquals(4,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_reward WHERE template_id=?",Integer.class,t));
    assertThrows(Exception.class,()->db.jdbc().update("UPDATE edu_user_coupon SET referral_reward_id=NULL WHERE template_id=?",t));
    var list=referrals.list(1,true,Map.of("kind","records","parentId",String.valueOf(old),"recipientRole","OLD"));assertEquals(2L,list.get("total"));
    assertEquals(0L,referrals.list(1,true,Map.of("kind","records","parentId",String.valueOf(old),"recipientRole","NEW")).get("total"));
  }
  @Test void ordinaryCouponIssueRetryLimitsAndRecipientFiltersRemainCompatible() {
    long parent=parent(),t=template(3);var body=new HashMap<String,Object>(Map.of("parentIds",List.of(parent,parent),"idempotencyKey",UUID.randomUUID().toString(),"reason","普通发券"));
    var first=coupons.issue(t,1,body);assertEquals(1,first.get("total"));assertEquals(first,coupons.issue(t,1,body));body.put("idempotencyKey",UUID.randomUUID().toString());assertThrows(Exception.class,()->coupons.issue(t,1,body));
    @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)first.get("rows");long coupon=id(rows.get(0).get("userCouponId"));coupons.revoke(coupon,1,Map.of("reason","普通撤回"));assertEquals("REVOKED",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=?",coupon).get("couponStatus"));
  }
  @Test void inventoryAndForcedCouponFailureRollbackSourceAndReview() {
    long old=parent();String code=paidCode(old);long record=qualified(old,code),t=template(2);var body=grantBody(t,List.of(record),"BOTH");preview(body);
    int rewards=count("edu_referral_reward"),batches=count("edu_referral_reward_batch");
    db.jdbc().execute("CREATE TRIGGER test_referral_coupon_failure BEFORE INSERT ON edu_user_coupon FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected referral coupon failure'");
    try{assertThrows(Exception.class,()->referrals.grant(1,body));}finally{db.jdbc().execute("DROP TRIGGER test_referral_coupon_failure");}
    assertEquals(rewards,count("edu_referral_reward"));assertEquals(batches,count("edu_referral_reward_batch"));assertEquals(0,((Number)db.one("SELECT issued_quantity FROM edu_coupon_template WHERE template_id=?",t).get("issuedQuantity")).intValue());
    assertEquals("PENDING",db.one("SELECT review_status FROM edu_referral_record WHERE record_id=?",record).get("reviewStatus"));
    coupons.saveTemplate(1,t,Map.of("totalQuantity",1));assertThrows(Exception.class,()->referrals.grant(1,body));
  }
  @Test void couponRevocationThroughOriginalApiKeepsRewardHistoryAndRejectsUsedOrLocked() {
    long old=parent();String code=paidCode(old);long record=qualified(old,code),t=template(3);var body=grantBody(t,List.of(record),"OLD");preview(body);referrals.grant(1,body);
    var reward=db.one("SELECT w.reward_id,c.user_coupon_id FROM edu_referral_reward w JOIN edu_user_coupon c ON c.referral_reward_id=w.reward_id WHERE w.record_id=? AND w.template_id=?",record,t);
    long coupon=id(reward.get("userCouponId"));coupons.revoke(coupon,1,Map.of("reason","原接口撤回推荐券"));assertEquals(2,db.jdbc().queryForObject("SELECT JSON_LENGTH(history) FROM edu_referral_reward WHERE reward_id=?",Integer.class,reward.get("rewardId")));
    assertThrows(Exception.class,()->referrals.revoke(1,id(reward.get("rewardId")),Map.of("reason","重复撤回")));
    long second=template(2);var next=grantBody(second,List.of(record),"OLD");preview(next);referrals.grant(1,next);var c=db.one("SELECT user_coupon_id FROM edu_user_coupon WHERE template_id=?",second);long target=id(c.get("userCouponId"));
    long bill=bill(old);tuition.price(bill,1,fixture.priceBody("MOCK"));var p=tuition.createPayment(bill,1,true,new HashMap<>(Map.of("channel","MOCK","expectedPayableAmount","900.00","financialVersion",1,"idempotencyKey",UUID.randomUUID().toString(),"userCouponId",target)));
    assertThrows(Exception.class,()->referrals.revoke(1,id(db.one("SELECT referral_reward_id FROM edu_user_coupon WHERE user_coupon_id=?",target).get("referralRewardId")),Map.of("reason","占用不可撤回")));
    tuition.confirmPayment(id(p.get("paymentId")),1,true,Map.of());assertEquals("USED",db.one("SELECT coupon_status FROM edu_user_coupon WHERE user_coupon_id=?",target).get("couponStatus"));
  }
  @Test void cancellationRefundAnomalyHistoryIsPreservedAndCannotAwardStaleRecord() {
    long old=parent();String code=paidCode(old);long record=qualified(old,code);var r=db.one("SELECT enrollment_id,payment_id FROM edu_referral_record WHERE record_id=?",record);long e=id(r.get("enrollmentId")),p=id(r.get("paymentId"));
    var refund=tuition.applyRefund(1,true,Map.of("paymentId",p,"refundKind","WITHDRAWAL","reason","核对退款异常","idempotencyKey",UUID.randomUUID().toString()));long rid=id(refund.get("refundId"));
    tuition.reviewRefund(rid,1,Map.of("approved",true,"approvedAmount","1000.00","version",0));tuition.markRefunded(rid,1,Map.of("version",1));
    long parent=id(db.one("SELECT parent_id FROM edu_enrollment WHERE enrollment_id=?",e).get("parentId"));referrals.refresh(parent);
    var anomaly=db.one("SELECT * FROM edu_referral_anomaly WHERE record_id=? LIMIT 1",record);assertEquals("OPEN",anomaly.get("state"));assertThrows(Exception.class,()->referrals.previewReward(1,grantBody(template(2),List.of(record),"BOTH")));
    referrals.resolve(1,id(anomaly.get("anomalyId")),Map.of("reason","已核对退课退款","result","未发奖励，无需追款"));referrals.refresh(parent);assertEquals("RESOLVED",db.one("SELECT state FROM edu_referral_anomaly WHERE anomaly_id=?",anomaly.get("anomalyId")).get("state"));
    assertEquals(2,db.jdbc().queryForObject("SELECT JSON_LENGTH(history) FROM edu_referral_anomaly WHERE anomaly_id=?",Integer.class,anomaly.get("anomalyId")));
  }
  long fullRefund(long payment) {
    var r=tuition.applyRefund(1,true,Map.of("paymentId",payment,"refundKind","WITHDRAWAL","reason","推荐边界核验","idempotencyKey",UUID.randomUUID().toString()));long refund=id(r.get("refundId"));
    tuition.reviewRefund(refund,1,Map.of("approved",true,"approvedAmount","1000.00","version",0));return refund;
  }
  @Test void failedNotifierCannotBlockPaymentAndCompensatesAfterFullRefund() throws Exception {
    long old=parent(),fresh=parent();String code=paidCode(old);referrals.bind(fresh,Map.of("code",code));
    db.jdbc().execute("CREATE TRIGGER test_qualification_failure BEFORE INSERT ON edu_referral_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected qualification failure'");
    long payment;
    try {payment=payment(fresh);}finally{db.jdbc().execute("DROP TRIGGER test_qualification_failure");}
    assertEquals("SUCCESS",db.one("SELECT payment_status FROM edu_tuition_payment WHERE payment_id=?",payment).get("paymentStatus"));
    assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record r JOIN edu_referral_binding b ON b.binding_id=r.binding_id WHERE b.invitee_id=?",Integer.class,fresh));
    Thread.sleep(10);long refund=fullRefund(payment);tuition.markRefunded(refund,1,Map.of("version",1));referrals.refresh(fresh);
    long record=record(fresh);assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_anomaly WHERE record_id=?",Integer.class,record));
    long late=parent();long latePay=payment(late);long lateRefund=fullRefund(latePay);tuition.markRefunded(lateRefund,1,Map.of("version",1));
    referrals.bind(late,Map.of("code",code));assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record r JOIN edu_referral_binding b ON b.binding_id=r.binding_id WHERE b.invitee_id=?",Integer.class,late));
  }
  @Test void concurrentAwardRetryAndNewBatchCannotDuplicateCoupon() throws Exception {
    long old=parent();long record=qualified(old,paidCode(old)),t=template(5);var body=grantBody(t,List.of(record),"BOTH");preview(body);var pool=Executors.newFixedThreadPool(4);
    try {var jobs=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->referrals.grant(1,body)));Object batch=null;for(var job:jobs){var r=job.get();if(batch==null)batch=r.get("batchId");assertEquals(batch,r.get("batchId"));}}
    finally{pool.shutdownNow();}
    var another=new HashMap<>(body);another.put("idempotencyKey",UUID.randomUUID().toString());assertThrows(Exception.class,()->referrals.grant(1,another));
    assertEquals(2,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_reward WHERE record_id=?",Integer.class,record));
  }
  @Test void grantWaitingForFinancialLockSeesNewlyCommittedRefund() throws Exception {
    long old=parent();long record=qualified(old,paidCode(old));var source=db.one("SELECT payment_id FROM edu_referral_record WHERE record_id=?",record);long refund=fullRefund(id(source.get("paymentId")));var body=grantBody(template(3),List.of(record),"BOTH");preview(body);
    var pool=Executors.newSingleThreadExecutor();var pending=new ArrayList<Future<?>>();
    try {
      db.transactions().execute(tx->{
        long connection=db.jdbc().queryForObject("SELECT CONNECTION_ID()",Long.class);db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=1 FOR UPDATE");
        pending.add(pool.submit(()->referrals.grant(1,body)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
        while(System.nanoTime()<deadline){waiting=db.jdbc().queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON t.thread_id=w.blocking_thread_id WHERE t.processlist_id=?",Integer.class,connection)>0;if(waiting)break;try{Thread.sleep(20);}catch(InterruptedException e){throw new RuntimeException(e);}}
        assertTrue(waiting,"grant must wait for the actual financial lock");tuition.markRefunded(refund,1,Map.of("version",1));return null;
      });
      assertThrows(ExecutionException.class,()->pending.get(0).get(10,TimeUnit.SECONDS));assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_reward WHERE record_id=?",Integer.class,record));
    }finally{pool.shutdownNow();}
  }
  @Test void productionIgnoresMockAndZeroReceiptsNeverQualify() {
    long mock=parent();payment(mock);var production=new ReferralService(db,coupons,false);assertFalse((Boolean)production.home(mock).get("eligible"));assertNull(production.home(mock).get("referralCode"));
    long free=parent(),bill=bill(free);var price=new HashMap<>(fixture.priceBody("MOCK"));price.put("originalAmount","0.00");tuition.price(bill,1,price);
    tuition.createPayment(bill,1,true,Map.of("channel","FREE","expectedPayableAmount","0.00","financialVersion",1,"idempotencyKey",UUID.randomUUID().toString()));assertFalse((Boolean)referrals.home(free).get("eligible"));assertNull(referrals.home(free).get("referralCode"));
  }
  @Test void productionCannotCompensateADevelopmentMockIntoNewQualification() throws Exception {
    long old=parent(),fresh=parent();String code=paidCode(old);referrals.bind(fresh,Map.of("code",code));
    var f=TuitionService.class.getDeclaredField("referrals");f.setAccessible(true);f.set(tuition,null);long p=payment(fresh);
    db.jdbc().update("UPDATE edu_referral_profile f JOIN edu_tuition_payment p ON p.payment_id=? SET f.first_payment_id=p.payment_id,f.first_confirmed_at=p.confirmed_time WHERE f.parent_id=?",p,fresh);
    var production=new ReferralService(db,coupons,false);production.refresh(fresh);
    assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record r JOIN edu_referral_binding b ON b.binding_id=r.binding_id WHERE b.invitee_id=?",Integer.class,fresh));
  }
  @Test void resolvedCancellationDoesNotHideASubsequentFullRefund() {
    long old=parent();long record=qualified(old,paidCode(old));var r=db.one("SELECT payment_id,enrollment_id FROM edu_referral_record WHERE record_id=?",record);
    long refund=fullRefund(id(r.get("paymentId")));
    long parent=id(db.one("SELECT parent_id FROM edu_enrollment WHERE enrollment_id=?",r.get("enrollmentId")).get("parentId"));
    db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='已取消',cancel_time=CURRENT_TIMESTAMP WHERE enrollment_id=?",r.get("enrollmentId"));referrals.refresh(parent);
    var cancelled=db.one("SELECT anomaly_id FROM edu_referral_anomaly WHERE record_id=? AND cause='CANCELLED'",record);
    referrals.resolve(1,id(cancelled.get("anomalyId")),Map.of("reason","已核查取消","result","等待财务处理"));
    tuition.markRefunded(refund,1,Map.of("version",1));referrals.refresh(parent);
    assertEquals("OPEN",db.one("SELECT state FROM edu_referral_anomaly WHERE record_id=? AND cause='FULL_REFUND'",record).get("state"));
    assertEquals("RESOLVED",db.one("SELECT state FROM edu_referral_anomaly WHERE anomaly_id=?",cancelled.get("anomalyId")).get("state"));
  }
}
