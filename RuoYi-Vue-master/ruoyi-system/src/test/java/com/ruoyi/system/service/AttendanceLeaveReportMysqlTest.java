package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceLeaveReportMysqlTest {
    final AttendanceLessonRosterMysqlTest fixture=new AttendanceLessonRosterMysqlTest();
    AttendanceLeaveReportService service;
    @BeforeAll void setup() throws Exception {
        fixture.setup();
        var db=fixture.db;
        db.jdbc().execute("ALTER TABLE edu_class_sign_in ADD sign_status CHAR(1)");
        db.jdbc().execute("ALTER TABLE edu_course_schedule ADD teacher_name VARCHAR(64)");
        fixture.tx(()->{for(String name:List.of("20261106_attendance_lesson_roster.sql","20261118_attendance_lesson_guests.sql","20261122_attendance_makeup.sql","20261123_teacher_led_makeup.sql"))
            for(String sql:Files.readString(Path.of("../../database/migrations/"+name)).split(";"))if(!sql.isBlank())db.jdbc().execute(sql);return null;});
        service=new AttendanceLeaveReportService(db);
        for(int i=1;i<=6;i++)db.jdbc().update("INSERT INTO edu_attendance_lesson(lesson_id,schedule_id,lesson_no,original_date,class_date,course_year,term_name,teacher_id,locked) VALUES(?,1,?,DATE_ADD('2026-11-01',INTERVAL ? DAY),DATE_ADD('2026-11-01',INTERVAL ? DAY),2026,'秋季',10,1)",i,i,i,i);
        db.jdbc().update("UPDATE edu_course_schedule SET course_year=2026");
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_id,enrollment_id,student_name,sign_status,review_status,review_stage,source_type) VALUES (1,'2026-11-02',1,1,'同名孩子','3','1','FINAL','1'),(1,'2026-11-03',2,2,'同名孩子','3','1','FINAL','1'),(1,'2026-11-04',1,1,'同名孩子','3','0','PENDING_FINAL','1'),(1,'2026-11-05',1,1,'同名孩子','6','1','FINAL','1'),(1,'2026-11-06',1,1,'同名孩子','2','1','FINAL','1'),(1,'2026-11-07',1,NULL,'同名孩子','5','0','PENDING_FINAL','3')");
        db.jdbc().update("INSERT INTO edu_attendance_lesson_guest(lesson_id,student_id,source,source_lesson_id,operator_id) VALUES(6,1,'TRANSFER',1,10)");
        AttendanceLessonRosterMysqlTest.principal(20,"education");
    }
    @Test @SuppressWarnings("unchecked") void parentSeesOnlyBoundChild() throws Exception {
        var db=fixture.db;
        db.jdbc().execute("CREATE TABLE IF NOT EXISTS sys_dict_data(dict_label VARCHAR(30),dict_type VARCHAR(30),dict_value VARCHAR(30),status CHAR(1),dict_code BIGINT)");
        db.jdbc().execute("ALTER TABLE edu_student ADD student_phone VARCHAR(11)");
        db.jdbc().execute("CREATE TABLE edu_student_lesson_account(account_id BIGINT PRIMARY KEY,parent_id BIGINT,student_id BIGINT,remaining_credits INT DEFAULT 0,reserved_credits INT DEFAULT 0,consumed_credits INT DEFAULT 0,request_key VARCHAR(50),create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME)");
        db.jdbc().execute("CREATE TABLE edu_classroom(classroom_id BIGINT PRIMARY KEY,campus_name VARCHAR(50),classroom_name VARCHAR(50))");
        db.jdbc().execute("ALTER TABLE edu_course_schedule ADD classroom_id BIGINT");
        db.jdbc().update("INSERT INTO edu_student_lesson_account(account_id,parent_id,student_id) VALUES(101,30,1),(102,30,2),(103,999,1)");
        var parents=new ParentAccountService();var dbField=ParentAccountService.class.getDeclaredField("db");dbField.setAccessible(true);dbField.set(parents,db);
        var parentsField=AttendanceLeaveReportService.class.getDeclaredField("parents");parentsField.setAccessible(true);parentsField.set(service,parents);
        AttendanceLessonRosterMysqlTest.principal(30,"parent");
        var a=service.parentReport(101L);var b=service.parentReport(102L);
        assertEquals(1,((List<?>)a.get("rows")).size());assertEquals(1,((List<?>)b.get("rows")).size());
        var own=(Map<String,Object>)((List<?>)a.get("rows")).get(0);
        assertEquals("2026-11-02",own.get("classDate"));assertFalse(own.containsKey("contactPhone"));assertFalse(own.containsKey("canArrange"));
        db.jdbc().update("INSERT INTO edu_student_lesson_account(account_id,parent_id,student_id) VALUES(104,30,3)");
        long original=db.insert("edu_attendance_lesson",Map.of("schedule_id",1,"lesson_no",7,"original_date","2026-11-08","class_date","2026-11-08","teacher_id",10));
        long target=db.insert("edu_attendance_lesson",Map.of("schedule_id",1,"lesson_no",8,"original_date","2026-11-09","class_date","2026-11-09","teacher_id",10));
        long leave=db.insert("edu_class_sign_in",Map.of("schedule_id",1,"class_date","2026-11-08","student_id",3,"student_name","线下学生","source_type","1","sign_status","3","review_status","1","review_stage","FINAL"));
        long guest=db.insert("edu_attendance_lesson_guest",Map.of("lesson_id",target,"student_id",3,"source","TRANSFER","source_lesson_id",original,"operator_id",20));
        long plan=db.insert("edu_attendance_makeup",Map.of("sign_in_id",leave,"source_lesson_id",original,"student_id",3,"target_lesson_id",target,"guest_id",guest,"operator_id",20));
        var planned=(Map<String,Object>)((List<?>)service.parentReport(104L).get("rows")).get(0);assertEquals("待补课",planned.get("stateText"));
        long makeup=db.insert("edu_class_sign_in",Map.of("schedule_id",1,"class_date","2026-11-09","student_id",3,"student_name","线下学生","source_type","3","sign_status","5","review_status","0","review_stage","PENDING_FIRST"));
        assertEquals("补课出勤确认中",((Map<?,?>)((List<?>)service.parentReport(104L).get("rows")).get(0)).get("stateText"));
        db.jdbc().update("UPDATE edu_class_sign_in SET review_status='1',review_stage='FINAL' WHERE sign_in_id=?",makeup);
        assertEquals(1,service.parentReport(104L).get("makeupCount"));
        db.jdbc().update("UPDATE edu_class_sign_in SET sign_status='3' WHERE sign_in_id=?",makeup);
        var failed=(List<Map<String,Object>>)service.parentReport(104L).get("rows");assertEquals(1,failed.size());assertEquals("failed",failed.get(0).get("state"));
        db.jdbc().update("UPDATE edu_attendance_makeup SET cancelled=1 WHERE plan_id=?",plan);db.jdbc().update("DELETE FROM edu_attendance_lesson_guest WHERE guest_id=?",guest);
        var cancelled=(Map<?,?>)((List<?>)service.parentReport(104L).get("rows")).get(0);assertEquals("已请假 · 待重新安排",cancelled.get("stateText"));assertEquals(1,((List<?>)cancelled.get("history")).size());
        db.jdbc().update("DELETE FROM edu_attendance_makeup WHERE plan_id=?",plan);db.jdbc().update("DELETE FROM edu_class_sign_in WHERE sign_in_id IN (?,?)",leave,makeup);db.jdbc().update("DELETE FROM edu_attendance_lesson WHERE lesson_id IN (?,?)",original,target);db.jdbc().update("DELETE FROM edu_student_lesson_account WHERE account_id=104");
        assertThrows(Exception.class,()->service.parentReport(103L));assertThrows(Exception.class,()->service.parentReport(null));
        db.jdbc().update("UPDATE edu_student SET parent_id=NULL WHERE student_id=2");
        assertThrows(Exception.class,()->service.parentReport(102L));
        db.jdbc().update("UPDATE edu_student SET parent_id=30 WHERE student_id=2");
        AttendanceLessonRosterMysqlTest.principal(10,"teacher");assertThrows(Exception.class,()->service.parentReport(101L));
    }
    @AfterAll void cleanup() throws Exception {fixture.cleanup();}
    @Test @SuppressWarnings("unchecked") void reportAndAccess() {
        var all=service.report(Map.of());
        var rows=(List<Map<String,Object>>)all.get("rows");assertEquals(2,rows.size());
        assertEquals(2,all.get("leaveCount"));assertEquals(2,all.get("pendingCount"));
        assertNotEquals(rows.get(0).get("studentKey"),rows.get(1).get("studentKey"));
        fixture.db.jdbc().update("UPDATE edu_class_sign_in SET review_status='1',review_stage='FINAL' WHERE sign_status='5'");
        var pending=service.report(Map.of("state","pending"));assertEquals(1,pending.get("total"));assertEquals(1,pending.get("pendingCount"));
        var completed=service.report(Map.of("state","completed"));assertEquals(1,completed.get("total"));
        assertEquals(1,service.report(Map.of("from","2026-11-03","to","2026-11-03")).get("leaveCount"));
        assertEquals(0,service.report(Map.of("from","2026-11-02","to","2026-11-02")).get("pendingCount"));
        assertEquals(0,service.report(Map.of("year","2025")).get("total"));
        assertEquals(2,service.report(Map.of("teacherName","teacher","termName","秋季")).get("total"));
        assertEquals(0,service.report(Map.of("keyword","不存在")).get("total"));
        assertEquals(1,((List<?>)service.report(Map.of("pageSize","1")).get("rows")).size());
        assertThrows(RuntimeException.class,()->service.report(Map.of("from","2026-02-30")));
        AttendanceLessonRosterMysqlTest.principal(30,"parent");assertThrows(RuntimeException.class,()->service.report(Map.of()));
        AttendanceLessonRosterMysqlTest.principal(10,"teacher");
        var own=service.report(Map.of());assertEquals(2,own.get("total"));assertEquals(false,own.get("readOnly"));assertEquals(true,own.get("canManageMakeup"));
        for(var ownRow:(List<Map<String,Object>>)own.get("rows"))for(var lesson:(List<Map<String,Object>>)ownRow.get("lessons")){assertEquals("pending".equals(lesson.get("state")),lesson.get("canArrange"));assertEquals(false,lesson.get("canCancel"));}
        AttendanceLessonRosterMysqlTest.principal(11,"teacher");assertEquals(0,service.report(Map.of()).get("total"));
        assertEquals(0,service.report(Map.of("teacherId","10","scope","staff")).get("total"));
        fixture.db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=11 WHERE schedule_id=1");
        assertEquals(0,service.report(Map.of()).get("total"),"frozen lecture teacher owns historical leave");
        AttendanceLessonRosterMysqlTest.principal(10,"teacher");assertEquals(2,service.report(Map.of()).get("total"));
        fixture.db.jdbc().update("UPDATE edu_course_schedule SET teacher_id=10 WHERE schedule_id=1");
        AttendanceLessonRosterMysqlTest.principal(40,"business_admin");assertEquals(2,service.report(Map.of()).get("total"));
        fixture.db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_id,student_name,sign_status,review_status,review_stage,source_type) VALUES(1,'2026-11-07',1,'同名孩子','5','1','FINAL','3')");
        assertEquals(1,service.report(Map.of()).get("pendingCount"));
        assertEquals(1,service.report(Map.of()).get("conflictCount"));
        fixture.db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_id,enrollment_id,student_name,sign_status,review_status,review_stage,source_type) VALUES(1,'2026-11-03',2,2,'同名孩子','1','1','FINAL','1')");
        var conflict=service.report(Map.of());assertEquals(2,conflict.get("conflictCount"));assertEquals(0,conflict.get("pendingCount"));
    }
}
