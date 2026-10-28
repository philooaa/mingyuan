package com.ruoyi.system.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import com.ruoyi.common.exception.ServiceException;

/** 教师简化创建参数；教师等级不接受客户端提供。复用现有排课表。 */
public class TeacherOneToOneDraft {
    public Long classroomId;
    public String timeSlot;
    public String gradeName;
    public String courseClassName;

    public LocalDate startDate;
    public EduCourseSchedule toSchedule(String configuredSubject, com.ruoyi.system.service.ScheduleAvailabilityService.Slot slot) {
        if (startDate == null || classroomId == null || slot == null)
            throw new ServiceException("请选择具体日期、固定时段和空闲教室");
        LocalTime startTime = slot.start();
        EduCourseSchedule s = new EduCourseSchedule();
        s.setClassroomId(classroomId); s.setLessonLocation(""); s.setGradeName(gradeName); s.setSubjectName(configuredSubject);
        s.setCourseClassName(courseClassName == null ? null : courseClassName.trim());
        s.setClassMode("2"); s.setClassType("一对一"); s.setStatus("0"); s.setRecruitStatus("0");
        s.setCourseYear((long) startDate.getYear());
        // 按首课日期区分既有若依网格定位，避免不同日期的同一时段被唯一键误判。
        s.setTermName("一对一" + startDate);
        s.setPeriodName(List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").get(startDate.getDayOfWeek().getValue() - 1));
        s.setStartTime(startTime); s.setEndTime(slot.end());
        s.setTimeSlot(slot.value());
        s.setStartDate(java.sql.Date.valueOf(startDate));
        s.setEndDate(java.sql.Date.valueOf(startDate));
        s.setClassPattern("WEEKLY");
        return s;
    }
}
