package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceTeacherMakeupMysqlTest {
    final AttendanceLessonRosterMysqlTest f=new AttendanceLessonRosterMysqlTest();
    AttendanceMakeupService service;AttendanceLessonRosterService rosters;
    LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(10);
    static long id(Map<String,Object> row,String key){return AttendanceLessonRosterMysqlTest.id(row,key);}
    static void actor(long id,String role){AttendanceLessonRosterMysqlTest.principal(id,role);}
    @BeforeAll void setup() throws Exception {
        f.date=day.minusDays(30);f.setup();
        f.db.jdbc().execute("ALTER TABLE edu_class_sign_in ADD sign_status CHAR(1)");
        f.db.jdbc().execute("ALTER TABLE edu_course_schedule ADD teacher_name VARCHAR(64),ADD classroom_id BIGINT");
        f.db.jdbc().execute("CREATE TABLE edu_classroom(classroom_id BIGINT PRIMARY KEY,campus_name VARCHAR(50),classroom_name VARCHAR(50))");
        f.tx(()->{for(String name:List.of("20261106_attendance_lesson_roster.sql","20261118_attendance_lesson_guests.sql","20261122_attendance_makeup.sql","20261123_teacher_led_makeup.sql"))for(int i=0;i<2;i++)for(String sql:Files.readString(Path.of("../../database/migrations/"+name)).split(";"))if(!sql.isBlank())f.db.jdbc().execute(sql);return null;});
        f.db.jdbc().execute("ALTER TABLE edu_student ADD student_phone VARCHAR(11)");
        f.db.jdbc().execute("CREATE TABLE sys_dict_data(dict_label VARCHAR(30),dict_type VARCHAR(30),dict_value VARCHAR(30),status CHAR(1),dict_code BIGINT)");
        f.db.jdbc().execute("CREATE TABLE edu_student_lesson_account(account_id BIGINT PRIMARY KEY,parent_id BIGINT,student_id BIGINT,remaining_credits INT DEFAULT 10,reserved_credits INT DEFAULT 0,consumed_credits INT DEFAULT 0,request_key VARCHAR(50),create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME)");
        f.db.jdbc().update("INSERT INTO edu_student_lesson_account(account_id,parent_id,student_id) VALUES(1,30,1),(2,30,2)");
        rosters=new AttendanceLessonRosterService(f.db);service=new AttendanceMakeupService(f.db,rosters);
    }
    @AfterAll void cleanup()throws Exception {f.cleanup();}
    long leave(long student){return f.db.insert("edu_class_sign_in",Map.of("schedule_id",1,"class_date",f.date,"student_id",student,"student_name","同名孩子","source_type","1","sign_status","3","review_stage","FINAL","review_status","1"));}
    AttendanceMakeupService.ArrangeRequest custom(long source,String start,String end,String location){return new AttendanceMakeupService.ArrangeRequest(source,null,"安排备注","CUSTOM",day.toString(),start,end,location);}
    @Test void legacyOwnershipChangingWhileTeacherLockWaitsCannotAuthorizeOldTeacher() throws Exception {
        f.db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(110,'旧请假归属竞态',?,'秋季','周一','DAILY',?,?,'01:00','02:00',10,'1')",f.date.getYear(),f.date,f.date);
        long source=f.db.insert("edu_class_sign_in",Map.of("schedule_id",110,"class_date",f.date,"student_id",3,"student_name","线下学生","source_type","1","sign_status","3","review_stage","FINAL","review_status","1"));
        var waiting=new java.util.concurrent.CountDownLatch(1);
        var hooked=new com.ruoyi.system.shop.ShopRepository(f.db.jdbc().getDataSource()) {
            @Override public Map<String,Object> one(String sql,Object... args) {
                if(sql.equals("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE"))waiting.countDown();
                return super.one(sql,args);
            }
        };
        var raced=new AttendanceMakeupService(hooked,new AttendanceLessonRosterService(hooked));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        final java.util.concurrent.Future<?>[] pending=new java.util.concurrent.Future<?>[1];
        try {
            f.tx(()->{
                f.db.one("SELECT user_id FROM sys_user WHERE user_id=10 FOR UPDATE");
                pending[0]=pool.submit(()->{actor(10,"teacher");return hooked.transactions().execute(status->raced.arrange(custom(source,"19:00","20:00","竞态教室")));});
                assertTrue(waiting.await(10,java.util.concurrent.TimeUnit.SECONDS));
                f.db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=11 WHERE schedule_id=110");return null;
            });
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->pending[0].get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class,failure.getCause());
            assertTrue(f.db.rows("SELECT plan_id FROM edu_attendance_makeup WHERE sign_in_id=?",source).isEmpty());
        } finally {pool.shutdownNow();f.db.jdbc().update("DELETE FROM edu_class_sign_in WHERE sign_in_id=?",source);f.db.jdbc().update("DELETE FROM edu_attendance_lesson WHERE schedule_id=110");f.db.jdbc().update("DELETE FROM edu_course_schedule WHERE schedule_id=110");}
    }
    @Test @SuppressWarnings("unchecked") void teacherCustomGroupSessionsKeepHistoryReviewAndFinanceIsolated()throws Exception {
        actor(1,"admin");long origin=id(f.tx(()->rosters.lessons(1)).get(0),"lessonId");
        long a=leave(1),b=leave(2);f.tx(()->{rosters.lockLesson(1,f.date.toString(),10L);return null;});
        var credits=f.db.rows("SELECT * FROM edu_student_lesson_account ORDER BY account_id");
        var signs=f.db.rows("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id");var finance=f.db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id");
        for(long staff:List.of(1L,20L,40L,30L,11L)){
            actor(staff,"teacher");assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(a,"10:00","11:00","A教室"))));
        }
        actor(20,"education");assertFalse((Boolean)f.tx(()->service.options(a,null,"")).get("canManageMakeup"));
        actor(10,"teacher");assertThrows(ServiceException.class,()->f.tx(()->rosters.lessons(1)),"public staff roster API stays restricted");
        var request=custom(a,"10:00","11:00","A教室");
        var plan=f.tx(()->service.arrange(request));long planId=id(plan,"planId"),target=id(plan,"targetLessonId");
        assertEquals("CUSTOM",plan.get("arrangeType"));assertEquals(planId,id(f.tx(()->service.arrange(request)),"planId"));
        var lesson=f.db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",target);long schedule=id(lesson,"scheduleId");
        assertEquals("1",f.db.one("SELECT recruit_status FROM edu_course_schedule WHERE schedule_id=?",schedule).get("recruitStatus"));
        var second=f.tx(()->service.arrange(custom(b,"10:00","11:00","A教室")));assertEquals(target,id(second,"targetLessonId"),"same source teacher time location forms one group");
        assertEquals(2,f.tx(()->rosters.teacherRoster(schedule,day.toString())).size());
        actor(1,"admin");assertThrows(ServiceException.class,()->f.tx(()->rosters.addGuest(new AttendanceLessonRosterService.GuestRequest(schedule,day.toString(),3L,null,"TRIAL",null))));
        actor(20,"education");assertTrue(rosters.courses("").stream().noneMatch(r->String.valueOf(schedule).equals(r.get("scheduleId"))));
        assertThrows(ServiceException.class,()->f.tx(()->rosters.add(new AttendanceLessonRosterService.MembershipRequest(schedule,3L,null,1,null,"OFFLINE","UNPAID","不能绕过补课管理"))));
        actor(10,"teacher");
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(new AttendanceMakeupService.ArrangeRequest(a,target,"","CUSTOM",day.toString(),"10:00","11:00","A教室"))));
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(a,"11:00","10:00","A教室"))));
        var report=new AttendanceLeaveReportService(f.db);
        var parents=new ParentAccountService();ScheduleCrudTest.inject(parents,"db",f.db);ScheduleCrudTest.inject(report,"parents",parents);
        actor(30,"parent");var child=report.parentReport(1L);assertEquals(1,((List<?>)child.get("rows")).size());
        var childRow=(Map<String,Object>)((List<?>)child.get("rows")).get(0);var childTarget=(Map<String,Object>)childRow.get("makeup");
        assertEquals("A教室",childTarget.get("placeText"));assertFalse(childRow.containsKey("canArrange"));assertFalse(childRow.containsKey("contactPhone"));assertFalse(childRow.containsKey("remark"));
        actor(10,"teacher");var teacher=f.tx(()->report.report(Map.of()));assertTrue((Boolean)teacher.get("canManageMakeup"));assertFalse((Boolean)teacher.get("readOnly"));
        actor(20,"education");var staff=f.tx(()->report.report(Map.of()));assertFalse((Boolean)staff.get("canManageMakeup"));assertTrue((Boolean)staff.get("readOnly"));
        actor(10,"teacher");f.tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(planId,"改期")));
        assertEquals(0,((Number)f.db.one("SELECT cancelled FROM edu_attendance_lesson WHERE lesson_id=?",target).get("cancelled")).intValue(),"other student keeps shared session active");
        f.tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(id(second,"planId"),"改期")));
        f.db.insert("edu_schedule_adjustment",Map.of("schedule_id",0,"term_name","秋季","period_name","补课","original_date",day,"adjusted_date",day.plusDays(1),"reason","全期调整"));
        var cancelled=f.tx(()->rosters.makeupLessons(schedule)).get(0);assertEquals(1,((Number)cancelled.get("cancelled")).intValue());assertEquals(day.toString(),cancelled.get("classDate"));
        assertEquals("1",f.db.one("SELECT status FROM edu_course_schedule WHERE schedule_id=?",schedule).get("status"),"empty canceled session releases calendar resources");
        var retry=f.tx(()->service.arrange(request));assertNotEquals(target,id(retry,"targetLessonId"));
        var current=f.db.one("SELECT * FROM edu_attendance_lesson WHERE lesson_id=?",retry.get("targetLessonId"));long currentSchedule=id(current,"scheduleId");
        assertEquals(day.toString(),f.tx(()->rosters.makeupLessons(currentSchedule)).get(0).get("classDate"));
        var availability=new ScheduleAvailabilityService();ScheduleCrudTest.inject(availability,"db",f.db);
        var probe=new com.ruoyi.system.domain.EduCourseSchedule();probe.setTeacherId(10L);probe.setStatus("0");probe.setClassPattern("DAILY");probe.setPeriodName("周一");probe.setStartDate(java.sql.Date.valueOf(day));probe.setEndDate(java.sql.Date.valueOf(day));probe.setStartTime(LocalTime.of(10,30));probe.setEndTime(LocalTime.of(11,30));
        assertThrows(ServiceException.class,()->f.tx(()->{availability.check(probe,List.of());return null;}),"ordinary planning still respects custom calendar under term adjustments");
        assertTrue(((List<Map<String,Object>>)f.tx(()->rosters.todos("teacher",true)).get("items")).stream().anyMatch(r->String.valueOf(currentSchedule).equals(r.get("scheduleId"))));
        var body=new MiniAppSignSubmitBody();body.setScheduleId(currentSchedule);body.setClassDate(day.toString());var detail=new MiniAppSignSubmitBody.Detail();detail.setStudentId(1L);detail.setSignStatus("5");body.setDetails(List.of(detail));
        f.tx(()->{rosters.validateSubmission(body);rosters.lockLesson(currentSchedule,day.toString(),10L);return null;});assertEquals("3",detail.getSourceType());
        long attendance=f.db.insert("edu_class_sign_in",Map.of("schedule_id",currentSchedule,"class_date",day,"student_id",1,"student_name","同名孩子","source_type","3","sign_status","5","review_stage","PENDING_FIRST","review_status","0"));
        assertEquals(0,f.tx(()->report.report(Map.of())).get("makeupCount"));
        assertThrows(ServiceException.class,()->f.tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(id(retry,"planId"),"待审"))));
        f.db.jdbc().update("UPDATE edu_class_sign_in SET review_stage='PENDING_FINAL' WHERE sign_in_id=?",attendance);assertEquals(0,f.tx(()->report.report(Map.of())).get("makeupCount"));
        f.db.jdbc().update("UPDATE edu_class_sign_in SET review_stage='FINAL',review_status='1' WHERE sign_in_id=?",attendance);assertEquals(1,f.tx(()->report.report(Map.of())).get("makeupCount"));
        assertThrows(ServiceException.class,()->f.tx(()->service.cancel(new AttendanceMakeupService.CancelRequest(id(retry,"planId"),"完成"))));
        assertEquals(2,((List<?>)f.tx(()->service.options(a,null,"")).get("history")).size());
        actor(30,"parent");assertEquals(1,report.parentReport(1L).get("makeupCount"));assertEquals(0,report.parentReport(2L).get("makeupCount"));
        actor(10,"teacher");
        // Existing teacher calendars and one-to-one reservations participate in custom conflict checks.
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(b,"10:15","10:45","B教室"))));
        f.db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(100,'学生其他班',?,'秋季','周一','DAILY',?,?,'12:00','13:00',11,'1'),(101,'预约',?,'秋季','补课','DAILY',?,?,'15:00','16:00',11,'2')",day.getYear(),day,day,day.getYear(),day,day);
        f.db.jdbc().update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name,class_date) VALUES(100,100,2,30,'同名孩子',NULL),(101,101,2,30,'同名孩子',?)",day);
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(b,"12:30","13:30","B教室"))));
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(b,"15:30","16:30","B教室"))));
        f.db.jdbc().update("DELETE FROM edu_enrollment WHERE enrollment_id IN (100,101)");
        f.db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=10 WHERE schedule_id=101");
        f.db.jdbc().update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name,class_date) VALUES(101,101,3,30,'线下学生',?)",day);
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(b,"15:30","16:30","B教室"))));
        f.db.jdbc().update("DELETE FROM edu_enrollment WHERE enrollment_id=101");
        f.db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=11 WHERE schedule_id=1");
        actor(11,"teacher");assertThrows(ServiceException.class,()->f.tx(()->service.options(b,null,"")));
        actor(10,"teacher");assertTrue((Boolean)f.tx(()->service.options(b,null,"")).get("canManageMakeup"));
        f.db.jdbc().update("UPDATE sys_role SET status='1' WHERE role_key='teacher'");
        assertThrows(ServiceException.class,()->f.tx(()->service.arrange(custom(b,"17:00","18:00","B教室"))));
        f.db.jdbc().update("UPDATE sys_role SET status='0' WHERE role_key='teacher'");
        assertEquals(credits,f.db.rows("SELECT * FROM edu_student_lesson_account ORDER BY account_id"));
        assertEquals(signs,f.db.rows("SELECT * FROM edu_class_sign_in WHERE schedule_id=1 ORDER BY sign_in_id"));assertEquals(finance,f.db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
    }
}
