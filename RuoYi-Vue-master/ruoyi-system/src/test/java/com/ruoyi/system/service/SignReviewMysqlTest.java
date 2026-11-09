package com.ruoyi.system.service;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.model.MiniAppSignReviewBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.EduClassSignInServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

/** Only runs explicitly, and only creates/writes its own randomly named temporary database. */
@EnabledIfEnvironmentVariable(named="MINIAPP_SIGN_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignReviewMysqlTest {
    final String schema="codex_sign_review_test_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    JdbcTemplate jdbc; EduClassSignInServiceImpl service; EduTeacherSalaryRecordMapper salary;
    @BeforeAll void setup() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?serverTimezone=Asia/Shanghai","root","");jdbc=new JdbcTemplate(ds);
        String dump=Files.readString(Path.of("../../database/schema.sql"));
        for(String name:List.of("edu_classroom","edu_course_schedule","edu_class_sign_in")){
            var m=Pattern.compile("CREATE TABLE `"+name+"` \\(.*?\\) ENGINE=.*?;",Pattern.DOTALL).matcher(dump);assertTrue(m.find());jdbc.execute(m.group());
        }
        jdbc.execute("ALTER TABLE edu_class_sign_in ADD COLUMN review_stage VARCHAR(24) NULL, ADD COLUMN student_id BIGINT NULL");
        jdbc.update("INSERT INTO edu_classroom(classroom_id,classroom_code,campus_name,classroom_name) VALUES(1,'TEST','测试校区','测试教室')");
        jdbc.update("INSERT INTO edu_course_schedule(schedule_id,schedule_code,classroom_id,term_name,period_name,course_class_name,teacher_name,teacher_id,grade_name,subject_name,time_slot,del_flag) VALUES(1,'TEST',1,'秋季','周一','测试班','测试教师',10,'一年级','数学','10:00-12:00','0')");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setTypeAliasesPackage("com.ruoyi.system.domain");factory.setMapperLocations(new ClassPathResource("mapper/system/EduClassSignInMapper.xml"),new ClassPathResource("mapper/system/EduTeacherSalaryRecordMapper.xml"));
        salary=new SqlSessionTemplate(factory.getObject()).getMapper(EduTeacherSalaryRecordMapper.class);
        service=new EduClassSignInServiceImpl();inject("signInMapper",new SqlSessionTemplate(factory.getObject()).getMapper(EduClassSignInMapper.class));
        inject("scheduleMapper",Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduCourseScheduleMapper.class},(p,m,a)->{
            if(!m.getName().equals("lockSchedule"))throw new AssertionError(m.getName());jdbc.queryForObject("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=? FOR UPDATE",Long.class,a[0]);var s=new EduCourseSchedule();s.setScheduleId(1L);s.setTeacherId(jdbc.queryForObject("SELECT teacher_id FROM edu_course_schedule WHERE schedule_id=1",Long.class));s.setDelFlag("0");s.setStatus("0");return s;
        }));
        inject("classLeaves",new ClassLeaveService(){@Override public Set<Long> approved(Long id,String date){return Set.of();}});
        inject("singleDb",new com.ruoyi.system.shop.ShopRepository(ds));
        jdbc.execute("CREATE TABLE edu_attendance_lesson(lesson_id BIGINT PRIMARY KEY,schedule_id BIGINT,class_date DATE,teacher_id BIGINT,start_time TIME,end_time TIME,locked TINYINT,course_name VARCHAR(200))");
        jdbc.execute("CREATE TABLE edu_enrollment(enrollment_id BIGINT PRIMARY KEY,schedule_id BIGINT,class_date DATE,lesson_completed_time DATETIME,student_id BIGINT,parent_id BIGINT,student_name VARCHAR(30),del_flag CHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE edu_student(student_id BIGINT PRIMARY KEY,parent_id BIGINT,student_name VARCHAR(30))");
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(30),nick_name VARCHAR(30),phonenumber VARCHAR(11),status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0')");
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,phonenumber) VALUES(10,'教师真名','教师昵称',NULL),(30,'家长','家长昵称','11111111111')");
        jdbc.update("INSERT INTO sys_user(user_id,user_name) VALUES(1,'admin'),(20,'education'),(21,'education2'),(40,'business'),(50,'disabled')");
        jdbc.execute("CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(30),status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT)");
        jdbc.update("INSERT INTO sys_role(role_id,role_key) VALUES(1,'education'),(2,'business_admin'),(3,'teacher'),(4,'parent')");
        jdbc.update("INSERT INTO sys_user_role VALUES(20,1),(21,1),(40,2),(50,2),(10,3),(30,4)");
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=50");
        jdbc.execute("CREATE TABLE edu_teacher_salary_record(record_id BIGINT PRIMARY KEY,teacher_id BIGINT,salary_month VARCHAR(7),confirm_status CHAR(1),del_flag CHAR(1))");
    }
    void inject(String name,Object value)throws Exception{var f=EduClassSignInServiceImpl.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);}
    @BeforeEach void rows(){jdbc.update("UPDATE sys_role SET status='0'");jdbc.update("DELETE FROM sys_user_role WHERE user_id=40 AND role_id=1");jdbc.update("DELETE FROM edu_attendance_lesson");jdbc.update("DELETE FROM edu_enrollment");jdbc.update("DELETE FROM edu_teacher_salary_record");jdbc.update("DELETE FROM edu_class_sign_in");for(int i=1;i<=5;i++)jdbc.update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,sign_status,review_status,create_by,create_time,del_flag) VALUES(1,'2026-10-19',?,'1',?,'0','teacher-test',NOW(),'0')","学员"+i,""+i);}
    Map<String,Object> detail(){return service.selectDetail(1L,"2026-10-19");}
    Map<String,Object> change(String action,String reason,Long signId,String status) {
        var values=new LinkedHashMap<String,Object>(); values.put("scheduleId",1); values.put("classDate","2026-10-19");
        values.put("submissionVersion",detail().get("submissionVersion")); values.put("reason",reason); values.put("signInId",signId); values.put("signStatus",status);
        var b=JSON.parseObject(JSON.toJSONString(values),MiniAppSignReviewBody.class);
        try { return (Map<String,Object>)service.getClass().getMethod(action,MiniAppSignReviewBody.class,Long.class,String.class).invoke(service,b,20L,"教务甲"); }
        catch(java.lang.reflect.InvocationTargetException e) { throw (RuntimeException)e.getCause(); }
        catch(ReflectiveOperationException e) { throw new AssertionError("缺少异常处理接口 "+action,e); }
    }
    MiniAppSignReviewBody body() {
        var b=new MiniAppSignReviewBody();b.setScheduleId(1L);b.setClassDate("2026-10-19");b.setSubmissionVersion((String)detail().get("submissionVersion"));b.setImages(List.of("/profile/monitor.jpg"));return b;
    }
    Map<String,Object> approve(long actor,String role) {ScheduleCrudTest.principal(actor,role);return service.review(body(),actor,role);}
    Map<?,?> review() {return (Map<?,?>)detail().get("review");}
    @Test void firstReviewRequiresImagesAndDistinctStagePermissions() {
        var b=body();b.setImages(List.of());assertThrows(ServiceException.class,()->service.review(b,20L,"教务"));
        for(var actor:Map.of(10L,"teacher",30L,"parent",40L,"business_admin").entrySet()) {
            ScheduleCrudTest.principal(actor.getKey(),actor.getValue());assertThrows(ServiceException.class,()->service.review(body(),actor.getKey(),actor.getValue()));
        }
        var stale=body();assertEquals("PENDING_FINAL",approve(20,"education").get("reviewStage"));
        assertEquals("0",review().get("reviewStatus"));assertTrue(salary.selectMonthlySessions("2026-10").isEmpty());
        assertThrows(ServiceException.class,()->service.review(body(),20L,"教务"));
        ScheduleCrudTest.principal(40L,"business_admin");assertThrows(ServiceException.class,()->service.review(stale,40L,"业务"));
        assertEquals("FINAL",approve(40,"business_admin").get("reviewStage"));
        assertEquals(1,salary.selectMonthlySessions("2026-10").size());
        assertEquals(3,((Number)salary.selectMonthlySessions("2026-10").get(0).get("attendedRows")).intValue());
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review1_admin_id=20 AND review2_admin_id=40 AND review_status='1'",Integer.class));
        var facts=jdbc.queryForList("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id");assertThrows(ServiceException.class,()->approve(40,"business_admin"));assertEquals(facts,jdbc.queryForList("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id"));
    }
    @Test void originalAdminAndDualRolesMustPerformTwoSeparateStageTransitions() {
        assertEquals("PENDING_FINAL",approve(1,"admin").get("reviewStage"));assertEquals("FINAL",approve(1,"admin").get("reviewStage"));
        jdbc.update("UPDATE edu_class_sign_in SET review_status='0',review_stage='PENDING_FIRST',review1_admin_id=NULL,review2_admin_id=NULL");jdbc.update("INSERT INTO sys_user_role VALUES(40,1)");
        assertEquals("PENDING_FINAL",approve(40,"business_admin").get("reviewStage"));assertEquals("FINAL",approve(40,"business_admin").get("reviewStage"));
    }
    @Test void databaseRevocationOverridesCachedBusinessRole() {
        approve(20,"education");ScheduleCrudTest.principal(50L,"business_admin");assertThrows(ServiceException.class,()->service.review(body(),50L,"disabled"));
        jdbc.update("UPDATE sys_role SET status='1' WHERE role_key='business_admin'");assertThrows(ServiceException.class,()->approve(40,"business_admin"));
    }
    @Test void finalReturnAndCorrectionInvalidateInitialApprovalAndPreserveAudit() {
        approve(20,"education");ScheduleCrudTest.principal(40L,"business_admin");var b=body();b.setReason("监控与点名不符");service.reject(b,40L,"业务");
        assertEquals("RETURNED",review().get("reviewStage"));assertEquals(false,detail().get("locked"));
        assertTrue(jdbc.queryForObject("SELECT CAST(review_history AS CHAR) FROM edu_class_sign_in LIMIT 1",String.class).contains("PENDING_FINAL"));
        ScheduleCrudTest.principal(20L,"education");Long id=jdbc.queryForObject("SELECT MIN(sign_in_id) FROM edu_class_sign_in",Long.class);change("correct","核实未到课",id,"6");
        assertEquals("PENDING_FIRST",review().get("reviewStage"));assertNull(review().get("review1Images"));assertNull(review().get("review1AdminId"));
        assertThrows(ServiceException.class,()->approve(40,"business_admin"));approve(20,"education");approve(40,"business_admin");
        assertEquals("teacher-test",jdbc.queryForObject("SELECT create_by FROM edu_class_sign_in WHERE sign_in_id=?",String.class,id));assertTrue(jdbc.queryForObject("SELECT CAST(review_history AS CHAR) FROM edu_class_sign_in WHERE sign_in_id=?",String.class,id).contains("核实未到课"));
    }
    @Test void returnRequiresReasonVersionAndCurrentStageRole() {
        var b=body();b.setReason(" ");assertThrows(ServiceException.class,()->service.reject(b,20L,"教务"));approve(20,"education");b.setReason("核实");assertThrows(ServiceException.class,()->service.reject(b,20L,"教务"));b.setSubmissionVersion((String)detail().get("submissionVersion"));assertThrows(ServiceException.class,()->service.reject(b,20L,"教务"));
    }
    @Test void pendingFormalTotalsStayZeroAndCapabilitiesTrackRoleAndStage() {
        assertEquals(3L,((Map<?,?>)detail().get("stats")).get("actualCount"));assertEquals(0L,((Map<?,?>)service.attendanceOverview(1L,null,null).get("stats")).get("attended"));
        assertEquals(true,review().get("canInitialReview"));assertEquals(false,review().get("canFinalReview"));approve(20,"education");assertEquals(false,review().get("canFinalReview"));
        ScheduleCrudTest.principal(40L,"business_admin");assertEquals(true,review().get("canFinalReview"));var row=service.selectReviewList(null).get(0);assertEquals("PENDING_FINAL",row.getReviewStage());assertTrue(row.getCanFinalReview());
        approve(40,"business_admin");assertEquals(3L,((Map<?,?>)service.attendanceOverview(1L,null,null).get("stats")).get("attended"));
    }
    @Test void legacyApprovalsRemainValidWithoutFabricatedReviewers() {
        jdbc.update("UPDATE edu_class_sign_in SET review_status='1',review1_admin_id=11,review1_admin_name='历史确认人',review_stage=NULL");assertEquals("FINAL",review().get("reviewStage"));assertNull(review().get("review2AdminId"));assertEquals(1,salary.selectMonthlySessions("2026-10").size());
        assertThrows(ServiceException.class,()->approve(20,"education"));assertThrows(ServiceException.class,()->change("reject","不可覆盖历史",null,null));assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review1_admin_id=11 AND review2_admin_id IS NULL",Integer.class));
    }
    @Test void teacherCanResubmitReturnedSheetWithStableIdsAndFreshVersion() {
        var ids=jdbc.queryForList("SELECT sign_in_id FROM edu_class_sign_in ORDER BY sign_in_id",Long.class);change("reject","点名有误",null,null);
        var b=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();b.setScheduleId(1L);b.setClassDate("2026-10-19");b.setReviewVersion(1L);var details=new ArrayList<com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail>();
        for(int i=1;i<=5;i++){var d=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();d.setStudentName("学员"+i);d.setSourceType("1");d.setSignStatus("1");details.add(d);}b.setDetails(details);
        ScheduleCrudTest.principal(10L,"teacher");service.submitSignIn(b,"teacher-test");assertEquals("PENDING_FIRST",review().get("reviewStage"));assertEquals(ids,jdbc.queryForList("SELECT sign_in_id FROM edu_class_sign_in ORDER BY sign_in_id",Long.class));assertThrows(ServiceException.class,()->service.submitSignIn(b,"teacher-test"));assertTrue(jdbc.queryForObject("SELECT CAST(review_history AS CHAR) FROM edu_class_sign_in LIMIT 1",String.class).contains("点名有误"));
    }
    @Test void consumptionAndSettledSalaryGuardsRemainUnchanged() {
        jdbc.update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,class_date,lesson_completed_time) VALUES(1,1,'2026-10-19',NOW())");assertTrue(assertThrows(ServiceException.class,()->change("reject","核实",null,null)).getMessage().contains("消课"));jdbc.update("DELETE FROM edu_enrollment");change("reject","核实",null,null);jdbc.update("INSERT INTO edu_teacher_salary_record VALUES(1,10,'2026-10','1','0')");assertTrue(assertThrows(ServiceException.class,()->change("correct","核实",jdbc.queryForObject("SELECT MIN(sign_in_id) FROM edu_class_sign_in",Long.class),"6")).getMessage().contains("结算"));
    }
    @Test void migrationIsRepeatableAndPreservesLegacyFacts() throws Exception {
        jdbc.execute("ALTER TABLE edu_class_sign_in DROP COLUMN review_stage");String sql=Files.readString(Path.of("../../database/migrations/20261106_attendance_review_stage.sql")).replace("DELIMITER $$","").replace("DELIMITER ;","");for(int i=0;i<2;i++)for(String command:sql.split("\\$\\$"))if(!command.isBlank())jdbc.execute(command);assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_stage IS NULL AND review_status='0' AND create_by='teacher-test'",Integer.class));
    }
    MiniAppSignReviewBody batch(List<Map<String,Object>> changes) {
        return JSON.parseObject(JSON.toJSONString(Map.of("scheduleId",1,"classDate","2026-10-19","submissionVersion",detail().get("submissionVersion"),"corrections",changes)),MiniAppSignReviewBody.class);
    }
    Map<String,Object> correction(String name,String status,String reason) {
        return Map.of("signInId",jdbc.queryForObject("SELECT sign_in_id FROM edu_class_sign_in WHERE student_name=?",Long.class,name),"signStatus",status,"reason",reason);
    }
    @Test void invalidStudentOrDuplicateIdentityRejectsTheWholeBatchBeforeAnyWrite() {
        change("reject","多人核实",null,null);
        var first=correction("学员1","6","核实");
        for(var changes:List.of(List.of(first,Map.<String,Object>of("signInId",Long.MAX_VALUE,"signStatus","3","reason","不属于本课次")),List.of(first,first),List.of(first,correction("学员2","6"," ")))){
            assertThrows(ServiceException.class,()->service.correct(batch(changes),20L,"教务甲"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE sign_status='6' OR JSON_LENGTH(review_history)<>1",Integer.class));
        }
    }
    @Test void sqlFailureOnTheSecondStudentRollsBackTheFirstStudentAndBothHistories() {
        change("reject","多人核实",null,null);
        var b=batch(List.of(correction("学员1","3","已请假"),correction("学员2","6","未到课")));
        var proxy=new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional=(IEduClassSignInService)proxy.getProxy();
        jdbc.execute("CREATE TRIGGER fail_second_correction BEFORE UPDATE ON edu_class_sign_in FOR EACH ROW BEGIN IF NEW.student_name='学员2' AND NEW.sign_status='6' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Intentional isolated test failure'; END IF; END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->transactional.correct(b,20L,"教务甲"));
            assertEquals("1",jdbc.queryForObject("SELECT sign_status FROM edu_class_sign_in WHERE student_name='学员1'",String.class));
            assertEquals("2",jdbc.queryForObject("SELECT sign_status FROM edu_class_sign_in WHERE student_name='学员2'",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE JSON_LENGTH(review_history)<>1",Integer.class));
        } finally {jdbc.execute("DROP TRIGGER fail_second_correction");}
    }
    @Test void exceptionRequiresReasonAndOnlyAllowsChangesToTheSameSheet() {
        assertThrows(ServiceException.class,()->change("reject"," ",null,null));
        change("reject","需核实",null,null);
        assertThrows(ServiceException.class,()->change("correct"," ",1L,"6"));
        assertThrows(ServiceException.class,()->change("correct","其他课次",Long.MAX_VALUE,"6"));
        assertThrows(ServiceException.class,()->change("correct","错误状态",jdbc.queryForObject("SELECT MIN(sign_in_id) FROM edu_class_sign_in",Long.class),"9"));
    }
    @Test void transferredStudentRetainsIdentityAndCombinedAttendanceHistory() {
        jdbc.update("DELETE FROM edu_student");jdbc.update("INSERT INTO edu_student VALUES(1,30,'换课学生')");
        jdbc.update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name) VALUES(100,2,1,30,'换课学生')");
        jdbc.update("UPDATE edu_class_sign_in SET enrollment_id=100 WHERE student_name='学员1'");
        var overview=service.attendanceOverview(1L,null,null);
        var students=(List<Map<String,Object>>)overview.get("rows");
        assertTrue(students.stream().anyMatch(row->"S:1".equals(row.get("studentKey")) && Boolean.TRUE.equals(row.get("linked"))));
        assertEquals(1,service.attendanceStudent(1L,"S:1",true,null,null,1).get("total"));
    }
    @Test void transferredStudentCanCorrectOnlyTheirExistingHistoricalLesson() throws Exception {
        jdbc.update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,parent_id,student_name) VALUES(100,2,30,'学员1')");
        jdbc.update("UPDATE edu_class_sign_in SET enrollment_id=100 WHERE student_name='学员1'");
        long sign=jdbc.queryForObject("SELECT sign_in_id FROM edu_class_sign_in WHERE enrollment_id=100",Long.class);
        var controller=new com.ruoyi.system.controller.MiniAppTeacherSignController();
        Map<String,Object> fields=Map.of("homeworkMapper",Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduHomeworkMapper.class},(p,m,a)->10L),"signInService",service,"singleDb",new com.ruoyi.system.shop.ShopRepository(jdbc.getDataSource()),
            "scheduleMapper",Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduCourseScheduleMapper.class},(p,m,a)->{var s=new EduCourseSchedule();s.setScheduleId(1L);s.setTeacherId(10L);s.setDelFlag("0");s.setClassMode("1");return s;}),
            "enrollmentMapper",Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduEnrollmentMapper.class},(p,m,a)->{var e=new com.ruoyi.system.domain.EduEnrollment();e.setEnrollmentId(100L);e.setScheduleId(2L);return e;}));
        for(var entry:fields.entrySet()){var f=controller.getClass().getDeclaredField(entry.getKey());f.setAccessible(true);f.set(controller,entry.getValue());}
        var body=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();body.setScheduleId(1L);body.setClassDate("2026-10-19");body.setReviewVersion(0L);
        var d=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();d.setEnrollmentId(100L);d.setStudentName("学员1");d.setSourceType("1");d.setSignStatus("6");body.setDetails(List.of(d));
        ScheduleCrudTest.principal(10L,"teacher");controller.submit(body);
        assertEquals(sign,jdbc.queryForObject("SELECT sign_in_id FROM edu_class_sign_in WHERE enrollment_id=100",Long.class));
        assertEquals("6",jdbc.queryForObject("SELECT sign_status FROM edu_class_sign_in WHERE enrollment_id=100",String.class));
        body.setClassDate("2026-10-20");body.setReviewVersion(0L);
        assertThrows(ServiceException.class,()->controller.submit(body));
    }
    @Test void countsAndVersionComeFromTheSubmittedRows(){
        var d=detail();var stats=(Map<?,?>)d.get("stats");assertNotNull(stats);assertEquals(5,((Number)stats.get("totalCount")).intValue());assertEquals(3L,stats.get("actualCount"));assertEquals(1L,stats.get("leave"));assertEquals(1L,stats.get("recorded"));assertFalse(String.valueOf(d.get("submissionVersion")).isBlank());
    }
    @Test void staleOrMissingSubmissionCannotBeConfirmed(){
        var stale=body();jdbc.update("UPDATE edu_class_sign_in SET sign_status='3' WHERE student_name='学员1'");
        assertThrows(ServiceException.class,()->service.review(stale,20L,"教务甲"));assertThrows(ServiceException.class,()->service.review(missingBody(),20L,"教务甲"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review1_admin_id IS NOT NULL",Integer.class));
    }
    @Test void differentDatesRemainSeparateAndTeacherSummaryCountsOnlyTheLatestOccurrence(){
        jdbc.update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,sign_status,review_status,del_flag) VALUES(1,'2026-10-20','另一课次甲','1','1','0','0'),(1,'2026-10-20','另一课次乙','1','3','0','0')");
        var row=service.selectTeacherSchedules(10L).get(0);assertEquals(2L,row.getTotalCount());assertEquals(1L,row.getActualCount());
        service.review(body(),20L,"教务甲");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE class_date='2026-10-20' AND review_status='0'",Integer.class));
    }
    @Test void teacherCannotOverwriteAnAlreadyConfirmedSheet(){
        service.review(body(),20L,"教务甲");
        approve(40,"business_admin");
        ScheduleCrudTest.principal(10L,"teacher");
        try {
            var b=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();b.setScheduleId(1L);b.setClassDate("2026-10-19");
            var d=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();d.setStudentName("覆盖尝试");d.setSourceType("1");d.setSignStatus("1");b.setDetails(List.of(d));
            assertThrows(ServiceException.class,()->service.submitSignIn(b,"teacher-test"));
            assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='1'",Integer.class));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void batchUpdatesMultipleStudentsWithSeparateReasonsAndAuditHistories() {
        change("reject","多人出勤有误",null,null);
        var b=batch(List.of(correction("学员1","3","已请假"),correction("学员2","6","核实未到课")));
        service.correct(b,20L,"教务甲");
        assertEquals("3",jdbc.queryForObject("SELECT sign_status FROM edu_class_sign_in WHERE student_name='学员1'",String.class));
        assertEquals("6",jdbc.queryForObject("SELECT sign_status FROM edu_class_sign_in WHERE student_name='学员2'",String.class));
        for(String name:List.of("学员1","学员2")){
            var h=JSON.parseArray(jdbc.queryForObject("SELECT review_history FROM edu_class_sign_in WHERE student_name=?",String.class,name));
            assertEquals(2,h.size()); assertEquals(name.equals("学员1")?"已请假":"核实未到课",h.getJSONObject(1).getString("reason"));
        }
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='0' AND review_stage='PENDING_FIRST' AND create_by='teacher-test'",Integer.class));
        assertThrows(ServiceException.class,()->service.correct(b,20L,"教务甲"));
    }
    @Test void educationOverviewKeepsMainStatisticsAndIncludesExplicitAbsence() {
        jdbc.update("DELETE FROM edu_student");jdbc.update("INSERT INTO edu_student VALUES(1,30,'当前孩子姓名')");
        jdbc.update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name) VALUES(100,1,1,30,'报名快照姓名')");
        jdbc.update("UPDATE edu_class_sign_in SET enrollment_id=100,sign_status='6' WHERE student_name='学员1'");
        approve(20,"education");approve(40,"business_admin");
        var before=jdbc.queryForList("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id");
        var overview=service.attendanceOverview(1L,null,null);var stats=(Map<?,?>)overview.get("stats");assertEquals(1L,stats.get("absent"));assertEquals(2L,stats.get("attended"));
        assertEquals(List.of("2026-10-19"),overview.get("dates"));
        var student=service.attendanceStudent(1L,"S:1",true,null,null,1);assertEquals(1,student.get("total"));
        assertEquals("当前孩子姓名",((Map<?,?>)student.get("student")).get("studentName"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id"));
        ScheduleCrudTest.principal(30L,"parent");assertThrows(ServiceException.class,()->service.attendanceOverview(1L,null,null));
        ScheduleCrudTest.principal(10L,"teacher");assertThrows(ServiceException.class,()->service.attendanceStudent(1L,"S:1",true,null,null,1));
    }
    @Test void migrationIsRepeatableAndDoesNotChangeLegacyAttendance() throws Exception {
        jdbc.execute("ALTER TABLE edu_class_sign_in DROP COLUMN review_reason,DROP COLUMN review_history");
        String migration=Files.readString(Path.of("../../database/migrations/20261019_sign_review_exception.sql"))
            .replace("DELIMITER $$","").replace("DELIMITER ;","");
        for(int attempt=0;attempt<2;attempt++) for(String command:migration.split("\\$\\$")) if(!command.isBlank()) jdbc.execute(command);
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='0' AND review_history IS NULL AND create_by='teacher-test'",Integer.class));
    }
    MiniAppSignReviewBody missingBody() {var b=body();b.setSubmissionVersion(null);b.setReviewVersion(null);return b;}
    @Test void numericWebVersionAndHashShareTheSameTwoStageWorkflow() {
        var b=body();b.setSubmissionVersion(null);b.setReviewVersion(0L);b.setAction("RETURN");b.setReason("多人核实");
        assertEquals("RETURNED",service.review(b,20L,"教务").get("reviewStage"));
        service.correct(batch(List.of(correction("学员1","6","未到课"),correction("学员2","3","请假"))),20L,"教务");
        b.setReviewVersion(2L);b.setAction("APPROVE");assertEquals("PENDING_FINAL",service.review(b,20L,"教务").get("reviewStage"));
        ScheduleCrudTest.principal(40L,"business_admin");b.setReviewVersion(3L);assertEquals("FINAL",service.review(b,40L,"业务").get("reviewStage"));
    }
    @Test void legacyPartialConfirmationMustStartARealNewInitialReview() {
        jdbc.update("UPDATE edu_class_sign_in SET review1_admin_id=11,review1_admin_name='旧确认人',review1_time='2026-10-18 12:00:00'");
        assertEquals("PENDING_FINAL",approve(20,"education").get("reviewStage"));assertTrue(salary.selectMonthlySessions("2026-10").isEmpty());
        assertTrue(jdbc.queryForObject("SELECT CAST(review_history AS CHAR) FROM edu_class_sign_in LIMIT 1",String.class).contains("旧确认人"));approve(40,"business_admin");
    }
    @Test void concurrentInitialApprovalOnlyCreatesOneAuditedTransition() throws Exception {
        var b=body();var proxy=new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional=(IEduClassSignInService)proxy.getProxy();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(long actor:List.of(20L,21L)) tasks.add(executor.submit(()->{ScheduleCrudTest.principal(actor,"education");start.await();try{transactional.review(b,actor,"教务");return true;}catch(ServiceException expected){return false;}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}}));
            start.countDown();int successes=0;for(var task:tasks)if(task.get(10,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(1,jdbc.queryForObject("SELECT MAX(review_version) FROM edu_class_sign_in",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT MAX(JSON_LENGTH(review_history)) FROM edu_class_sign_in",Integer.class));
        } finally {executor.shutdownNow();}
    }
    @Test void approvedSalaryUsesTheSubmittedTeacherAndTimeSnapshotAfterReassignment() {
        approve(20,"education");approve(40,"business_admin");
        jdbc.update("INSERT INTO edu_attendance_lesson(lesson_id,schedule_id,class_date,teacher_id,start_time,end_time,locked) VALUES(1,1,'2026-10-19',10,'10:00:00','12:00:00',1)");
        jdbc.update("UPDATE edu_course_schedule SET teacher_id=99,start_time='13:00:00',end_time='16:00:00' WHERE schedule_id=1");
        try { var session=salary.selectMonthlySessions("2026-10").get(0);assertEquals(10L,((Number)session.get("teacherId")).longValue());assertEquals("10:00:00",session.get("startTime").toString());
            assertEquals("教师真名",detail().get("teacherName"));assertEquals("10:00-12:00",detail().get("timeSlot"));assertEquals("教师真名",service.selectReviewList(null).get(0).getTeacherName());
            var overview=(List<Map<String,Object>>)service.attendanceOverview(1L,null,null).get("rows");assertFalse(overview.isEmpty()); }
        finally {jdbc.update("UPDATE edu_course_schedule SET teacher_id=10,start_time=NULL,end_time=NULL WHERE schedule_id=1");}
    }
    @Test void plannedOverviewSeparatesFormalExpectedFromUnsubmittedAndPendingCategories() throws Exception {
        inject("rosterService",new AttendanceLessonRosterService(null){@Override public List<Map<String,Object>> courseOverviewLessons(long id,String from,String to) {
            return List.of(Map.of("lessonNo",1,"classDate","2026-10-19","expectedCount",5,"reviewStage",review().get("reviewStage")),Map.of("lessonNo",2,"classDate","2026-10-20","expectedCount",3,"reviewStage","UNSUBMITTED"));
        }});
        try {
            var overview=service.attendanceOverview(1L,null,null);assertEquals(List.of("2026-10-19","2026-10-20"),overview.get("dates"));assertEquals(8L,overview.get("plannedExpectedCount"));
            var pending=(Map<?,?>)overview.get("pendingStats");assertEquals(8L,pending.get("expectedCount"));assertEquals(3L,pending.get("attended"));assertEquals(1L,pending.get("video"));assertEquals(1L,pending.get("leave"));assertEquals(3L,pending.get("unmarked"));
            assertEquals(0L,((Map<?,?>)overview.get("stats")).get("expectedCount"));approve(20,"education");approve(40,"business_admin");
            overview=service.attendanceOverview(1L,null,null);assertEquals(5L,((Map<?,?>)overview.get("stats")).get("expectedCount"));assertEquals(3L,((Map<?,?>)overview.get("pendingStats")).get("expectedCount"));
        } finally {inject("rosterService",null);}
    }
    @Test void returnedSheetCanBeResubmittedOnlyBySnapshotTeacherAfterReassignment() {
        jdbc.update("INSERT INTO edu_attendance_lesson(lesson_id,schedule_id,class_date,teacher_id,start_time,end_time,locked) VALUES(1,1,'2026-10-19',10,'10:00:00','12:00:00',1)");
        change("reject","教师核实",null,null);jdbc.update("UPDATE edu_course_schedule SET teacher_id=99 WHERE schedule_id=1");
        var b=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();b.setScheduleId(1L);b.setClassDate("2026-10-19");b.setReviewVersion(1L);var d=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();d.setStudentName("学员1");d.setSourceType("1");d.setSignStatus("1");b.setDetails(List.of(d));
        try {
            ScheduleCrudTest.principal(99L,"teacher");assertThrows(ServiceException.class,()->service.submitSignIn(b,"new-teacher"));ScheduleCrudTest.principal(10L,"teacher");service.submitSignIn(b,"teacher-test");assertEquals("PENDING_FIRST",review().get("reviewStage"));assertEquals("教师真名",detail().get("teacherName"));
        } finally {jdbc.update("UPDATE edu_course_schedule SET teacher_id=10 WHERE schedule_id=1");}
    }
    @BeforeEach void reviewer(){ScheduleCrudTest.principal(20L,"education");}
    @AfterEach void clearReviewer(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @AfterAll void cleanup()throws Exception{if(jdbc!=null&&schema.matches("codex_sign_review_test_[0-9a-f]{12}"))try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("DROP DATABASE "+schema);}}
}
