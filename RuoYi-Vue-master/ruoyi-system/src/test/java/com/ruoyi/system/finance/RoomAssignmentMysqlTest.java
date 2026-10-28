package com.ruoyi.system.finance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.RoomAssignService;
import static org.junit.jupiter.api.Assertions.*;

/** 在随机临时库核对两个 PR 的校区排课、日期级调课和教室分配衔接。 */
class RoomAssignmentMysqlTest {
    @Test void campusFirstAssignmentPreservesHistoryAndDateLevelRecords() throws Exception {
        var fixture = new TuitionMysqlTest();
        try {
            fixture.setup();
            var db = fixture.db;
            String schema = Files.readString(Path.of("../../database/schema.sql"));
            var matcher = Pattern.compile("CREATE TABLE `edu_schedule_adjustment` \\(.*?\\) ENGINE=.*?;", Pattern.DOTALL).matcher(schema);
            assertTrue(matcher.find());
            db.jdbc().execute(matcher.group());
            // Reproduce the deployed schema before this merge: no date-level fields.
            db.jdbc().execute("ALTER TABLE edu_schedule_adjustment DROP INDEX uk_schedule_date, DROP term_name, DROP period_name, DROP course_count, ADD UNIQUE INDEX uk_schedule_date(schedule_id,original_date)");
            db.jdbc().update("INSERT INTO edu_schedule_adjustment(schedule_id,original_date,adjusted_date) VALUES(1,'2030-11-01','2030-11-02')");
            fixture.runMigration("20261028_schedule_campus_first.sql", "20261028_schedule_adjustment_date_level.sql", "20261028_room_assignment.sql");
            fixture.runMigration("20261028_schedule_adjustment_date_level.sql", "20261028_room_assignment.sql");
            assertEquals("秋季", db.one("SELECT term_name FROM edu_schedule_adjustment WHERE schedule_id=1").get("termName"));
            db.jdbc().update("INSERT INTO edu_schedule_adjustment(schedule_id,term_name,period_name,original_date) VALUES(0,'秋季','周六','2031-01-01'),(0,'秋季','周日','2031-01-01')");
            assertEquals(2, db.rows("SELECT adjustment_id FROM edu_schedule_adjustment WHERE schedule_id=0").size());
            db.jdbc().update("INSERT INTO edu_classroom(classroom_id,classroom_code,campus_name,classroom_name) VALUES(2,'TEST2','测试校区','B'),(3,'OTHER','其他校区','C')");
            db.jdbc().update("INSERT INTO edu_course_schedule(schedule_id,schedule_code,campus_name,term_name,period_name,time_slot,grade_name,subject_name,course_class_name,start_date,end_date,class_pattern,enrolled_count) VALUES(10,'UNPLACED','测试校区','秋季','周六','8:00-10:00','初一','数学','待分配班','2030-10-05','2030-10-19','WEEKLY',1)");
            var service = new RoomAssignService();
            var field = RoomAssignService.class.getDeclaredField("db"); field.setAccessible(true); field.set(service, db);
            assertEquals("测试校区", unplaced(service.day("2030-10-12")).get(0).get("campusName"));
            assertThrows(ServiceException.class, () -> service.move(10L,"2030-10-12",3L,"test"));
            assertTrue(db.rows("SELECT assignment_id FROM edu_room_assignment").isEmpty());
            service.move(10L,"2030-10-12",2L,"test");
            assertEquals(1, unplaced(service.day("2030-10-05")).size(), "首次分配前仍应没有教室");
            assertTrue(unplaced(service.day("2030-10-12")).isEmpty());
            var saved = db.one("SELECT classroom_id,campus_name,enrolled_count FROM edu_course_schedule WHERE schedule_id=10");
            assertEquals("2", saved.get("classroomId")); assertEquals("测试校区", saved.get("campusName"));
            assertEquals(1, ((Number)saved.get("enrolledCount")).intValue());
        } finally { fixture.cleanup(); }
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> unplaced(Map<String,Object> day) {
        return (List<Map<String,Object>>)day.get("unplaced");
    }
}
