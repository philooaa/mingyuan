package com.ruoyi.system.service;

import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.EduTeacherSalaryRecordServiceImpl;
import com.ruoyi.system.shop.ShopRepository;
import com.ruoyi.common.exception.ServiceException;
import java.nio.file.*;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SalarySpecialAttendanceMysqlTest {
    @Test void hourlySpecialPayAndMonthlyCountsRemainIndependent() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("salary.mysql"));
        String schema="codex_salary_special_test_"+UUID.randomUUID().toString().replace("-","");
        String password=System.getenv().getOrDefault("MYSQL_PWD","");
        try(Connection admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?serverTimezone=Asia/Shanghai","root",password)) {
            admin.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
            try {
                var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root",password);
                var db=new ShopRepository(source);var jdbc=db.jdbc();
                Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("database/schema.sql")))root=root.getParent();assertNotNull(root);
                String ddl=Files.readString(root.resolve("database/schema.sql"));
                for(String table:List.of("sys_dict_data","edu_teacher_salary_config","edu_teacher_salary_record")) {
                    var matcher=Pattern.compile("CREATE TABLE `"+table+"` \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(ddl);assertTrue(matcher.find());jdbc.execute(matcher.group());
                }
                jdbc.execute("ALTER TABLE edu_teacher_salary_config ADD commission_amount DECIMAL(10,2) NOT NULL DEFAULT 0");
                jdbc.execute("ALTER TABLE edu_teacher_salary_record ADD commission_amount DECIMAL(10,2) NOT NULL DEFAULT 0");
                jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64),nick_name VARCHAR(64),status CHAR(1),del_flag CHAR(1))");
                jdbc.execute("CREATE TABLE sys_role(role_id BIGINT,role_key VARCHAR(30),status CHAR(1),del_flag CHAR(1))");
                jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
                jdbc.execute("CREATE TABLE edu_teacher_profile(user_id BIGINT,teacher_subject VARCHAR(30))");
                jdbc.execute("CREATE TABLE edu_course_schedule(schedule_id BIGINT PRIMARY KEY,teacher_id BIGINT,class_mode CHAR(1),class_type VARCHAR(30),grade_name VARCHAR(30),start_time TIME,end_time TIME,del_flag CHAR(1))");
                jdbc.execute("CREATE TABLE edu_attendance_lesson(schedule_id BIGINT,class_date DATE,teacher_id BIGINT,start_time TIME,end_time TIME,locked TINYINT)");
                jdbc.execute("CREATE TABLE edu_class_sign_in(sign_in_id BIGINT PRIMARY KEY,schedule_id BIGINT,class_date DATE,student_name VARCHAR(30),sign_status CHAR(1),review_status CHAR(1),del_flag CHAR(1))");
                jdbc.execute("INSERT INTO sys_user VALUES(10,'teacher','教师','0','0')");jdbc.execute("INSERT INTO sys_role VALUES(7,'teacher','0','0')");jdbc.execute("INSERT INTO sys_user_role VALUES(10,7)");
                jdbc.execute("INSERT INTO edu_teacher_salary_config(teacher_id,base_salary,rate_primary_class,rate_jh_class,rate_junior_1on1,class_hours_per_session) VALUES(10,1000,100,150,200,1.5)");
                jdbc.execute("INSERT INTO edu_teacher_salary_record(teacher_id,salary_month,total_amount,confirm_status) VALUES(10,'2026-10',1234.56,'1')");
                for(String file:List.of("20261125_salary_special_attendance.sql","20261125_salary_attendance_commission.sql")) {
                    String migration=Files.readString(root.resolve("database/migrations/"+file)).replaceAll("(?m)^\\s*--.*$","");
                    for(int i=0;i<2;i++)try(Connection c=source.getConnection();Statement st=c.createStatement()){for(String sql:migration.split(";"))if(!sql.isBlank())st.execute(sql);}
                }
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_dict_data WHERE dict_type='edu_class_type' AND dict_value='特价课' AND status='0'",Integer.class));
                assertEquals(new BigDecimal("1234.56"),jdbc.queryForObject("SELECT total_amount FROM edu_teacher_salary_record WHERE salary_month='2026-10'",BigDecimal.class));
                jdbc.execute("UPDATE edu_teacher_salary_config SET rate_special_class=40 WHERE teacher_id=10");
                jdbc.execute("INSERT INTO edu_course_schedule VALUES(1,10,'1','常规班','初一','08:00','09:30','0'),(2,10,'1','特价课','初一','08:00','09:30','0'),(3,10,'2','特价课','初一','08:00','09:30','0')");
                jdbc.execute("INSERT INTO edu_class_sign_in VALUES(1,1,'2026-11-01','甲','1','1','0'),(2,1,'2026-11-01','乙','4','1','0'),(3,1,'2026-11-01','丙','5','1','0'),(4,1,'2026-11-01','丁','3','1','0'),(5,1,'2026-11-01','戊','2','1','0'),(6,2,'2026-11-02','甲','1','1','0'),(7,1,'2026-11-03','甲','1','1','0'),(8,3,'2026-11-04','甲','1','1','0'),(9,1,'2026-11-05','甲','1','0','0'),(10,1,'2026-12-01','甲','1','1','0')");
                var cfg=new Configuration(new Environment("test",new JdbcTransactionFactory(),source));cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);
                cfg.getTypeAliasRegistry().registerAlias("EduTeacherSalaryConfig",EduTeacherSalaryConfig.class);cfg.getTypeAliasRegistry().registerAlias("EduTeacherSalaryRecord",EduTeacherSalaryRecord.class);
                for(String mapper:List.of("EduTeacherSalaryConfigMapper","EduTeacherSalaryRecordMapper")){String name="mapper/system/"+mapper+".xml";try(var in=getClass().getResourceAsStream('/'+name)){new XMLMapperBuilder(in,cfg,name,cfg.getSqlFragments()).parse();}}
                try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(true)) {
                    var records=session.getMapper(EduTeacherSalaryRecordMapper.class);var configs=session.getMapper(EduTeacherSalaryConfigMapper.class);
                    assertEquals(new BigDecimal("40.00"),configs.selectConfigByTeacherId(10L).getRateSpecialClass());
                    var payroll=new EduTeacherSalaryRecordServiceImpl();ScheduleCrudTest.inject(payroll,"recordMapper",records);ScheduleCrudTest.inject(payroll,"configMapper",configs);
                    var before=payroll.previewMonth("2026-11").get(0);
                    assertEquals(2L,before.getJhClassCount());assertEquals(1L,before.getSpecialClassCount());assertEquals(new BigDecimal("60.00"),before.getSpecialClassAmount());
                    assertEquals(new BigDecimal("810.00"),before.getLessonAmount());assertEquals(new BigDecimal("1810.00"),before.getTotalAmount());
                    var counts=new TeacherClassAttendanceService(db,records);ScheduleCrudTest.principal(1,"admin");
                    assertEquals(5L,counts.summary(10,"2026-11").get("count"));
                    counts.adjust(10,"2026-11",new BigDecimal("12"));assertEquals(12L,counts.summary(10,"2026-11").get("count"));assertEquals(5L,counts.summary(10,"2026-11").get("automaticCount"));
                    assertEquals(1L,counts.summary(10,"2026-12").get("count"));
                    assertEquals(before.getTotalAmount(),payroll.previewMonth("2026-11").get(0).getTotalAmount());assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in",Integer.class));
                    assertThrows(ServiceException.class,()->counts.adjust(10,"2026-11",new BigDecimal("1.5")));assertThrows(ServiceException.class,()->counts.adjust(10,"2026-11",new BigDecimal("-1")));
                    counts.adjust(10,"2026-11",null);assertEquals(5L,counts.summary(10,"2026-11").get("count"));
                    var configService=new com.ruoyi.system.service.impl.EduTeacherSalaryConfigServiceImpl();ScheduleCrudTest.inject(configService,"configMapper",configs);
                    var price=new EduTeacherSalaryConfig();price.setConfigId(configs.selectConfigByTeacherId(10L).getConfigId());price.setClassAttendanceUnitPrice(new BigDecimal("2.35"));price.setCommissionAmount(new BigDecimal("7.50"));configService.updateConfig(price);
                    assertEquals(new BigDecimal("2.35"),configs.selectConfigByTeacherId(10L).getClassAttendanceUnitPrice());
                    assertEquals(new BigDecimal("11.75"),counts.summary(10,"2026-11").get("totalCommission"));
                    assertEquals(new BigDecimal("1829.25"),payroll.previewMonth("2026-11").get(0).getTotalAmount());
                    counts.adjust(10,"2026-11",new BigDecimal("12"));
                    assertEquals(new BigDecimal("28.20"),counts.summary(10,"2026-11").get("totalCommission"));
                    assertEquals(1,payroll.generateMonth("2026-11"));
                    var saved=records.selectRecordByTeacherMonth(10L,"2026-11");assertEquals(new BigDecimal("60.00"),saved.getSpecialClassAmount());
                    assertEquals(12L,saved.getClassAttendanceCount());assertEquals(new BigDecimal("2.35"),saved.getClassAttendanceUnitPrice());assertEquals(new BigDecimal("28.20"),saved.getClassAttendanceCommission());
                    assertEquals(new BigDecimal("7.50"),saved.getCommissionAmount());assertEquals(new BigDecimal("1845.70"),saved.getTotalAmount());
                    counts.adjust(10,"2026-11",BigDecimal.ZERO);payroll.generateMonth("2026-11");
                    assertEquals(new BigDecimal("0.00"),records.selectRecordByTeacherMonth(10L,"2026-11").getClassAttendanceCommission());
                    counts.adjust(10,"2026-11",null);payroll.generateMonth("2026-11");payroll.confirmRecord(saved.getRecordId());
                    price.setClassAttendanceUnitPrice(new BigDecimal("6"));configService.updateConfig(price);counts.adjust(10,"2026-11",new BigDecimal("8"));
                    assertEquals(new BigDecimal("48.00"),counts.summary(10,"2026-11").get("totalCommission"));
                    var confirmed=payroll.previewMonth("2026-11").get(0);assertEquals(new BigDecimal("1829.25"),confirmed.getTotalAmount());assertEquals(5L,confirmed.getClassAttendanceCount());assertEquals(new BigDecimal("2.35"),confirmed.getClassAttendanceUnitPrice());
                    assertThrows(ServiceException.class,()->payroll.generateMonth("2026-11"));
                    assertEquals(new BigDecimal("1234.56"),payroll.previewMonth("2026-10").get(0).getTotalAmount());
                    price.setClassAttendanceUnitPrice(new BigDecimal("0.001"));assertThrows(ServiceException.class,()->configService.updateConfig(price));
                    assertEquals(new BigDecimal("6.00"),configs.selectConfigByTeacherId(10L).getClassAttendanceUnitPrice());assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in",Integer.class));
                }
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext();admin.createStatement().execute("DROP DATABASE "+schema); }
        }
    }
}
