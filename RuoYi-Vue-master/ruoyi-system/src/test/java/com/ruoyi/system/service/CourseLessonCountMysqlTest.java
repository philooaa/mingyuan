package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

/** All writes occur in a disposable database; no business database is modified. */
@EnabledIfEnvironmentVariable(named="COURSE_COUNT_MYSQL_TEST",matches="true")
class CourseLessonCountMysqlTest {
    final String schema="codex_count_test_"+UUID.randomUUID().toString().replace("-","");
    ShopRepository db; CoursePriceService service;
    String user=System.getenv("MYSQL_USER"),password=System.getenv("MYSQL_PASSWORD");
    @BeforeEach void setup() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password)) {
            c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
        }
        db=new ShopRepository(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai",user,password));
        db.jdbc().execute("CREATE TABLE edu_course_schedule(schedule_id BIGINT PRIMARY KEY,course_class_name VARCHAR(100),class_mode CHAR(1),start_date DATE,end_date DATE,class_pattern VARCHAR(30),period_name VARCHAR(30),lesson_count INT,lesson_count_override INT NULL,lesson_count_override_reason VARCHAR(255),unit_price DECIMAL(10,2),tuition_price DECIMAL(10,2),del_flag CHAR(1) DEFAULT '0',update_by VARCHAR(64),update_time DATETIME)");
        db.jdbc().execute("CREATE TABLE edu_schedule_adjustment(adjustment_id BIGINT,schedule_id BIGINT,term_name VARCHAR(64),period_name VARCHAR(64),original_date DATE,adjusted_date DATE)");
        db.jdbc().execute("ALTER TABLE edu_course_schedule ADD term_name VARCHAR(64)");
        db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,course_class_name,class_mode,start_date,end_date,class_pattern,period_name,lesson_count,unit_price,tuition_price) VALUES(1,'测试班课','1','2026-11-02','2026-11-30','WEEKLY','周一',14,100,1400)");
        for(String table:List.of("edu_enrollment","edu_tuition_payment","edu_tuition_receipt")) {
            db.jdbc().execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY, snapshot_amount DECIMAL(10,2))");
            db.jdbc().update("INSERT INTO "+table+" VALUES(1,1400)");
        }
        service=new CoursePriceService(db); ScheduleCrudTest.principal(42,"education");
    }
    @AfterEach void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password)) { c.createStatement().execute("DROP DATABASE IF EXISTS "+schema); }
    }
    Map<String,Object> command(int count) {
        return new HashMap<>(Map.of("mode","MANUAL","lessonCount",count,"version",service.lessonCount(1).get("lessonCountVersion")));
    }
    void save(Map<String,Object> body) { db.transactions().execute(status->service.updateLessonCount(1,body)); }
    void snapshotsUnchanged() {
        for(String table:List.of("edu_enrollment","edu_tuition_payment","edu_tuition_receipt"))
            assertEquals("1400.00",db.one("SELECT snapshot_amount FROM "+table+" WHERE id=1").get("snapshotAmount"));
    }
    @Test void mismatchNeedsConfirmationAndHistoryNeverChanges() {
        assertEquals(5,service.lessonCount(1).get("systemLessonCount"));
        var body=command(3);
        assertThrows(ServiceException.class,()->save(body));
        assertNull(db.one("SELECT lesson_count_override FROM edu_course_schedule WHERE schedule_id=1").get("lessonCountOverride"));
        body.put("confirmMismatch",true);body.put("reason","仅计费三讲");save(body);
        assertEquals(3,service.lessonCount(1).get("lessonCount"));
        assertEquals("300.00",db.one("SELECT tuition_price FROM edu_course_schedule WHERE schedule_id=1").get("tuitionPrice"));
        db.jdbc().update("UPDATE edu_course_schedule SET end_date='2026-12-31' WHERE schedule_id=1");
        db.transactions().execute(status->{service.syncSchedulePricing(1);return null;});
        assertEquals(3,service.lessonCount(1).get("lessonCount"));
        var auto=new HashMap<String,Object>(Map.of("mode","AUTO","version",service.lessonCount(1).get("lessonCountVersion")));save(auto);
        assertEquals(9,service.lessonCount(1).get("lessonCount"));
        assertEquals("900.00",db.one("SELECT tuition_price FROM edu_course_schedule WHERE schedule_id=1").get("tuitionPrice"));
        snapshotsUnchanged();
    }
    @Test void staleChangesInvalidNumbersAndTeachersAreRejected() {
        var body=command(5);db.jdbc().update("UPDATE edu_course_schedule SET end_date='2026-12-31' WHERE schedule_id=1");
        assertThrows(ServiceException.class,()->save(body));
        for(String invalid:List.of("0","-1","1.5","3662")) {
            var value=command(5);value.put("lessonCount",invalid);assertThrows(ServiceException.class,()->save(value));
        }
        ScheduleCrudTest.principal(42,"teacher");assertThrows(ServiceException.class,()->save(command(9)));
        snapshotsUnchanged();
    }
}
