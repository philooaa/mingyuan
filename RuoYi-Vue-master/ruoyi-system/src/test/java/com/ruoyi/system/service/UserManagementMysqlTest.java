package com.ruoyi.system.service;

import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.system.mapper.SysUserMapper;
import static org.junit.jupiter.api.Assertions.*;

class UserManagementMysqlTest {
    @Test void roleFilterIncludesMultiRoleUsersWithoutDuplicateRows() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("user.mysql"));
        String schema = "codex_user_management_" + UUID.randomUUID().toString().replace("-", "");
        String password = System.getenv().getOrDefault("MYSQL_PWD", "");
        try (Connection admin = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/", "root", password)) {
            admin.createStatement().execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            try {
                var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + schema, "root", password);
                try (Connection c = ds.getConnection()) {
                    String source = Files.readString(Path.of("../../database/schema.sql"));
                    for (String table : List.of("sys_dept", "sys_user", "sys_role", "sys_user_role")) {
                        Matcher m = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=.*?;", Pattern.DOTALL).matcher(source);
                        assertTrue(m.find(), table); c.createStatement().execute(m.group());
                    }
                    c.createStatement().execute("INSERT INTO sys_user(user_id,user_name,nick_name) VALUES(10,'teacher10','老师'),(20,'parent20','家长')");
                    c.createStatement().execute("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(10,'教师','teacher',1,'0'),(20,'家长','parent',2,'0')");
                    c.createStatement().execute("INSERT INTO sys_user_role VALUES(10,10),(10,20),(20,20)");
                }
                Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
                cfg.getTypeAliasRegistry().registerAliases("com.ruoyi.common.core.domain.entity");
                try (InputStream in = getClass().getResourceAsStream("/mapper/system/SysUserMapper.xml")) {
                    new XMLMapperBuilder(in, cfg, "SysUserMapper.xml", cfg.getSqlFragments()).parse();
                }
                try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession()) {
                    SysUserMapper mapper = session.getMapper(SysUserMapper.class);
                    SysUser query = new SysUser(); query.setRoleId(10L);
                    var teachers = mapper.selectUserList(query);
                    assertEquals(List.of(10L), teachers.stream().map(SysUser::getUserId).toList());
                    assertEquals("教师、家长", teachers.get(0).getRoleNames());
                    query.setRoleId(20L);
                    assertEquals(2, mapper.selectUserList(query).size());
                    query.setRoleId(null);
                    assertEquals(2, mapper.selectUserList(query).size());
                    SysUser edit = new SysUser(); edit.setUserId(10L); edit.setUserName("teacher_renamed"); edit.setNickName("新昵称");
                    mapper.updateUserName(edit); mapper.updateUser(edit);
                    assertEquals("teacher_renamed", mapper.selectUserById(10L).getUserName());
                    assertEquals("新昵称", mapper.selectUserById(10L).getNickName());
                    assertEquals(2, mapper.selectUserById(10L).getRoles().size());
                    // An old login session updating profile fields must not revert the new login name.
                    edit.setUserName("teacher10"); edit.setNickName("个人资料昵称"); mapper.updateUser(edit);
                    assertEquals("teacher_renamed", mapper.selectUserById(10L).getUserName());
                }
            } finally { admin.createStatement().execute("DROP DATABASE " + schema); }
        }
    }
}
