package com.ruoyi.system.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.EduCourseScheduleServiceImpl;
import com.ruoyi.system.shop.ShopRepository;
import static org.junit.jupiter.api.Assertions.*;

/** 显式运行的隔离 MySQL 回归；拒绝连接业务库，不由默认构建自动执行。 */
public class LessonCreditMysqlTest {
    ShopRepository db; LessonCreditService credits; OneToOneEnrollmentService single; EduCourseScheduleServiceImpl courses;
    EduTeacherProfileService profiles; ScheduleAvailabilityService availability; com.ruoyi.system.finance.TuitionService payments;
    com.ruoyi.system.service.impl.EduScheduleAdjustmentServiceImpl adjustments;
    com.ruoyi.system.service.impl.EduClassroomServiceImpl classrooms;
    EduTeacherImportService imports;
    SqlSessionTemplate session;
    long teacher,otherTeacher,parent,otherParent,room,otherRoom; String grade; LocalDate date;
    static void inject(Object target,String name,Object value) throws Exception { var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value); }
    void setup() throws Exception {
        String url=System.getenv("MINGYUAN_TEST_DB_URL");
        if(url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/mingyuan_single_test_[A-Za-z0-9_]+(?:\\?.*)?")) throw new IllegalStateException("Only an isolated mingyuan_single_test_ database is allowed");
        var source=new DriverManagerDataSource(url,"root",System.getenv("MYSQL_PWD"));db=new ShopRepository(source);
        db.transactions().setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setTypeAliasesPackage("com.ruoyi.system.domain,com.ruoyi.common.core.domain.entity");
        factory.setMapperLocations(new ClassPathResource("mapper/system/EduCourseScheduleMapper.xml"),new ClassPathResource("mapper/system/EduEnrollmentMapper.xml"),new ClassPathResource("mapper/system/MiniAppParentMapper.xml"),new ClassPathResource("mapper/system/EduScheduleAdjustmentMapper.xml"),new ClassPathResource("mapper/system/EduClassroomMapper.xml"),new ClassPathResource("mapper/system/SysUserMapper.xml"));
        session=new SqlSessionTemplate(factory.getObject());
        credits=new LessonCreditService();inject(credits,"db",db);profiles=new EduTeacherProfileService();inject(profiles,"db",db);
        availability=new ScheduleAvailabilityService();inject(availability,"db",db);
        single=new OneToOneEnrollmentService();inject(single,"db",db);inject(single,"credits",credits);inject(single,"teacherProfiles",profiles);
        inject(single,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(single,"parent",session.getMapper(MiniAppParentMapper.class));inject(single,"enrollments",session.getMapper(EduEnrollmentMapper.class));
        courses=new EduCourseScheduleServiceImpl();inject(courses,"db",db);inject(courses,"availability",availability);inject(courses,"singleService",single);inject(courses,"eduCourseScheduleMapper",session.getMapper(EduCourseScheduleMapper.class));inject(courses,"parentMapper",session.getMapper(MiniAppParentMapper.class));
        var financeRoot=java.nio.file.Files.createTempDirectory("mingyuan-finance-test-");
        new com.ruoyi.common.config.RuoYiConfig().setProfile(financeRoot.resolve("public").toString());
        var storage=new com.ruoyi.system.finance.FinancePrivateStorage(financeRoot.resolve("private").toString());
        payments=new com.ruoyi.system.finance.TuitionService(db,storage,new com.ruoyi.system.finance.TuitionReceiptService(db,storage),new com.ruoyi.system.finance.CouponService(db),true);
        inject(single,"tuition",payments);
        adjustments=new com.ruoyi.system.service.impl.EduScheduleAdjustmentServiceImpl();inject(adjustments,"eduScheduleAdjustmentMapper",session.getMapper(EduScheduleAdjustmentMapper.class));inject(adjustments,"singleSchedules",session.getMapper(EduCourseScheduleMapper.class));inject(adjustments,"singleDb",db);inject(adjustments,"availability",availability);
        classrooms=new com.ruoyi.system.service.impl.EduClassroomServiceImpl();inject(classrooms,"eduClassroomMapper",session.getMapper(EduClassroomMapper.class));inject(classrooms,"db",db);inject(classrooms,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(classrooms,"availability",availability);
        imports=new EduTeacherImportService();inject(imports,"profiles",profiles);inject(imports,"users",session.getMapper(SysUserMapper.class));
        var teachers=db.rows("SELECT u.user_id FROM sys_user u WHERE u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0') ORDER BY user_id LIMIT 2");
        teacher=id(teachers.get(0),"userId");otherTeacher=id(teachers.get(1),"userId");
        var parents=db.rows("SELECT u.user_id FROM sys_user u WHERE u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='parent' AND r.status='0' AND r.del_flag='0') ORDER BY user_id LIMIT 2");
        parent=id(parents.get(0),"userId");otherParent=id(parents.get(1),"userId");
        var rooms=db.rows("SELECT classroom_id FROM edu_classroom WHERE status='0' ORDER BY classroom_id LIMIT 2");room=id(rooms.get(0),"classroomId");otherRoom=id(rooms.get(1),"classroomId");
        grade=db.one("SELECT dict_label FROM sys_dict_data WHERE dict_type='edu_grade' AND status='0' ORDER BY dict_sort LIMIT 1").get("dictLabel").toString();
        String subject=db.one("SELECT dict_label FROM sys_dict_data WHERE dict_type='edu_subject' AND status='0' ORDER BY dict_sort LIMIT 1").get("dictLabel").toString();
        ScheduleCrudTest.principal(1,"admin");tx(()->{profiles.save(teacher,subject,"senior","mysql-check");profiles.save(otherTeacher,subject,"elite","mysql-check");return null;});
        date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusYears(2);
    }
    <T> T tx(Callable<T> task) { return db.transactions().execute(status->{try{return task.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}}); }
    static long id(Map<String,Object> row,String key){return Long.parseLong(row.get(key).toString());}
    EduCourseSchedule draft(long roomId,LocalDate day) { var d=new TeacherOneToOneDraft();d.startDate=day;d.classroomId=roomId;d.gradeName=grade;d.courseClassName="isolated fixed slot check";return d.toSchedule(single.subject(com.ruoyi.common.utils.SecurityUtils.getUserId()),availability.slots().get(0)); }
    long publish(long who,long roomId,LocalDate day) { ScheduleCrudTest.principal(who,"teacher");return tx(()->{var s=draft(roomId,day);courses.insertEduCourseSchedule(s);return s.getScheduleId();}); }
    long account(long parentId,String student) {ScheduleCrudTest.principal(1,"admin");var a=new LessonCreditService.AccountDraft();a.parentId=parentId;a.studentName=student;a.gradeName=grade;a.requestKey=UUID.randomUUID().toString();return tx(()->credits.create(a));}
    void adjust(long account,String action,int n,String key) {ScheduleCrudTest.principal(1,"admin");var a=new LessonCreditService.Adjustment();a.action=action;a.quantity=n;a.reason="isolated test";a.requestKey=key;tx(()->{credits.adjust(account,a);return null;});}
    long enroll(long who,long schedule,LocalDate day,String name,Long account) {ScheduleCrudTest.principal(who,"parent");var b=new MiniAppEnrollmentBody();b.setScheduleId(schedule);b.setClassDate(day.toString());b.setStudentName(name);b.setSettlementMethod(account == null?"C":"P");b.setLessonAccountId(account);b.setStudentRequestKey(UUID.randomUUID().toString());tx(()->single.enroll(b));return db.jdbc().queryForObject("SELECT MAX(enrollment_id) FROM edu_enrollment WHERE schedule_id=?",Long.class,schedule);}
    void payCash(long enrollment) {
        ScheduleCrudTest.principal(1,"admin");
        var e=db.one("SELECT financial_version FROM edu_enrollment WHERE enrollment_id=?",enrollment);
        var priced=payments.price(enrollment,1,Map.of("originalAmount","100","financeMode","MOCK","financialVersion",e.get("financialVersion")));
        var bill=db.one("SELECT financial_version FROM edu_enrollment WHERE enrollment_id=?",enrollment);
        var p=payments.createPayment(enrollment,1,true,Map.of("channel","MOCK","expectedPayableAmount","100","financialVersion",bill.get("financialVersion"),"idempotencyKey",UUID.randomUUID().toString()));
        payments.confirmPayment(id(p,"paymentId"),1,true,Map.of());
    }
    void balances(long account,int remaining,int reserved,int consumed) {var a=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=?",account);assertEquals(remaining,id(a,"remainingCredits"));assertEquals(reserved,id(a,"reservedCredits"));assertEquals(consumed,id(a,"consumedCredits"));}
    void run() throws Exception {
        long a=account(parent,"学生甲"),b=account(parent,"学生乙");String key=UUID.randomUUID().toString();adjust(a,"GRANT",5,key);adjust(a,"GRANT",5,key);balances(a,5,0,0);balances(b,0,0,0);
        assertThrows(ServiceException.class,()->adjust(a,"GRANT",6,key));assertThrows(ServiceException.class,()->adjust(a,"WITHDRAW",6,UUID.randomUUID().toString()));
        ScheduleCrudTest.principal(teacher,"teacher");assertThrows(ServiceException.class,()->credits.accounts(""));assertThrows(ServiceException.class,()->payments.price(1L,teacher,Map.of("originalAmount","100")));
        long s=publish(teacher,room,date);
        // 教室容量不再限制排课（报名名额不限）；同教室同时段允许重复排课（前端红色提示），此处仅保留教师冲突的硬校验。
        assertThrows(ServiceException.class,()->publish(teacher,otherRoom,date));
        ScheduleCrudTest.principal(teacher,"teacher");var invalid=draft(otherRoom,date.plusDays(3));invalid.setStartTime(LocalTime.of(8,1));assertThrows(ServiceException.class,()->tx(()->courses.insertEduCourseSchedule(invalid)));
        long e=enroll(parent,s,date,"学生甲",a);balances(a,5,2,0);
        assertEquals(2,id(db.one("SELECT lesson_credits FROM edu_enrollment WHERE enrollment_id=?",e),"lessonCredits"));
        ScheduleCrudTest.principal(otherParent,"parent");assertThrows(ServiceException.class,()->credits.history(a,false));
        long s2=publish(teacher,room,date.plusDays(1));assertThrows(ServiceException.class,()->enroll(otherParent,s2,date.plusDays(1),"学生甲",a));
        ScheduleCrudTest.principal(1,"admin");tx(()->{profiles.save(teacher,single.subject(teacher),"elite","mysql-check");return null;});
        adjust(a,"WITHDRAW",3,UUID.randomUUID().toString());balances(a,2,2,0);assertThrows(ServiceException.class,()->adjust(a,"WITHDRAW",1,UUID.randomUUID().toString()));
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(e,"CONFIRM",null));
        ScheduleCrudTest.principal(parent,"parent");tx(()->single.requestCancellation(e,"isolated cancel"));balances(a,2,2,0);
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(e,"APPROVE_CANCEL","isolated cancel"));tx(()->single.decide(e,"APPROVE_CANCEL","isolated cancel"));balances(a,2,0,0);
        long e2=enroll(parent,s,date,"学生甲",a);balances(a,2,1,0);
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(e2,"REJECT","test reject"));balances(a,2,0,0);
        long cash=enroll(parent,s2,date.plusDays(1),"首次付费学生",null);
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(cash,"CONFIRM",null));
        assertEquals("0",db.one("SELECT pay_status FROM edu_enrollment WHERE enrollment_id=?",cash).get("payStatus"));
        ScheduleCrudTest.principal(1,"admin");payCash(cash);assertEquals("已支付",db.one("SELECT pay_status FROM edu_enrollment WHERE enrollment_id=?",cash).get("payStatus"));
        assertThrows(ServiceException.class,()->tx(()->{payments.price(e2,1,Map.of("originalAmount","100","financeMode","MOCK","financialVersion",0));return null;}));
        long completed=enroll(parent,s,date,"学生甲",a);
        ScheduleCrudTest.principal(teacher,"teacher");tx(()->single.decide(completed,"CONFIRM",null));
        LocalDate past=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2);
        db.jdbc().update("UPDATE edu_enrollment SET class_date=? WHERE enrollment_id=?",past,completed);
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,enrollment_id,student_name,source_type,sign_status,review_status,del_flag) VALUES(?,?,?,'学生甲','1','1','0','0')",s,past,completed);
        tx(()->single.complete(completed));tx(()->single.complete(completed));balances(a,1,0,1);
        assertEquals(1L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_lesson_credit_event WHERE enrollment_id=? AND action='CONSUME'",Long.class,completed));
        assertThrows(ServiceException.class,()->tx(()->single.decide(completed,"APPROVE_CANCEL","cannot undo")));
        // 并发收回只能有一个成功，不能把同一个可用课次扣成负数。
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);var won=new AtomicInteger();
        List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{try{gate.await();adjust(a,"WITHDRAW",1,UUID.randomUUID().toString());won.incrementAndGet();}catch(ServiceException expected){}catch(Exception ex){throw new RuntimeException(ex);}}));gate.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);pool.shutdownNow();assertEquals(1,won.get());balances(a,0,0,1);
        long raceAccount=account(parent,"并发预约学生");adjust(raceAccount,"GRANT",1,UUID.randomUUID().toString());long s3=publish(teacher,room,date.plusDays(4)),s4=publish(teacher,room,date.plusDays(5));
        var pool2=Executors.newFixedThreadPool(2);var gate2=new CountDownLatch(1);var booked=new AtomicInteger();tasks.clear();
        for(long course:new long[]{s3,s4})tasks.add(pool2.submit(()->{try{gate2.await();enroll(parent,course,course==s3?date.plusDays(4):date.plusDays(5),"并发预约学生",raceAccount);booked.incrementAndGet();}catch(ServiceException expected){}catch(Exception ex){throw new RuntimeException(ex);}}));gate2.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);pool2.shutdownNow();assertEquals(1,booked.get());balances(raceAccount,1,1,0);
        // 已发布课程占用教室；调课和撤销也必须复核，失败不能留下半次修改。
        long move=publish(teacher,otherRoom,date.plusDays(8));publish(teacher,otherRoom,date.plusDays(9));
        var adj=new EduScheduleAdjustment();adj.setScheduleId(move);adj.setOriginalDate(java.sql.Date.valueOf(date.plusDays(8)));adj.setAdjustedDate(java.sql.Date.valueOf(date.plusDays(9)));
        assertThrows(ServiceException.class,()->tx(()->adjustments.insertEduScheduleAdjustment(adj)));
        assertEquals(0L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_schedule_adjustment WHERE schedule_id=?",Long.class,move));
        adj.setAdjustedDate(java.sql.Date.valueOf(date.plusDays(10)));tx(()->adjustments.insertEduScheduleAdjustment(adj));
        publish(otherTeacher,otherRoom,date.plusDays(8));
        assertThrows(ServiceException.class,()->tx(()->adjustments.deleteEduScheduleAdjustmentByAdjustmentId(adj.getAdjustmentId())));
        assertEquals(1L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_schedule_adjustment WHERE schedule_id=?",Long.class,move));
        var disable=new EduClassroom();disable.setClassroomId(otherRoom);disable.setStatus("1");
        assertThrows(ServiceException.class,()->tx(()->classrooms.updateEduClassroom(disable)));
        assertEquals("0",db.one("SELECT status FROM edu_classroom WHERE classroom_id=?",otherRoom).get("status"));
        long seat=publish(teacher,room,date.plusDays(12));
        var seatPool=Executors.newFixedThreadPool(2);var seatGate=new CountDownLatch(1);var seats=new AtomicInteger();tasks.clear();
        try {
            for(long applicant:new long[]{parent,otherParent})tasks.add(seatPool.submit(()->{try{seatGate.await();enroll(applicant,seat,date.plusDays(12),"单次并发学生",null);seats.incrementAndGet();}catch(ServiceException expected){}catch(Exception ex){throw new RuntimeException(ex);}}));
            seatGate.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);
        } finally {seatPool.shutdownNow();}
        assertEquals(1,seats.get());
        // 原导入链路写入业务资料；失败和越权不得改教师或用户资料。
        var row=new EduTeacherUserImport();row.setUserId(teacher);row.setNickName("隔离导入验证");row.setTeacherLevel("资深教师");
        ScheduleCrudTest.principal(1,"admin");tx(()->imports.update(row,"isolated-import"));assertEquals("senior",profiles.get(teacher).get("teacherLevel"));
        row.setNickName("失败不应保存");row.setTeacherLevel("invalid");assertThrows(ServiceException.class,()->tx(()->imports.update(row,"isolated-import")));
        assertEquals("隔离导入验证",db.one("SELECT nick_name FROM sys_user WHERE user_id=?",teacher).get("nickName"));
        ScheduleCrudTest.principal(teacher,"teacher");row.setTeacherLevel("elite");assertThrows(ServiceException.class,()->tx(()->imports.update(row,"forged-import")));
        System.out.println("PASS: isolated MySQL fixed slots, adjustment/undo rollback, classroom protection, role/child isolation, senior snapshot, idempotency, release/consume, cash registration, concurrent balance/booking/seat and teacher import scenarios");
    }
    public static void main(String[] args) throws Exception {var test=new LessonCreditMysqlTest();test.setup();test.run();}
}
