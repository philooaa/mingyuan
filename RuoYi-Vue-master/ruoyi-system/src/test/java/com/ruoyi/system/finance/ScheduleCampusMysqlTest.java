package com.ruoyi.system.finance;

import java.io.InputStream;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.mapper.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅在随机临时库验证新校区字段和未分配教室的报名占位。 */
class ScheduleCampusMysqlTest {
    @Test void campusOnlyClassIsVisibleAndEnrollmentStillEnforcesCapacity() throws Exception {
        var fixture=new TuitionMysqlTest();
        try {
            fixture.setup();
            // The shared finance fixture predates the existing one-to-one and enrollment-limit upgrades.
            fixture.db.jdbc().execute("ALTER TABLE edu_course_schedule ADD lesson_location varchar(255), ADD unit_price decimal(10,2) DEFAULT 0");
            fixture.runMigration("20261019_schedule_enroll_limit.sql");
            fixture.runMigration("20261028_schedule_campus_first.sql");
            fixture.runMigration("20261028_schedule_campus_first.sql");
            var cfg=new Configuration(new Environment("test",new JdbcTransactionFactory(),fixture.db.jdbc().getDataSource()));
            cfg.getTypeAliasRegistry().registerAlias("EduCourseSchedule",EduCourseSchedule.class);
            for(String mapper:List.of("EduCourseScheduleMapper","MiniAppParentMapper")) {
                String name="mapper/system/"+mapper+".xml";
                try(InputStream in=getClass().getResourceAsStream('/'+name)) {new XMLMapperBuilder(in,cfg,name,cfg.getSqlFragments()).parse();}
            }
            try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession()) {
                var course=new EduCourseSchedule();
                course.setScheduleCode("CAMPUS_ONLY");course.setCampusName("测试校区");course.setTeacherId(1L);
                course.setTermName("秋季");course.setPeriodName("周六");course.setTimeSlot("8:00-10:00");
                course.setGradeName("初一");course.setSubjectName("数学");course.setCourseClassName("校区待分配教室班");
                course.setClassMode("1");course.setRecruitStatus("0");course.setStatus("0");course.setDelFlag("0");course.setEnrollLimit(1);
                var schedules=session.getMapper(EduCourseScheduleMapper.class);schedules.insertEduCourseSchedule(course);
                var saved=schedules.selectEduCourseScheduleByScheduleId(course.getScheduleId());
                assertEquals("测试校区",saved.getCampusName());assertNull(saved.getClassroomId());
                var parent=session.getMapper(MiniAppParentMapper.class);
                assertTrue(parent.selectAvailableSchedules(2L,"初一","数学",null).stream().anyMatch(row->"校区待分配教室班".equals(row.get("courseClassName"))));
                assertEquals(1,parent.countScheduleAvailable(course.getScheduleId()));
                assertEquals(1,parent.increaseScheduleEnrollment(course.getScheduleId()));
                assertEquals(0,parent.increaseScheduleEnrollment(course.getScheduleId()));
                assertEquals(1L,schedules.selectEduCourseScheduleByScheduleId(course.getScheduleId()).getEnrolledCount());
                session.getConnection().createStatement().execute("INSERT INTO edu_classroom(classroom_id,classroom_code,campus_name,classroom_name) VALUES(2,'TEST2','另一校区','B')");
                schedules.swapClassroom(null,"秋季","周日",1L,2L,"test");
                assertEquals("另一校区",schedules.selectEduCourseScheduleByScheduleId(1L).getCampusName());
                session.commit();
                fixture.db.jdbc().update("INSERT INTO edu_enrollment(enrollment_code,schedule_id,parent_id,student_name,pay_status,billing_status) VALUES('CAMPUS_ENR',?,2,'校区测试学生','未支付','PENDING_PRICE')",course.getScheduleId());
                long enrollment=fixture.db.jdbc().queryForObject("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_code='CAMPUS_ENR'",Long.class);
                ((TuitionService)fixture.service("TuitionService")).initializeBill(enrollment,2L);
                var bill=fixture.db.one("SELECT fee_title_snapshot,campus_name_snapshot FROM edu_enrollment WHERE enrollment_id=?",enrollment);
                assertEquals("校区待分配教室班",bill.get("feeTitleSnapshot"));
                assertEquals("测试校区",bill.get("campusNameSnapshot"));
            }
        } finally {fixture.cleanup();}
    }
}
