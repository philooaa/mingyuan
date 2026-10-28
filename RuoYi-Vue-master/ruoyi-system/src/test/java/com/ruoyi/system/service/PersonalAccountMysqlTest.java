package com.ruoyi.system.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.*;
import static org.junit.jupiter.api.Assertions.*;

/** 复用显式隔离数据库夹具，验证身份关联、真实事务回滚和个人账户聚合。 */
public class PersonalAccountMysqlTest extends LessonCreditMysqlTest {
    LessonCreditService.AccountDraft child(String name,String key) {var d=new LessonCreditService.AccountDraft();d.studentName=name;d.gradeName=grade;d.contactPhone="";d.requestKey=key;return d;}
    long createMine(String name,String key) {ScheduleCrudTest.principal(parent,"parent");return tx(()->credits.createMine(child(name,key)));}
    void personal() throws Exception {
        var personal=new ParentAccountService();inject(personal,"db",db);inject(personal,"schedules",session.getMapper(EduCourseScheduleMapper.class));
        var classes=new MiniAppParentServiceImpl();inject(classes,"tuition",payments);inject(classes,"db",db);inject(classes,"singleService",single);inject(classes,"credits",credits);inject(classes,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(classes,"miniAppParentMapper",session.getMapper(MiniAppParentMapper.class));
        long a=createMine("个人账户孩子",UUID.randomUUID().toString());long sibling=createMine("个人账户孩子",UUID.randomUUID().toString());assertNotEquals(a,sibling);
        long before=db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account",Long.class);
        String retry=UUID.randomUUID().toString();long repeated=createMine("幂等孩子",retry);assertEquals(repeated,createMine("幂等孩子",retry));assertThrows(ServiceException.class,()->createMine("错误重试",retry));assertEquals(before+1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account",Long.class));
        ScheduleCrudTest.principal(otherParent,"parent");assertThrows(ServiceException.class,()->tx(()->{credits.editChild(a,child("越权",retry),false);return null;}));assertThrows(ServiceException.class,()->tx(()->{credits.deleteChild(a,false);return null;}));assertThrows(ServiceException.class,()->personal.detail(parent,false));assertThrows(ServiceException.class,()->personal.records(parent,false,"history",1,false));
        ScheduleCrudTest.principal(teacher,"teacher");assertThrows(ServiceException.class,()->credits.mine());assertThrows(ServiceException.class,()->personal.detail(parent,false));assertThrows(ServiceException.class,()->personal.pending(true));
        ScheduleCrudTest.principal(parent,"parent");tx(()->{credits.editChild(a,child("改名孩子",retry),false);return null;});assertEquals("改名孩子",credits.mine().stream().filter(x->id(x,"accountId")==a).findFirst().orElseThrow().get("studentName"));
        tx(()->{credits.deleteChild(sibling,false);return null;});assertEquals(0L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account WHERE account_id=?",Long.class,sibling));
        long course=publish(teacher,room,date.plusDays(50));
        var b=new MiniAppEnrollmentBody();b.setScheduleId(course);b.setClassDate(date.plusDays(50).toString());b.setStudentName("错误客户端姓名");b.setLessonAccountId(a);b.setSettlementMethod("C");
        final var cashBody=b;
        ScheduleCrudTest.principal(parent,"parent");tx(()->single.enroll(cashBody));var cash=db.one("SELECT * FROM edu_enrollment WHERE schedule_id=?",course);long cashId=id(cash,"enrollmentId");assertEquals(a,id(cash,"lessonAccountId"));assertEquals("改名孩子",cash.get("studentName"));balances(a,0,0,0);assertNull(cash.get("lessonCredits"));
        // 修改资料不覆盖报名快照，后续报名按稳定孩子ID识别。
        tx(()->{credits.editChild(a,child("孩子新姓名",retry),false);return null;});assertEquals("改名孩子",db.one("SELECT student_name FROM edu_enrollment WHERE enrollment_id=?",cashId).get("studentName"));
        assertThrows(ServiceException.class,()->tx(()->{credits.deleteChild(a,false);return null;}));
        // 自动新增孩子与一对一报名共用事务，故意让考勤写入失败不得留下孤立账户。
        long failed=publish(teacher,room,date.plusDays(51));long accountCount=db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account",Long.class);
        MiniAppParentMapper original=session.getMapper(MiniAppParentMapper.class);
        inject(single,"parent",ScheduleCrudTest.proxy(MiniAppParentMapper.class,(p,m,args)->{if(m.getName().equals("insertInitialAttendance"))throw new ServiceException("test rollback");return m.invoke(original,args);}));
        b=new MiniAppEnrollmentBody();b.setScheduleId(failed);b.setClassDate(date.plusDays(51).toString());b.setStudentName("回滚孩子");b.setSettlementMethod("C");b.setStudentRequestKey(UUID.randomUUID().toString());final var failure=b;
        ScheduleCrudTest.principal(parent,"parent");assertThrows(ServiceException.class,()->tx(()->single.enroll(failure)));assertEquals(accountCount,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account",Long.class));assertEquals(0L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment WHERE schedule_id=?",Long.class,failed));inject(single,"parent",original);
        tx(()->single.enroll(failure));var created=db.one("SELECT * FROM edu_enrollment WHERE schedule_id=?",failed);long newChild=id(created,"lessonAccountId");balances(newChild,0,0,0);assertThrows(ServiceException.class,()->tx(()->single.enroll(failure)));assertEquals(accountCount+1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account",Long.class));
        // 班课也必须自动关联；孩子改名不能绕开已有报名检查。
        var classRow=db.one("SELECT schedule_id FROM edu_course_schedule s JOIN edu_classroom c ON c.classroom_id=s.classroom_id WHERE s.class_mode='1' AND s.del_flag='0' AND s.status='0' AND s.recruit_status='0' AND c.status='0' LIMIT 1");
        var classBody=new MiniAppEnrollmentBody();classBody.setScheduleId(id(classRow,"scheduleId"));classBody.setLessonAccountId(a);classBody.setStudentName("旧姓名");classBody.setContactPhone("");ScheduleCrudTest.principal(parent,"parent");tx(()->classes.enroll(classBody));
        assertEquals(a,id(db.one("SELECT lesson_account_id FROM edu_enrollment WHERE parent_id=? AND schedule_id=? AND lesson_account_id=?",parent,classBody.getScheduleId(),a),"lessonAccountId"));
        tx(()->{credits.editChild(a,child("再次改名",retry),false);return null;});assertThrows(ServiceException.class,()->tx(()->classes.enroll(classBody)));
        long sameNameSibling=createMine("再次改名",UUID.randomUUID().toString());
        classBody.setLessonAccountId(sameNameSibling);tx(()->classes.enroll(classBody));
        assertEquals(2L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment WHERE parent_id=? AND schedule_id=? AND lesson_account_id IN (?,?)",Long.class,parent,classBody.getScheduleId(),a,sameNameSibling));
        // 待办只有有效未付款现金报名；取消、拒绝、课包和商城不增加。
        long baseline=personal.pending(false);ScheduleCrudTest.principal(1,"admin");payCash(cashId);ScheduleCrudTest.principal(parent,"parent");assertEquals(baseline-1,personal.pending(false));
        db.jdbc().update("UPDATE edu_enrollment SET enrollment_status='2' WHERE enrollment_id=?",created.get("enrollmentId"));assertEquals(baseline-2,personal.pending(false));
        // 旧记录始终显示，并且只能核对关联本家长孩子；关联动作不发放课次。
        db.jdbc().update("UPDATE edu_enrollment SET lesson_account_id=NULL WHERE enrollment_id=?",cashId);ScheduleCrudTest.principal(parent,"parent");var records=personal.records(parent,false,"enrollments",1,false);assertTrue(records.get("rows") instanceof List);assertTrue(((Number)personal.detail(parent,false).get("unlinkedCount")).longValue()>0);
        long foreign=account(otherParent,"其他家长孩子");var link=new ParentAccountService.Link();link.accountId=foreign;link.reason="人工核对";ScheduleCrudTest.principal(1,"admin");assertThrows(ServiceException.class,()->tx(()->{personal.link(cashId,link);return null;}));link.accountId=a;tx(()->{personal.link(cashId,link);return null;});tx(()->{personal.link(cashId,link);return null;});balances(a,0,0,0);
        assertEquals(1L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment_event WHERE enrollment_id=? AND action='LINK_CHILD'",Long.class,cashId));
        ScheduleCrudTest.principal(parent,"parent");Object modified=db.one("SELECT update_time FROM edu_enrollment WHERE enrollment_id=?",cashId).get("updateTime");
        assertNotNull(personal.records(parent,false,"orders",1,false));var history=personal.records(parent,false,"history",1,false);assertTrue(((Number)history.get("total")).longValue()>0);assertEquals(modified,db.one("SELECT update_time FROM edu_enrollment WHERE enrollment_id=?",cashId).get("updateTime"));
        var eventRows=(List<Map<String,Object>>)history.get("rows");for(int i=1;i<eventRows.size();i++)assertTrue(eventRows.get(i-1).get("modifiedTime").toString().compareTo(eventRows.get(i).get("modifiedTime").toString())>=0);
        ScheduleCrudTest.principal(1,"admin");assertTrue(((Number)personal.list("",1).get("total")).longValue()>0);
        // 同一新孩子请求并发重复提交只建一个账户。
        String concurrent=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);List<Future<Long>> futures=new ArrayList<>();
        try{for(int i=0;i<2;i++)futures.add(pool.submit(()->{gate.await();return createMine("并发建账",concurrent);}));gate.countDown();assertEquals(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        // 历史连续课程同一孩子跨日期可报名，不受班课姓名唯一索引误拦。
        long repeating=publish(teacher,room,date.plusDays(70));
        db.jdbc().update("UPDATE edu_course_schedule SET end_date=? WHERE schedule_id=?",java.sql.Date.valueOf(date.plusDays(77)),repeating);
        enroll(parent,repeating,date.plusDays(70),"历史连续课程孩子",null);
        var repeatChild=db.one("SELECT lesson_account_id FROM edu_enrollment WHERE schedule_id=?",repeating);
        var repeatBody=new MiniAppEnrollmentBody(); repeatBody.setScheduleId(repeating);repeatBody.setClassDate(date.plusDays(77).toString());repeatBody.setStudentName("历史连续课程孩子");repeatBody.setSettlementMethod("C");repeatBody.setLessonAccountId(id(repeatChild,"lessonAccountId"));
        ScheduleCrudTest.principal(parent,"parent");tx(()->single.enroll(repeatBody));
        assertEquals(2L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_enrollment WHERE schedule_id=?",Long.class,repeating));
        // 合并衔接：财务不得绕过教师审批；教师取消不能伪造退款或重复返次。
        long paidCourse=publish(teacher,room,date.plusDays(60));
        long paidEnrollment=enroll(parent,paidCourse,date.plusDays(60),"现金审批孩子",null);
        payCash(paidEnrollment);
        var payment=db.one("SELECT payment_id FROM edu_tuition_payment WHERE enrollment_id=?",paidEnrollment);
        var refundBody=Map.<String,Object>of("paymentId",payment.get("paymentId"),"refundKind","WITHDRAWAL","reason","测试退课","idempotencyKey",UUID.randomUUID().toString());
        assertThrows(ServiceException.class,()->payments.cancelEnrollment(paidEnrollment,1,true));
        assertThrows(ServiceException.class,()->payments.applyRefund(parent,false,refundBody));
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(paidEnrollment,"CONFIRM",null));
        ScheduleCrudTest.principal(parent,"parent");tx(()->single.requestCancellation(paidEnrollment,"测试取消"));
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(paidEnrollment,"APPROVE_CANCEL","同意"));
        assertEquals("SUCCESS",db.one("SELECT payment_status FROM edu_tuition_payment WHERE enrollment_id=?",paidEnrollment).get("paymentStatus"));
        assertEquals("OPEN",db.one("SELECT billing_status FROM edu_enrollment WHERE enrollment_id=?",paidEnrollment).get("billingStatus"));
        var refund=payments.applyRefund(parent,false,refundBody);
        payments.reviewRefund(id(refund,"refundId"),1,Map.of("approved",true,"approvedAmount","100","reviewRemark","同意","version",0));
        payments.markRefunded(id(refund,"refundId"),1,Map.of("version",1));
        assertEquals("CLOSED",db.one("SELECT billing_status FROM edu_enrollment WHERE enrollment_id=?",paidEnrollment).get("billingStatus"));
        assertEquals("2",db.one("SELECT enrollment_status FROM edu_enrollment WHERE enrollment_id=?",paidEnrollment).get("enrollmentStatus"));
        System.out.println("PASS: personal account child CRUD/ownership, cash and class automatic linkage, rollback/idempotency/concurrent creation, rename duplicate protection, scoped pending count, manual legacy link, unified history ordering and read-only queries");
    }
    public static void main(String[] args)throws Exception{var t=new PersonalAccountMysqlTest();t.setup();t.run();t.personal();}
}
