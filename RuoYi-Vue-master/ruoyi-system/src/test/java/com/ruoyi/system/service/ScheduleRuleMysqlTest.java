package com.ruoyi.system.service;

import com.ruoyi.system.controller.EduScheduleRuleController;
import com.ruoyi.system.shop.ShopRepository;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises migrations and template CRUD in a random, disposable database only. */
class ScheduleRuleMysqlTest {
    @Test void migrationAndConcurrentRuleEditsPreserveExistingCourses() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("schedule.mysql"));
        String schema="codex_schedule_rule_test_"+UUID.randomUUID().toString().replace("-","");
        String password=System.getenv().getOrDefault("MYSQL_PWD","");
        try(Connection admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?serverTimezone=Asia/Shanghai","root",password)) {
            admin.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
            try {
                Path root=Path.of("").toAbsolutePath();
                while(root!=null && !Files.exists(root.resolve("database/schema.sql")))root=root.getParent();
                assertNotNull(root);
                ShopRepository db=new ShopRepository(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root",password));
                String ddl=Files.readString(root.resolve("database/schema.sql"));
                for(String table:List.of("sys_role","sys_role_menu","sys_menu","edu_classroom","edu_course_schedule")) {
                    Matcher m=Pattern.compile("CREATE TABLE `"+table+"` \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(ddl);
                    assertTrue(m.find(),table); db.jdbc().execute(m.group());
                }
                db.jdbc().execute("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(927,'教务','education',1,'0')");
                db.jdbc().execute("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type) VALUES(981,'教务工具',0,1,'teaching','M')");
                db.jdbc().execute("INSERT INTO edu_course_schedule(schedule_id,schedule_code,term_name,period_name,time_slot,grade_name,subject_name,course_class_name) VALUES(31,'SENTINEL','秋季','周六','上午','初一','数学','已有课程')");
                var before=db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=31");
                String migration=Files.readString(root.resolve("database/migrations/20261123_schedule_rules.sql")).replaceAll("(?m)^\\s*--.*$","");
                for(int run=0;run<2;run++)try(Connection c=db.jdbc().getDataSource().getConnection();Statement statement=c.createStatement()) {
                    for(String sql:migration.split(";"))if(!sql.isBlank())statement.execute(sql);
                }
                var after=db.one("SELECT * FROM edu_course_schedule WHERE schedule_id=31");
                before.forEach((key,value)->assertEquals(value,after.get(key),key));
                assertNull(after.get("customDates"));
                assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM sys_menu WHERE component='system/scheduleRule/index'",Integer.class));
                assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=927",Integer.class));
                ScheduleCrudTest.principal(1,"admin");
                var controller=new EduScheduleRuleController(db);
                Map<String,Object> body=new HashMap<>(Map.of("ruleName","跨年规则","dates",List.of("2027-01-02","2026-12-26","2026-12-26"),"status","0"));
                long id=((Number)db.transactions().execute(tx->controller.create(body)).get("data")).longValue();
                @SuppressWarnings("unchecked") var detail=(Map<String,Object>)controller.detail(id).get("data");
                assertEquals(List.of("2026-12-26","2027-01-02"),detail.get("dates"));
                body.put("version",1);body.put("status","1");body.put("dates",List.of("2027-02-01"));
                db.transactions().execute(tx->controller.update(id,body));
                assertThrows(com.ruoyi.common.exception.ServiceException.class,()->db.transactions().execute(tx->controller.update(id,body)));
                @SuppressWarnings("unchecked") var disabled=(Map<String,Object>)controller.detail(id).get("data");
                assertEquals(2L,((Number)disabled.get("version")).longValue());
                assertEquals(List.of("2027-02-01"),disabled.get("dates"));
                assertTrue(((List<?>)controller.list(true).get("data")).isEmpty());
                assertNull(db.one("SELECT custom_dates FROM edu_course_schedule WHERE schedule_id=31").get("customDates"));
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext();admin.createStatement().execute("DROP DATABASE "+schema); }
        }
    }
}
