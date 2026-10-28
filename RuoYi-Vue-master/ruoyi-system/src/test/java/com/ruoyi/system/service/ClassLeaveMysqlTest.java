package com.ruoyi.system.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;
import com.ruoyi.common.core.domain.model.MiniAppSignReviewBody;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.EduClassSignInServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

/** 显式执行，沿用拒绝业务库的隔离夹具；包含真实锁、事务、历史与复核保护。 */
public class ClassLeaveMysqlTest extends LessonCreditMysqlTest {
    ClassLeaveService leaves;
    EduClassSignInServiceImpl signs;
    long course, enrollment, sibling;
    static void principal(long id,String role) {
        ScheduleCrudTest.principal(id,role);
        com.ruoyi.common.utils.SecurityUtils.getLoginUser().getUser().getRoles().forEach(r -> r.setStatus("0"));
    }
    ClassLeaveService.Apply body(long e,LocalDate d,String reason,Integer version) {
        var b=new ClassLeaveService.Apply(); b.enrollmentId=e;b.classDate=d.toString();b.reason=reason;b.version=version;return b;
    }
    ClassLeaveService.Decision decision(String action,int version) {
        var b=new ClassLeaveService.Decision();b.action=action;b.reason="测试原因";b.version=version;return b;
    }
    long apply(long who,long e,LocalDate day,String reason,Integer version) {
        principal(who,"parent");return tx(()->leaves.apply(body(e,day,reason,version)));
    }
    void decide(long who,String role,long request,String action,int version) {
        principal(who,role);tx(()->{leaves.decide(request,decision(action,version),"teacher".equals(role));return null;});
    }
    long classEnrollment(long account) {
        var a=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=?",account);
        Map<String,Object> v=new LinkedHashMap<>();v.put("enrollment_code",UUID.randomUUID().toString().replace("-",""));v.put("schedule_id",course);
        v.put("parent_id",parent);v.put("student_id",a.get("studentId"));v.put("lesson_account_id",account);v.put("student_name","同名孩子");
        v.put("enrollment_status","1");v.put("pay_status","0");v.put("status","0");v.put("del_flag","0");return db.insert("edu_enrollment",v);
    }
    void fixture()throws Exception {
        leaves=new ClassLeaveService();inject(leaves,"db",db);inject(leaves,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(leaves,"calendar",availability);
        course=publish(teacher,room,date);db.jdbc().update("UPDATE edu_course_schedule SET class_mode='1',end_date=? WHERE schedule_id=?",date.plusWeeks(8).toString(),course);
        long first=account(parent,"同名孩子"),second=account(parent,"同名孩子");enrollment=classEnrollment(first);sibling=classEnrollment(second);
        var f=new SqlSessionFactoryBean();f.setDataSource(db.jdbc().getDataSource());f.setTypeAliasesPackage("com.ruoyi.system.domain");f.setMapperLocations(new ClassPathResource("mapper/system/EduClassSignInMapper.xml"));
        var signMapper=new SqlSessionTemplate(f.getObject()).getMapper(EduClassSignInMapper.class);
        signs=new EduClassSignInServiceImpl();inject(signs,"signInMapper",signMapper);inject(signs,"scheduleMapper",session.getMapper(EduCourseScheduleMapper.class));inject(signs,"singleDb",db);inject(signs,"classLeaves",leaves);
    }
    void runLeave()throws Exception {
        fixture();principal(parent,"parent");assertTrue(leaves.options().stream().anyMatch(r->id(r,"enrollmentId")==enrollment));assertFalse(leaves.lessons(enrollment).isEmpty());
        principal(otherParent,"parent");assertThrows(ServiceException.class,()->leaves.lessons(enrollment));assertThrows(ServiceException.class,()->tx(()->leaves.apply(body(enrollment,date,"越权",null))));
        principal(teacher,"teacher");assertThrows(ServiceException.class,()->leaves.options());
        principal(parent,"parent");assertThrows(ServiceException.class,()->tx(()->leaves.apply(body(enrollment,date.plusDays(1),"不是课次",null))));
        var invalid=body(enrollment,date,"原因",null);invalid.classDate="2028-02-30";assertThrows(ServiceException.class,()->tx(()->leaves.apply(invalid)));
        long single=publish(teacher,otherRoom,date.plusDays(1));long singleE=enroll(parent,single,date.plusDays(1),"单次",null);assertThrows(ServiceException.class,()->apply(parent,singleE,date.plusDays(1),"排除一对一",null));
        long request=apply(parent,enrollment,date,"首次请假",null);assertEquals(request,apply(parent,enrollment,date,"首次请假",null));assertThrows(ServiceException.class,()->apply(parent,enrollment,date,"重复其他原因",null));
        principal(otherTeacher,"teacher");assertThrows(ServiceException.class,()->leaves.detail(request,true));assertThrows(ServiceException.class,()->decide(otherTeacher,"teacher",request,"APPROVE",1));
        principal(otherParent,"parent");assertThrows(ServiceException.class,()->leaves.detail(request,false));assertThrows(ServiceException.class,()->decide(otherParent,"parent",request,"WITHDRAW",1));
        decide(parent,"parent",request,"WITHDRAW",1);assertThrows(ServiceException.class,()->apply(parent,enrollment,date,"重提",null));
        assertEquals(request,apply(parent,enrollment,date,"重新申请",2));assertThrows(ServiceException.class,()->decide(teacher,"teacher",request,"APPROVE",1));
        decide(teacher,"teacher",request,"APPROVE",3);decide(teacher,"teacher",request,"APPROVE",3);assertThrows(ServiceException.class,()->decide(parent,"parent",request,"WITHDRAW",4));
        assertEquals(4L,db.jdbc().queryForObject("SELECT JSON_LENGTH(history) FROM edu_class_leave_request WHERE leave_id=?",Long.class,request));
        assertEquals(Set.of(enrollment),leaves.approved(course,date.toString()));
        principal(teacher,"teacher");assertEquals(Set.of(enrollment),signs.selectDetail(course,date.toString()).get("approvedLeaveEnrollmentIds"));
        assertEquals(0L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE schedule_id=? AND class_date=?",Long.class,course,date.toString()));
        var sign=new MiniAppSignSubmitBody();sign.setScheduleId(course);sign.setClassDate(date.toString());var d=new MiniAppSignSubmitBody.Detail();d.setEnrollmentId(enrollment);d.setStudentName("同名孩子");d.setSignStatus("3");d.setSourceType("1");sign.setDetails(List.of(d));
        var d2=new MiniAppSignSubmitBody.Detail();d2.setEnrollmentId(sibling);d2.setStudentName("同名孩子");d2.setSignStatus("1");d2.setSourceType("1");
        sign.setDetails(List.of(d,d2));tx(()->signs.submitSignIn(sign,"teacher-test"));
        assertEquals(2L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE schedule_id=? AND class_date=?",Long.class,course,date.toString()));
        sign.setDetails(List.of(d,d));assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"teacher-test")));sign.setDetails(List.of(d,d2));
        var review=new MiniAppSignReviewBody();review.setScheduleId(course);review.setClassDate(date.toString());
        review.setSubmissionVersion((String)signs.selectDetail(course,date.toString()).get("submissionVersion"));
        review.setReviewVersion(1L);principal(1,"admin");
        tx(()->signs.review(review,1L,"管理员甲"));assertEquals(Set.of(),signs.selectDetail(course,date.toString()).get("approvedLeaveEnrollmentIds"));
        principal(teacher,"teacher");
        assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"teacher-test")));
        principal(2,"admin");
        tx(()->signs.review(review,2L,"管理员乙"));assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"teacher-test")));
        assertEquals("1",db.one("SELECT review_status FROM edu_class_sign_in WHERE schedule_id=? AND class_date=?",course,date.toString()).get("reviewStatus"));
        // 审核可晚于上课时间，但绝不覆盖已复核考勤。
        long late=apply(parent,sibling,date.plusWeeks(1),"晚审核",null);
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,enrollment_id,student_name,source_type,sign_status,review_status,review1_admin_id,del_flag) VALUES(?,?,?,'同名孩子','1','1','1',1,'0')",course,date.plusWeeks(1).toString(),sibling);
        decide(teacher,"teacher",late,"APPROVE",1);assertEquals(Set.of(),signs.selectDetail(course,date.plusWeeks(1).toString()).get("approvedLeaveEnrollmentIds"));
        assertEquals("1",db.one("SELECT sign_status FROM edu_class_sign_in WHERE schedule_id=? AND class_date=?",course,date.plusWeeks(1).toString()).get("signStatus"));
        // 同名孩子分别申请，稳定标识防重复；课次调课和停课不能凭任意日期申请。
        long rejected=apply(parent,sibling,date.plusWeeks(2),"拒绝后重提",null);decide(teacher,"teacher",rejected,"REJECT",1);assertEquals(rejected,apply(parent,sibling,date.plusWeeks(2),"补充说明",2));
        long moved=apply(parent,enrollment,date.plusWeeks(3),"调课前申请",null);
        db.jdbc().update("INSERT INTO edu_schedule_adjustment(schedule_id,original_date,adjusted_date) VALUES(?,?,?)",course,date.plusWeeks(3).toString(),date.plusWeeks(3).plusDays(1).toString());
        assertThrows(ServiceException.class,()->decide(teacher,"teacher",moved,"APPROVE",1));
        assertThrows(ServiceException.class,()->apply(parent,sibling,date.plusWeeks(3),"原日期失效",null));
        assertTrue(apply(parent,sibling,date.plusWeeks(3).plusDays(1),"新日期有效",null)>0);
        db.jdbc().update("INSERT INTO edu_schedule_adjustment(schedule_id,original_date,adjusted_date) VALUES(?,?,NULL)",course,date.plusWeeks(4).toString());
        assertThrows(ServiceException.class,()->apply(parent,enrollment,date.plusWeeks(4),"停课",null));
        // 开始时刻及之后拒绝提交；待审核撤回不受开课时间限制。
        var s=session.getMapper(EduCourseScheduleMapper.class).selectEduCourseScheduleByScheduleId(course);
        inject(leaves,"clock",Clock.fixed(LocalDateTime.of(date.plusWeeks(5),s.getStartTime()).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneId.of("Asia/Shanghai")));
        assertThrows(ServiceException.class,()->apply(parent,enrollment,date.plusWeeks(5),"已经开始",null));
        inject(leaves,"clock",Clock.system(ZoneId.of("Asia/Shanghai")));
        // 注入历史写入失败，审批必须完整回滚。
        long rollback=apply(parent,enrollment,date.plusWeeks(6),"回滚",null);
        db.jdbc().execute("CREATE TRIGGER leave_test_failure BEFORE UPDATE ON edu_class_leave_request FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'");
        assertThrows(RuntimeException.class,()->decide(teacher,"teacher",rollback,"APPROVE",1));db.jdbc().execute("DROP TRIGGER leave_test_failure");
        assertEquals("PENDING",db.one("SELECT state FROM edu_class_leave_request WHERE leave_id=?",rollback).get("state"));
        // 两个并发请求只产生一条申请和一次历史。
        var pool=Executors.newFixedThreadPool(2);
        try { var tasks=new ArrayList<Callable<Long>>();for(int i=0;i<2;i++)tasks.add(()->apply(parent,sibling,date.plusWeeks(7),"并发相同请求",null));
            var results=pool.invokeAll(tasks);assertEquals(results.get(0).get(),results.get(1).get());
            assertEquals(1L,db.jdbc().queryForObject("SELECT JSON_LENGTH(history) FROM edu_class_leave_request WHERE leave_id=?",Long.class,results.get(0).get()));
        } finally {pool.shutdownNow();}
        long race=apply(parent,enrollment,date.plusWeeks(8),"撤回审核竞争",null);
        var reviewers=Executors.newFixedThreadPool(2);
        try {
            var results=reviewers.invokeAll(List.<Callable<Boolean>>of(
                ()->{try {decide(parent,"parent",race,"WITHDRAW",1);return true;}catch(ServiceException e){return false;}},
                ()->{try {decide(teacher,"teacher",race,"APPROVE",1);return true;}catch(ServiceException e){return false;}}));
            assertNotEquals(results.get(0).get(),results.get(1).get());
            assertEquals(2L,db.jdbc().queryForObject("SELECT JSON_LENGTH(history) FROM edu_class_leave_request WHERE leave_id=?",Long.class,race));
        } finally {reviewers.shutdownNow();}
        principal(parent,"parent");
        long pending=db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_class_leave_request WHERE parent_id=? AND state='PENDING'",Long.class,parent);
        String before=db.jdbc().queryForObject("SELECT CONCAT(history,update_time) FROM edu_class_leave_request WHERE leave_id=?",String.class,request);
        assertEquals(pending,leaves.list(false,"",1).get("pendingCount"));leaves.detail(request,false);
        assertEquals(before,db.jdbc().queryForObject("SELECT CONCAT(history,update_time) FROM edu_class_leave_request WHERE leave_id=?",String.class,request));
        principal(otherTeacher,"teacher");assertEquals(0L,leaves.list(true,"",1).get("total"));
        System.out.println("PASS: class leave ownership/roles, real dates/adjustments, cutoff, duplicate and concurrent idempotency, withdraw/resubmit/stale review, approval/rejection/history, atomic rollback, sign prefill and first/final review protection");
    }
    public static void main(String[] args)throws Exception {var t=new ClassLeaveMysqlTest();t.setup();t.runLeave();}
}
