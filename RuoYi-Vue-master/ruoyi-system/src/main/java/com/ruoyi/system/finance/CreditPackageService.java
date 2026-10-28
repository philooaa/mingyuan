package com.ruoyi.system.finance;

import static com.ruoyi.system.finance.FinanceRules.*;
import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.shop.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** New package purchases use existing bills, coupons, child accounts and credit events, not legacy package tables. */
@Service
public class CreditPackageService {
  public static final String TYPE="CREDIT_PACKAGE";
  private final ShopRepository db;
  private final CouponService coupons;
  public CreditPackageService(ShopRepository db,CouponService coupons){this.db=db;this.coupons=coupons;}
  public static boolean isPackage(Map<String,?> bill){return TYPE.equals(bill.get("billType"));}
  @SuppressWarnings("unchecked") public static Map<String,Object> snapshot(Map<String,?> bill){
    Object raw=bill.get("packageSnapshot");
    if(raw instanceof Map<?,?> map)return (Map<String,Object>)map;
    require(raw!=null,"课包价格快照缺失，请联系管理员核对");return JSON.parseObject(raw.toString());
  }
  public static BigDecimal bulkDiscount(Map<String,?> bill){return isPackage(bill)?money(snapshot(bill).get("packageDiscount")):money("0");}
  public static Map<String,Object> couponBase(Map<String,Object> bill){
    if(!isPackage(bill))return bill;
    var copy=new HashMap<>(bill);copy.put("originalAmount",money(bill.get("originalAmount")).subtract(bulkDiscount(bill)).toPlainString());return copy;
  }
  private long parent(){require(SecurityUtils.hasRole("parent"),"请以家长身份购买课包");return SecurityUtils.getUserId();}
  private Map<String,Object> child(long account,long actor,boolean lock){
    return db.one("SELECT a.account_id,a.parent_id,a.student_id,st.student_name,st.grade_code,st.student_phone,(SELECT d.dict_label FROM sys_dict_data d WHERE d.dict_type='edu_grade' AND d.dict_value=st.grade_code ORDER BY d.status,d.dict_code LIMIT 1) AS grade_name FROM edu_student_lesson_account a JOIN edu_student st ON st.student_id=a.student_id AND st.parent_id=a.parent_id JOIN sys_user u ON u.user_id=a.parent_id WHERE a.account_id=? AND a.parent_id=? AND st.del_flag='0' AND u.status='0' AND u.del_flag='0'"+(lock?" FOR UPDATE":""),account,actor);
  }
  public Map<String,Object> catalog(){return values("creditsPerPackage",15,"prices",List.of(values("label","小学","unitPrice","400.00"),values("label","初一、初二","unitPrice","500.00"),values("label","初三","unitPrice","600.00"),values("label","高一、高二","unitPrice","700.00"),values("label","高三","unitPrice","800.00")),"discounts",List.of(values("label","1包95折","rate","0.95"),values("label","2包92折","rate","0.92"),values("label","3包及以上9折","rate","0.90")),"notice","每包15基础次数。每节2小时，精英扣1、资深扣2。按本次购买包数折扣后抵扣适用通用券，线下确认收款后入账。");}
  private Map<String,Object> quote(Map<String,Object> b,long actor,boolean lock){
    long account=id(b.get("accountId"));int count=integer(b.get("packageCount"),1,100);
    var c=child(account,actor,lock);String grade=str(c,"gradeCode");require(grade.matches("g(?:[1-9]|1[0-2])"),"孩子年级尚未完善，请先更新档案");
    int n=Integer.parseInt(grade.substring(1)), unit=n<=6?400:n<=8?500:n==9?600:n<=11?700:800;
    var gross=money(Integer.toString(unit)).multiply(BigDecimal.valueOf(count*15L));
    var rate=new BigDecimal(count==1?"0.95":count==2?"0.92":"0.90");
    var subtotal=gross.multiply(rate).setScale(2);var bulk=gross.subtract(subtotal);
    var bill=values("billType",TYPE,"parentId",actor,"originalAmount",gross.toPlainString(),"packageSnapshot",values("packageDiscount",bulk.toPlainString()),"scheduleId",null);
    Long coupon=str(b,"userCouponId").isEmpty()?null:id(b.get("userCouponId"));
    var off=coupon==null?money("0"):coupons.discount(coupon,bill,lock);
    var result=values("pricingVersion","credit-package-v1","accountId",Long.toString(account),"studentId",c.get("studentId"),"studentName",c.get("studentName"),"gradeCode",grade,"gradeName",str(c,"gradeName").isEmpty()?grade:c.get("gradeName"),"packageCount",count,"credits",count*15,"unitPrice",money(Integer.toString(unit)).toPlainString(),"grossAmount",gross.toPlainString(),"packageDiscount",bulk.toPlainString(),"rate",rate.toPlainString(),"subtotal",subtotal.toPlainString(),"userCouponId",coupon==null?null:coupon.toString(),"couponDiscount",off.toPlainString(),"payableAmount",subtotal.subtract(off).toPlainString());
    result.put("quoteHash",hash(result));result.put("coupons",coupons.available(bill));return result;
  }
  public Map<String,Object> quote(Map<String,Object> b){return quote(b,parent(),false);}
  private String requestHash(Map<String,Object> b){return hash(values("accountId",Long.toString(id(b.get("accountId"))),"packageCount",integer(b.get("packageCount"),1,100),"userCouponId",str(b,"userCouponId").isEmpty()?"":Long.toString(id(b.get("userCouponId"))),"expectedPayableAmount",money(b.get("expectedPayableAmount")).toPlainString(),"quoteHash",str(b,"quoteHash")));}
  public long create(Map<String,Object> b){
    long actor=parent();String key=key(b), fingerprint=requestHash(b);
    return db.transactions().execute(tx->{
      db.one("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",actor);
      var previous=db.rows("SELECT * FROM edu_enrollment WHERE parent_id=? AND package_request_key=?",actor,key);
      if(!previous.isEmpty()){var old=previous.get(0);require(fingerprint.equals(snapshot(old).get("requestHash")),"IDEMPOTENCY_CONFLICT: 购买请求编号已使用，请核对原订单");return id(old.get("enrollmentId"));}
      var q=quote(b,actor,true);require(str(q,"quoteHash").equals(str(b,"quoteHash")) && money(q.get("payableAmount")).compareTo(money(b.get("expectedPayableAmount")))==0,"BILL_VERSION_CONFLICT: 孩子年级、价格或优惠券已变化，请重新试算");
      var snap=new LinkedHashMap<>(q);snap.remove("coupons");snap.put("requestHash",fingerprint);
      var v=values("enrollment_code","PK"+UUID.randomUUID().toString().replace("-","").substring(0,30),"bill_type",TYPE,"schedule_id",null,"parent_id",actor,"student_id",q.get("studentId"),"lesson_account_id",q.get("accountId"),"student_name",q.get("studentName"),"enrollment_status","0","pay_status","未支付","settlement_method","C","status","0","del_flag","0","billing_status","OPEN","finance_mode","REAL","original_amount",money(q.get("grossAmount")),"discount_amount",money(q.get("packageDiscount")).add(money(q.get("couponDiscount"))),"payable_amount",money(q.get("payableAmount")),"user_coupon_id",q.get("userCouponId"),"price_confirmed_by",actor,"price_confirmed_time",now(),"fee_title_snapshot",q.get("gradeName")+"一对一课包 · "+q.get("packageCount")+"包 / "+q.get("credits")+"基础次数","campus_name_snapshot","通用课包","package_request_key",key,"package_snapshot",JSON.toJSONString(snap),"create_by",SecurityUtils.getUsername(),"create_time",now());
      long order=db.insert("edu_enrollment",v);
      if(q.get("userCouponId")!=null)require(db.jdbc().update("UPDATE edu_user_coupon SET coupon_status='LOCKED',locked_enrollment_id=?,locked_until=NULL,version=version+1,update_time=? WHERE user_coupon_id=? AND parent_id=? AND coupon_status='AVAILABLE'",order,now(),q.get("userCouponId"),actor)==1,"优惠券已被其他订单占用，请刷新");
      return order;
    });
  }
  public void cancel(long order,Long account){
    long actor=parent();db.transactions().execute(tx->{
      var before=db.one("SELECT lesson_account_id FROM edu_enrollment WHERE enrollment_id=? AND parent_id=? AND bill_type=?",order,actor,TYPE);
      if(account!=null)require(Long.toString(account).equals(str(before,"lessonAccountId")),"订单不属于当前孩子");
      child(id(before.get("lessonAccountId")),actor,true);
      var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",order);
      if("2".equals(e.get("enrollmentStatus")))return null;
      require(e.get("packageCreditedTime")==null && db.rows("SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=? AND payment_status IN ('PENDING','UNKNOWN','SUCCESS') FOR UPDATE",order).isEmpty(),"已登记收款或结果待核对，请联系教务处理，不能取消");
      db.update("edu_enrollment","enrollment_id",order,values("enrollment_status","2","billing_status","CLOSED","cancel_reason","家长取消未缴费课包","cancel_time",now(),"billing_closed_time",now(),"billing_close_reason","家长取消未缴费课包","financial_version",integer(e.get("financialVersion"),0,Integer.MAX_VALUE)+1,"update_by",SecurityUtils.getUsername(),"update_time",now()));
      db.jdbc().update("UPDATE edu_user_coupon SET coupon_status='AVAILABLE',locked_enrollment_id=NULL,locked_until=NULL,version=version+1,update_time=? WHERE locked_enrollment_id=? AND coupon_status='LOCKED'",now(),order);return null;
    });
  }
  /** Called only inside the successful collection transaction; ledger key and bill marker both guard retries. */
  public void credit(long order,long actor){
    require(TransactionSynchronizationManager.isActualTransactionActive(),"课包入账必须与收款处于同一事务");tuitionStaff(db,actor);
    var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",order);require(isPackage(e),"不是新课包订单");
    if(e.get("packageCreditedTime")!=null)return;
    require("OPEN".equals(e.get("billingStatus")) && !"2".equals(e.get("enrollmentStatus")),"课包已取消，不能增加次数");
    require(!db.rows("SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=? AND payment_status='SUCCESS' AND amount=?",order,e.get("payableAmount")).isEmpty(),"确认收款前不能增加课次");
    var a=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? AND parent_id=? FOR UPDATE",e.get("lessonAccountId"),e.get("parentId"));
    require(Objects.equals(str(a,"studentId"),str(e,"studentId")),"孩子账户归属不一致");
    int quantity=integer(snapshot(e).get("credits"),15,1500),remaining=integer(a.get("remainingCredits"),0,10000000),reserved=integer(a.get("reservedCredits"),0,10000000),consumed=integer(a.get("consumedCredits"),0,10000000);
    require(remaining+quantity<=10000000,"课次余额超过上限，请联系管理员核对");
    require(db.rows("SELECT event_id FROM edu_lesson_credit_event WHERE request_key=?","PURCHASE:"+order).isEmpty(),"课包入账流水已存在但账单未标记，请核对");
    db.update("edu_student_lesson_account","account_id",id(a.get("accountId")),values("remaining_credits",remaining+quantity,"update_by",coupons.username(actor),"update_time",now()));
    db.insert("edu_lesson_credit_event",values("account_id",a.get("accountId"),"enrollment_id",order,"action","GRANT","quantity",quantity,"remaining_after",remaining+quantity,"reserved_after",reserved,"consumed_after",consumed,"request_key","PURCHASE:"+order,"reason","课包收款确认入账","actor_id",actor,"actor_name",coupons.username(actor),"create_time",now()));
    db.update("edu_enrollment","enrollment_id",order,values("package_credited_time",now(),"enrollment_status","1","update_by",coupons.username(actor),"update_time",now()));
  }
}
