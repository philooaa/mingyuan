package com.ruoyi.system.service;

import java.lang.reflect.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.shop.ShopRepository;
import static org.junit.jupiter.api.Assertions.*;

/** Only clone table structures; all fixture writes stay in this randomly named disposable schema. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminOneToOneMysqlTest {
    final String schema="codex_admin_single_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    ShopRepository db; OneToOneEnrollmentService service; com.ruoyi.system.controller.AdminOneToOneController controller;
    @BeforeAll void setup() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){
            c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
            for(String table:List.of("sys_user","sys_role","sys_user_role","sys_dict_data","edu_teacher_profile","edu_schedule_adjustment","edu_classroom","edu_course_schedule","edu_enrollment","edu_enrollment_event","edu_class_sign_in","edu_attendance","edu_student_lesson_account","edu_lesson_credit_event","edu_tuition_payment","edu_tuition_refund"))
                c.createStatement().execute("CREATE TABLE "+schema+"."+table+" LIKE mingyuaneduminiapp_main_20261013."+table);
        }
        var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root","");
        db=new ShopRepository(ds);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setTypeAliasesPackage("com.ruoyi.system.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/system/EduCourseScheduleMapper.xml"),new ClassPathResource("mapper/system/EduEnrollmentMapper.xml"),new ClassPathResource("mapper/system/MiniAppParentMapper.xml"));
        var session=new SqlSessionTemplate(factory.getObject());
        service=new OneToOneEnrollmentService();LessonCreditMysqlTest.inject(service,"db",db);
        LessonCreditMysqlTest.inject(service,"schedules",session.getMapper(EduCourseScheduleMapper.class));
        LessonCreditMysqlTest.inject(service,"enrollments",session.getMapper(EduEnrollmentMapper.class));
        var credits=new LessonCreditService();LessonCreditMysqlTest.inject(credits,"db",db);LessonCreditMysqlTest.inject(service,"credits",credits);
        var availability=new ScheduleAvailabilityService();LessonCreditMysqlTest.inject(availability,"db",db);
        var profiles=new EduTeacherProfileService();LessonCreditMysqlTest.inject(profiles,"db",db);LessonCreditMysqlTest.inject(service,"teacherProfiles",profiles);
        LessonCreditMysqlTest.inject(service,"parent",session.getMapper(MiniAppParentMapper.class));
        var courses=new com.ruoyi.system.service.impl.EduCourseScheduleServiceImpl();LessonCreditMysqlTest.inject(courses,"db",db);
        LessonCreditMysqlTest.inject(courses,"singleService",service);LessonCreditMysqlTest.inject(courses,"availability",availability);
        LessonCreditMysqlTest.inject(courses,"eduCourseScheduleMapper",session.getMapper(EduCourseScheduleMapper.class));LessonCreditMysqlTest.inject(courses,"parentMapper",session.getMapper(MiniAppParentMapper.class));
        LessonCreditMysqlTest.inject(courses,"configService",ScheduleCrudTest.proxy(ISysConfigService.class,(p,m,a)->"true"));
        controller=new com.ruoyi.system.controller.AdminOneToOneController();LessonCreditMysqlTest.inject(controller,"single",service);
        LessonCreditMysqlTest.inject(controller,"availability",availability);LessonCreditMysqlTest.inject(controller,"schedules",courses);
        LessonCreditMysqlTest.inject(controller,"mapper",session.getMapper(EduCourseScheduleMapper.class));LessonCreditMysqlTest.inject(controller,"db",db);
        // Package bookings deliberately have no cash bill; use real finance guard without creating payment data.
        var storage=new com.ruoyi.system.finance.FinancePrivateStorage(java.nio.file.Files.createTempDirectory("admin-single-finance-").toString());
        LessonCreditMysqlTest.inject(service,"tuition",new com.ruoyi.system.finance.TuitionService(db,storage,new com.ruoyi.system.finance.TuitionReceiptService(db,storage),new com.ruoyi.system.finance.CouponService(db),true));
    }
    @BeforeEach void rows(){
        for(String t:List.of("edu_tuition_refund","edu_tuition_payment","edu_enrollment_event","edu_class_sign_in","edu_attendance","edu_lesson_credit_event","edu_enrollment","edu_student_lesson_account","edu_schedule_adjustment","edu_course_schedule","edu_teacher_profile","sys_dict_data","sys_user_role","sys_role","edu_classroom","sys_user"))db.jdbc().update("DELETE FROM "+t);
        db.jdbc().update("INSERT INTO sys_user(user_id,user_name,nick_name) VALUES(10,'teacher-fixture','测试教师'),(11,'teacher-other','另一教师'),(20,'parent-fixture','测试家长'),(30,'admin-fixture','业务管理员')");
        db.jdbc().update("INSERT INTO edu_classroom(classroom_id,classroom_code,campus_name,classroom_name) VALUES(1,'TEST','测试','测试教室')");
        db.jdbc().update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES(1,'测试教师','teacher',1,'0','0')");
        db.jdbc().update("INSERT INTO sys_user_role(user_id,role_id) VALUES(10,1),(11,1)");
        db.jdbc().update("INSERT INTO edu_teacher_profile(user_id,teacher_subject,teacher_level) VALUES(10,'数学','senior'),(11,'英语','elite')");
        db.jdbc().update("INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,status) VALUES(1,'8:00-10:00','8:00-10:00','edu_time_slot','0')");
        var future=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(30);
        db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,schedule_code,classroom_id,teacher_id,teacher_name,course_class_name,grade_name,subject_name,term_name,period_name,time_slot,start_time,end_time,start_date,end_date,class_mode,status,recruit_status,del_flag) VALUES(1,'S1',1,10,'测试教师','一对一','五年级','数学','测试','周一','8:00-10:00','08:00:00','10:00:00',?,?,'2','0','0','0')",future,future);
        db.jdbc().update("INSERT INTO edu_student_lesson_account(account_id,parent_id,student_name,grade_name,remaining_credits,reserved_credits,consumed_credits,request_key,create_by,create_time) VALUES(1,20,'学生甲','五年级',8,2,0,'fixture-child-key','fixture',NOW())");
        db.jdbc().update("INSERT INTO edu_enrollment(enrollment_id,enrollment_code,schedule_id,parent_id,student_name,class_date,enrollment_status,pay_status,settlement_method,lesson_account_id,lesson_credits,teacher_level_snapshot,billing_status,del_flag) VALUES(1,'E1',1,20,'学生甲',?,'0','0','P',1,2,'senior','CLOSED','0')",future);
        db.jdbc().update("INSERT INTO edu_lesson_credit_event(account_id,enrollment_id,action,quantity,remaining_after,reserved_after,consumed_after,request_key,reason,actor_id,actor_name,create_time) VALUES(1,1,'HOLD',2,8,2,0,'HOLD:1','报名暂占',20,'parent-fixture',NOW())");
        ScheduleCrudTest.principal(30,"admin");
    }
    Object invoke(String method,Class<?>[] types,Object... args){
        try{return db.transactions().execute(status->{try{return service.getClass().getMethod(method,types).invoke(service,args);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(ReflectiveOperationException e){throw new AssertionError("缺少管理员一对一入口: "+method,e);}});}
        finally { /* actual caller identity must never be swapped */ }
    }
    Object decide(String action,String reason){return invoke("adminDecide",new Class[]{Long.class,String.class,String.class},1L,action,reason);}
    @Test void adminConfirmsAnotherTeachersBookingAndKeepsActualActor(){
        assertEquals(1,decide("CONFIRM",null));
        assertEquals(30L,db.jdbc().queryForObject("SELECT teacher_confirm_by FROM edu_enrollment WHERE enrollment_id=1",Long.class));
        assertEquals("teacher30",db.one("SELECT actor_name FROM edu_enrollment_event WHERE action='CONFIRM'").get("actorName"));
        assertEquals(30L,com.ruoyi.common.utils.SecurityUtils.getUserId());
        ScheduleCrudTest.principal(11,"teacher");assertThrows(ServiceException.class,()->db.transactions().execute(s->service.decide(1L,"CONFIRM",null)));
    }
    @Test void teacherAndEducationCannotUseAdminMethodsEvenWithEnrollmentPermission(){
        for(String role:List.of("teacher","education","parent")){
            ScheduleCrudTest.principal(10,role);
            assertThrows(ServiceException.class,()->decide("CONFIRM",null));
            assertThrows(ServiceException.class,()->invoke("adminComplete",new Class[]{Long.class},1L));
            assertThrows(ServiceException.class,()->invoke("adminHistory",new Class[]{Long.class},1L));
            assertThrows(ServiceException.class,()->invoke("adminTransactions",new Class[]{Long.class,Long.class},null,null));
        }
    }
    @Test void cancellationSharesReleaseLogicAndIsIdempotent(){
        decide("CONFIRM",null);
        ScheduleCrudTest.principal(20,"parent");db.transactions().execute(s->service.requestCancellation(1L,"家长请假"));
        ScheduleCrudTest.principal(30,"admin");decide("APPROVE_CANCEL","已沟通取消");decide("APPROVE_CANCEL","已沟通取消");
        assertEquals(8,db.jdbc().queryForObject("SELECT remaining_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
        assertEquals(0,db.jdbc().queryForObject("SELECT reserved_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_lesson_credit_event WHERE action='RELEASE'",Integer.class));
        assertEquals(30L,db.jdbc().queryForObject("SELECT cancel_processed_by FROM edu_enrollment WHERE enrollment_id=1",Long.class));
        assertThrows(ServiceException.class,()->decide("REJECT",""));
    }
    @Test void adminCompletionConsumesSnapshotOnceAndRequiresAttendance(){
        decide("CONFIRM",null);
        assertThrows(ServiceException.class,()->invoke("adminComplete",new Class[]{Long.class},1L));
        var past=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2);
        db.jdbc().update("UPDATE edu_enrollment SET class_date=? WHERE enrollment_id=1",past);
        assertThrows(ServiceException.class,()->invoke("adminComplete",new Class[]{Long.class},1L));
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,enrollment_id,student_name,source_type,sign_status,review_status,del_flag) VALUES(1,?,1,'学生甲','1','1','2','0')",past);
        assertThrows(ServiceException.class,()->invoke("adminComplete",new Class[]{Long.class},1L));
        db.jdbc().update("UPDATE edu_class_sign_in SET review_status='1'");
        invoke("adminComplete",new Class[]{Long.class},1L);invoke("adminComplete",new Class[]{Long.class},1L);
        assertEquals(6,db.jdbc().queryForObject("SELECT remaining_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
        assertEquals(2,db.jdbc().queryForObject("SELECT consumed_credits FROM edu_student_lesson_account WHERE account_id=1",Integer.class));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_lesson_credit_event WHERE action='CONSUME' AND actor_id=30",Integer.class));
    }
    @Test void adminListFiltersByTeacherAndPreservesTeacherScope(){
        var all=(List<?>)invoke("adminTransactions",new Class[]{Long.class,Long.class},null,null);assertEquals(1,all.size());
        assertEquals(0,((List<?>)invoke("adminTransactions",new Class[]{Long.class,Long.class},11L,null)).size());
        assertEquals(0,((List<?>)invoke("adminTransactions",new Class[]{Long.class,Long.class},null,2L)).size());
        ScheduleCrudTest.principal(11,"teacher");assertEquals(0,service.transactions().size());
    }
    com.ruoyi.system.controller.AdminOneToOneController.Draft draft(LocalDate day){
        var d=new com.ruoyi.system.controller.AdminOneToOneController.Draft();d.teacherId=10L;d.classroomId=1L;d.timeSlot="8:00-10:00";d.startDate=day;d.gradeName="五年级";d.courseClassName="管理员发布数学";return d;
    }
    @Test void adminPublishAndEditKeepRealClassroomAndOperator(){
        var day=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(40);
        var response=db.transactions().execute(s->controller.publish(draft(day)));
        long id=((Number)response.get("data")).longValue();
        assertEquals("teacher30",db.one("SELECT create_by FROM edu_course_schedule WHERE schedule_id=?",id).get("createBy"));
        assertEquals("数学",db.one("SELECT subject_name FROM edu_course_schedule WHERE schedule_id=?",id).get("subjectName"));
        db.transactions().execute(s->controller.edit(id,draft(day.plusDays(1))));
        assertEquals(1L,db.jdbc().queryForObject("SELECT classroom_id FROM edu_course_schedule WHERE schedule_id=?",Long.class,id));
        assertEquals(day.plusDays(1).toString(),db.jdbc().queryForObject("SELECT CAST(start_date AS CHAR) FROM edu_course_schedule WHERE schedule_id=?",String.class,id));
        assertThrows(ServiceException.class,()->db.transactions().execute(s->controller.publish(draft(day.plusDays(1)))));
        var other=draft(day.plusDays(1));other.teacherId=11L;
        assertThrows(ServiceException.class,()->db.transactions().execute(s->controller.publish(other)));
        assertThrows(ServiceException.class,()->db.transactions().execute(s->controller.withdraw(1L)));
    }
    @Test void publicationRejectsInvalidTeachersSlotsAndRoomExclusion(){
        var day=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(42);
        var d=draft(day);d.teacherId=20L;assertThrows(ServiceException.class,()->controller.publish(d));
        d.teacherId=10L;d.timeSlot="08:01-10:01";assertThrows(ServiceException.class,()->controller.publish(d));
        assertThrows(ServiceException.class,()->controller.availability(20L,day,1L));
        assertEquals("@ss.hasRole('admin')",controller.getClass().getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
    }
    @Test void cancellingPaidCashReservationDoesNotFabricateARefund(){
        db.jdbc().update("DELETE FROM edu_lesson_credit_event");db.jdbc().update("UPDATE edu_student_lesson_account SET reserved_credits=0");
        db.jdbc().update("UPDATE edu_enrollment SET settlement_method='C',lesson_credits=NULL,teacher_level_snapshot=NULL,billing_status='OPEN',finance_mode='REAL',original_amount=100,discount_amount=0,payable_amount=100,price_confirmed_by=30,price_confirmed_time=NOW(),pay_status='已支付' WHERE enrollment_id=1");
        db.jdbc().update("INSERT INTO edu_tuition_payment(payment_id,payment_no,enrollment_id,channel,payment_status,finance_mode,amount,payer_name,bill_snapshot,recorded_by,confirmed_by,confirmed_time,paid_time,idempotency_key,request_hash) VALUES(1,'TPTEST',1,'CASH','SUCCESS','REAL',100,'测试家长','{}',30,30,NOW(),NOW(),'fixture-payment',REPEAT('a',64))");
        decide("REJECT","管理员拒绝，退费另行处理");
        assertEquals("SUCCESS",db.one("SELECT payment_status FROM edu_tuition_payment WHERE payment_id=1").get("paymentStatus"));
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_tuition_refund",Integer.class));
        assertEquals("OPEN",db.one("SELECT billing_status FROM edu_enrollment WHERE enrollment_id=1").get("billingStatus"));
        assertEquals("已支付",db.one("SELECT pay_status FROM edu_enrollment WHERE enrollment_id=1").get("payStatus"));
    }
    @AfterEach void clear(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @AfterAll void cleanup()throws Exception{
        if(!schema.matches("codex_admin_single_test_[a-f0-9]{12}"))throw new IllegalStateException("Unsafe schema");
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("DROP DATABASE IF EXISTS "+schema);}
    }
}
