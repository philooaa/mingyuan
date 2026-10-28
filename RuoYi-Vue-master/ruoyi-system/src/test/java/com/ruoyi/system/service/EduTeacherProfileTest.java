package com.ruoyi.system.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;
import static org.junit.jupiter.api.Assertions.*;

/** 存储拆分的身份、缺配置与写入校验；真实 SQL/外键另在隔离 MySQL 核验。 */
public class EduTeacherProfileTest {
    private final MemoryDb db = new MemoryDb();
    private final EduTeacherProfileService service = new EduTeacherProfileService();

    public EduTeacherProfileTest() throws Exception {
        ScheduleCrudTest.inject(service, "db", db);
    }

    @Test void readsBusinessProfileForSpecifiedTeacherAndRejectsUnavailableAccounts() {
        assertEquals("化学", service.getAvailableTeacher(21L).get("teacherSubject"));
        assertEquals("数学", service.getAvailableTeacher(22L).get("teacherSubject"));
        assertThrows(ServiceException.class, () -> service.getAvailableTeacher(23L));
        db.available = false;
        assertThrows(ServiceException.class, () -> service.getAvailableTeacher(21L));
    }

    @Test void missingProfileDoesNotInventSubjectOrLevel() {
        db.subject = null;
        var row = service.getAvailableTeacher(21L);
        assertNull(row.get("teacherSubject"));
        assertNull(row.get("teacherLevel"));
        assertNull(service.get(21L).get("teacherSubject"));
    }

    @Test void writesOnlyBusinessTableAndKeepsCreatorOnUpdate() {
        service.save(21L, "化学", "senior", "admin");
        assertEquals(1, db.writes.size());
        assertEquals(21L, db.writes.get(0)[0]);
        assertEquals("化学", db.writes.get(0)[1]);
        assertEquals("senior", db.writes.get(0)[2]);
        assertEquals("admin", db.writes.get(0)[3]);
        assertTrue(db.writeSql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(db.writeSql.substring(db.writeSql.indexOf("ON DUPLICATE KEY UPDATE")).contains("create_by"));
    }

    @Test void invalidTeacherOrDisabledSubjectCannotWrite() {
        db.teacherRole = false;
        assertThrows(ServiceException.class, () -> service.save(21L, "化学", "senior", "admin"));
        db.teacherRole = true;
        db.subjectEnabled = false;
        assertThrows(ServiceException.class, () -> service.save(21L, "化学", "elite", "admin"));
        db.subjectEnabled = true;
        db.accountExists = false;
        assertThrows(ServiceException.class, () -> service.save(21L, "化学", "elite", "admin"));
        assertTrue(db.writes.isEmpty());
    }

    @Test void malformedConfigurationCannotWrite() {
        assertThrows(ServiceException.class, () -> service.save(null, "化学", "elite", "admin"));
        assertThrows(ServiceException.class, () -> service.save(21L, null, "elite", "admin"));
        assertThrows(ServiceException.class, () -> service.save(21L, " ", "elite", "admin"));
        assertThrows(ServiceException.class, () -> service.save(21L, "化".repeat(21), "elite", "admin"));
        assertThrows(ServiceException.class, () -> service.save(21L, "化学", "2", "admin"));
        assertThrows(ServiceException.class, () -> service.save(21L, "化学", null, "admin"));
        assertTrue(db.writes.isEmpty());
    }

    @Test void optionalTeacherExcelColumnsPreserveOldUserFieldsAndNormalizeLevel() throws Exception {
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var bytes=new java.io.ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("用户数据");
            var head=sheet.createRow(0); var row=sheet.createRow(1);
            String[] names={"登录名称","用户名称","手机号码","教师授课学科","教师等级"};
            String[] values={"teacher_import","教师全名","13900000001","化学","资深教师"};
            for(int i=0;i<names.length;i++) {head.createCell(i).setCellValue(names[i]);row.createCell(i).setCellValue(values[i]);}
            workbook.write(bytes);
            var imported=new com.ruoyi.common.utils.poi.ExcelUtil<>(com.ruoyi.system.domain.EduTeacherUserImport.class).importExcel(new java.io.ByteArrayInputStream(bytes.toByteArray())).get(0);
            assertEquals("teacher_import",imported.getUserName()); assertEquals("教师全名",imported.getNickName());
            assertEquals("13900000001",imported.getPhonenumber()); assertEquals("化学",imported.getTeacherSubject()); assertEquals("senior",imported.getTeacherLevel());
            assertTrue(imported.hasTeacherProfile());
            sheet.getRow(0).removeCell(head.getCell(3)); sheet.getRow(0).removeCell(head.getCell(4));bytes.reset();workbook.write(bytes);
            var old=new com.ruoyi.common.utils.poi.ExcelUtil<>(com.ruoyi.system.domain.EduTeacherUserImport.class).importExcel(new java.io.ByteArrayInputStream(bytes.toByteArray())).get(0);
            assertEquals("teacher_import",old.getUserName());assertFalse(old.hasTeacherProfile());
        }
    }

    private static class MemoryDb extends ShopRepository {
        boolean available = true, teacherRole = true, subjectEnabled = true, accountExists = true;
        String subject = "化学", writeSql;
        List<Object[]> writes = new ArrayList<>();
        MemoryDb() { super(new DriverManagerDataSource()); }

        @Override public Map<String, Object> one(String sql, Object... args) {
            if (!accountExists) throw new ServiceException("账号不存在");
            if (sql.contains("FOR UPDATE")) return Map.of("userId", args[0]);
            return rows(sql, args).get(0);
        }

        @Override public List<Map<String, Object>> rows(String sql, Object... args) {
            if (sql.contains("edu_teacher_profile")) {
                assertFalse(sql.contains("u.teacher_subject"));
                assertFalse(sql.contains("u.teacher_level"));
                long id = ((Number) args[0]).longValue();
                if (sql.contains("EXISTS") && (!available || id == 23L)) return List.of();
                Map<String, Object> row = new HashMap<>();
                row.put("userId", id);
                row.put("teacherSubject", id == 22L ? "数学" : subject);
                row.put("teacherLevel", null);
                return List.of(row);
            }
            if (sql.contains("sys_user_role")) return teacherRole ? List.of(Map.of("userId", args[0])) : List.of();
            if (sql.contains("sys_dict_data")) return subjectEnabled ? List.of(Map.of("dictCode", 1)) : List.of();
            throw new AssertionError(sql);
        }

        @Override public JdbcTemplate jdbc() {
            return new JdbcTemplate() {
                @Override public int update(String sql, Object... args) {
                    assertTrue(sql.startsWith("INSERT INTO edu_teacher_profile "));
                    writeSql = sql;
                    writes.add(args);
                    return 1;
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        int count = 0;
        for (Method method : EduTeacherProfileTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) continue;
            try { method.invoke(new EduTeacherProfileTest()); count++; }
            catch (InvocationTargetException e) { throw new AssertionError(method.getName(), e.getCause()); }
        }
        System.out.println("PASS: " + count + " teacher profile scenarios (mock dependencies)");
    }
}
