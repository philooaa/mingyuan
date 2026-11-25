package com.ruoyi.system.service;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.EduTeacherSalaryRecordServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

class AttendanceSalaryApprovalTest {
    @Test void payrollUsesApprovedSessionsAndPreservesClassAndOneToOneRates() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();
        var service=service(rows,null);
        assertEquals(0L,service.previewMonth("2026-11").get(0).getTotalLessons());
        rows.add(session("1","primary",3));rows.add(session("1","primary",0));rows.add(session("2","junior",2));
        var result=service.previewMonth("2026-11").get(0);
        assertEquals(2L,result.getPrimaryClassCount());assertEquals(2L,result.getJunior1on1Count());assertEquals(4L,result.getTotalLessons());
        assertEquals(new BigDecimal("1200.00"),result.getLessonAmount());
    }
    @Test void settledPreviewUsesTheSavedFinancialRecord() throws Exception {
        var saved=new EduTeacherSalaryRecord();saved.setTeacherId(10L);saved.setConfirmStatus("1");saved.setTotalAmount(new BigDecimal("1234.56"));saved.setTotalLessons(9L);
        var service=service(List.of(),saved);var preview=service.previewMonth("2026-11").get(0);
        assertEquals(9L,preview.getTotalLessons());assertEquals(new BigDecimal("1234.56"),preview.getTotalAmount());
    }
    @Test void specialClassesUseOnlyTheirOwnHourlyRate() throws Exception {
        var special=new HashMap<>(session("1","primary",8));special.put("classType","特价课");
        var one=new HashMap<>(session("2","junior",1));one.put("classType","特价课");
        var result=service(List.of(special,session("1","primary",2),one),null).previewMonth("2026-11").get(0);
        assertEquals(1L,result.getSpecialClassCount());assertEquals(1L,result.getPrimaryClassCount());assertEquals(1L,result.getJunior1on1Count());
        assertEquals(new BigDecimal("80.00"),result.getSpecialClassAmount());assertEquals(new BigDecimal("680.00"),result.getLessonAmount());
    }
    Map<String,Object> session(String mode,String stage,long attended){return Map.of("teacherId",10L,"classMode",mode,"stage",stage,"attendedRows",attended);}
    @Test void attendanceCommissionUsesClassStudentsAndManualOverridesWithoutHourlyMultipliers() throws Exception {
        var rows=List.of(session("1","primary",3),session("1","junior",2),session("2","junior",8));
        var automatic=service(rows,null,new BigDecimal("2.35"),List.of()).previewMonth("2026-11").get(0);
        assertEquals(5L,automatic.getClassAttendanceCount());assertEquals(new BigDecimal("11.75"),automatic.getClassAttendanceCommission());
        assertEquals(automatic.getLessonAmount().add(new BigDecimal("11.75")),automatic.getTotalAmount());
        var manual=service(rows,null,new BigDecimal("2.35"),List.of(Map.of("teacherId",10L,"attendanceCount",12L))).previewMonth("2026-11").get(0);
        assertEquals(new BigDecimal("28.20"),manual.getClassAttendanceCommission());assertEquals(automatic.getLessonAmount(),manual.getLessonAmount());
        var zero=service(rows,null,new BigDecimal("2.35"),List.of(Map.of("teacherId",10L,"attendanceCount",0L))).previewMonth("2026-11").get(0);
        assertEquals(0L,zero.getClassAttendanceCount());assertEquals(new BigDecimal("0.00"),zero.getClassAttendanceCommission());
    }
    @Test void attendanceUnitPriceUsesExactCentsAndRejectsInvalidPricesOrOverflow() {
        assertEquals(new BigDecimal("0.30"),TeacherClassAttendanceService.commission(3,new BigDecimal("0.10")));
        assertEquals(new BigDecimal("0.00"),TeacherClassAttendanceService.commission(12,null));
        assertEquals(new BigDecimal("2.35"),TeacherClassAttendanceService.unitPrice(new BigDecimal("2.3500")));
        for(String price:List.of("-1","0.001","100000000"))assertThrows(com.ruoyi.common.exception.ServiceException.class,()->TeacherClassAttendanceService.unitPrice(new BigDecimal(price)));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->TeacherClassAttendanceService.commission(1000000000,new BigDecimal("1")));
    }
    EduTeacherSalaryRecordServiceImpl service(List<Map<String,Object>> rows,EduTeacherSalaryRecord saved) throws Exception {
        return service(rows,saved,BigDecimal.ZERO,List.of());
    }
    EduTeacherSalaryRecordServiceImpl service(List<Map<String,Object>> rows,EduTeacherSalaryRecord saved,BigDecimal price,List<Map<String,Object>> overrides) throws Exception {
        var service=new EduTeacherSalaryRecordServiceImpl();
        var mapper=Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduTeacherSalaryRecordMapper.class},(p,m,a)->{
            switch(m.getName()) {
                case "selectTeacherUsers":return List.of(Map.of("teacherId",10L,"realName","教师"));
                case "selectMonthlySessions":return rows;
                case "selectMonthlyAttendanceOverrides":return overrides;
                case "selectRecordByTeacherMonth":return saved;
                default:throw new AssertionError("Unexpected salary source: "+m.getName());
            }
        });
        var config=new EduTeacherSalaryConfig();config.setTeacherId(10L);config.setClassHoursPerSession(new BigDecimal("2"));config.setRateSpecialClass(new BigDecimal("40"));config.setRatePrimaryClass(new BigDecimal("100"));config.setRateJunior1on1(new BigDecimal("200"));
        config.setClassAttendanceUnitPrice(price);
        var configs=Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduTeacherSalaryConfigMapper.class},(p,m,a)->List.of(config));
        for(var entry:Map.of("recordMapper",mapper,"configMapper",configs).entrySet()){var f=service.getClass().getDeclaredField(entry.getKey());f.setAccessible(true);f.set(service,entry.getValue());}
        return service;
    }
}
