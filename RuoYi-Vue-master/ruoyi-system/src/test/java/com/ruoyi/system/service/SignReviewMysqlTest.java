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
        jdbc.update("INSERT INTO edu_classroom(classroom_id,classroom_code,campus_name,classroom_name) VALUES(1,'TEST','测试校区','测试教室')");
        jdbc.update("INSERT INTO edu_course_schedule(schedule_id,schedule_code,classroom_id,term_name,period_name,course_class_name,teacher_name,teacher_id,grade_name,subject_name,time_slot,del_flag) VALUES(1,'TEST',1,'秋季','周一','测试班','测试教师',10,'一年级','数学','10:00-12:00','0')");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setTypeAliasesPackage("com.ruoyi.system.domain");factory.setMapperLocations(new ClassPathResource("mapper/system/EduClassSignInMapper.xml"),new ClassPathResource("mapper/system/EduTeacherSalaryRecordMapper.xml"));
        salary=new SqlSessionTemplate(factory.getObject()).getMapper(EduTeacherSalaryRecordMapper.class);
        service=new EduClassSignInServiceImpl();inject("signInMapper",new SqlSessionTemplate(factory.getObject()).getMapper(EduClassSignInMapper.class));
        inject("scheduleMapper",Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EduCourseScheduleMapper.class},(p,m,a)->{
            if(!m.getName().equals("lockSchedule"))throw new AssertionError(m.getName());var s=new EduCourseSchedule();s.setScheduleId(1L);s.setTeacherId(10L);s.setDelFlag("0");return s;
        }));
        inject("classLeaves",new ClassLeaveService(){@Override public Set<Long> approved(Long id,String date){return Set.of();}});
        inject("singleDb",new com.ruoyi.system.shop.ShopRepository(ds));
        jdbc.execute("CREATE TABLE edu_enrollment(enrollment_id BIGINT PRIMARY KEY,schedule_id BIGINT,class_date DATE,lesson_completed_time DATETIME,student_id BIGINT,parent_id BIGINT,student_name VARCHAR(30),del_flag CHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE edu_student(student_id BIGINT PRIMARY KEY,parent_id BIGINT,student_name VARCHAR(30))");
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(30),nick_name VARCHAR(30),phonenumber VARCHAR(11))");
        jdbc.update("INSERT INTO sys_user VALUES(10,'教师真名','教师昵称',NULL),(30,'家长','家长昵称','11111111111')");
        jdbc.execute("CREATE TABLE edu_teacher_salary_record(record_id BIGINT PRIMARY KEY,teacher_id BIGINT,salary_month VARCHAR(7),confirm_status CHAR(1),del_flag CHAR(1))");
    }
    void inject(String name,Object value)throws Exception{var f=EduClassSignInServiceImpl.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);}
    @BeforeEach void rows(){jdbc.update("DELETE FROM edu_enrollment");jdbc.update("DELETE FROM edu_teacher_salary_record");jdbc.update("DELETE FROM edu_class_sign_in");for(int i=1;i<=5;i++)jdbc.update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,sign_status,review_status,create_by,create_time,del_flag) VALUES(1,'2026-10-19',?,'1',?,'0','teacher-test',NOW(),'0')","学员"+i,""+i);}
    Map<String,Object> detail(){return service.selectDetail(1L,"2026-10-19");}
    Map<String,Object> change(String action,String reason,Long signId,String status) {
        var values=new LinkedHashMap<String,Object>(); values.put("scheduleId",1); values.put("classDate","2026-10-19");
        values.put("submissionVersion",detail().get("submissionVersion")); values.put("reason",reason); values.put("signInId",signId); values.put("signStatus",status);
        var b=JSON.parseObject(JSON.toJSONString(values),MiniAppSignReviewBody.class);
        try { return (Map<String,Object>)service.getClass().getMethod(action,MiniAppSignReviewBody.class,Long.class,String.class).invoke(service,b,20L,"教务甲"); }
        catch(java.lang.reflect.InvocationTargetException e) { throw (RuntimeException)e.getCause(); }
        catch(ReflectiveOperationException e) { throw new AssertionError("缺少异常处理接口 "+action,e); }
    }
    @Test void rejectCorrectAndConfirmPreservesTeacherRecordAndAuditTrail() {
        String originalVersion=(String)detail().get("submissionVersion");
        change("reject","漏记未到课",null,null);
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='2'",Integer.class));
        assertNotEquals(originalVersion,detail().get("submissionVersion"));
        assertThrows(ServiceException.class,()->service.review(body(detail().get("submissionVersion")),20L,"教务甲"));
        Long signId=jdbc.queryForObject("SELECT sign_in_id FROM edu_class_sign_in WHERE student_name='学员1'",Long.class);
        change("correct","核实当天未到课",signId,"6");
        assertEquals("teacher-test",jdbc.queryForObject("SELECT create_by FROM edu_class_sign_in WHERE sign_in_id=?",String.class,signId));
        var history=JSON.parseArray(jdbc.queryForObject("SELECT review_history FROM edu_class_sign_in WHERE sign_in_id=?",String.class,signId));
        assertEquals("1",history.getJSONObject(1).getString("beforeStatus")); assertEquals("6",history.getJSONObject(1).getString("afterStatus"));
        assertEquals(1L,((Map<?,?>)detail().get("stats")).get("absent"));
        service.review(body(detail().get("submissionVersion")),20L,"教务甲");
        assertThrows(ServiceException.class,()->change("correct","尝试改回",signId,"1"));
        assertThrows(ServiceException.class,()->change("reject","尝试解锁",null,null));
    }
    MiniAppSignReviewBody batch(List<Map<String,Object>> changes) {
        return JSON.parseObject(JSON.toJSONString(Map.of("scheduleId",1,"classDate","2026-10-19","submissionVersion",detail().get("submissionVersion"),"corrections",changes)),MiniAppSignReviewBody.class);
    }
    Map<String,Object> correction(String name,String status,String reason) {
        return Map.of("signInId",jdbc.queryForObject("SELECT sign_in_id FROM edu_class_sign_in WHERE student_name=?",Long.class,name),"signStatus",status,"reason",reason);
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
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='2' AND create_by='teacher-test'",Integer.class));
        assertThrows(ServiceException.class,()->service.correct(b,20L,"教务甲"));
    }
    @Test void mergedWebVersionAndMiniappHashShareOneEducationCorrectionWorkflow() {
        var web=body(null);web.setSubmissionVersion(null);web.setReviewVersion(0L);web.setAction("RETURN");web.setReason("核实多位学员");
        assertEquals("2",service.review(web,20L,"教务甲").get("reviewStatus"));assertEquals(true,detail().get("locked"));
        assertThrows(ServiceException.class,()->service.review(web,20L,"教务甲"));
        ScheduleCrudTest.principal(10L,"teacher");assertThrows(ServiceException.class,()->service.correct(batch(List.of(correction("学员1","6","不能越权"))),10L,"教师"));
        ScheduleCrudTest.principal(20L,"education");
        service.correct(batch(List.of(correction("学员1","6","第一位未到课"),correction("学员2","3","第二位请假"))),20L,"教务甲");
        var approval=body(null);approval.setSubmissionVersion(null);approval.setReviewVersion(2L);approval.setAction("APPROVE");
        assertEquals("1",service.review(approval,20L,"教务甲").get("reviewStatus"));
        assertEquals(3L,((Number)((Map<?,?>)detail().get("review")).get("reviewVersion")).longValue());
        assertTrue(jdbc.queryForObject("SELECT CAST(review_history AS CHAR) FROM edu_class_sign_in WHERE student_name='学员1'",String.class).contains("第一位未到课"));
        assertTrue(jdbc.queryForObject("SELECT CAST(review_history AS CHAR) FROM edu_class_sign_in WHERE student_name='学员2'",String.class).contains("第二位请假"));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='1' AND review2_admin_id IS NULL",Integer.class));
    }
    @Test void teacherResubmissionKeepsSignIdsAndRejectsOldVersionBeforeEducationReview() {
        var ids=jdbc.queryForList("SELECT sign_in_id FROM edu_class_sign_in WHERE student_name IN ('学员1','学员2') ORDER BY sign_in_id",Long.class);
        var submit=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();submit.setScheduleId(1L);submit.setClassDate("2026-10-19");submit.setReviewVersion(0L);
        var a=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();a.setStudentName("学员1");a.setSourceType("1");a.setSignStatus("1");
        var b=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();b.setStudentName("学员2");b.setSourceType("1");b.setSignStatus("3");submit.setDetails(List.of(a,b));
        ScheduleCrudTest.principal(10L,"teacher");service.submitSignIn(submit,"teacher-test");
        assertEquals(ids,jdbc.queryForList("SELECT sign_in_id FROM edu_class_sign_in WHERE del_flag='0' ORDER BY sign_in_id",Long.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE del_flag='2'",Integer.class));
        assertThrows(ServiceException.class,()->service.submitSignIn(submit,"teacher-test"));
        submit.setReviewVersion(1L);a.setSignStatus("6");service.submitSignIn(submit,"teacher-test");
        assertEquals(ids,jdbc.queryForList("SELECT sign_in_id FROM edu_class_sign_in WHERE del_flag='0' ORDER BY sign_in_id",Long.class));
        ScheduleCrudTest.principal(20L,"education");service.review(body(detail().get("submissionVersion")),20L,"教务甲");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE del_flag='0' AND review_status='1'",Integer.class));
    }
    @Test void educationOverviewKeepsMainStatisticsAndIncludesExplicitAbsence() {
        jdbc.update("DELETE FROM edu_student");jdbc.update("INSERT INTO edu_student VALUES(1,30,'当前孩子姓名')");
        jdbc.update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,student_id,parent_id,student_name) VALUES(100,1,1,30,'报名快照姓名')");
        jdbc.update("UPDATE edu_class_sign_in SET enrollment_id=100,sign_status='6' WHERE student_name='学员1'");
        var before=jdbc.queryForList("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id");
        var overview=service.attendanceOverview(1L,null,null);var stats=(Map<?,?>)overview.get("stats");assertEquals(1L,stats.get("absent"));assertEquals(2L,stats.get("attended"));
        assertEquals(List.of("2026-10-19"),overview.get("dates"));
        var student=service.attendanceStudent(1L,"S:1",true,null,null,1);assertEquals(1,student.get("total"));
        assertEquals("当前孩子姓名",((Map<?,?>)student.get("student")).get("studentName"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id"));
        ScheduleCrudTest.principal(30L,"parent");assertThrows(ServiceException.class,()->service.attendanceOverview(1L,null,null));
        ScheduleCrudTest.principal(10L,"teacher");assertThrows(ServiceException.class,()->service.attendanceStudent(1L,"S:1",true,null,null,1));
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
    @Test void consumedOrSettledSheetsCannotBeRejectedOrCorrected() {
        jdbc.update("INSERT INTO edu_enrollment(enrollment_id,schedule_id,class_date,lesson_completed_time) VALUES(1,1,'2026-10-19',NOW())");
        assertTrue(assertThrows(ServiceException.class,()->change("reject","核实",null,null)).getMessage().contains("消课"));
        jdbc.update("DELETE FROM edu_enrollment");
        change("reject","核实",null,null);
        jdbc.update("INSERT INTO edu_teacher_salary_record VALUES(1,10,'2026-10','1','0')");
        Long signId=jdbc.queryForObject("SELECT MIN(sign_in_id) FROM edu_class_sign_in",Long.class);
        assertTrue(assertThrows(ServiceException.class,()->change("correct","核实",signId,"6")).getMessage().contains("结算"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE sign_status='6'",Integer.class));
    }
    @Test void salaryOnlyUsesConfirmedAttendanceNotPendingOrExceptionRows() {
        assertTrue(salary.selectMonthlySessions("2026-10").isEmpty());
        change("reject","核实",null,null); assertTrue(salary.selectMonthlySessions("2026-10").isEmpty());
        change("correct","未到课",jdbc.queryForObject("SELECT MIN(sign_in_id) FROM edu_class_sign_in",Long.class),"6");
        assertTrue(salary.selectMonthlySessions("2026-10").isEmpty());
        service.review(body(detail().get("submissionVersion")),20L,"教务甲");
        assertEquals(1,salary.selectMonthlySessions("2026-10").size());
        assertEquals(2,((Number)salary.selectMonthlySessions("2026-10").get(0).get("attendedRows")).intValue());
    }
    @Test void exceptionCannotBeOverwrittenByTeacherOrConfirmedWithAStaleVersion() {
        var stale=body(detail().get("submissionVersion"));change("reject","需核实",null,null);
        assertThrows(ServiceException.class,()->service.review(stale,20L,"教务甲"));
        ScheduleCrudTest.principal(10L,"teacher");
        try {
            var b=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();b.setScheduleId(1L);b.setClassDate("2026-10-19");
            var d=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();d.setStudentName("覆盖尝试");d.setSourceType("1");d.setSignStatus("1");b.setDetails(List.of(d));
            assertThrows(ServiceException.class,()->service.submitSignIn(b,"teacher-test"));
            assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='2'",Integer.class));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void migrationIsRepeatableAndDoesNotChangeLegacyAttendance() throws Exception {
        jdbc.execute("ALTER TABLE edu_class_sign_in DROP COLUMN review_reason,DROP COLUMN review_history");
        String migration=Files.readString(Path.of("../../database/migrations/20261019_sign_review_exception.sql"))
            .replace("DELIMITER $$","").replace("DELIMITER ;","");
        for(int attempt=0;attempt<2;attempt++) for(String command:migration.split("\\$\\$")) if(!command.isBlank()) jdbc.execute(command);
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='0' AND review_history IS NULL AND create_by='teacher-test'",Integer.class));
    }
    MiniAppSignReviewBody body(Object version){return JSON.parseObject(JSON.toJSONString(new LinkedHashMap<>(Map.of("scheduleId",1,"classDate","2026-10-19","submissionVersion",version==null?"":version))),MiniAppSignReviewBody.class);}
    @Test void oneConfirmationCompletesEveryRowAndRetryPreservesOriginalAuditor(){
        var b=body(detail().get("submissionVersion"));assertEquals("1",service.review(b,20L,"教务甲").get("reviewStatus"));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='1' AND review1_admin_id=20 AND review2_admin_id IS NULL",Integer.class));
        ScheduleCrudTest.principal(21L,"education");service.review(b,21L,"教务乙");assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review1_admin_id=20 AND review2_admin_id IS NULL",Integer.class));
    }
    @Test void countsAndVersionComeFromTheSubmittedRows(){
        var d=detail();var stats=(Map<?,?>)d.get("stats");assertNotNull(stats);assertEquals(5,((Number)stats.get("totalCount")).intValue());assertEquals(3L,stats.get("actualCount"));assertEquals(1L,stats.get("leave"));assertEquals(1L,stats.get("recorded"));assertFalse(String.valueOf(d.get("submissionVersion")).isBlank());
    }
    @Test void staleOrMissingSubmissionCannotBeConfirmed(){
        var stale=body(detail().get("submissionVersion"));jdbc.update("UPDATE edu_class_sign_in SET sign_status='3' WHERE student_name='学员1'");
        assertThrows(ServiceException.class,()->service.review(stale,20L,"教务甲"));assertThrows(ServiceException.class,()->service.review(body(null),20L,"教务甲"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review1_admin_id IS NOT NULL",Integer.class));
    }
    @Test void oldPartialConfirmationIsArchivedWithOneClickWithoutErasingHistory(){
        jdbc.update("UPDATE edu_class_sign_in SET review1_admin_id=11,review1_admin_name='旧确认人',review1_time='2026-10-18 12:00:00'");
        assertEquals("1",service.review(body(detail().get("submissionVersion")),20L,"教务甲").get("reviewStatus"));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='1' AND review1_admin_id=11 AND review1_time='2026-10-18 12:00:00'",Integer.class));
    }
    @Test void differentDatesRemainSeparateAndTeacherSummaryCountsOnlyTheLatestOccurrence(){
        jdbc.update("INSERT INTO edu_class_sign_in(schedule_id,class_date,student_name,source_type,sign_status,review_status,del_flag) VALUES(1,'2026-10-20','另一课次甲','1','1','0','0'),(1,'2026-10-20','另一课次乙','1','3','0','0')");
        var row=service.selectTeacherSchedules(10L).get(0);assertEquals(2L,row.getTotalCount());assertEquals(1L,row.getActualCount());
        service.review(body(detail().get("submissionVersion")),20L,"教务甲");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE class_date='2026-10-20' AND review_status='0'",Integer.class));
    }
    @Test void teacherCannotOverwriteAnAlreadyConfirmedSheet(){
        service.review(body(detail().get("submissionVersion")),20L,"教务甲");
        ScheduleCrudTest.principal(10L,"teacher");
        try {
            var b=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody();b.setScheduleId(1L);b.setClassDate("2026-10-19");
            var d=new com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody.Detail();d.setStudentName("覆盖尝试");d.setSourceType("1");d.setSignStatus("1");b.setDetails(List.of(d));
            assertThrows(ServiceException.class,()->service.submitSignIn(b,"teacher-test"));
            assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_sign_in WHERE review_status='1'",Integer.class));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @BeforeEach void reviewer(){ScheduleCrudTest.principal(20L,"education");}
    @AfterEach void clearReviewer(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @AfterAll void cleanup()throws Exception{if(jdbc!=null&&schema.matches("codex_sign_review_test_[0-9a-f]{12}"))try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/","root","")){c.createStatement().execute("DROP DATABASE "+schema);}}
}
