package com.ruoyi.system.service;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Only writes a randomly named disposable database, never the application database. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EducationDictionaryMysqlTest {
    final String schema="codex_education_dict_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    JdbcTemplate jdbc;
    @BeforeAll void setup() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root",""));
        jdbc.execute("CREATE TABLE sys_dict_data(dict_code BIGINT PRIMARY KEY,dict_sort INT,dict_label VARCHAR(100),dict_value VARCHAR(100),dict_type VARCHAR(100),status CHAR(1))");
        String[] labels={"语文","数学","英语","物理","化学","生物","历史","地理","政治"};
        String[] codes={"chinese","math","english","physics","chemistry","biology","history","geography","politics"};
        for(int i=0;i<labels.length;i++) jdbc.update("INSERT INTO sys_dict_data VALUES(?,?,?,?,?,?)",i+1,i+1,labels[i],codes[i],"edu_subject",i==8?"1":"0");
        jdbc.update("INSERT INTO sys_dict_data VALUES(50,5,'五年级','g5','edu_grade','0')");
    }
    @Test void migrationOrdersNineSubjectsWithoutChangingCodesLabelsIdsStatusOrGrades() throws Exception {
        var path=Path.of("../../database/migrations/20261019_education_dictionary_order.sql");
        assertTrue(Files.exists(path),"需要可协作执行的学科排序迁移");
        var before=jdbc.queryForList("SELECT dict_code,dict_label,dict_value,dict_type,status FROM sys_dict_data ORDER BY dict_code");
        String migration=Files.readString(path);
        for(int attempt=0;attempt<2;attempt++) for(String statement:migration.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        assertEquals(List.of("语文","数学","英语","政治","历史","地理","物理","化学","生物"),jdbc.queryForList("SELECT dict_label FROM sys_dict_data WHERE dict_type='edu_subject' ORDER BY dict_sort",String.class));
        assertEquals(before,jdbc.queryForList("SELECT dict_code,dict_label,dict_value,dict_type,status FROM sys_dict_data ORDER BY dict_code"));
        assertEquals(5,jdbc.queryForObject("SELECT dict_sort FROM sys_dict_data WHERE dict_type='edu_grade'",Integer.class));
    }
    @AfterAll void cleanup() throws Exception {
        if(jdbc!=null&&schema.matches("codex_education_dict_test_[0-9a-f]{12}"))try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("DROP DATABASE "+schema);}
    }
}
