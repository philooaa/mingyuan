package com.ruoyi.system.timetable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.domain.EduScheduleAdjustment;
import com.ruoyi.system.service.IEduClassroomService;
import com.ruoyi.system.service.IEduScheduleAdjustmentService;
import com.ruoyi.system.service.ScheduleCalendar;

/**
 * 课表日历：把「学期 + 期次」下的循环排课展开成具体日期，供页面日历高亮有课的日期。
 *
 * <p>展开规则复用 {@link ScheduleCalendar#dates}，与「课次（按具体日期）」弹窗、小程序教室分配
 * 用的是同一套逻辑：停课（adjusted_date 为空）的日期不出现，调到别的日期的课次出现在目标日期。
 *
 * <p>只回传日历和当天清单要用的字段，避免把整张排课表塞进响应。
 */
@Service
public class TimetableCalendarService
{
    private static final String[] WEEK = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };

    @Autowired
    private IEduScheduleAdjustmentService adjustmentService;

    @Autowired
    private IEduClassroomService classroomService;

    public Map<String, Object> build(List<EduCourseSchedule> rows)
    {
        Map<Long, EduClassroom> roomById = new HashMap<>();
        for (EduClassroom c : classroomService.selectEduClassroomList(new EduClassroom()))
        {
            if (c.getClassroomId() != null) roomById.put(c.getClassroomId(), c);
        }

        Map<Long, List<Map<String, Object>>> adjustments = adjustments(rows);

        // 日期 -> 当天的课；TreeMap 保证日期升序
        TreeMap<LocalDate, List<Map<String, Object>>> byDate = new TreeMap<>();
        for (EduCourseSchedule s : rows)
        {
            List<Map<String, Object>> changes = adjustments.getOrDefault(s.getScheduleId(), List.of());
            SortedSet<LocalDate> dates = ScheduleCalendar.dates(
                    ScheduleCalendar.date(s.getStartDate()), ScheduleCalendar.date(s.getEndDate()),
                    StringUtils.trimToEmpty(s.getClassPattern()), StringUtils.trimToEmpty(s.getPeriodName()),
                    changes);
            if (dates.isEmpty()) continue;   // 没设起止日期（待排）或规则认不出的，不猜日期

            Map<String, Object> item = item(s, roomById.get(s.getClassroomId()));
            for (LocalDate d : dates) byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Map<String, Object>>> en : byDate.entrySet())
        {
            List<Map<String, Object>> items = en.getValue();
            items.sort(Comparator.comparing((Map<String, Object> m) -> startTimeOf(str(m.get("timeSlot"))))
                    .thenComparing(m -> str(m.get("courseClassName"))));
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", en.getKey().toString());
            day.put("weekday", WEEK[en.getKey().getDayOfWeek().getValue() - 1]);
            day.put("count", items.size());
            day.put("items", items);
            days.add(day);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start", byDate.isEmpty() ? null : byDate.firstKey().toString());
        out.put("end", byDate.isEmpty() ? null : byDate.lastKey().toString());
        out.put("dayCount", days.size());
        out.put("days", days);
        return out;
    }

    /**
     * 停课 / 调课记录，按排课分组，交给 ScheduleCalendar 展开。
     * 日期级记录（schedule_id = 0）一条管整天，按「学期 + 期次」挂到该期次的每门课上；
     * 若某门课对同一天另有单条记录，以单条为准，避免同一日期出现两条变更。
     */
    private Map<Long, List<Map<String, Object>>> adjustments(List<EduCourseSchedule> rows)
    {
        Map<Long, List<Map<String, Object>>> own = new HashMap<>();
        Map<String, List<Map<String, Object>>> byTermPeriod = new HashMap<>();
        for (EduScheduleAdjustment a : adjustmentService.selectEduScheduleAdjustmentList(new EduScheduleAdjustment()))
        {
            Map<String, Object> m = new HashMap<>();
            m.put("originalDate", a.getOriginalDate());
            m.put("adjustedDate", a.getAdjustedDate());
            if (a.getScheduleId() != null && a.getScheduleId() == 0L)
            {
                byTermPeriod.computeIfAbsent(key(a.getTermName(), a.getPeriodName()), k -> new ArrayList<>()).add(m);
            }
            else
            {
                own.computeIfAbsent(a.getScheduleId(), k -> new ArrayList<>()).add(m);
            }
        }
        Map<Long, List<Map<String, Object>>> map = new HashMap<>();
        for (EduCourseSchedule s : rows)
        {
            List<Map<String, Object>> mine = own.getOrDefault(s.getScheduleId(), List.of());
            List<Map<String, Object>> list = new ArrayList<>(mine);
            Set<LocalDate> mineDates = new HashSet<>();
            for (Map<String, Object> m : mine) mineDates.add(ScheduleCalendar.date(m.get("originalDate")));
            for (Map<String, Object> m : byTermPeriod.getOrDefault(key(s.getTermName(), s.getPeriodName()), List.of()))
            {
                if (!mineDates.contains(ScheduleCalendar.date(m.get("originalDate")))) list.add(m);
            }
            if (!list.isEmpty()) map.put(s.getScheduleId(), list);
        }
        return map;
    }

    private static String key(String termName, String periodName)
    {
        return StringUtils.trimToEmpty(termName) + "|" + StringUtils.trimToEmpty(periodName);
    }

    private static Map<String, Object> item(EduCourseSchedule s, EduClassroom room)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("scheduleId", s.getScheduleId());
        // 一个日历混着所有学期期次，清单里要能看出这门课属于哪一期
        item.put("termName", str(s.getTermName()));
        item.put("periodName", str(s.getPeriodName()));
        item.put("timeSlot", str(s.getTimeSlot()));
        item.put("gradeName", str(s.getGradeName()));
        item.put("subjectName", str(s.getSubjectName()));
        item.put("classType", str(s.getClassType()));
        item.put("courseClassName", str(s.getCourseClassName()));
        item.put("teacherName", str(s.getTeacherName()));
        item.put("campusName", room == null ? "" : str(room.getCampusName()));
        item.put("classroomName", room == null ? "" : str(room.getClassroomName()));
        item.put("classMode", str(s.getClassMode()));
        item.put("enrolledCount", s.getEnrolledCount());
        return item;
    }

    /** 时段字符串（如 8:00-10:00）→ 开始时间，用于当天清单排序；认不出的排最后。 */
    private static LocalTime startTimeOf(String slot)
    {
        try
        {
            String first = slot.split("[-–—~]")[0].trim().replace("：", ":");
            String[] parts = first.split(":");
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        catch (RuntimeException e)
        {
            return LocalTime.MAX;
        }
    }

    private static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
