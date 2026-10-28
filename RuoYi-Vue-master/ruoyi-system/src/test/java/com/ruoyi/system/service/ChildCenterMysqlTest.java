package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.sql.Timestamp;
import java.time.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.*;

/** Explicit disposable MySQL clone: same-name siblings, actionable TODOs and shared interfaces. */
public class ChildCenterMysqlTest extends LessonCreditMysqlTest {
    @SuppressWarnings("unchecked") void childCenter() throws Exception {
        var personal=new ParentAccountService();inject(personal,"db",db);inject(personal,"schedules",session.getMapper(EduCourseScheduleMapper.class));
        var classes=new MiniAppParentServiceImpl();inject(classes,"tuition",payments);inject(classes,"db",db);inject(classes,"singleService",single);inject(classes,"credits",credits);inject(classes,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(classes,"miniAppParentMapper",session.getMapper(MiniAppParentMapper.class));
        long a=account(parent,"同名学生"),b=account(parent,"同名学生"),foreign=account(otherParent,"同名学生");
        var course=db.one("SELECT schedule_id,teacher_id FROM edu_course_schedule WHERE class_mode='1' AND del_flag='0' AND status='0' AND recruit_status='0' ORDER BY schedule_id LIMIT 1");long schedule=id(course,"scheduleId");
        ScheduleCrudTest.principal(parent,"parent");
        long ea=classEnroll(classes,schedule,a),eb=classEnroll(classes,schedule,b);
        long baseA=((Number)personal.childDetail(a).get("pendingCount")).longValue(),baseB=((Number)personal.childDetail(b).get("pendingCount")).longValue();
        assertEquals(1L,((Map<String,Object>)personal.childDetail(a).get("child")).get("courseCount"));
        assertEquals(1L,personal.records(parent,false,"enrollments",1,false,a,false).get("total"));
        assertEquals(String.valueOf(ea),String.valueOf(((List<Map<String,Object>>)personal.records(parent,false,"enrollments",1,false,a,false).get("rows")).get(0).get("enrollmentId")));
        assertThrows(Exception.class,()->personal.ownedChild(foreign));assertThrows(Exception.class,()->personal.records(parent,false,"history",1,false,foreign,false));
        assertThrows(Exception.class,()->personal.records(parent,false,"orders",1,false,a,false));
        var all=classes.selectMyTimetable();assertTrue(personal.filterChild(all,a).stream().allMatch(e->String.valueOf(e.get("enrollmentId")).equals(String.valueOf(ea))));
        var price=Map.<String,Object>of("originalAmount","100","financeMode","MOCK","financialVersion",db.one("SELECT financial_version FROM edu_enrollment WHERE enrollment_id=?",ea).get("financialVersion"));ScheduleCrudTest.principal(1,"admin");payments.price(ea,1,price);
        ScheduleCrudTest.principal(parent,"parent");assertEquals(baseA+1,personal.childDetail(a).get("pendingCount"));assertEquals(baseB,personal.childDetail(b).get("pendingCount"));
        assertEquals(1L,personal.records(parent,false,"enrollments",1,true,a,false).get("total"));
        ScheduleCrudTest.principal(1,"admin");var pending=payments.createPayment(ea,1,true,Map.of("channel","MOCK","expectedPayableAmount","100","financialVersion",db.one("SELECT financial_version FROM edu_enrollment WHERE enrollment_id=?",ea).get("financialVersion"),"idempotencyKey",UUID.randomUUID().toString()));
        ScheduleCrudTest.principal(parent,"parent");assertEquals(baseA,personal.childDetail(a).get("pendingCount"));payments.checkChild(ea,parent,a);
        assertThrows(Exception.class,()->payments.checkChild(ea,parent,b));assertThrows(Exception.class,()->payments.checkChild(ea,parent,foreign));
        assertEquals(1L,payments.listBills(parent,false,Map.of("accountId",String.valueOf(a))).get("total"));assertThrows(Exception.class,()->payments.listRefunds(parent,false,Map.of("accountId",String.valueOf(foreign))));
        var progress=(List<Map<String,Object>>)personal.records(parent,false,"enrollments",1,false,a,false).get("rows");assertEquals(1L,((Number)progress.get(0).get("paymentPendingCount")).longValue());
        ScheduleCrudTest.principal(1,"admin");payments.confirmPayment(id(pending,"paymentId"),1,true,Map.of());
        ScheduleCrudTest.principal(parent,"parent");var receipts=new com.ruoyi.system.finance.TuitionReceiptService(db,null);
        assertEquals(1L,receipts.list(parent,false,Map.of("accountId",String.valueOf(a))).get("total"));
        assertEquals(0L,receipts.list(parent,false,Map.of("accountId",String.valueOf(b))).get("total"));
        assertThrows(Exception.class,()->receipts.list(parent,false,Map.of("accountId",String.valueOf(foreign))));
        com.ruoyi.common.utils.SecurityUtils.getLoginUser().getUser().getRoles().forEach(r->r.setStatus("0"));var leave=new ClassLeaveService();inject(leave,"db",db);inject(leave,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(leave,"calendar",availability);
        assertTrue(leave.options(a).stream().allMatch(x->String.valueOf(x.get("lessonAccountId")).equals(String.valueOf(a))));
        assertThrows(Exception.class,()->leave.options(foreign));assertThrows(Exception.class,()->leave.list(false,null,1,foreign));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(db.jdbc().getDataSource());factory.setTypeAliasesPackage("com.ruoyi.system.domain");factory.setMapperLocations(new ClassPathResource("mapper/system/EduHomeworkMapper.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(EduHomeworkMapper.class);var homework=new EduHomeworkServiceImpl();inject(homework,"mapper",mapper);inject(homework,"accounts",personal);
        long h=work(schedule,id(course,"teacherId"),"1",date),closed=work(schedule,id(course,"teacherId"),"2",date),expired=work(schedule,id(course,"teacherId"),"1",LocalDate.now().minusDays(2));
        ScheduleCrudTest.principal(parent,"parent");
        assertEquals(ea,homework.parentList(a).stream().filter(x->x.getHomeworkId()==h).findFirst().orElseThrow().getEnrollmentId());
        assertEquals(eb,homework.parentList(b).stream().filter(x->x.getHomeworkId()==h).findFirst().orElseThrow().getEnrollmentId());
        assertThrows(Exception.class,()->homework.parentList(foreign));assertEquals(eb,((EduHomework)homework.parentDetail(h,schedule,eb).get("homework")).getEnrollmentId());
        var body=new HomeworkSubmitBody();body.setHomeworkId(h);body.setEnrollmentId(eb);body.setAccountId(a);body.setDescription("同班兄弟姐妹必须区分");assertThrows(Exception.class,()->tx(()->homework.submit(body)));
        body.setAccountId(b);long before=id((Map<String,Object>)personal.childDetail(b).get("child"),"homeworkPendingCount");tx(()->homework.submit(body));
        assertEquals(before-1,id((Map<String,Object>)personal.childDetail(b).get("child"),"homeworkPendingCount"));assertNull(homework.parentDetail(h,schedule,ea).get("submission"));assertNotNull(homework.parentDetail(h,schedule,eb).get("submission"));
        assertThrows(Exception.class,()->tx(()->homework.submit(body)));body.setEnrollmentId(ea);body.setAccountId(a);body.setHomeworkId(closed);assertThrows(Exception.class,()->tx(()->homework.submit(body)));body.setHomeworkId(expired);assertThrows(Exception.class,()->tx(()->homework.submit(body)));
        db.insert("edu_class_sign_in",Map.of("schedule_id",schedule,"enrollment_id",ea,"student_name","同名学生","class_date",java.sql.Date.valueOf(date),"sign_status","3","review_status","1"));
        db.insert("edu_class_sign_in",Map.of("schedule_id",schedule,"enrollment_id",eb,"student_name","同名学生","class_date",java.sql.Date.valueOf(date),"sign_status","1","review_status","0"));
        var signs=(List<Map<String,Object>>)personal.records(parent,false,"attendance",1,false,a,false).get("rows");assertEquals(1,signs.size());assertEquals("3",signs.get(0).get("signStatus"));assertEquals("1",signs.get(0).get("reviewStatus"));
        assertEquals(0L,personal.childAttendanceSummary(a).get(0).get("attendedCount"));assertEquals(1L,personal.childAttendanceSummary(b).get(0).get("attendedCount"));
        assertEquals("3",personal.childAttendance(a,ea).get(0).get("attendanceStatus"));assertEquals("0",personal.childAttendance(b,eb).get(0).get("reviewStatus"));
        assertThrows(Exception.class,()->personal.childAttendance(a,eb));assertThrows(Exception.class,()->personal.childAttendanceSummary(foreign));
        adjust(a,"GRANT",5,UUID.randomUUID().toString());long lesson=publish(teacher,room,date.plusDays(20));enroll(parent,lesson,date.plusDays(20),"同名学生",a);
        ScheduleCrudTest.principal(parent,"parent");var creditsRow=(Map<String,Object>)personal.childDetail(a).get("child");assertEquals(3,id(creditsRow,"availableCredits"));assertEquals(2,id(creditsRow,"reservedCredits"));
        var history=(List<Map<String,Object>>)personal.records(parent,false,"history",1,false,a,false).get("rows");assertTrue(history.stream().noneMatch(x->"SHOP".equals(x.get("source"))));
        // Profile edits must not rewrite financial or attendance snapshots or household referral links.
        String bindings=db.rows("SELECT * FROM edu_referral_binding WHERE invitee_id=? OR referrer_id=?",parent,parent).toString();
        var paymentBefore=db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?",pending.get("paymentId"));
        var receiptBefore=db.rows("SELECT * FROM edu_tuition_receipt WHERE payment_id=?",pending.get("paymentId"));assertFalse(receiptBefore.isEmpty());
        var draft=new LessonCreditService.AccountDraft();draft.studentName="当前新姓名";draft.gradeName=grade;draft.contactPhone="";tx(()->{credits.editChild(a,draft,false);return null;});
        assertEquals("当前新姓名",personal.ownedChild(a).get("studentName"));assertEquals("同名学生",db.one("SELECT student_name FROM edu_enrollment WHERE enrollment_id=?",ea).get("studentName"));assertEquals("同名学生",db.one("SELECT student_name FROM edu_class_sign_in WHERE enrollment_id=?",ea).get("studentName"));
        assertEquals(bindings,db.rows("SELECT * FROM edu_referral_binding WHERE invitee_id=? OR referrer_id=?",parent,parent).toString());
        assertEquals(paymentBefore,db.one("SELECT * FROM edu_tuition_payment WHERE payment_id=?",pending.get("paymentId")));
        assertEquals(receiptBefore,db.rows("SELECT * FROM edu_tuition_receipt WHERE payment_id=?",pending.get("paymentId")));
        assertThrows(Exception.class,()->tx(()->{credits.deleteChild(a,false);return null;}));
        ScheduleCrudTest.principal(otherParent,"parent");assertThrows(Exception.class,()->personal.childDetail(a));ScheduleCrudTest.principal(teacher,"teacher");assertThrows(Exception.class,()->personal.childDetail(a));
        System.out.println("PASS: child center same-name sibling isolation, course/finance/homework/attendance scope, parent actionable vs progress counts, senior allowance holds, protected profile snapshots/history and parent-owned referrals");
    }
    long classEnroll(MiniAppParentServiceImpl service,long schedule,long account){var body=new MiniAppEnrollmentBody();body.setScheduleId(schedule);body.setLessonAccountId(account);body.setStudentName("同名学生");body.setContactPhone("");tx(()->service.enroll(body));return db.jdbc().queryForObject("SELECT enrollment_id FROM edu_enrollment WHERE parent_id=? AND lesson_account_id=? AND schedule_id=?",Long.class,parent,account,schedule);}
    long work(long schedule,long teacher,String status,LocalDate deadline){long h=db.insert("edu_homework",Map.of("schedule_id",schedule,"teacher_id",teacher,"title","孩子隔离测试","status",status,"deadline",Timestamp.valueOf(deadline.atTime(23,59))));db.jdbc().update("INSERT INTO edu_homework_target(homework_id,schedule_id) VALUES (?,?)",h,schedule);return h;}
    public static void main(String[] args)throws Exception{var t=new ChildCenterMysqlTest();t.setup();t.childCenter();}
}
