package com.ruoyi.system.finance;

import static com.ruoyi.system.finance.FinanceRules.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.shop.ShopRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Referral relationships are separate from qualified recommendations and coupon rewards. */
@Service
public class ReferralService {
  private final ShopRepository db;
  private final CouponService coupons;
  private final boolean mockEnabled;
  private Clock clock = Clock.system(ZONE);
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReferralService.class);

  @Autowired public ReferralService(ShopRepository db,CouponService coupons,Environment env) {
    this(db,coupons,mockAllowed(env.getActiveProfiles(),env.getProperty("payment.mode","disabled")));
  }
  public ReferralService(ShopRepository db,CouponService coupons,boolean mockEnabled) {
    this.db=db;this.coupons=coupons;this.mockEnabled=mockEnabled;
  }
  private Timestamp instant() { return Timestamp.valueOf(LocalDateTime.now(clock)); }
  private <T> T locked(Supplier<T> work) {
    for(int n=0;;n++) try {
      var transaction=new org.springframework.transaction.support.TransactionTemplate(db.transactions().getTransactionManager());
      transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      // Financial mutations do not take the referral lock. Read their latest committed facts
      // after waiting for schedule/enrollment/payment locks, including successful refunds.
      transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
      return transaction.execute(tx->{db.one("SELECT program_id FROM edu_referral_program WHERE program_id=1 FOR UPDATE");return work.get();});
    } catch(PessimisticLockingFailureException e) { if(n>=2) throw e; }
  }
  private void parent(long id) {
    require(db.jdbc().queryForObject("SELECT COUNT(*) FROM sys_user u WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='parent' AND r.status='0' AND r.del_flag='0')",Integer.class,id)>0,"FORBIDDEN: 请使用正常家长账号");
  }
  private String event(String action,long actor,String reason,Map<String,Object> extra) {
    var e=new LinkedHashMap<String,Object>(extra);e.put("action",action);e.put("actorId",String.valueOf(actor));e.put("reason",reason);e.put("time",instant().toLocalDateTime().toString());return JSON.toJSONString(e);
  }
  private String event(String action,long actor,String reason) { return event(action,actor,reason,Map.of()); }
  private String facts() { return "e.schedule_id IS NOT NULL AND p.payment_status='SUCCESS' AND p.confirmed_by IS NOT NULL AND p.confirmed_time IS NOT NULL AND p.amount>0 AND p.finance_mode IN ("+(mockEnabled?"'REAL','MOCK'":"'REAL'")+")"; }
  private boolean hasReceipt(long parent) {return db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_tuition_payment p JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE e.parent_id=? AND "+facts(),Long.class,parent)>0;}
  private Map<String,Object> profile(long parent) {
    db.jdbc().update("INSERT INTO edu_referral_profile(parent_id) SELECT ? WHERE NOT EXISTS(SELECT 1 FROM edu_referral_profile WHERE parent_id=?)",parent,parent);
    // Historical verification is not a new customer's first payment, even when entered today.
    if(db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_tuition_payment p JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE e.parent_id=? AND p.record_source='HISTORY_VERIFIED'",Integer.class,parent)>0)
      db.jdbc().update("UPDATE edu_referral_profile SET historical_customer=1 WHERE parent_id=?",parent);
    if(db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment WHERE parent_id=? AND billing_status='HISTORY_PENDING' AND pay_status IN('1','2','已支付','已退款','部分退款')",Integer.class,parent)>0)
      db.jdbc().update("UPDATE edu_referral_profile SET historical_customer=1 WHERE parent_id=?",parent);
    var p=db.one("SELECT * FROM edu_referral_profile WHERE parent_id=? FOR UPDATE",parent);
    if(p.get("firstPaymentId")==null) {
      var first=db.rows("SELECT p.payment_id FROM edu_tuition_payment p JOIN edu_enrollment e ON e.enrollment_id=p.enrollment_id WHERE e.parent_id=? AND "+facts()+" ORDER BY p.confirmed_time,p.payment_id LIMIT 1",parent);
      if(!first.isEmpty()) db.jdbc().update("UPDATE edu_referral_profile f JOIN edu_tuition_payment p ON p.payment_id=? SET f.first_payment_id=p.payment_id,f.first_confirmed_at=p.confirmed_time WHERE f.parent_id=? AND f.first_payment_id IS NULL",first.get(0).get("paymentId"),parent);
    }
    p=db.one("SELECT * FROM edu_referral_profile WHERE parent_id=?",parent);
    if(p.get("firstPaymentId")!=null && p.get("referralCode")==null) {
      db.jdbc().update("UPDATE edu_referral_profile SET referral_code=? WHERE parent_id=? AND referral_code IS NULL",UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT),parent);
      p=db.one("SELECT * FROM edu_referral_profile WHERE parent_id=?",parent);
    }
    return p;
  }
  private Timestamp firstTime(long parent) {
    return db.jdbc().queryForObject("SELECT first_confirmed_at FROM edu_referral_profile WHERE parent_id=?",Timestamp.class,parent);
  }
  private void canBind(long parent,Map<String,Object> profile) {
    require(((Number)profile.get("historicalCustomer")).intValue()==0,"历史已缴费家长不具备新人补绑资格");
    Timestamp first=firstTime(parent);
    require(first==null || !instant().after(Timestamp.valueOf(first.toLocalDateTime().plusDays(7))),"首次确认收款后7天补绑期限已过");
  }
  private long referrer(String code,long invitee) {
    require(code!=null && code.trim().matches("[A-Za-z0-9]{16}"),"请输入有效推荐码");
    var p=db.one("SELECT parent_id FROM edu_referral_profile WHERE referral_code=?",code.trim().toUpperCase(Locale.ROOT));long who=id(p.get("parentId"));
    require(who!=invitee,"不能绑定自己的推荐码");parent(who);
    profile(who);require(hasReceipt(who),"推荐人尚未满足后台确认收款资格");
    return who;
  }
  private void noCycle(long invitee,long referrer) {
    var seen=new HashSet<Long>();long current=referrer;
    for(int i=0;i<10000;i++) {
      require(current!=invitee && seen.add(current),"不能建立循环推荐关系");
      var links=db.rows("SELECT referrer_id FROM edu_referral_binding WHERE invitee_id=?",current);
      if(links.isEmpty()) return;current=id(links.get(0).get("referrerId"));
    }
    throw new com.ruoyi.common.exception.ServiceException("推荐关系过深，请联系管理员核查");
  }
  private String maskedName(long parent) {
    // 口径同 FinanceRules.parentNameSql：真名优先，避免把「王文晶」这类老师昵称当成家长名脱敏显示
    var u=db.one("SELECT "+FinanceRules.parentNameSql("u")+" parent_name FROM sys_user u WHERE u.user_id=?",parent);
    String name=str(u,"parentName");return name.isEmpty()?"家长":name.substring(0,1)+"**";
  }
  public Map<String,Object> home(long parent) {
    parent(parent);
    return locked(()->{
      var p=profile(parent);reconcile(parent);
      Timestamp first=firstTime(parent);var out=new LinkedHashMap<String,Object>();
      boolean eligible=hasReceipt(parent);out.put("referralCode",eligible?p.get("referralCode"):null);out.put("eligible",eligible);out.put("historicalCustomer",p.get("historicalCustomer"));
      var binding=db.rows("SELECT binding_id,referrer_id,bound_at,locked_at,version FROM edu_referral_binding WHERE invitee_id=?",parent);
      out.put("binding",binding.isEmpty()?null:binding.get(0));
      if(!binding.isEmpty()) binding.get(0).put("referrerName",maskedName(id(binding.get(0).get("referrerId"))));
      long remaining=first==null?0:Math.max(0,Duration.between(instant().toLocalDateTime(),first.toLocalDateTime().plusDays(7)).getSeconds());
      out.put("remainingSeconds",remaining);out.put("bindDeadline",first==null?null:first.toLocalDateTime().plusDays(7).toString());
      out.put("canBind",binding.isEmpty() && ((Number)p.get("historicalCustomer")).intValue()==0 && (first==null || !instant().after(Timestamp.valueOf(first.toLocalDateTime().plusDays(7)))));
      return out;
    });
  }
  public Map<String,Object> previewCode(long parent,String code) {
    parent(parent);return locked(()->{var p=profile(parent);canBind(parent,p);require(db.rows("SELECT binding_id FROM edu_referral_binding WHERE invitee_id=?",parent).isEmpty(),"已绑定推荐人，不可自行更换");long who=referrer(code,parent);noCycle(parent,who);return Map.of("referrerId",String.valueOf(who),"referrerName",maskedName(who));});
  }
  public Map<String,Object> bind(long parent,Map<String,Object> body) {
    parent(parent);return locked(()->{
      var p=profile(parent);long who=referrer(str(body,"code"),parent);
      var old=db.rows("SELECT * FROM edu_referral_binding WHERE invitee_id=?",parent);
      if(!old.isEmpty()) {require(id(old.get(0).get("referrerId"))==who,"已绑定推荐人，不可自行更换");reconcile(parent);return Map.of("bindingId",old.get(0).get("bindingId"));}
      canBind(parent,p);noCycle(parent,who);
      long binding=db.insert("edu_referral_binding",values("invitee_id",parent,"referrer_id",who,"locked_at",firstTime(parent)==null?null:instant(),"history","["+event("BIND",parent,"家长确认绑定",Map.of("referrerId",String.valueOf(who)))+"]"));
      reconcile(parent);canBind(parent,profile(parent));return Map.of("bindingId",String.valueOf(binding));
    });
  }
  public Map<String,Object> correct(long actor,long binding,Map<String,Object> body) {
    coupons.admin(actor);String reason=reason(body,"reason",255);
    return locked(()->{
      var b=db.one("SELECT * FROM edu_referral_binding WHERE binding_id=? FOR UPDATE",binding);long invitee=id(b.get("inviteeId"));parent(invitee);profile(invitee);
      require(firstTime(invitee)==null && b.get("lockedAt")==null,"首次确认收款后关系已锁定，不可更改");
      require(integer(body.get("version"),1,Integer.MAX_VALUE)==((Number)b.get("version")).intValue(),"绑定已变化，请刷新");
      long who=referrer(str(body,"code"),invitee);noCycle(invitee,who);
      db.jdbc().update("UPDATE edu_referral_binding SET referrer_id=?,version=version+1,history=JSON_ARRAY_APPEND(history,'$',CAST(? AS JSON)),update_time=CURRENT_TIMESTAMP(3) WHERE binding_id=?",who,event("CORRECT",actor,reason,Map.of("fromReferrerId",b.get("referrerId"),"referrerId",String.valueOf(who))),binding);
      profile(invitee);require(firstTime(invitee)==null,"首次确认收款后关系已锁定，不可更改");
      return Map.of("bindingId",String.valueOf(binding));
    });
  }
  private String invalid(Map<String,Object> record,boolean lock) {
    long enrollment=id(record.get("enrollmentId")),payment=id(record.get("paymentId"));
    if(lock) {
      var course=db.one("SELECT schedule_id FROM edu_enrollment WHERE enrollment_id=?",enrollment);
      if(course.get("scheduleId")!=null)db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",course.get("scheduleId"));
    }
    var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?"+(lock?" FOR UPDATE":""),enrollment);
    var p=db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?"+(lock?" FOR UPDATE":""),payment);
    if(!"SUCCESS".equals(p.get("paymentStatus")) || p.get("confirmedBy")==null || p.get("confirmedTime")==null || money(p.get("amount")).signum()<=0 || (!mockEnabled && !"REAL".equals(p.get("financeMode")))) return "PAYMENT_INVALID";
    var refunded=db.jdbc().queryForObject("SELECT COALESCE(SUM(approved_amount),0) FROM edu_tuition_refund WHERE payment_id=? AND refund_status='SUCCESS'",java.math.BigDecimal.class,payment);
    if(refunded.compareTo(money(p.get("amount")))>=0)return "FULL_REFUND";
    if(!Set.of("1","报名成功","已确认").contains(str(e,"enrollmentStatus")) || !"0".equals(e.get("delFlag")) || !"0".equals(e.get("status"))) return "CANCELLED";
    return "";
  }
  private void reconcile(long invitee) {
    var p=profile(invitee);var links=db.rows("SELECT * FROM edu_referral_binding WHERE invitee_id=?",invitee);if(links.isEmpty())return;var b=links.get(0);
    if(firstTime(invitee)!=null && b.get("lockedAt")==null) db.jdbc().update("UPDATE edu_referral_binding SET locked_at=?,update_time=CURRENT_TIMESTAMP(3) WHERE binding_id=?",instant(),b.get("bindingId"));
    var records=db.rows("SELECT * FROM edu_referral_record WHERE binding_id=?",b.get("bindingId"));
    if(records.isEmpty() && p.get("firstPaymentId")!=null && ((Number)p.get("historicalCustomer")).intValue()==0) {
      var fact=db.one("SELECT p.payment_id,p.enrollment_id,p.record_source,p.finance_mode FROM edu_tuition_payment p WHERE p.payment_id=?",p.get("firstPaymentId"));
      Timestamp bound=db.jdbc().queryForObject("SELECT bound_at FROM edu_referral_binding WHERE binding_id=?",Timestamp.class,b.get("bindingId"));
      Timestamp qualified=bound.after(firstTime(invitee))?bound:firstTime(invitee);
      if(!"HISTORY_VERIFIED".equals(fact.get("recordSource")) && (mockEnabled || "REAL".equals(fact.get("financeMode"))) && qualifiedAt(fact,qualified)) {
        db.insert("edu_referral_record",values("binding_id",b.get("bindingId"),"enrollment_id",fact.get("enrollmentId"),"payment_id",fact.get("paymentId"),"qualified_at",qualified));
        records=db.rows("SELECT * FROM edu_referral_record WHERE binding_id=?",b.get("bindingId"));
      }
    }
    for(var r:records) {
      String cause=invalid(r,false);if(cause.isEmpty())continue;
      if(db.rows("SELECT anomaly_id FROM edu_referral_anomaly WHERE record_id=? AND cause=?",r.get("recordId"),cause).isEmpty())
        db.insert("edu_referral_anomaly",values("record_id",r.get("recordId"),"cause",cause,"history","["+event("DETECTED",0,cause)+"]"));
    }
  }
  /** Reconstruct a missed qualification from persisted finance/cancellation timestamps.
   * A binding made after cancellation/full refund must never create a new qualification. */
  private boolean qualifiedAt(Map<String,Object> fact,Timestamp at) {
    var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",fact.get("enrollmentId"));
    var payment=db.one("SELECT bill_snapshot FROM edu_tuition_payment WHERE payment_id=?",fact.get("paymentId"));
    Boolean atConfirmation=JSON.parseObject(str(payment,"billSnapshot")).getBoolean("confirmationEnrollmentActive");
    Timestamp confirmed=db.jdbc().queryForObject("SELECT confirmed_time FROM edu_tuition_payment WHERE payment_id=?",Timestamp.class,fact.get("paymentId"));
    boolean active=Set.of("1","报名成功","已确认").contains(str(e,"enrollmentStatus")) && "0".equals(e.get("delFlag")) && "0".equals(e.get("status"));
    if(at.equals(confirmed) && atConfirmation!=null){if(!atConfirmation)return false;active=true;}
    if(!active) {
      Timestamp cancelled=db.jdbc().queryForObject("SELECT cancel_time FROM edu_enrollment WHERE enrollment_id=?",Timestamp.class,fact.get("enrollmentId"));
      if(cancelled==null || !cancelled.after(at))return false;
    }
    var refunded=db.jdbc().queryForObject("SELECT COALESCE(SUM(approved_amount),0) FROM edu_tuition_refund WHERE payment_id=? AND refund_status='SUCCESS' AND (completed_time IS NULL OR completed_time<=?)",java.math.BigDecimal.class,fact.get("paymentId"),at);
    var amount=db.jdbc().queryForObject("SELECT amount FROM edu_tuition_payment WHERE payment_id=?",java.math.BigDecimal.class,fact.get("paymentId"));
    return refunded.compareTo(amount)<0;
  }
  public void refresh(long parent) { locked(()->{reconcile(parent);return null;}); }
  public void afterFinancialChange(long enrollment) {
    Runnable run=()->{try {long parent=db.jdbc().queryForObject("SELECT parent_id FROM edu_enrollment WHERE enrollment_id=?",Long.class,enrollment);refresh(parent);}catch(Exception e){LOG.warn("推荐核验延后补偿 enrollmentId={}, type={}",enrollment,e.getClass().getSimpleName());}};
    if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){run.run();}});
    else run.run();
  }
  private long sweepCursor;
  @Scheduled(fixedDelay=30000) public synchronized void compensate() {
    try {
      var batch=db.rows("SELECT invitee_id FROM edu_referral_binding WHERE invitee_id>? ORDER BY invitee_id LIMIT 200",sweepCursor);
      if(batch.isEmpty()) {sweepCursor=0;return;}
      for(var b:batch) {long parent=id(b.get("inviteeId"));try {refresh(parent);}catch(Exception e){LOG.warn("推荐补偿将重试 parentId={}, type={}",parent,e.getClass().getSimpleName());}sweepCursor=parent;}
    }catch(Exception e){LOG.warn("推荐补偿暂不可用 type={}",e.getClass().getSimpleName());}
  }
  private static final String RELATIONS=" FROM edu_referral_binding b JOIN sys_user n ON n.user_id=b.invitee_id JOIN sys_user o ON o.user_id=b.referrer_id ";
  /** 邀请人/被邀请人的展示名，口径同 FinanceRules.parentNameSql（真名优先，非中文名退回中文昵称）。 */
  private static final String PEOPLE_NAMES=
      FinanceRules.parentNameSql("n")+" invitee_name,"+FinanceRules.parentNameSql("o")+" referrer_name";
  public Map<String,Object> list(long actor,boolean admin,Map<String,String> q) {
    if(admin)coupons.admin(actor);else parent(actor);
    String kind=q.getOrDefault("kind","bindings"),select,from,order;
    switch(kind) {
      case "bindings" -> {select="b.*,"+PEOPLE_NAMES+",IF(r.record_id IS NULL,'WAITING',r.review_status) recommendation_status";from=RELATIONS+" LEFT JOIN edu_referral_record r ON r.binding_id=b.binding_id ";order="b.update_time DESC,b.binding_id DESC";}
      case "records" -> {select="r.*,b.invitee_id,b.referrer_id,"+PEOPLE_NAMES+"";from=RELATIONS+" JOIN edu_referral_record r ON r.binding_id=b.binding_id ";order="r.update_time DESC,r.record_id DESC";}
      case "rewards" -> {select="w.*,c.update_time,b.binding_id,b.invitee_id,b.referrer_id,"+PEOPLE_NAMES+",c.user_coupon_id,c.coupon_status,t.coupon_name,t.discount_amount";from=RELATIONS+" JOIN edu_referral_record r ON r.binding_id=b.binding_id JOIN edu_referral_reward w ON w.record_id=r.record_id JOIN edu_user_coupon c ON c.referral_reward_id=w.reward_id JOIN edu_coupon_template t ON t.template_id=w.template_id ";order="c.update_time DESC,w.reward_id DESC";}
      case "anomalies" -> {require(admin,"FORBIDDEN: 仅管理员可查看异常");select="a.*,b.binding_id,b.invitee_id,b.referrer_id,"+PEOPLE_NAMES+"";from=RELATIONS+" JOIN edu_referral_record r ON r.binding_id=b.binding_id JOIN edu_referral_anomaly a ON a.record_id=r.record_id ";order="a.update_time DESC,a.anomaly_id DESC";}
      default -> throw new com.ruoyi.common.exception.ServiceException("查询类别无效");
    }
    if(admin)select+=",n.phonenumber invitee_phone,o.phonenumber referrer_phone"+(kind.equals("rewards")?",(SELECT phonenumber FROM sys_user recipient WHERE recipient.user_id=w.parent_id) parent_phone":"");
    String where=" WHERE 1=1";var args=new ArrayList<Object>();
    if(!admin) {where+=kind.equals("rewards")?" AND w.parent_id=?":" AND (b.invitee_id=? OR b.referrer_id=?)";args.add(actor);if(!kind.equals("rewards"))args.add(actor);}
    String relationRole=str(q,"recipientRole");require(Set.of("","NEW","OLD").contains(relationRole),"新老角色无效");
    if(kind.equals("rewards")) {
      if(!str(q,"parentId").isEmpty()){where+=" AND w.parent_id=?";args.add(id(q.get("parentId")));}
      if(!relationRole.isEmpty()){where+=" AND w.recipient_role=?";args.add(relationRole);}
    } else if(!str(q,"parentId").isEmpty()) {long p=id(q.get("parentId"));where+=relationRole.equals("NEW")?" AND b.invitee_id=?":relationRole.equals("OLD")?" AND b.referrer_id=?":" AND (b.invitee_id=? OR b.referrer_id=?)";args.add(p);if(relationRole.isEmpty())args.add(p);}
    if(admin&&!str(q,"parentPhone").isBlank()){
      String phone=CouponService.phone(q.get("parentPhone"))+"%";
      where+=kind.equals("rewards")?" AND EXISTS(SELECT 1 FROM sys_user recipient WHERE recipient.user_id=w.parent_id AND recipient.phonenumber LIKE ?)":relationRole.equals("NEW")?" AND n.phonenumber LIKE ?":relationRole.equals("OLD")?" AND o.phonenumber LIKE ?":" AND (n.phonenumber LIKE ? OR o.phonenumber LIKE ?)";
      args.add(phone);if(!kind.equals("rewards")&&relationRole.isEmpty())args.add(phone);
    }
    if(!str(q,"state").isEmpty()) {require(Set.of("PENDING","REWARDED","OPEN","RESOLVED","WAITING").contains(q.get("state")),"状态无效");where+=kind.equals("anomalies")?" AND a.state=?":kind.equals("bindings")?" AND IF(r.record_id IS NULL,'WAITING',r.review_status)=?":" AND r.review_status=?";args.add(q.get("state"));}
    return CouponService.page(db,"SELECT "+select+from+where+" ORDER BY "+order,"SELECT COUNT(*)"+from+where,args,q);
  }
  public Map<String,Object> counts(long actor) {
    coupons.admin(actor);return Map.of("pending",db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_record WHERE review_status='PENDING'",Long.class),"anomalies",db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_referral_anomaly WHERE state='OPEN'",Long.class));
  }
  private List<Map<String,Object>> recipients(Map<String,Object> body,boolean lock) {
    require(body.get("recordIds") instanceof Collection<?>,"请选择达标推荐记录");
    var ids=new TreeSet<Long>();for(var value:(Collection<?>)body.get("recordIds"))ids.add(id(value));require(!ids.isEmpty() && ids.size()<=100,"请选择1至100条推荐记录");
    String role=str(body,"recipientRole");require(Set.of("NEW","OLD","BOTH").contains(role),"请选择新用户、老用户或双方");
    var selected=new TreeSet<Long>();if(body.containsKey("parentIds")) {require(body.get("parentIds") instanceof Collection<?>,"接收人无效");for(var p:(Collection<?>)body.get("parentIds"))selected.add(id(p));require(!selected.isEmpty(),"请选择接收人");}
    var result=new ArrayList<Map<String,Object>>();long template=id(body.get("templateId"));
    if(lock) {
      // Lock all selected financial objects in the same order as the finance service.
      var schedules=new TreeSet<Long>();var enrollments=new TreeSet<Long>();var payments=new TreeSet<Long>();
      for(long record:ids) {var r=db.one("SELECT r.enrollment_id,r.payment_id,e.schedule_id FROM edu_referral_record r JOIN edu_enrollment e ON e.enrollment_id=r.enrollment_id WHERE r.record_id=?",record);if(r.get("scheduleId")!=null)schedules.add(id(r.get("scheduleId")));enrollments.add(id(r.get("enrollmentId")));payments.add(id(r.get("paymentId")));}
      for(long s:schedules)db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",s);
      for(long e:enrollments)db.one("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",e);
      for(long p:payments)db.one("SELECT payment_id FROM edu_tuition_payment WHERE payment_id=? FOR UPDATE",p);
    }
    for(long record:ids) {
      var r=db.one("SELECT r.*,b.invitee_id,b.referrer_id,b.locked_at FROM edu_referral_record r JOIN edu_referral_binding b ON b.binding_id=r.binding_id WHERE r.record_id=?",record);
      require(invalid(r,lock).isEmpty(),"推荐资格已变化：报名取消、退款或收款无效，请先核查异常");
      parent(id(r.get("inviteeId")));parent(id(r.get("referrerId")));
      require(hasReceipt(id(r.get("referrerId"))),"推荐人收款资格无效，请核查");
      require(((Number)profile(id(r.get("inviteeId"))).get("historicalCustomer")).intValue()==0,"历史家长不能获得首次推荐奖励");
      for(String side:role.equals("BOTH")?List.of("NEW","OLD"):List.of(role)) {
        long parent=id(r.get(side.equals("NEW")?"inviteeId":"referrerId"));if(body.containsKey("parentIds") && !selected.contains(parent))continue;
        if(!db.rows("SELECT reward_id FROM edu_referral_reward WHERE record_id=? AND parent_id=? AND template_id=?",record,parent,template).isEmpty())continue;
        result.add(values("recordId",String.valueOf(record),"parentId",String.valueOf(parent),"recipientRole",side,"parentName",maskedName(parent),"parentPhone",str(db.one("SELECT phonenumber FROM sys_user WHERE user_id=?",parent),"phonenumber")));
      }
    }
    require(!result.isEmpty() && result.size()<=100,"没有可发奖励，或本批超过100张券；已发的同记录同模板奖励不会重复发放");
    if(body.containsKey("parentIds"))require(result.stream().map(r->id(r.get("parentId"))).collect(java.util.stream.Collectors.toSet()).containsAll(selected),"接收人不在所选可发奖励范围，请重新预览");
    return result;
  }
  private Map<String,Object> rewardPreview(Map<String,Object> body,List<Map<String,Object>> items) {
    long template=id(body.get("templateId"));var t=db.one("SELECT * FROM edu_coupon_template WHERE template_id=?",template);
    require("ACTIVE".equals(t.get("templateStatus")) && time(t.get("validUntil")).after(instant()),"模板未启用或已过期");
    require(((Number)t.get("totalQuantity")).intValue()-((Number)t.get("issuedQuantity")).intValue()>=items.size(),"优惠券发行额度不足");
    var people=new LinkedHashMap<String,Map<String,Object>>();for(var item:items)people.put(str(item,"parentId"),Map.of("parentId",item.get("parentId"),"parentName",item.get("parentName"),"parentPhone",item.get("parentPhone")));
    String previewHash=hash(Map.of("items",items.stream().map(i->i.get("recordId")+":"+i.get("parentId")+":"+i.get("recipientRole")).sorted().toList(),"templateId",String.valueOf(template),"templateVersion",t.get("version")));
    return Map.of("items",items,"recipients",people.values(),"recipientCount",people.size(),"couponCount",items.size(),"template",t,"previewHash",previewHash);
  }
  public Map<String,Object> previewReward(long actor,Map<String,Object> body) {
    coupons.admin(actor);return locked(()->rewardPreview(body,recipients(body,false)));
  }
  public Map<String,Object> grant(long actor,Map<String,Object> body) {
    coupons.admin(actor);String key=key(body),reason=reason(body,"reason",255);var canonical=new TreeMap<String,Object>(body);
    for(String field:List.of("recordIds","parentIds"))if(body.get(field) instanceof Collection<?> c)canonical.put(field,c.stream().map(FinanceRules::id).distinct().sorted().toList());
    String fingerprint=hash(canonical);
    return locked(()->{
      var batches=db.rows("SELECT * FROM edu_referral_reward_batch WHERE actor_id=? AND request_key=?",actor,key);
      if(!batches.isEmpty()) {require(fingerprint.equals(batches.get(0).get("requestHash")),"IDEMPOTENCY_CONFLICT: 原批次内容已变化");return batchResult(id(batches.get(0).get("batchId")));}
      var items=recipients(body,true);var preview=rewardPreview(body,items);require(preview.get("previewHash").equals(body.get("previewHash")),"预览已变化，请重新核对接收名单与模板");long template=id(body.get("templateId"));
      long batch=db.insert("edu_referral_reward_batch",values("actor_id",actor,"request_key",key,"request_hash",fingerprint));var sources=new ArrayList<CouponService.ReferralRecipient>();
      for(var item:items) {
        long record=id(item.get("recordId")),parent=id(item.get("parentId"));
        long reward=db.insert("edu_referral_reward",values("record_id",record,"parent_id",parent,"template_id",template,"recipient_role",item.get("recipientRole"),"batch_id",batch,"actor_id",actor,"reason",reason,"history","["+event("GRANT",actor,reason)+"]"));
        sources.add(new CouponService.ReferralRecipient(reward,parent));
        db.jdbc().update("UPDATE edu_referral_record SET review_status='REWARDED',reviewed_by=?,reviewed_at=?,update_time=CURRENT_TIMESTAMP(3) WHERE record_id=?",actor,instant(),record);
      }
      coupons.issueReferral(template,actor,Map.of("parentIds",sources.stream().map(CouponService.ReferralRecipient::parentId).distinct().toList(),"reason",reason,"idempotencyKey","REFERRAL-BATCH-"+batch),sources);
      return batchResult(batch);
    });
  }
  private Map<String,Object> batchResult(long batch) {var rows=db.rows("SELECT w.*,c.user_coupon_id,c.coupon_status FROM edu_referral_reward w JOIN edu_user_coupon c ON c.referral_reward_id=w.reward_id WHERE w.batch_id=? ORDER BY w.reward_id",batch);return Map.of("rows",rows,"total",rows.size(),"batchId",String.valueOf(batch));}
  public Map<String,Object> revoke(long actor,long reward,Map<String,Object> body) {
    coupons.admin(actor);return locked(()->{var c=db.one("SELECT user_coupon_id FROM edu_user_coupon WHERE referral_reward_id=?",reward);return coupons.revoke(id(c.get("userCouponId")),actor,body);});
  }
  public Map<String,Object> resolve(long actor,long anomaly,Map<String,Object> body) {
    coupons.admin(actor);String reason=reason(body,"reason",255),result=reason(body,"result",255);
    return locked(()->{var a=db.one("SELECT * FROM edu_referral_anomaly WHERE anomaly_id=? FOR UPDATE",anomaly);require("OPEN".equals(a.get("state")),"异常已处理，请刷新");db.jdbc().update("UPDATE edu_referral_anomaly SET state='RESOLVED',history=JSON_ARRAY_APPEND(history,'$',CAST(? AS JSON)),update_time=CURRENT_TIMESTAMP(3) WHERE anomaly_id=?",event("RESOLVE",actor,reason,Map.of("result",result)),anomaly);return Map.of("anomalyId",String.valueOf(anomaly));});
  }
}
