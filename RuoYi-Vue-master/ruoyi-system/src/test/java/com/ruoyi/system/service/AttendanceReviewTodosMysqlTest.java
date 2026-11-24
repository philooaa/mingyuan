package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.system.shop.ShopRepository;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A staff worklist must scale with submitted sheets, never with the school calendar. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceReviewTodosMysqlTest {
    final AttendanceLessonRosterMysqlTest fixture=new AttendanceLessonRosterMysqlTest();
    static class CountingRepository extends ShopRepository {
        int selects;
        CountingRepository(javax.sql.DataSource source){super(source);}
        @Override public List<Map<String,Object>> rows(String sql,Object... args){selects++;return super.rows(sql,args);}
    }
    CountingRepository db;AttendanceLessonRosterService service;
    @BeforeAll void setup() throws Exception {
        fixture.setup();db=new CountingRepository(fixture.db.jdbc().getDataSource());service=new AttendanceLessonRosterService(db);
        fixture.tx(()->{for(String file:List.of("20261106_attendance_lesson_roster.sql","20261118_attendance_lesson_guests.sql","20261122_attendance_makeup.sql","20261123_teacher_led_makeup.sql"))for(String statement:Files.readString(Path.of("../../database/migrations/"+file)).split(";"))if(!statement.isBlank())db.jdbc().execute(statement);return null;});
    }
    @AfterAll void cleanup() throws Exception {fixture.cleanup();}
    @Test @SuppressWarnings("unchecked") void boundedStaffQueriesPreserveReviewAndLegacySheets() {
        String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        AttendanceLessonRosterMysqlTest.principal(20,"education");
        for(int i=2;i<=204;i++)db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,course_year,term_name,period_name,class_pattern,start_date,end_date,start_time,end_time,teacher_id,class_mode) VALUES(?,'工作列表性能测试',2026,'秋季','一期','DAILY',?,?,'10:00','12:00',10,?)",i,day,day,i==3?"2":"1");
        var lesson=fixture.tx(()->service.lessons(2)).get(0);
        db.jdbc().update("UPDATE edu_attendance_lesson SET locked=1,roster_snapshot='[{\"studentId\":\"1\"},{\"studentId\":\"2\"}]' WHERE lesson_id=?",lesson.get("lessonId"));
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_id,student_name,source_type,review_stage) VALUES(2,?,1,'学生A','1','PENDING_FIRST'),(3,?,2,'学生B','1',NULL),(4,?,3,'学生C','1','PENDING_FINAL')",day,day,day);
        fixture.tx(()->service.lessons(4));db.jdbc().update("UPDATE edu_course_schedule SET status='1' WHERE schedule_id=4");
        long before=db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_attendance_lesson",Long.class);db.selects=0;
        var result=fixture.tx(()->service.todos("education"));var rows=(List<Map<String,Object>>)result.get("items");
        assertEquals(2,rows.size());assertTrue(db.selects<=6,"staff SELECT count must stay bounded, got "+db.selects);
        assertEquals(before,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_attendance_lesson",Long.class));
        var frozen=rows.stream().filter(r->"2".equals(r.get("scheduleId"))).findFirst().orElseThrow();
        assertEquals(2,((Number)frozen.get("expectedCount")).intValue());assertEquals("PENDING_FIRST",frozen.get("stage"));assertFalse(frozen.containsKey("rosterSnapshot"));
        assertTrue(rows.stream().anyMatch(r->"3".equals(r.get("scheduleId"))),"legacy single-class pending sheet is retained");
        AttendanceLessonRosterMysqlTest.principal(40,"business_admin");var finals=(List<Map<String,Object>>)fixture.tx(()->service.todos("admin")).get("items");assertEquals(1,finals.size());assertEquals("4",finals.get(0).get("scheduleId"));
        AttendanceLessonRosterMysqlTest.principal(1,"admin");assertEquals(3,fixture.tx(()->service.todos("admin")).get("count"));
        // Unmaterialized legacy class: repair only its course, not the 200 unrelated calendars.
        db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_id,student_name,source_type,review_stage) VALUES(5,?,1,'学生A','1','PENDING_FIRST')",day);
        AttendanceLessonRosterMysqlTest.principal(20,"education");var legacy=(List<Map<String,Object>>)fixture.tx(()->service.todos("education")).get("items");
        var repaired=legacy.stream().filter(r->"5".equals(r.get("scheduleId"))).findFirst().orElseThrow();assertEquals(1,((Number)repaired.get("lessonNo")).intValue());assertEquals(1,((Number)repaired.get("expectedCount")).intValue());
        assertEquals(0L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_attendance_lesson WHERE schedule_id>5",Long.class));
        db.selects=0;fixture.tx(()->service.todos("education"));assertTrue(db.selects<=6,"steady-state review must not issue per-course or per-lesson SELECTs");
        AttendanceLessonRosterMysqlTest.principal(30,"parent");assertThrows(Exception.class,()->fixture.tx(()->service.todos("education")));
        AttendanceLessonRosterMysqlTest.principal(10,"teacher");assertThrows(Exception.class,()->fixture.tx(()->service.todos("education")));
        System.out.println("PASS: bounded staff review queries, frozen counts, legacy and single-class sheets, two review levels and closed-course history");
    }
}
