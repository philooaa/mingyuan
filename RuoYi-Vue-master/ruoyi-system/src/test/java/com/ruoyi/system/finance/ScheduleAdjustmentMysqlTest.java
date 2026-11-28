package com.ruoyi.system.finance;

import java.nio.file.*;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.ScheduleAvailabilityService;
import com.ruoyi.system.service.impl.EduScheduleAdjustmentServiceImpl;
import static org.junit.jupiter.api.Assertions.*;

/** 使用随机临时库和真实事务验证停课、调课与恢复，不连接业务库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleAdjustmentMysqlTest {
    final TuitionMysqlTest fixture = new TuitionMysqlTest();
    EduScheduleAdjustmentServiceImpl service;
    EduCourseScheduleMapper schedules;
    ScheduleAvailabilityService availability;

    @BeforeAll void setup() throws Exception {
        fixture.setup();
        fixture.db.jdbc().execute("ALTER TABLE edu_course_schedule ADD lesson_location varchar(255), ADD unit_price decimal(10,2) DEFAULT 0");
        fixture.runMigration("20261019_schedule_enroll_limit.sql");
        fixture.runMigration("20261028_schedule_campus_first.sql");
        String schema = Files.readString(Path.of("../../database/schema.sql"));
        var table = Pattern.compile("CREATE TABLE `edu_schedule_adjustment` \\(.*?\\) ENGINE=.*?;", Pattern.DOTALL).matcher(schema);
        assertTrue(table.find());fixture.db.jdbc().execute(table.group());
        for (String file : List.of("20261106_attendance_lesson_roster.sql", "20261123_teacher_led_makeup.sql")) {
            String sql = Files.readString(Path.of("../../database/migrations/" + file));
            // 只建本服务读取的课次表、补课排课表，不执行其他升级。
            String name = file.contains("roster") ? "edu_attendance_lesson" : "edu_attendance_makeup_session";
            var create = Pattern.compile("CREATE TABLE IF NOT EXISTS " + name + " \\(.*?;", Pattern.DOTALL).matcher(sql);
            assertTrue(create.find());fixture.db.jdbc().execute(create.group());
        }
        var factory = new SqlSessionFactoryBean();factory.setDataSource(fixture.db.jdbc().getDataSource());
        factory.setTypeAliasesPackage("com.ruoyi.system.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/system/EduCourseScheduleMapper.xml"),
                new ClassPathResource("mapper/system/EduScheduleAdjustmentMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());schedules = session.getMapper(EduCourseScheduleMapper.class);
        availability = new ScheduleAvailabilityService();inject(availability, "db", fixture.db);
        service = new EduScheduleAdjustmentServiceImpl();
        inject(service, "singleDb", fixture.db);inject(service, "singleSchedules", schedules);
        inject(service, "eduScheduleAdjustmentMapper", session.getMapper(EduScheduleAdjustmentMapper.class));
        inject(service, "availability", availability);
    }
    static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }
    @BeforeEach void reset() {
        fixture.db.jdbc().update("DELETE FROM edu_schedule_adjustment");
        fixture.db.jdbc().update("DELETE FROM edu_class_sign_in");
        fixture.db.jdbc().update("DELETE FROM edu_course_schedule WHERE schedule_id>=10");
        fixture.db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,schedule_code,classroom_id,teacher_id,term_name,period_name,time_slot,grade_name,subject_name,course_class_name,class_mode,class_pattern,start_date,end_date,start_time,end_time,status,del_flag) "
                + "VALUES(10,'ADJUST_A',1,1,'秋季','周六','08:00-10:00','初一','数学','待停课课程','1','WEEKLY','2026-09-05','2026-09-26','08:00:00','10:00:00','0','0'),"
                + "(20,'ADJUST_B',1,1,'其他季度','一期','08:00-10:00','初一','数学','历史冲突课程','1','DAILY','2026-09-05','2026-09-05','08:00:00','10:00:00','0','0')");
        var user = new SysUser();user.setUserId(1L);user.setUserName("admin");
        var login = new LoginUser();login.setUserId(1L);login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }
    @AfterEach void clearUser() { SecurityContextHolder.clearContext(); }
    @AfterAll void cleanup() throws Exception { fixture.cleanup(); }
    <T> T tx(Supplier<T> action) { return fixture.db.transactions().execute(status -> action.get()); }
    EduScheduleAdjustment change(String original, String adjusted) {
        var a = new EduScheduleAdjustment();a.setScheduleId(10L);a.setTermName("秋季");a.setPeriodName("周六");
        a.setOriginalDate(Date.valueOf(original));a.setAdjustedDate(adjusted == null ? null : Date.valueOf(adjusted));return a;
    }
    EduScheduleAdjustmentBatch batch(String mode) {
        var b = new EduScheduleAdjustmentBatch();b.setTermName("秋季");b.setPeriodName("周六");
        b.setOriginalStartDate(Date.valueOf("2026-09-19"));b.setOriginalEndDate(Date.valueOf("2026-09-19"));
        b.setAdjustMode(mode);b.setReason("隔离测试");return b;
    }
    void conflictOn(String date) {
        fixture.db.jdbc().update("UPDATE edu_course_schedule SET start_date=?,end_date=? WHERE schedule_id=20", date, date);
    }
    long seed(Long schedule, String original, String adjusted) {
        var row = new LinkedHashMap<String, Object>();
        row.put("schedule_id", schedule);row.put("term_name", "秋季");row.put("period_name", "周六");
        row.put("original_date", original);row.put("adjusted_date", adjusted);
        return fixture.db.insert("edu_schedule_adjustment", row);
    }
    long count() { return fixture.db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_schedule_adjustment", Long.class); }
    Set<LocalDate> dates() { return availability.dates(schedules.selectEduCourseScheduleByScheduleId(10L), availability.adjustments(10L, false)); }
    void assertConflict(Runnable action) {
        assertTrue(assertThrows(ServiceException.class, action::run).getMessage().contains("教师或教室已被其他课程占用"));
    }
    @Test void batchCancellationIgnoresOtherDatesAndRemovesOnlyTheSelectedDate() {
        assertEquals(1, tx(() -> service.batchAdjust(batch("CANCEL"))));
        assertEquals(0L, fixture.db.jdbc().queryForObject("SELECT schedule_id FROM edu_schedule_adjustment", Long.class));
        assertEquals(Set.of(LocalDate.parse("2026-09-05"), LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-26")), dates());
    }
    @Test void filteredBatchCancellationAlsoIgnoresOtherDates() {
        var b = batch("CANCEL");b.setGradeName("初一");assertEquals(1, tx(() -> service.batchAdjust(b)));
        assertEquals(10L, fixture.db.jdbc().queryForObject("SELECT schedule_id FROM edu_schedule_adjustment", Long.class));
        assertFalse(dates().contains(LocalDate.parse("2026-09-19")));
    }
    @Test void singleCancellationIgnoresOtherDates() {
        assertEquals(1, tx(() -> service.insertEduScheduleAdjustment(change("2026-09-19", null))));
        assertFalse(dates().contains(LocalDate.parse("2026-09-19")));
    }
    @Test void editingAMoveToCancellationClearsTheStoredTargetDate() {
        long id = seed(10L, "2026-09-19", "2026-09-20");
        var a = change("2026-09-19", null);a.setAdjustmentId(id);
        assertEquals(1, tx(() -> service.updateEduScheduleAdjustment(a)));
        assertNull(fixture.db.jdbc().queryForObject("SELECT adjusted_date FROM edu_schedule_adjustment WHERE adjustment_id=?", Date.class, id));
        assertFalse(dates().contains(LocalDate.parse("2026-09-19")));assertFalse(dates().contains(LocalDate.parse("2026-09-20")));
    }
    @Test void batchMoveStillRejectsConflictAndRollsBack() {
        conflictOn("2026-09-20");var b = batch("MOVE_TO");b.setTargetDate(Date.valueOf("2026-09-20"));
        assertConflict(() -> tx(() -> service.batchAdjust(b)));assertEquals(0, count());
    }
    @Test void batchPostponeStillRejectsConflictAndRollsBack() {
        conflictOn("2026-09-20");var b = batch("POSTPONE");b.setOffsetDays(1);
        assertConflict(() -> tx(() -> service.batchAdjust(b)));assertEquals(0, count());
    }
    @Test void singleMoveStillRejectsConflictAndRollsBack() {
        conflictOn("2026-09-20");assertConflict(() -> tx(() -> service.insertEduScheduleAdjustment(change("2026-09-19", "2026-09-20"))));
        assertEquals(0, count());
    }
    @Test void editingCancellationToMoveStillRejectsConflict() {
        long id = seed(10L, "2026-09-19", null);conflictOn("2026-09-20");
        var a = change("2026-09-19", "2026-09-20");a.setAdjustmentId(id);
        assertConflict(() -> tx(() -> service.updateEduScheduleAdjustment(a)));
        assertNull(fixture.db.jdbc().queryForObject("SELECT adjusted_date FROM edu_schedule_adjustment WHERE adjustment_id=?", Date.class, id));
    }
    @Test void movingCancellationToAnotherOriginalDateMustCheckTheRestoredDate() {
        long id = seed(10L, "2026-09-19", null);conflictOn("2026-09-19");
        var a = change("2026-09-12", null);a.setAdjustmentId(id);
        assertConflict(() -> tx(() -> service.updateEduScheduleAdjustment(a)));
        assertEquals(Date.valueOf("2026-09-19"), fixture.db.jdbc().queryForObject("SELECT original_date FROM edu_schedule_adjustment WHERE adjustment_id=?", Date.class, id));
    }
    @Test void restoringSingleCancellationStillChecksConflictAndRollsBack() {
        long id = seed(10L, "2026-09-19", null);conflictOn("2026-09-19");
        assertConflict(() -> tx(() -> service.deleteEduScheduleAdjustmentByAdjustmentId(id)));assertEquals(1, count());
    }
    @Test void restoringWholeDayCancellationStillChecksConflictAndRollsBack() {
        long id = seed(0L, "2026-09-19", null);conflictOn("2026-09-19");
        assertConflict(() -> tx(() -> service.deleteEduScheduleAdjustmentByAdjustmentId(id)));assertEquals(1, count());
    }
    @Test void cancellationStillRejectsSubmittedAttendanceForBatchAndSingle() {
        fixture.db.jdbc().update("INSERT INTO edu_class_sign_in(schedule_id,class_date,enrollment_id,student_name,sign_status,del_flag) VALUES(10,'2026-09-19',1,'测试学生','1','0')");
        assertTrue(assertThrows(ServiceException.class, () -> tx(() -> service.batchAdjust(batch("CANCEL")))).getMessage().contains("已提交点名"));
        assertTrue(assertThrows(ServiceException.class, () -> tx(() -> service.insertEduScheduleAdjustment(change("2026-09-19", null)))).getMessage().contains("已提交点名"));
        assertEquals(0, count());
    }
    @Test void cancellationStillRejectsInvalidOrAlreadyAdjustedDates() {
        assertTrue(assertThrows(ServiceException.class, () -> tx(() -> service.insertEduScheduleAdjustment(change("2026-09-20", null)))).getMessage().contains("有效课次"));
        seed(10L, "2026-09-19", null);
        assertTrue(assertThrows(ServiceException.class, () -> tx(() -> service.insertEduScheduleAdjustment(change("2026-09-19", null)))).getMessage().contains("已有调课记录"));
        assertEquals(1, count());
    }
}
