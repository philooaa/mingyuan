package com.ruoyi.system.finance;

import java.util.HashMap;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceDashboardStudentCountSqlTest {
    @Test void studentCountUsesIdentityAndAppliesCourseFiltersBeforeCounting() throws Exception {
        Configuration config=new Configuration();
        String path="mapper/system/EduFinanceDashboardMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input); new XMLMapperBuilder(input,config,path,config.getSqlFragments()).parse();
        }
        var parameters=new HashMap<String,Object>();parameters.put("year",2026L);parameters.put("term","寒假");parameters.put("stage","junior");parameters.put("grade","初一");parameters.put("subject","数学");parameters.put("teacherId",9L);
        var sql=config.getMappedStatement("com.ruoyi.system.mapper.EduFinanceDashboardMapper.selectStudentCount").getBoundSql(parameters);
        String text=sql.getSql().replaceAll("\\s+"," ");
        assertTrue(text.contains("count(distinct coalesce(e.student_id, a.student_id))"));
        assertTrue(text.contains("s.grade_name=?")); assertTrue(text.contains("s.subject_name=?"));
        assertTrue(text.contains("s.teacher_id=?")); assertTrue(text.contains("'初一'"));
        assertEquals(5,sql.getParameterMappings().size());
        var list=config.getMappedStatement("com.ruoyi.system.mapper.EduFinanceDashboardMapper.selectStudents").getBoundSql(parameters);
        String listSql=list.getSql().replaceAll("\\s+"," ");
        assertTrue(listSql.contains("select distinct coalesce(e.student_id, a.student_id) as studentId"));
        assertTrue(listSql.contains("s.grade_name=?")); assertTrue(listSql.contains("s.subject_name=?"));
        assertTrue(listSql.contains("s.teacher_id=?")); assertEquals(5,list.getParameterMappings().size());
    }
}
