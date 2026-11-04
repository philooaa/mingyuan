package com.ruoyi.system.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CourseNameRecognitionTest {
    private Map<String,Object> dimensions(List<Map<String,Object>> teachers) {
        return Map.of("grades",List.of(Map.of("label","高一"),Map.of("label","一年级")),"subjects",List.of(Map.of("label","数学"),Map.of("label","英语")),"teachers",teachers);
    }
    private Map<String,Object> teacher(long id,String name,String nick) { return Map.of("userId",id,"realName",name,"nickName",nick); }
    @Test void recognisesCustomClassAndPreservesTeacherIdentity() {
        var result=CourseNameRecognition.recognize(" 高一 数学 卓越班 陈雅娇 ",dimensions(List.of(teacher(7,"陈雅娇","陈老师"))));
        assertEquals("高一",result.get("gradeName"));assertEquals("数学",result.get("subjectName"));
        assertEquals("卓越班",result.get("classType"));assertEquals("7",result.get("teacherId"));
    }
    @Test void neverSelectsAmbiguousOrPartialTeacher() {
        var d=dimensions(List.of(teacher(7,"陈雅娇","陈老师"),teacher(8,"陈雅娇","陈老师")));
        var result=CourseNameRecognition.recognize("高一数学尖子班陈雅娇",d);
        assertFalse(result.containsKey("teacherId"));assertEquals(2,((List<?>)result.get("teacherCandidates")).size());
        var unknown=CourseNameRecognition.recognize("高一数学尖子班王老师",d);
        assertFalse(unknown.containsKey("teacherId"));assertFalse(unknown.containsKey("classType"));
    }
    @Test void validatesOrderAndFlagsExtraText() {
        var d=dimensions(List.of(teacher(7,"陈雅娇","陈老师")));
        assertFalse(CourseNameRecognition.recognize("数学高一尖子班陈雅娇",d).containsKey("gradeName"));
        var result=CourseNameRecognition.recognize("高一数学尖子班周末陈雅娇",d);
        assertEquals("尖子班周末",result.get("classType"));assertFalse(((List<?>)result.get("warnings")).isEmpty());
        assertEquals("尖子班",CourseNameRecognition.recognize("高一数学尖子班",d).get("classType"));
    }
}
