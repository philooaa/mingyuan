package com.ruoyi.system.service;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.system.timetable.TimetableImportService;
import com.ruoyi.system.service.impl.EduCourseScheduleServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

class TimetableTeacherMatchingTest {
    private TimetableImportService service(List<SysUser> users) throws Exception {
        var service = new TimetableImportService();
        SysRole role = new SysRole(); role.setRoleId(101L); role.setRoleKey("teacher");
        Object roles = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ISysRoleService.class},
                (p,m,a) -> m.getName().equals("selectRoleList") ? List.of(role) : null);
        Object teachers = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ISysUserService.class},
                (p,m,a) -> m.getName().equals("selectAllocatedList") ? users : null);
        for (var entry : Map.of("roleService", roles, "userService", teachers).entrySet()) {
            Field f = TimetableImportService.class.getDeclaredField(entry.getKey()); f.setAccessible(true); f.set(service, entry.getValue());
        }
        return service;
    }
    private SysUser teacher(long id, String login, String name) {
        SysUser u = new SysUser(); u.setUserId(id); u.setUserName(login); u.setNickName(name); u.setStatus("0"); return u;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> parse(TimetableImportService service, String text) throws Exception {
        Method names = TimetableImportService.class.getDeclaredMethod("loadTeacherNames"); names.setAccessible(true);
        var aliases = (Map<String,SysUser>) names.invoke(service);
        Method parse = TimetableImportService.class.getDeclaredMethod("parseCourse", String.class, List.class, List.class, List.class, Map.class, Map.class);
        parse.setAccessible(true);
        Map<String,Object> result = new HashMap<>();
        parse.invoke(service, text, List.of("初三", "初二"), List.of("英语", "数学"), List.of("常规班"), aliases, result);
        return result;
    }
    @Test void matchesAllThreeReportedTeachersAndDisplaysNamesRatherThanPhoneLogin() throws Exception {
        var service = service(List.of(teacher(314,"13900000000","王文晶"), teacher(379,"陈赟","陈赟"), teacher(380,"孙宁宁","孙宁宁")));
        assertEquals(380L, parse(service,"初三数学常规班孙宁宁").get("teacherId"));
        assertEquals(379L, parse(service,"初二英语常规班陈赟").get("teacherId"));
        var wang = parse(service,"初二英语常规班王文晶");
        assertEquals(314L, wang.get("teacherId")); assertEquals("王文晶", wang.get("teacherName"));
        assertEquals("王文晶", EduCourseScheduleServiceImpl.realNameOf(Map.of("userName","13900000000","nickName","王文晶")));
    }
    @Test void normalizesSpreadsheetWhitespaceAndBothDirectionsOfKnownVariant() throws Exception {
        var service = service(List.of(teacher(379,"陈赟","陈赟"), teacher(380,"孙　宁宁","孙　宁宁")));
        assertEquals(379L, parse(service,"初二英语常规班陈贇").get("teacherId"));
        assertEquals(380L, parse(service,"初三数学常规班孙宁宁").get("teacherId"));
        assertEquals(379L, parse(service(List.of(teacher(379,"陈贇","陈贇"))),"初二英语常规班陈赟").get("teacherId"));
    }

    @Test @SuppressWarnings("unchecked") void excelPreviewUsesTheSameMatchingForThreeTeacherNames() throws Exception {
        var service = service(List.of(teacher(314,"13900000000","王文晶"),teacher(379,"陈赟","陈赟"),teacher(380,"孙宁宁","孙宁宁")));
        Object rooms = Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IEduClassroomService.class},(p,m,a)->List.of());
        Object dicts = Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ISysDictDataService.class},(p,m,a)->List.of());
        for (var entry : Map.of("classroomService",rooms,"dictDataService",dicts).entrySet()) {
            Field f=TimetableImportService.class.getDeclaredField(entry.getKey());f.setAccessible(true);f.set(service,entry.getValue());
        }
        byte[] bytes;
        try (var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            var sheet=book.createSheet("寒假");
            String[] header={"排课编码","学期","期次或上课日","时段","年级","科目","教师姓名","课程名称"};
            var h=sheet.createRow(0);for(int c=0;c<header.length;c++)h.createCell(c).setCellValue(header[c]);
            String[] names={"孙 宁宁","陈贇","王文晶"};
            for(int r=0;r<names.length;r++) {
                var row=sheet.createRow(r+1);
                String[] values={"TEST"+r,"寒假","一期","8:00-10:00","初二","数学",names[r],"测试班"};
                for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);
            }
            book.write(out);bytes=out.toByteArray();
        }
        var file=(org.springframework.web.multipart.MultipartFile)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.springframework.web.multipart.MultipartFile.class},(p,m,a)->switch(m.getName()) {
            case "isEmpty" -> false;case "getOriginalFilename","getName" -> "teachers.xlsx";
            case "getInputStream" -> new java.io.ByteArrayInputStream(bytes);case "getBytes" -> bytes;
            case "getSize" -> (long)bytes.length;default -> null;
        });
        var rows=(List<Map<String,Object>>)service.preview(file,2026,Map.of()).get("rows");
        assertEquals(List.of(380L,379L,314L),rows.stream().map(r->r.get("teacherId")).toList());
        assertEquals("王文晶",rows.get(2).get("teacherName"));
    }
}
