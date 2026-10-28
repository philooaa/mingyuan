package com.ruoyi.system.service;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.shop.ShopRepository;

/** 排课、空闲查询和调课共用实际日期及半开时间区间；停招仍占用资源。 */
@Service
public class ScheduleAvailabilityService {
    @Autowired private ShopRepository db;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record Slot(String value, LocalTime start, LocalTime end) {}
    public List<Slot> slots() {
        List<Slot> result = new ArrayList<>();
        for (var row : db.rows("SELECT dict_value FROM sys_dict_data WHERE dict_type='edu_time_slot' AND status='0' ORDER BY dict_sort,dict_code")) {
            String value = String.valueOf(row.get("dictValue"));
            try {
                String[] parts = value.split("[-–—]");
                LocalTime start = parseTime(parts[0]), end = parseTime(parts[1]);
                if (parts.length == 2 && Duration.between(start,end).toMinutes() == 120)
                    result.add(new Slot(value,start,end));
            } catch (RuntimeException ignored) { /* 无效字典不能变成可选时段。 */ }
        }
        return result.stream().distinct().sorted(Comparator.comparing(Slot::start)).toList();
    }
    private static LocalTime parseTime(String text) {
        String[] parts = text.trim().split(":");
        return LocalTime.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
    }
    public Slot slot(String value) {
        return slots().stream().filter(s -> s.value().equals(value)).findFirst()
            .orElseThrow(() -> new ServiceException("请选择机构启用的固定两小时时段"));
    }
    public void validateSingle(EduCourseSchedule s) {
        LocalDate date = ScheduleCalendar.date(s.getStartDate());
        require(date != null && date.equals(ScheduleCalendar.date(s.getEndDate())),"新建一对一仅支持指定日期的一节课");
        require(s.getClassroomId() != null,"一对一必须选择机构空闲教室");
        Slot slot = slot(s.getTimeSlot());
        require(slot.start().equals(s.getStartTime()) && slot.end().equals(s.getEndTime()),"上课时间必须与固定班课时段一致");
        require("WEEKLY".equals(s.getClassPattern()) && ScheduleCalendar.weekday(s.getPeriodName()) == date.getDayOfWeek().getValue(),"上课日期与星期不一致");
        require(LocalDateTime.of(date,slot.start()).isAfter(LocalDateTime.now(ZONE)),"已经开始的时段不能发布");
    }
    public void lockResources(Collection<EduCourseSchedule> courses) {
        SortedSet<Long> rooms = new TreeSet<>(), teachers = new TreeSet<>();
        for (var s : courses) {
            if (s.getClassroomId() != null) rooms.add(s.getClassroomId());
            if (s.getTeacherId() != null) teachers.add(s.getTeacherId());
        }
        for (var id : rooms) db.one("SELECT classroom_id FROM edu_classroom WHERE classroom_id=? FOR UPDATE",id);
        for (var id : teachers) db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",id);
    }
    public List<Map<String,Object>> adjustments(Long id, boolean locked) {
        if (id == null) return List.of();
        return db.rows("SELECT adjustment_id,original_date,adjusted_date FROM edu_schedule_adjustment WHERE schedule_id=? ORDER BY adjustment_id"+(locked ? " FOR UPDATE" : ""),id);
    }
    public SortedSet<LocalDate> dates(EduCourseSchedule s, List<Map<String,Object>> changes) {
        return ScheduleCalendar.dates(ScheduleCalendar.date(s.getStartDate()),ScheduleCalendar.date(s.getEndDate()),s.getClassPattern(),s.getPeriodName(),changes);
    }
    public void check(EduCourseSchedule s) { check(s,adjustments(s.getScheduleId(),true)); }
    public void check(EduCourseSchedule s,List<Map<String,Object>> changes) {
        if (!"0".equals(s.getStatus())) return;
        var dates = dates(s,changes);
        Set<LocalDate> unique = new HashSet<>(dates(s,List.of()));
        for (var change : changes) unique.remove(ScheduleCalendar.date(change.get("originalDate")));
        for (var change : changes) {
            LocalDate moved=ScheduleCalendar.date(change.get("adjustedDate"));
            require(moved == null || unique.add(moved),"调课后同一课程存在重叠课次，请选择其他日期");
        }
        if (dates.isEmpty()) return; // 开发阶段保留未排日期，不猜测历史占用。
        var candidates = db.rows("SELECT schedule_id,teacher_id,classroom_id,start_time,end_time,start_date,end_date,class_pattern,period_name,course_class_name FROM edu_course_schedule WHERE schedule_id<>? AND status='0' AND del_flag='0' AND (teacher_id=? OR classroom_id=?) ORDER BY schedule_id FOR UPDATE",s.getScheduleId() == null ? -1 : s.getScheduleId(),s.getTeacherId(),s.getClassroomId());
        for (var row : candidates) {
            if (!overlaps(s.getStartTime(),s.getEndTime(),time(row.get("startTime")),time(row.get("endTime")))) continue;
            var other = rowDates(row,true);
            for (var date : dates) require(!other.contains(date),date+" 教师或教室已被其他课程占用，请刷新后选择空闲时段");
        }
    }
    private SortedSet<LocalDate> rowDates(Map<String,Object> row,boolean locked) {
        return ScheduleCalendar.dates(ScheduleCalendar.date(row.get("startDate")),ScheduleCalendar.date(row.get("endDate")),(String)row.get("classPattern"),(String)row.get("periodName"),adjustments(id(row.get("scheduleId")),locked));
    }
    public Map<String,Object> week(Long teacherId,LocalDate selected,Long excludeId) {
        require(selected != null && selected.getYear() >= 2000 && selected.getYear() <= 2100,"请选择有效日期");
        LocalDate first = selected.minusDays(selected.getDayOfWeek().getValue()-1);
        var rooms = db.rows("SELECT classroom_id,campus_name,classroom_name FROM edu_classroom WHERE status='0' ORDER BY campus_name,classroom_name,classroom_id");
        var rows = db.rows("SELECT schedule_id,teacher_id,classroom_id,start_time,end_time,start_date,end_date,class_pattern,period_name FROM edu_course_schedule WHERE status='0' AND del_flag='0' AND schedule_id<>?",excludeId == null ? -1 : excludeId);
        Map<String,SortedSet<LocalDate>> dates = new HashMap<>();
        Map<Long,List<Map<String,Object>>> changes = new HashMap<>();
        for(var change : db.rows("SELECT schedule_id,original_date,adjusted_date FROM edu_schedule_adjustment ORDER BY adjustment_id"))
            changes.computeIfAbsent(id(change.get("scheduleId")),ignored -> new ArrayList<>()).add(change);
        int pending = 0;
        for (var row : rows) {
            var expanded = ScheduleCalendar.dates(ScheduleCalendar.date(row.get("startDate")),ScheduleCalendar.date(row.get("endDate")),(String)row.get("classPattern"),(String)row.get("periodName"),changes.getOrDefault(id(row.get("scheduleId")),List.of())); dates.put(String.valueOf(row.get("scheduleId")),expanded);
            if (row.get("startDate") == null || row.get("endDate") == null) pending++;
        }
        List<Map<String,Object>> cells = new ArrayList<>();
        var fixedSlots=slots();
        for (int day=0; day<7; day++) for (var slot : fixedSlots) {
            LocalDate date = first.plusDays(day);
            boolean teacherBusy = false; Set<String> busyRooms = new HashSet<>();
            for (var row : rows) {
                if (!dates.get(String.valueOf(row.get("scheduleId"))).contains(date) || !overlaps(slot.start(),slot.end(),time(row.get("startTime")),time(row.get("endTime")))) continue;
                if (Objects.equals(teacherId,id(row.get("teacherId")))) teacherBusy = true;
                if (row.get("classroomId") != null) busyRooms.add(String.valueOf(row.get("classroomId")));
            }
            var available = rooms.stream().filter(r -> !busyRooms.contains(String.valueOf(r.get("classroomId")))).toList();
            boolean future = LocalDateTime.of(date,slot.start()).isAfter(LocalDateTime.now(ZONE));
            var cell = new LinkedHashMap<String,Object>();
            cell.put("date",date.toString()); cell.put("timeSlot",slot.value()); cell.put("startTime",slot.start().toString()); cell.put("endTime",slot.end().toString());
            cell.put("canPublish",future && !teacherBusy && !available.isEmpty());
            cell.put("reason",!future ? "时段已开始" : teacherBusy ? "本人已有课程" : available.isEmpty() ? "暂无空闲教室" : "可发布");
            cell.put("classrooms",future && !teacherBusy ? available : List.of()); cells.add(cell);
        }
        return Map.of("weekStart",first.toString(),"cells",cells,"pendingScheduleCount",pending);
    }
    public static boolean overlaps(LocalTime a,LocalTime b,LocalTime c,LocalTime d) { return a != null && b != null && c != null && d != null && a.isBefore(d) && c.isBefore(b); }
    private static LocalTime time(Object value) { return value == null ? null : value instanceof java.sql.Time t ? t.toLocalTime() : LocalTime.parse(value.toString()); }
    private static Long id(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private static void require(boolean condition,String message) { if (!condition) throw new ServiceException(message); }
}
