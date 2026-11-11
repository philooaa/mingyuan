package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import com.ruoyi.system.domain.EduEnrollment;
import com.ruoyi.system.mapper.EduEnrollmentMapper;

class TeacherStudentFiltersTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();c.getTypeAliasRegistry().registerAliases("com.ruoyi.system.domain");
        String path="mapper/system/EduEnrollmentMapper.xml";
        try(var in=EduEnrollmentMapper.class.getClassLoader().getResourceAsStream(path)) {new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        return c;
    }
    @Test void combinedSearchFiltersStayInsideTheTeachersCourses() throws Exception {
        var q=new EduEnrollment();q.getParams().put("teacherUserId",466L);
        q.getParams().put("adminYear",2027);q.getParams().put("adminTerm","寒假");q.getParams().put("adminGrade","初一");
        q.getParams().put("adminCampus","万达");q.getParams().put("adminCourseStatus","0");q.getParams().put("adminKeyword","学员");
        var bound=configuration().getMappedStatement("com.ruoyi.system.mapper.EduEnrollmentMapper.selectEduEnrollmentList").getBoundSql(q);
        String sql=bound.getSql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("s.teacher_id = ?"));assertTrue(sql.contains("s.grade_name = ?"));assertTrue(sql.contains("s.course_year = ?"));
        assertTrue(sql.contains("s.term_name = ?"));assertTrue(sql.contains("s.status = ?"));assertTrue(sql.contains("edu_classroom"));
        var params=bound.getParameterMappings().stream().map(p->p.getProperty()).toList();
        assertTrue(params.contains("params.adminCampus"));assertTrue(params.contains("params.adminCourseStatus"));
    }
    @Test void openingAnOldCourseDoesNotImplicitlyHideItsStudents() throws Exception {
        var q=new EduEnrollment();q.setScheduleId(123L);q.getParams().put("teacherUserId",466L);
        String sql=configuration().getMappedStatement("com.ruoyi.system.mapper.EduEnrollmentMapper.selectEduEnrollmentList").getBoundSql(q).getSql().replaceAll("\\s+"," ");
        assertTrue(sql.contains("s.teacher_id = ?"));assertFalse(sql.contains("s.status = ?"));
    }
}
