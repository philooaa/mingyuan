package com.ruoyi.system.service;

import java.util.*;
import java.time.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.shop.ShopRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 模拟业务依赖的状态回归；真实MySQL锁与回滚由联调另行验证。 */
public class OneToOneFlowTest {
    OneToOneEnrollmentService service = new OneToOneEnrollmentService();
    MemoryDb db = new MemoryDb();
    EduCourseSchedule schedule;
    OneToOneFlowTest() throws Exception {
        ScheduleCrudTest.principal(21,"teacher");
        var draft = new TeacherOneToOneDraft(); draft.classroomId=1L; draft.gradeName="一年级"; draft.courseClassName="数学辅导";
        draft.startDate=LocalDate.now().plusDays(7);
        schedule=draft.toSchedule("数学",new ScheduleAvailabilityService.Slot("8:00-10:00",LocalTime.of(8,0),LocalTime.of(10,0)));
        // 旧连续课程仍可按具体日期报名；新建参数只生成一节。
        schedule.setEndDate(java.sql.Date.valueOf(draft.startDate.plusWeeks(3))); schedule.setScheduleId(10L); schedule.setTeacherId(21L); schedule.setDelFlag("0");
        ScheduleCrudTest.inject(service,"db",db);
        ScheduleCrudTest.inject(service,"tuition",new com.ruoyi.system.finance.TuitionService(null,null,null,null,true) {
            @Override public void initializeBill(long enrollment,long actor) {}
            @Override public void closeSingleReservation(long enrollment,long teacher,String reason) {}
        });
        var credits=new LessonCreditService() {
            @Override public Map<String,Object> enrollmentChild(Long parentId,Long accountId,String name,String grade,String phone,String key) {return Map.of("accountId",80L+parentId,"studentName",name);}
        }; ScheduleCrudTest.inject(service,"credits",credits);
        var profiles = new EduTeacherProfileService();
        ScheduleCrudTest.inject(profiles,"db",db);
        ScheduleCrudTest.inject(service,"teacherProfiles",profiles);
        ScheduleCrudTest.inject(service,"schedules",ScheduleCrudTest.proxy(EduCourseScheduleMapper.class,(p,m,a)->schedule));
        ScheduleCrudTest.inject(service,"parent",ScheduleCrudTest.proxy(MiniAppParentMapper.class,(p,m,a)->m.getName().equals("selectScheduleAdjustments") ? List.of() : 1));
        ScheduleCrudTest.inject(service,"enrollments",ScheduleCrudTest.proxy(EduEnrollmentMapper.class,(p,m,a)->{db.invalidated++; return 1;}));
    }
    MiniAppEnrollmentBody body(int weeks) {
        var b=new MiniAppEnrollmentBody(); b.setScheduleId(10L); b.setClassDate(LocalDate.now().plusDays(7 + 7*weeks).toString()); b.setStudentName("学生甲"); return b;
    }
    @Test void pendingOccupiesOnlyOneDateAndRetainsUnpaid() {
        ScheduleCrudTest.principal(31,"parent"); assertEquals(1,service.enroll(body(0)));
        assertEquals("0",db.records.get(1L).get("enrollmentStatus")); assertEquals("0",db.records.get(1L).get("payStatus"));
        ScheduleCrudTest.principal(32,"parent"); assertThrows(ServiceException.class,()->service.enroll(body(0)));
        assertEquals(1,service.enroll(body(1))); assertEquals(2,db.records.size());
    }
    @Test void teacherConfirmOwnershipRepeatAndCancelHistory() {
        ScheduleCrudTest.principal(31,"parent"); service.enroll(body(0));
        ScheduleCrudTest.principal(22,"teacher"); assertThrows(ServiceException.class,()->service.decide(1L,"CONFIRM",null));
        ScheduleCrudTest.principal(21,"teacher"); assertEquals(1,service.decide(1L,"CONFIRM",null)); assertEquals(0,service.decide(1L,"CONFIRM",null));
        ScheduleCrudTest.principal(32,"parent"); assertThrows(ServiceException.class,()->service.requestCancellation(1L,"改期"));
        ScheduleCrudTest.principal(31,"parent"); assertEquals(1,service.requestCancellation(1L,"改期")); assertEquals(0,service.requestCancellation(1L,"改期"));
        ScheduleCrudTest.principal(32,"parent"); assertThrows(ServiceException.class,()->service.enroll(body(0)));
        ScheduleCrudTest.principal(21,"teacher"); assertEquals(1,service.decide(1L,"REJECT_CANCEL","继续保留")); assertEquals("1",db.records.get(1L).get("enrollmentStatus"));
        ScheduleCrudTest.principal(31,"parent"); service.requestCancellation(1L,"再次申请");
        ScheduleCrudTest.principal(21,"teacher"); assertEquals(1,service.decide(1L,"APPROVE_CANCEL","同意")); assertEquals(0,service.decide(1L,"APPROVE_CANCEL","同意"));
        assertEquals(1,db.invalidated); assertEquals("2",db.records.get(1L).get("enrollmentStatus")); assertEquals("0",db.records.get(1L).get("payStatus"));
        ScheduleCrudTest.principal(32,"parent"); assertEquals(1,service.enroll(body(0))); assertEquals(2,db.records.size()); assertTrue(db.events.size()>=7);
        assertThrows(ServiceException.class,()->service.history(1L,false));
    }
    @Test void rejectionReleasesDateButDoesNotDeleteAndHistoryBlocksCancellation() {
        ScheduleCrudTest.principal(31,"parent"); service.enroll(body(0));
        ScheduleCrudTest.principal(21,"teacher"); service.decide(1L,"REJECT","时间不合适");
        assertEquals("3",db.records.get(1L).get("enrollmentStatus")); assertEquals(1,db.records.size());
        ScheduleCrudTest.principal(32,"parent"); service.enroll(body(0)); db.attended=true;
        assertThrows(ServiceException.class,()->service.requestCancellation(2L,"取消"));
        assertEquals("0",db.records.get(2L).get("cancelRequestStatus"));
    }
    @Test void stoppedExpiredAndInvalidDateCannotEnroll() {
        ScheduleCrudTest.principal(31,"parent"); schedule.setStatus("1"); assertThrows(ServiceException.class,()->service.enroll(body(0)));
        schedule.setStatus("0"); var invalid=body(0); invalid.setClassDate(LocalDate.now().plusDays(8).toString()); assertThrows(ServiceException.class,()->service.enroll(invalid));
        schedule.setStartDate(java.sql.Date.valueOf(LocalDate.now().minusDays(7))); schedule.setEndDate(java.sql.Date.valueOf(LocalDate.now().minusDays(7)));
        invalid.setClassDate(LocalDate.now().minusDays(7).toString()); assertThrows(ServiceException.class,()->service.enroll(invalid));
    }
    static class MemoryDb extends ShopRepository {
        Map<Long,Map<String,Object>> records=new LinkedHashMap<>(); List<Map<String,Object>> events=new ArrayList<>(); int invalidated; boolean attended;
        MemoryDb() {super(new DriverManagerDataSource());}
        @Override public Map<String,Object> one(String sql,Object... a) {
            var e=records.get(Long.valueOf(a[0].toString())); if(e==null)throw new ServiceException("不存在");
            if(sql.startsWith("SELECT *")) return new LinkedHashMap<>(e);
            return Map.of("scheduleId",10L,"parentId",e.get("parentId"),"teacherId",21L);
        }
        @Override public List<Map<String,Object>> rows(String sql,Object... a) {
            if(sql.contains("teacher_subject"))return List.of(Map.of("teacherSubject","数学"));
            if(sql.contains("sign_in_id")||sql.contains("attendance_id"))return attended ? List.of(Map.of("id",1)) : List.of();
            if(sql.contains("class_date IS NULL")) return List.of();
            if(sql.contains("FOR UPDATE"))return records.values().stream().filter(e->e.get("classDate").toString().equals(a[1].toString())&&List.of("0","1").contains(e.get("enrollmentStatus"))).toList();
            if(sql.contains("edu_enrollment_event"))return events;
            throw new AssertionError(sql);
        }
        @Override public long insert(String table,Map<String,Object> values) {
            if(table.equals("edu_enrollment_event")){events.add(new LinkedHashMap<>(values));return events.size();}
            long id=records.size()+1;Map<String,Object> row=new LinkedHashMap<>();values.forEach((k,v)->row.put(camelKey(k),v)); row.put("enrollmentId",id);row.put("cancelRequestStatus","0");records.put(id,row);return id;
        }
        @Override public void update(String table,String col,long id,Map<String,Object> values) {values.forEach((k,v)->records.get(id).put(camelKey(k),v));}
        static String camelKey(String s) {StringBuilder r=new StringBuilder();boolean upper=false;for(char c:s.toCharArray()){if(c=='_')upper=true;else{r.append(upper?Character.toUpperCase(c):c);upper=false;}}return r.toString();}
    }
    public static void main(String[] args)throws Exception {
        int n=0;for(Method m:OneToOneFlowTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){var t=new OneToOneFlowTest();try{m.invoke(t);n++;}catch(InvocationTargetException e){throw new AssertionError(m.getName(),e.getCause());}}
        System.out.println("PASS: "+n+" stateful one-to-one flow scenarios (mock DB; not MySQL concurrency)");
    }
}
