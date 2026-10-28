package com.ruoyi.system.lesson;

import static com.ruoyi.system.finance.FinanceRules.*;
import com.ruoyi.system.shop.ShopRepository;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Financial hooks for the unified enrollment model; never mutate lesson-credit balances.
 * Package settlement stays in LessonCreditService, reservations in OneToOneEnrollmentService.
 */
public class LessonFinance {
  private final ShopRepository db;
  public LessonFinance(ShopRepository db) { this.db = db; }
  private Map<String,Object> cashBill(long enrollment, long actor) {
    require(TransactionSynchronizationManager.isActualTransactionActive(), "课次核对必须在财务事务内");
    require(db.rows("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'", actor).size()==1, "操作账号无效");
    var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE", enrollment);
    require(!com.ruoyi.system.finance.CreditPackageService.isPackage(e),"课包余额须按购买订单核对，不能套用课程退课或退费");
    require(!"LESSON_PACKAGE".equals(e.get("billType")), "旧课包账单须先核对迁移，不能启用旧预约账本");
    require(!"P".equals(e.get("settlementMethod")), "课包报名通过课次账户结算，不能登记现金收退款");
    return e;
  }
  public void activate(long enrollment, long actor) {
    var e=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",enrollment);
    if(com.ruoyi.system.finance.CreditPackageService.isPackage(e)){
      new com.ruoyi.system.finance.CreditPackageService(db,new com.ruoyi.system.finance.CouponService(db)).credit(enrollment,actor);
    }else cashBill(enrollment, actor);
  }
  public void freeze(long enrollment, long refund, long actor) { cashBill(enrollment, actor); }
  public void unfreeze(long enrollment, long actor) { cashBill(enrollment, actor); }
  public void close(long enrollment, long refund, long actor) { cashBill(enrollment, actor); }

  private static final String TARGET_USED = "refund_status IN ('WAIT_EDU','WAIT_ADMIN','PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN','SUCCESS')";
  public List<Map<String,Object>> refundTargets(Map<String,Object> e) {
    if (!"OPEN".equals(e.get("billingStatus")) || Set.of("2","已取消","3","已拒绝").contains(str(e,"enrollmentStatus"))
        || "P".equals(e.get("settlementMethod")) || "LESSON_PACKAGE".equals(e.get("billType"))) return List.of();
    return db.rows("SELECT 'CLASS_SIGN_IN' target_type,CAST(s.sign_in_id AS CHAR) target_id,CONCAT(s.class_date,' · ',c.course_class_name,' · ',COALESCE(NULLIF(c.teacher_name,''),NULLIF(u.nick_name,''),'教师未设置'),' · 已返0次') label,0 returned_units FROM edu_class_sign_in s JOIN edu_course_schedule c ON c.schedule_id=s.schedule_id LEFT JOIN sys_user u ON u.user_id=c.teacher_id WHERE s.enrollment_id=? AND s.schedule_id=? AND s.source_type='1' AND s.sign_status='3' AND s.review_status='1' AND s.del_flag='0' AND NOT EXISTS(SELECT 1 FROM edu_tuition_refund r WHERE r.class_sign_in_id=s.sign_in_id AND "+TARGET_USED+") ORDER BY s.class_date,s.sign_in_id",e.get("enrollmentId"),e.get("scheduleId"));
  }
  public void validateSessionTarget(long enrollment, Long signIn, Long booking, long actor) {
    var e=cashBill(enrollment,actor);
    require(signIn!=null && booking==null, "请选择当前报名已复核的请假考勤，不支持旧预约编号");
    var s=db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=? FOR UPDATE",signIn);
    require(Objects.equals(s.get("enrollmentId"),e.get("enrollmentId")) && Objects.equals(s.get("scheduleId"),e.get("scheduleId"))
        && "1".equals(s.get("sourceType")) && "3".equals(s.get("signStatus")) && "1".equals(s.get("reviewStatus"))
        && "0".equals(s.get("delFlag")), "请假课次状态或归属已变化，请核对后处理");
    require(e.get("classDate")==null || Objects.equals(e.get("classDate"),s.get("classDate")), "请假考勤不属于当前一对一课次");
  }
  public void validateSession(long enrollment, long refund, long actor) {
    cashBill(enrollment,actor);
    var r=db.one("SELECT r.* FROM edu_tuition_refund r JOIN edu_tuition_payment p ON p.payment_id=r.payment_id WHERE r.refund_id=? AND p.enrollment_id=? FOR UPDATE",refund,enrollment);
    if (!"SESSION".equals(r.get("refundKind"))) return;
    validateSessionTarget(enrollment,r.get("classSignInId")==null?null:id(r.get("classSignInId")),r.get("lessonBookingId")==null?null:id(r.get("lessonBookingId")),actor);
  }
  public void reserveSession(long enrollment, long refund, long actor) { validateSession(enrollment,refund,actor); }
  public void releaseSession(long enrollment, long refund, long actor) { cashBill(enrollment,actor); }
  public void reclaimSession(long enrollment, long refund, long actor) { validateSession(enrollment,refund,actor); }
}
