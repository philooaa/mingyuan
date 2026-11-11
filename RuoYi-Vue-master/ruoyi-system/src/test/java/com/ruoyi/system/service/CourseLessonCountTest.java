package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

class CourseLessonCountTest {
    @Test void automaticCountAndExplicitOverrideAreIndependent() {
        var course = new HashMap<String,Object>();
        course.put("startDate", "2026-11-02"); course.put("endDate", "2026-11-30");
        course.put("classPattern", "WEEKLY"); course.put("periodName", "周一");
        assertEquals(5, CoursePriceService.systemLessonCount(course, List.of()));
        course.put("lessonCount",14);
        assertEquals(5, CoursePriceService.billingLessonCount(course,5));
        course.put("lessonCountOverride",3);
        assertEquals(3, CoursePriceService.billingLessonCount(course,5));
        course.put("endDate", "2026-12-31");
        assertEquals(9, CoursePriceService.systemLessonCount(course,List.of()));
        assertEquals(3, CoursePriceService.billingLessonCount(course,9));
    }
    @Test void missingCalendarIsNotFalselyReportedAsOneLesson() {
        var course=new HashMap<String,Object>();
        assertNull(CoursePriceService.systemLessonCount(course,List.of()));
        assertNull(CoursePriceService.billingLessonCount(course,null));
        course.put("lessonCount",14);
        assertEquals(14,CoursePriceService.billingLessonCount(course,null));
    }
    @Test void mismatchRequiresExplicitConfirmationAndReasonIncludingUnknownCalendar() {
        assertDoesNotThrow(()->CoursePriceService.validateCountChange(5,5,Map.of()));
        assertThrows(ServiceException.class,()->CoursePriceService.validateCountChange(3,5,Map.of()));
        assertThrows(ServiceException.class,()->CoursePriceService.validateCountChange(3,5,Map.of("confirmMismatch",true)));
        assertDoesNotThrow(()->CoursePriceService.validateCountChange(3,5,Map.of("confirmMismatch",true,"reason","少上两讲")));
        assertThrows(ServiceException.class,()->CoursePriceService.validateCountChange(3,null,Map.of("reason","补录")));
        assertDoesNotThrow(()->CoursePriceService.validateCountChange(3,null,Map.of("confirmMismatch",true,"reason","补录")));
    }
}
