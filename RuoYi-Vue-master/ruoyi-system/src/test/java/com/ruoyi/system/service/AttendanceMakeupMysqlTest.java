package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Uses only a UUID-named disposable schema; never connects to a business schema. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceMakeupMysqlTest {
    final AttendanceLessonRosterMysqlTest fixture=new AttendanceLessonRosterMysqlTest();
    ShopRepository db;AttendanceLessonRosterService rosters;AttendanceMakeupService service;
    LocalDate targetDate=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7);
    static long id(Map<String,Object> r,String k){return AttendanceLessonRosterMysqlTest.id(r,k);}
    static void principal(long actor,String role){AttendanceLessonRosterMysqlTest.principal(actor,role);}
    <T>T tx(Callable<T> action){return fixture.tx(action);}
    @BeforeAll void setup()throws Exception {
        fixture.date=targetDate.minusDays(30);fixture.setup();db=fixture.db;
        assertTrue(db.jdbc().queryForObject("SELECT DATABASE()",String.class).matches("codex_[a-zA-Z0-9_]+"));
        db.jdbc().execute("ALTER TABLE edu_class_sign_in ADD sign_status CHAR(1)");
        db.jdbc().execute("ALTER TABLE edu_course_schedule ADD teacher_name VARCHAR(64)");
        for(String file:List.of("20261106_attendance_lesson_roster.sql","20261118_attendance_lesson_guests.sql","20261122_attendance_makeup.sql","20261123_teacher_led_makeup.sql")) {
            String migration=Files.readString(Path.of("../../database/migrations/"+file));
            for(int i=0;i<2;i++)tx(()->{for(String statement:migration.split(";"))if(!statement.isBlank())db.jdbc().execute(statement);return null;});
        }
        db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(2,'补课班',?,'秋季','周一','DAILY',?,?,'14:00','15:00',10,'1'),(3,'冲突班',?,'秋季','周一','DAILY',?,?,'14:30','15:30',10,'1')",targetDate.getYear(),targetDate,targetDate.plusDays(4),targetDate.getYear(),targetDate,targetDate);
        db.jdbc().update("INSERT INTO sys_user_role VALUES(10,3)");
        rosters=new AttendanceLessonRosterService(db);service=new AttendanceMakeupService(db,rosters);
    }
    @AfterAll void cleanup()throws Exception {fixture.cleanup();}
    private long sign(long schedule,LocalDate date,String status,String stage,String review) {
        return db.insert("edu_class_sign_in",Map.of("schedule_id",schedule,"class_date",date,"student_id",1L,"student_name","同名孩子","source_type",schedule==1?"1":"3","sign_status",status,"review_stage",stage,"review_status",review));
    }
    @Test void arrangementCancellationReviewAndRetryPreserveHistoryAndOriginalAttendance()throws Exception {
        principal(10,"teacher");
        var originals=tx(()->rosters.lessons(1));long origin=id(originals.get(0),"lessonId");
        long source=sign(1,fixture.date,"3","FINAL","1");
        tx(()->{rosters.lockLesson(1,fixture.date.toString(),10L);return null;});
        var originalSign=db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=?",source);
        var originalLesson=db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",origin);
        var finance=db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id");
        principal(10,"teacher");assertDoesNotThrow(()->tx(()->service.options(source,2L,"")));
        principal(30,"admin");assertThrows(ServiceException.class,()->tx(()->service.options(source,2L,"")),"JWT role alone cannot grant staff access");
        principal(10,"teacher");
        var options=tx(()->service.options(source,2L,""));
        @SuppressWarnings("unchecked") var lessons=(List<Map<String,Object>>)options.get("lessons");
        assertEquals(5,lessons.size());long target=id(lessons.get(0),"lessonId"),next=id(lessons.get(1),"lessonId"),last=id(lessons.get(2),"lessonId");
        var input=new AttendanceMakeupService.ArrangeRequest(source,target,"请假补课");
        var plan=tx(()->service.arrange(input));long planId=id(plan,"planId"),firstGuest=id(plan,"guestId");
        assertEquals(planId,id(tx(()->service.arrange(input)),"planId"));
        assertEquals(1,db.rows("SELECT * FROM edu_attendance_makeup WHERE cancelled=0").size());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.insert("edu_attendance_makeup",Map.of("sign_in_id",source,"source_lesson_id",origin,"student_id",1,"target_lesson_id",next,"guest_id",firstGuest,"operator_id",20)));
        assertTrue(tx(()->rosters.roster(2,target)).stream().anyMatch(r->"1".equals(r.get("studentId"))&&"TRANSFER".equals(r.get("source"))));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(source,next,""))));
        var report=new AttendanceLeaveReportService(db);
        assertEquals(true,tx(()->report.report(Map.of("scope","teacher"))).get("canManageMakeup"));
        assertEquals(false,tx(()->report.report(Map.of())).get("canManageMakeup"));
        var scheduled=tx(()->report.report(Map.of()));
        assertEquals(1,((Number)scheduled.get("scheduledCount")).intValue());assertEquals(1,((Number)scheduled.get("pendingCount")).intValue());
        principal(11,"teacher");assertThrows(ServiceException.class,()->tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(planId,""))));
        principal(10,"teacher");tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(planId,"改期")));
        assertEquals(0,db.rows("SELECT * FROM edu_attendance_lesson_guest WHERE lesson_id=?",target).size());
        assertEquals(1,((Number)db.one("SELECT cancelled FROM edu_attendance_makeup WHERE plan_id=?",planId).get("cancelled")).intValue());
        // Roster duplication, time collisions and submitted/locked/cancelled targets all reject.
        db.jdbc().update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name) VALUES(3,3,1,30,'同名孩子')");
        assertThrows(ServiceException.class,()->tx(()->service.arrange(input)));
        db.jdbc().update("DELETE FROM edu_enrollment WHERE enrollment_id=3");
        long member=db.insert("edu_attendance_membership",Map.of("schedule_id",3,"student_id",1,"start_lesson",1,"source","OFFLINE","payment_status","UNPAID","reason","名单冲突验证","operator_id",20));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(input)));
        db.jdbc().update("DELETE FROM edu_attendance_membership WHERE membership_id=?",member);
        long collisionLesson=id(tx(()->rosters.lessons(3)).get(0),"lessonId");
        long collisionGuest=db.insert("edu_attendance_lesson_guest",Map.of("lesson_id",collisionLesson,"student_id",1,"source","TRIAL","operator_id",20));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(input)));
        db.jdbc().update("DELETE FROM edu_attendance_lesson_guest WHERE guest_id=?",collisionGuest);
        db.jdbc().update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name) VALUES(3,2,1,30,'同名孩子')");
        assertThrows(ServiceException.class,()->tx(()->service.arrange(input)));
        db.jdbc().update("DELETE FROM edu_enrollment WHERE enrollment_id=3");
        db.jdbc().update("UPDATE edu_attendance_lesson SET locked=1 WHERE lesson_id=?",target);
        assertThrows(ServiceException.class,()->tx(()->service.arrange(input)));
        db.jdbc().update("UPDATE edu_attendance_lesson SET locked=0 WHERE lesson_id=?",target);
        // Adopted submitted attendance is locked; never insert a guest into it.
        long unrelated=db.insert("edu_class_sign_in",Map.of("schedule_id",2,"class_date",targetDate,"student_id",2,"student_name","同名孩子","source_type","1","sign_status","1","review_stage","PENDING_FIRST","review_status","0"));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(input)));
        db.jdbc().update("DELETE FROM edu_class_sign_in WHERE sign_in_id=?",unrelated);
        db.jdbc().update("UPDATE edu_attendance_lesson SET locked=0,roster_snapshot=NULL WHERE lesson_id=?",target);
        plan=tx(()->service.arrange(input));long retryPlan=id(plan,"planId");
        long targetSign=sign(2,targetDate,"3","PENDING_FINAL","0");
        tx(()->{rosters.lockLesson(2,targetDate.toString(),10L);return null;});
        assertThrows(ServiceException.class,()->tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(retryPlan,"审核期间"))));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(source,next,"审核期间重排"))));
        var pendingReview=tx(()->report.report(Map.of()));
        assertEquals(1,((Number)pendingReview.get("scheduledCount")).intValue());assertEquals(0,((Number)pendingReview.get("makeupCount")).intValue());
        db.jdbc().update("UPDATE edu_class_sign_in SET review_stage='FINAL',review_status='1' WHERE sign_in_id=?",targetSign);
        var failed=tx(()->report.report(Map.of()));assertEquals(1,((Number)failed.get("total")).intValue());
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)failed.get("rows");
        @SuppressWarnings("unchecked") var details=(List<Map<String,Object>>)rows.get(0).get("lessons");assertEquals("failed",details.get(0).get("state"));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(targetSign,last,"不能产生新欠课"))));
        var failedSign=db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=?",targetSign);
        var retry=tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(source,next,"终审未到课后重排")));long finalPlan=id(retry,"planId");
        assertEquals(failedSign,db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=?",targetSign));
        assertEquals(1,((Number)db.one("SELECT cancelled FROM edu_attendance_makeup WHERE plan_id=?",retryPlan).get("cancelled")).intValue());
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(targetSign,last,"历史仍不能产生新欠课"))));
        sign(2,targetDate.plusDays(1),"5","FINAL","1");
        var completed=tx(()->report.report(Map.of()));assertEquals(0,((Number)completed.get("pendingCount")).intValue());assertEquals(1,((Number)completed.get("makeupCount")).intValue());
        assertThrows(ServiceException.class,()->tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(finalPlan,"已到课"))));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(source,last,"已到课"))));
        assertEquals(3,((List<?>)tx(()->service.options(source,2L,"")).get("history")).size());
        assertEquals(originalSign,db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=?",source));
        assertEquals(originalLesson,db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",origin));
        assertEquals(finance,db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
        // The same original leave is serialized across concurrent requests.
        long secondSource=sign(1,fixture.date.plusDays(1),"3","PENDING_FIRST","0");
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(secondSource,last,"待审原记录"))));
        db.jdbc().update("UPDATE edu_class_sign_in SET review_stage='FINAL',review_status='1' WHERE sign_in_id=?",secondSource);
        long duplicate=sign(1,fixture.date.plusDays(1),"3","FINAL","1");
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(secondSource,last,"重复原记录"))));
        db.jdbc().update("DELETE FROM edu_class_sign_in WHERE sign_in_id=?",duplicate);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Long>>();
        try {
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{principal(10,"teacher");gate.await();return id(tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(secondSource,last,"并发安排"))),"planId");}));
            gate.countDown();long firstPlan=futures.get(0).get(20,TimeUnit.SECONDS);
            assertEquals(firstPlan,futures.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,db.rows("SELECT * FROM edu_attendance_makeup WHERE sign_in_id=? AND cancelled=0",secondSource).size());
            principal(40,"business_admin");
            db.jdbc().update("UPDATE sys_role SET status='1' WHERE role_key='business_admin'");
            assertThrows(ServiceException.class,()->tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(firstPlan,"停用角色"))));
            db.jdbc().update("UPDATE sys_role SET status='0' WHERE role_key='business_admin'");
            principal(10,"teacher");
            // A cancelled target can retire the arrangement without losing its original sign-in.
            db.insert("edu_schedule_adjustment",Map.of("schedule_id",2,"original_date",targetDate.plusDays(2),"reason","取消目标讲次"));
            tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(firstPlan,"目标课取消")));
            assertEquals(0,db.rows("SELECT * FROM edu_attendance_lesson_guest WHERE lesson_id=?",last).size());
        } finally {pool.shutdownNow();}
        // Removing or disabling a target course must not trap a historical arrangement.
        principal(10,"teacher");
        for(int schedule:List.of(4,5))db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(?,'停用课程退回测试',?,'秋季','周一','DAILY',?,?,'14:00','15:00',10,'1')",schedule,targetDate.getYear(),targetDate.plusDays(schedule+5),targetDate.plusDays(schedule+5));
        long removedTarget=id(tx(()->rosters.lessons(4)).get(0),"lessonId");
        long replacementTarget=id(tx(()->rosters.lessons(5)).get(0),"lessonId");
        long thirdSource=sign(1,fixture.date.plusDays(2),"3","FINAL","1");
        var thirdOriginal=db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=?",thirdSource);
        var removedInput=new AttendanceMakeupService.ArrangeRequest(thirdSource,removedTarget,"课程删除后的取消与重排");
        long removedPlan=id(tx(()->service.arrange(removedInput)),"planId");
        db.jdbc().update("UPDATE edu_course_schedule SET del_flag='2' WHERE schedule_id=4");
        tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(removedPlan,"课程已删除")));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(removedInput)),"new plans still reject deleted courses");
        db.jdbc().update("UPDATE edu_course_schedule SET del_flag='0' WHERE schedule_id=4");
        long readdedPlan=id(tx(()->service.arrange(removedInput)),"planId");
        db.jdbc().update("UPDATE edu_course_schedule SET del_flag='2' WHERE schedule_id=4");
        var replacement=tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(thirdSource,replacementTarget,"课程已删除后直接重排")));
        assertEquals(1,((Number)db.one("SELECT cancelled FROM edu_attendance_makeup WHERE plan_id=?",readdedPlan).get("cancelled")).intValue());
        assertTrue(db.rows("SELECT guest_id FROM edu_attendance_lesson_guest WHERE lesson_id=?",removedTarget).isEmpty());
        db.jdbc().update("UPDATE edu_course_schedule SET status='1' WHERE schedule_id=5");
        long replacementPlan=id(replacement,"planId");
        tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(replacementPlan,"课程已停用")));
        assertThrows(ServiceException.class,()->tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(thirdSource,replacementTarget,"停用课程不能新安排"))));
        assertEquals(thirdOriginal,db.one("SELECT * FROM edu_class_sign_in WHERE sign_in_id=?",thirdSource));
        assertEquals(finance,db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
        System.out.println("PASS: makeup arrange/idempotency/cancel/review/retry, roster & overlap checks, stable history, immutable original/finance, DB roles, report closed loop");
    }
}
