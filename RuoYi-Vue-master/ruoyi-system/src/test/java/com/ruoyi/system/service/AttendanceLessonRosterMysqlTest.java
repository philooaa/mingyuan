package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import java.sql.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.system.shop.ShopRepository;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;

/** Explicit disposable-MySQL regression suite; inherited setup rejects business schemas. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AttendanceLessonRosterMysqlTest {
    final String schema="codex_attendance_roster_"+UUID.randomUUID().toString().replace("-","");
    ShopRepository db; long course=1,enrollment=1,sibling=2,teacher=10,otherTeacher=11,parent=30;
    LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusYears(2);
    static long id(Map<String,Object> row,String key){return Long.parseLong(row.get(key).toString());}
    static void principal(long actor,String role){ScheduleCrudTest.principal(actor,role);}
    <T> T tx(Callable<T> work){return db.transactions().execute(status->{try{return work.call();}catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new RuntimeException(ex);}});}
    @BeforeAll void setup()throws Exception {
        String password=System.getenv().getOrDefault("MYSQL_PWD","");
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root",password)){c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        db=new ShopRepository(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root",password));
        db.jdbc().execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64),nick_name VARCHAR(64),phonenumber VARCHAR(20),status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0')");
        db.jdbc().execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(30),status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0')");
        db.jdbc().execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        db.jdbc().execute("CREATE TABLE edu_course_schedule(schedule_id BIGINT PRIMARY KEY,course_class_name VARCHAR(100),grade_name VARCHAR(64) DEFAULT '一年级',subject_name VARCHAR(64) DEFAULT '数学',course_year INT,term_name VARCHAR(64),period_name VARCHAR(30),class_pattern VARCHAR(30),start_date DATE,end_date DATE,start_time TIME,end_time TIME,teacher_id BIGINT,class_mode CHAR(1),status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0')");
        db.jdbc().execute("CREATE TABLE edu_student(student_id BIGINT PRIMARY KEY AUTO_INCREMENT,parent_id BIGINT,student_name VARCHAR(64),grade_code VARCHAR(30),create_by VARCHAR(64),update_by VARCHAR(64),update_time DATETIME,del_flag CHAR(1) DEFAULT '0')");
        db.jdbc().execute("CREATE TABLE edu_enrollment(enrollment_id BIGINT PRIMARY KEY,schedule_id BIGINT,student_id BIGINT,parent_id BIGINT,student_name VARCHAR(64),contact_phone VARCHAR(20),class_date DATE,enrollment_status CHAR(1) DEFAULT '1',del_flag CHAR(1) DEFAULT '0')");
        db.jdbc().execute("CREATE TABLE edu_schedule_adjustment(adjustment_id BIGINT PRIMARY KEY AUTO_INCREMENT,schedule_id BIGINT,term_name VARCHAR(64),period_name VARCHAR(64),original_date DATE,adjusted_date DATE,reason VARCHAR(255))");
        db.jdbc().execute("CREATE TABLE edu_class_sign_in(sign_in_id BIGINT PRIMARY KEY AUTO_INCREMENT,schedule_id BIGINT,class_date DATE,student_id BIGINT,enrollment_id BIGINT,student_name VARCHAR(64),source_type CHAR(1),review_stage VARCHAR(24),review_status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0')");
        db.jdbc().update("INSERT INTO sys_user(user_id,user_name) VALUES(1,'admin'),(10,'teacher'),(11,'other'),(30,'parent'),(20,'education'),(40,'business')");
        db.jdbc().update("INSERT INTO sys_role(role_id,role_key) VALUES(1,'teacher'),(2,'parent'),(3,'education'),(4,'business_admin')");
        db.jdbc().update("INSERT INTO sys_user_role VALUES(10,1),(11,1),(30,2),(20,3),(40,4)");
        db.jdbc().update("INSERT INTO edu_student(student_id,parent_id,student_name) VALUES(1,30,'同名孩子'),(2,30,'同名孩子'),(3,30,'线下学生')");
        db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(1,'隔离测试',?,'秋季','周一','DAILY',?,?,'00:00:00','00:01:00',10,'1')",date.getYear(),date.toString(),date.plusDays(5).toString());
        db.jdbc().update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name) VALUES(1,1,1,30,'同名孩子'),(2,1,2,30,'同名孩子')");
    }
    @AfterAll void cleanup()throws Exception{if(db!=null)try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root",System.getenv().getOrDefault("MYSQL_PWD",""))){c.createStatement().execute("DROP DATABASE "+schema);}}
    MiniAppSignSubmitBody submitBody(String day){var b=new MiniAppSignSubmitBody();b.setScheduleId(course);b.setClassDate(day);var a=new MiniAppSignSubmitBody.Detail();a.setEnrollmentId(enrollment);var c=new MiniAppSignSubmitBody.Detail();c.setEnrollmentId(sibling);b.setDetails(List.of(a,c));return b;}
    @Test void runRoster() throws Exception {
        String schema=db.jdbc().queryForObject("SELECT DATABASE()",String.class);
        assertTrue(schema.matches("codex_[a-zA-Z0-9_]+"),"Disposable schema required");
        String migration=Files.readString(Path.of("../../database/migrations/20261106_attendance_lesson_roster.sql"));
        for(int repeat=0;repeat<2;repeat++)tx(()->{for(String statement:migration.split(";"))if(!statement.isBlank())db.jdbc().execute(statement);return null;});
        var service=new AttendanceLessonRosterService(db);
        db.jdbc().update("UPDATE edu_course_schedule SET class_pattern='DAILY',start_date=?,end_date=?,start_time='00:00:00',end_time='00:01:00' WHERE schedule_id=?",date.toString(),date.plusDays(5).toString(),course);
        principal(1,"admin");var lessons=tx(()->service.lessons(course));assertEquals(6,lessons.size());
        db.jdbc().update("UPDATE edu_attendance_lesson SET class_date=NULL,cancelled=1 WHERE schedule_id=? AND lesson_no=6",course);
        assertDoesNotThrow(()->tx(()->service.lessons(course)));
        assertTrue(tx(()->service.hasLessonInRange(course,date.toString(),date.toString())));
        assertFalse(tx(()->service.hasLessonInRange(course,date.minusDays(1).toString(),date.minusDays(1).toString())));
        assertTrue(tx(()->service.hasLessonInRange(course,null,date.toString())));
        assertThrows(ServiceException.class,()->tx(()->service.hasLessonInRange(course,date.plusDays(1).toString(),date.toString())));
        long first=id(lessons.get(0),"lessonId"),third=id(lessons.get(2),"lessonId"),fifth=id(lessons.get(4),"lessonId");
        var initial=tx(()->service.roster(course,first));assertEquals(2,initial.size());assertEquals(2,initial.stream().map(r->r.get("studentId")).distinct().count());
        long child=id(db.one("SELECT student_id FROM edu_enrollment WHERE enrollment_id=?",enrollment),"studentId");
        var financeBefore=db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id");
        long member=tx(()->service.add(new AttendanceLessonRosterService.MembershipRequest(course,child,enrollment,3,null,"ENROLLED","ENROLLMENT","第三讲插班")));
        assertEquals(1,tx(()->service.roster(course,first)).size());assertEquals(2,tx(()->service.roster(course,third)).size());
        principal(teacher,"teacher");var invalid=submitBody(date.toString());assertThrows(ServiceException.class,()->tx(()->{service.validateSubmission(invalid);return null;}));
        principal(1,"admin");tx(()->{service.lockLesson(course,date.plusDays(2).toString(),teacher);return null;});
        var snapshot=tx(()->service.roster(course,third));
        assertThrows(ServiceException.class,()->tx(()->{service.remove(member,new AttendanceLessonRosterService.RemovalRequest(course,3,"不能更改本讲"));return null;}));
        tx(()->{service.remove(member,new AttendanceLessonRosterService.RemovalRequest(course,5,"第五讲起移出"));return null;});
        assertEquals(snapshot,tx(()->service.roster(course,third)));assertEquals(1,tx(()->service.roster(course,fifth)).size());
        var collision=new LinkedHashMap<String,Object>();collision.put("schedule_id",course);collision.put("original_date",date.toString());collision.put("adjusted_date",date.plusDays(2).toString());collision.put("reason","不得覆盖已提交讲次");long collisionId=db.insert("edu_schedule_adjustment",collision);
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,review_stage) VALUES(1,?,'已提交','1','PENDING_FIRST')",date.plusDays(2).toString());
        var beforeCollision=db.rows("SELECT * FROM edu_attendance_lesson ORDER BY lesson_id");assertThrows(ServiceException.class,()->tx(()->service.lessons(course)));assertEquals(beforeCollision,db.rows("SELECT * FROM edu_attendance_lesson ORDER BY lesson_id"));
        db.jdbc().update("UPDATE edu_schedule_adjustment SET adjusted_date=? WHERE adjustment_id=?",date.plusDays(1).toString(),collisionId);assertThrows(ServiceException.class,()->tx(()->service.lessons(course)));assertEquals(beforeCollision,db.rows("SELECT * FROM edu_attendance_lesson ORDER BY lesson_id"));
        db.jdbc().update("DELETE FROM edu_schedule_adjustment WHERE adjustment_id=?",collisionId);db.jdbc().update("DELETE FROM edu_class_sign_in WHERE schedule_id=1");

        assertEquals(financeBefore,db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
        var adjustment=new LinkedHashMap<String,Object>();adjustment.put("schedule_id",course);adjustment.put("original_date",date.toString());adjustment.put("adjusted_date",date.plusDays(10).toString());adjustment.put("reason","调课身份测试");db.insert("edu_schedule_adjustment",adjustment);
        var moved=tx(()->service.lessons(course));assertEquals(first,id(moved.get(0),"lessonId"));assertEquals(date.plusDays(10).toString(),moved.get(0).get("classDate"));
        db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=?,start_date=? WHERE schedule_id=?",otherTeacher,date.plusDays(1).toString(),course);
        var locked=tx(()->service.lessons(course)).stream().filter(r->id(r,"lessonId")==third).findFirst().orElseThrow();assertEquals(String.valueOf(teacher),locked.get("teacherId"));assertEquals(3,((Number)locked.get("lessonNo")).intValue());assertEquals(snapshot,tx(()->service.roster(course,third)));
        principal(teacher,"teacher");var returned=submitBody(date.plusDays(2).toString());tx(()->{service.validateSubmission(returned);return null;});assertEquals(2,tx(()->service.teacherRoster(course,date.plusDays(2).toString())).size());
        principal(otherTeacher,"teacher");assertThrows(ServiceException.class,()->tx(()->{service.validateSubmission(submitBody(date.plusDays(2).toString()));return null;}));assertThrows(ServiceException.class,()->tx(()->service.teacherRoster(course,date.plusDays(2).toString())));
        long offline=3;principal(1,"admin");
        long unpaid=tx(()->service.add(new AttendanceLessonRosterService.MembershipRequest(course,offline,null,5,null,"OFFLINE","UNPAID","线下名单不创建账单")));
        assertTrue(unpaid>0);assertEquals(financeBefore,db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
        assertThrows(ServiceException.class,()->tx(()->service.add(new AttendanceLessonRosterService.MembershipRequest(course,offline,null,5,null,"OFFLINE","PAID","禁止伪造缴费"))));
        principal(parent,"parent");assertThrows(ServiceException.class,()->service.courses(""));assertThrows(ServiceException.class,()->service.students(""));assertThrows(ServiceException.class,()->tx(()->service.todos("education")));
        principal(teacher,"teacher");assertThrows(ServiceException.class,()->service.students(""));assertThrows(ServiceException.class,()->tx(()->service.lessons(course)));assertThrows(ServiceException.class,()->tx(()->service.todos("business_admin")));
        principal(otherTeacher,"teacher");
        var canonical=new MiniAppSignSubmitBody();canonical.setScheduleId(course);canonical.setClassDate(date.plusDays(4).toString());
        var linked=new MiniAppSignSubmitBody.Detail();linked.setEnrollmentId(sibling);linked.setStudentName("伪造姓名");linked.setSourceType("3");
        var temp=new MiniAppSignSubmitBody.Detail();temp.setStudentId(offline);temp.setStudentName("伪造姓名");temp.setSourceType("2");canonical.setDetails(List.of(linked,temp));
        tx(()->{service.validateSubmission(canonical);return null;});assertEquals("同名孩子",linked.getStudentName());assertEquals("线下学生",temp.getStudentName());assertEquals("1",temp.getSourceType());
        var dup=new MiniAppSignSubmitBody.Detail();dup.setStudentId(linked.getStudentId());canonical.setDetails(List.of(linked,temp,dup));assertThrows(ServiceException.class,()->tx(()->{service.validateSubmission(canonical);return null;}));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();
        try {for(int effective:List.of(5,6))jobs.add(pool.submit(()->{principal(1,"admin");try{gate.await();tx(()->{service.remove(unpaid,new AttendanceLessonRosterService.RemovalRequest(course,effective,"并发移出"));return null;});}catch(ServiceException expected){}catch(Exception ex){throw new RuntimeException(ex);}}));gate.countDown();for(var job:jobs)job.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(4,((Number)db.one("SELECT end_lesson FROM edu_attendance_membership WHERE membership_id=?",unpaid).get("endLesson")).intValue());
        principal(1,"admin");var expectedSummary=tx(()->service.summary(course,null,null));assertEquals(7L,expectedSummary.get("expectedPersonOccurrences"));assertEquals(0L,expectedSummary.get("formalExpectedPersonOccurrences"));assertEquals(2,((Number)tx(()->service.courseOverviewLessons(course,date.plusDays(2).toString(),date.plusDays(2).toString())).get(0).get("expectedCount")).intValue());
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,review_stage,review_status) VALUES(1,?,'终审名单','1','FINAL','1')",date.plusDays(2).toString());assertEquals(2L,tx(()->service.summary(course,null,null)).get("formalExpectedPersonOccurrences"));db.jdbc().update("DELETE FROM edu_class_sign_in WHERE schedule_id=1");
        var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(2,'待办测试',?,'秋季','周一','DAILY',?,?,'00:00:00','00:00:01',10,'1')",today.getYear(),today.minusDays(1).toString(),today.plusDays(1).toString());
        db.jdbc().update("UPDATE sys_user SET nick_name='王老师' WHERE user_id=?",teacher);
        principal(teacher,"teacher");assertEquals(2,((Number)tx(()->service.todos("teacher")).get("count")).intValue());
        var todoRows=(List<Map<String,Object>>)tx(()->service.todos("teacher")).get("items");
        assertEquals("一年级",todoRows.get(0).get("gradeName"));assertEquals("数学",todoRows.get(0).get("subjectName"));assertEquals("王老师",todoRows.get(0).get("teacherName"));assertEquals(2L,tx(()->service.todos("teacher")).get("overdueCount"));
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,review_stage) VALUES(2,?,'旧点名','2','PENDING_FIRST')",today.minusDays(1).toString());
        principal(20,"education");assertEquals(1,tx(()->service.todos("education")).get("count"));
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,review_stage) VALUES(2,?,'旧点名','2','PENDING_FINAL')",today.toString());
        principal(40,"business_admin");assertEquals(1,tx(()->service.todos("admin")).get("count"));assertThrows(ServiceException.class,()->tx(()->service.todos("education")));
        assertFalse(service.courses("").isEmpty());
        db.jdbc().update("UPDATE sys_role SET status='1' WHERE role_key='business_admin'");assertThrows(ServiceException.class,()->tx(()->service.todos("admin")));
        db.jdbc().execute("CREATE TABLE sys_dict_data(dict_type VARCHAR(30),dict_label VARCHAR(30),dict_value VARCHAR(30),status CHAR(1))");
        db.jdbc().update("INSERT INTO sys_dict_data VALUES ('edu_grade','一年级','1','0')");
        db.jdbc().execute("CREATE TABLE edu_student_lesson_account(account_id BIGINT PRIMARY KEY AUTO_INCREMENT,student_id BIGINT UNIQUE,parent_id BIGINT,student_name VARCHAR(30),grade_name VARCHAR(20),request_key VARCHAR(100),create_by VARCHAR(64),create_time DATETIME)");
        db.jdbc().update("UPDATE sys_user SET phonenumber='13800000000' WHERE user_id=30");
        db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,teacher_id,class_mode) VALUES(3,'补录测试',?,'秋季','周一','DAILY',?,?,10,'1')", today.minusDays(10).getYear(), today.minusDays(10).toString(), today.minusDays(5).toString());
        principal(20,"education");
        var offlineLessons = tx(() -> service.lessons(3));
        long offlineFirst = id(offlineLessons.get(0),"lessonId"), offlineThird = id(offlineLessons.get(2),"lessonId");
        var offlineDraft = new AttendanceLessonRosterService.OfflineStudentRequest(3L,"仅姓名学生",3);
        principal(teacher,"teacher");
        assertThrows(ServiceException.class, () -> tx(() -> service.addOfflineStudent(offlineDraft)));
        principal(20,"education");
        long studentId = tx(() -> service.addOfflineStudent(offlineDraft)).get("studentId");
        assertThrows(ServiceException.class, () -> tx(() -> service.addOfflineStudent(offlineDraft)));
        assertTrue(tx(() -> service.roster(3, offlineFirst)).stream().noneMatch(row -> id(row,"studentId")==studentId));
        assertTrue(tx(() -> service.roster(3, offlineThird)).stream().anyMatch(row -> id(row,"studentId")==studentId));
        assertNull(db.one("SELECT parent_id FROM edu_student WHERE student_id=?",studentId).get("parentId"));
        tx(() -> {
            service.bindOfflineStudent(studentId, new AttendanceLessonRosterService.ParentBindingRequest("13800000000"));
            return null;
        });
        assertEquals(30L,id(db.one("SELECT parent_id FROM edu_student WHERE student_id=?",studentId),"parentId"));
        assertEquals(30L,id(db.one("SELECT parent_id FROM edu_student_lesson_account WHERE student_id=?",studentId),"parentId"));
        tx(() -> {
            service.remove(tx(() -> service.roster(3, offlineThird)).stream()
                    .filter(row -> id(row,"studentId")==studentId).map(row -> id(row,"membershipId"))
                    .findFirst().orElseThrow(), new AttendanceLessonRosterService.RemovalRequest(3L,3,"修正历史名单"));
            return null;
        });
        assertTrue(tx(() -> service.roster(3, offlineThird)).stream().noneMatch(row -> id(row,"studentId")==studentId));
        assertEquals(financeBefore, db.rows("SELECT * FROM edu_enrollment ORDER BY enrollment_id"));
        System.out.println("PASS: stable lesson identity, effective insertion/removal, locked snapshot and teacher, same-name IDs, unpaid roster-only membership, immutable finance and role checks");
    }
}
