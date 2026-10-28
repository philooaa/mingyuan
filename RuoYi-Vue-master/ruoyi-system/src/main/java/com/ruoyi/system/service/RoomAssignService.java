package com.ruoyi.system.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;

/**
 * 教室按日期分配（小程序教务端 / 管理员端「教室分配」）。
 *
 * <p>数据模型：{@code edu_course_schedule} 是「学期+期次+时段+教室」的循环模板，
 * {@code edu_room_assignment} 记录「某排课从某天起换到某教室」。
 *
 * <p>某日 D 的有效教室按下面顺序解析（见 {@link #resolveRoom}）：
 * <ol>
 *   <li>有 effective_date ≤ D 的记录 → 取最近一条的 classroom_id；</li>
 *   <li>记录全部晚于 D → 取最早一条的 from_classroom_id（还原这次调整之前的历史教室）；</li>
 *   <li>没有任何记录 → 回落到排课模板的 classroom_id。</li>
 * </ol>
 *
 * <p>每次调整会同步把模板的 classroom_id 改成新教室，这样网页版「可视化课表」（没有日期维度）
 * 看到的就是最新安排；历史日期由上面的第 2 条还原，不会被改写。
 */
@Service
public class RoomAssignService
{
    private static final String[] WEEK = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };
    private static final List<String> CAMPUS_ORDER = List.of("万达", "百家湖");

    @Autowired
    private ShopRepository db;

    // =================================================================
    // 某一天的课表：两个校区的教室 × 时段，附带每格的实际课程
    // =================================================================
    public Map<String, Object> day(String dateText)
    {
        LocalDate date = parseDate(dateText);
        List<Map<String, Object>> rooms = activeRooms();

        // 横轴：按校区归组（万达在前，百家湖其次，其余按名字排）
        Map<String, List<Map<String, Object>>> byCampus = new LinkedHashMap<>();
        for (String campus : CAMPUS_ORDER) byCampus.put(campus, new ArrayList<>());
        for (Map<String, Object> room : rooms)
        {
            String campus = str(room.get("campusName"));
            if (campus.isEmpty()) campus = "未分配校区";
            byCampus.computeIfAbsent(campus, k -> new ArrayList<>()).add(room);
        }
        byCampus.values().removeIf(List::isEmpty);

        // 当天真正要上的课（含被调到当天的），并解析出该日期的实际教室
        List<Map<String, Object>> courses = occurringCourses(date);

        // 纵轴：字典固定时段 ∪ 当天实际出现的时段，按开始时间排
        List<String> slots = slotValues(courses);

        // 教室 × 时段 → 课程列表
        Map<String, List<Map<String, Object>>> cells = new LinkedHashMap<>();
        List<Map<String, Object>> unplaced = new ArrayList<>();
        for (Map<String, Object> c : courses)
        {
            Object roomId = c.get("roomId");
            if (roomId == null)
            {
                unplaced.add(c);
                continue;
            }
            cells.computeIfAbsent(roomId + "@" + str(c.get("timeSlot")), k -> new ArrayList<>()).add(c);
        }

        List<Map<String, Object>> campuses = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> en : byCampus.entrySet())
        {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("campusName", en.getKey());
            c.put("rooms", en.getValue());
            campuses.add(c);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("weekday", WEEK[date.getDayOfWeek().getValue() - 1]);
        out.put("campuses", campuses);
        out.put("slots", slots);
        out.put("cells", cells);
        out.put("unplaced", unplaced);
        out.put("courseCount", courses.size());
        return out;
    }

    /**
     * 把某门课在所选日期及之后改到目标教室；目标教室同时段已有课时自动互换。
     *
     * @param scheduleId      要调整的排课
     * @param dateText        生效起始日期（含当天）
     * @param targetRoomId    目标教室
     */
    public Map<String, Object> move(Long scheduleId, String dateText, Long targetRoomId, String operator)
    {
        LocalDate date = parseDate(dateText);
        if (scheduleId == null) throw new ServiceException("请选择要调整的课程");
        if (targetRoomId == null) throw new ServiceException("请选择目标教室");

        return db.transactions().execute(status ->
        {
            Map<String, Object> schedule = db.one("SELECT schedule_id,classroom_id,time_slot,teacher_name,course_class_name "
                    + "FROM edu_course_schedule WHERE schedule_id=? AND del_flag='0' AND status='0' FOR UPDATE", scheduleId);

            // 该日期必须真的上课（停课/调走的课次不能调教室）
            SortedSet<LocalDate> dates = datesOf(schedule);
            if (!dates.contains(date)) throw new ServiceException("该日期这门课不上课（可能已停课或已调到别的日期）");

            Map<String, Object> target = db.one("SELECT classroom_id,campus_name,classroom_name FROM edu_classroom "
                    + "WHERE classroom_id=? AND status='0'", targetRoomId);
            String targetCampus = str(target.get("campusName"));
            String targetName = str(target.get("classroomName"));

            Long currentRoomId = resolveRoom(scheduleId, id(schedule.get("classroomId")), date);
            if (targetRoomId.equals(currentRoomId))
                throw new ServiceException("这门课已经在「" + targetName + "」了");

            // 跨校区必须拦住：学生和老师跑错校区代价太大
            if (currentRoomId != null)
            {
                Map<String, Object> current = db.one("SELECT campus_name FROM edu_classroom WHERE classroom_id=?", currentRoomId);
                if (!str(current.get("campusName")).equals(targetCampus))
                    throw new ServiceException("只能在同校区内调整教室：当前在" + str(current.get("campusName"))
                            + "，目标教室属于" + targetCampus);
            }

            String slot = str(schedule.get("timeSlot"));
            Map<String, Object> occupant = findOccupant(scheduleId, date, slot, targetRoomId);

            if (occupant == null)
            {
                assign(scheduleId, date, targetRoomId, currentRoomId, "1", null, operator);
                db.jdbc().update("UPDATE edu_course_schedule SET classroom_id=? WHERE schedule_id=?", targetRoomId, scheduleId);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("moved", 1);
                out.put("swapped", false);
                out.put("message", "已把「" + str(schedule.get("courseClassName")) + "」调到 " + targetName + "，从 " + date + " 起生效");
                return out;
            }

            // 目标格已有人：两门课互换。没有原教室就没法互换（会把对方挤成"无教室"）
            if (currentRoomId == null)
                throw new ServiceException("这门课还没有教室，无法与其他课程互换，请先移到空教室");
            Long occupantId = id(occupant.get("scheduleId"));
            assign(scheduleId, date, targetRoomId, currentRoomId, "2", occupantId, operator);
            assign(occupantId, date, currentRoomId, targetRoomId, "2", scheduleId, operator);
            db.jdbc().update("UPDATE edu_course_schedule SET classroom_id=? WHERE schedule_id=?", targetRoomId, scheduleId);
            db.jdbc().update("UPDATE edu_course_schedule SET classroom_id=? WHERE schedule_id=?", currentRoomId, occupantId);

            Map<String, Object> current = db.one("SELECT classroom_name FROM edu_classroom WHERE classroom_id=?", currentRoomId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("moved", 2);
            out.put("swapped", true);
            out.put("message", "已互换：「" + str(schedule.get("courseClassName")) + "」→ " + targetName
                    + "，「" + str(occupant.get("courseClassName")) + "」→ " + str(current.get("classroomName"))
                    + "，从 " + date + " 起生效");
            return out;
        });
    }

    /** 目标教室在该日期、该时段是否已被别的课占用（返回占用者，没有则 null）。 */
    private Map<String, Object> findOccupant(Long scheduleId, LocalDate date, String timeSlot, Long roomId)
    {
        for (Map<String, Object> c : occurringCourses(date))
        {
            if (scheduleId.equals(id(c.get("scheduleId")))) continue;
            if (!timeSlot.equals(str(c.get("timeSlot")))) continue;
            if (roomId.equals(id(c.get("roomId")))) return c;
        }
        return null;
    }

    /**
     * 当天所有要上的课，并带上「该日期实际生效的教室」。
     * <p>课次判定复用 {@link ScheduleCalendar#dates}：它已经把停课（adjusted_date 为空）和
     * 调到别的日期都算进去了，保证与网页端「课次」弹窗看到的一致。
     */
    private List<Map<String, Object>> occurringCourses(LocalDate date)
    {
        List<Map<String, Object>> rows = db.rows("SELECT schedule_id,classroom_id,time_slot,start_time,end_time,"
                + "start_date,end_date,class_pattern,period_name,grade_name,subject_name,teacher_name,teacher_id,"
                + "course_class_name,class_mode,enrolled_count,term_name "
                + "FROM edu_course_schedule WHERE del_flag='0' AND status='0' "
                + "AND start_date IS NOT NULL AND end_date IS NOT NULL "
                + "AND start_date<=? AND end_date>=? ORDER BY time_slot,schedule_id",
                java.sql.Date.valueOf(date), java.sql.Date.valueOf(date));

        Map<String, List<Map<String, Object>>> changes = adjustments();
        Map<String, List<Map<String, Object>>> assignments = assignments();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            Long scheduleId = id(row.get("scheduleId"));
            String key = String.valueOf(scheduleId);
            SortedSet<LocalDate> dates = ScheduleCalendar.dates(
                    ScheduleCalendar.date(row.get("startDate")), ScheduleCalendar.date(row.get("endDate")),
                    str(row.get("classPattern")), str(row.get("periodName")),
                    changes.getOrDefault(key, List.of()));
            if (!dates.contains(date)) continue;

            Long templateRoom = id(row.get("classroomId"));
            Long roomId = resolveRoom(scheduleId, templateRoom, date, assignments.get(key));
            // 只回传前端要用的字段：日期字段是 java.sql.Date，直接序列化会变成时间戳，没必要带上
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scheduleId", String.valueOf(scheduleId));
            item.put("roomId", roomId == null ? null : String.valueOf(roomId));
            item.put("templateRoomId", templateRoom == null ? null : String.valueOf(templateRoom));
            item.put("timeSlot", str(row.get("timeSlot")));
            item.put("termName", str(row.get("termName")));
            item.put("periodName", str(row.get("periodName")));
            item.put("gradeName", str(row.get("gradeName")));
            item.put("subjectName", str(row.get("subjectName")));
            item.put("teacherName", str(row.get("teacherName")));
            item.put("teacherId", row.get("teacherId"));
            item.put("courseClassName", str(row.get("courseClassName")));
            item.put("classMode", str(row.get("classMode")));
            item.put("enrolledCount", row.get("enrolledCount"));
            item.put("moved", !Objects.equals(roomId, templateRoom));
            out.add(item);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> startTimeOf(str(m.get("timeSlot"))))
                .thenComparing(m -> str(m.get("courseClassName"))));
        return out;
    }

    /**
     * 解析某日期实际生效的教室（规则见类注释）。
     */
    private Long resolveRoom(Long scheduleId, Long templateRoom, LocalDate date)
    {
        return resolveRoom(scheduleId, templateRoom, date, assignments().get(String.valueOf(scheduleId)));
    }

    private Long resolveRoom(Long scheduleId, Long templateRoom, LocalDate date, List<Map<String, Object>> list)
    {
        if (list == null || list.isEmpty()) return templateRoom;
        Long latest = null;
        for (Map<String, Object> a : list)
        {
            LocalDate effective = ScheduleCalendar.date(a.get("effectiveDate"));
            if (effective != null && !effective.isAfter(date)) latest = id(a.get("classroomId"));
        }
        if (latest != null) return latest;
        // 所有记录都晚于该日期：用最早一条的「变更前教室」还原历史
        Long from = id(list.get(0).get("fromClassroomId"));
        return from == null ? templateRoom : from;
    }

    /**
     * 写入某排课从某天起的教室记录。
     *
     * <p>同一天可能反复调整，所以这里保留第一次记录的「变更前教室」，
     * 否则更早的日期会还原到错误的教室；如果又调回原教室，这条记录就删掉，等于没调过。
     */
    private void assign(Long scheduleId, LocalDate date, Long roomId, Long fromRoomId,
                        String opType, Long swapScheduleId, String operator)
    {
        java.sql.Date d = java.sql.Date.valueOf(date);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        // 注意：one() 查不到会抛异常，这里要用 rows() 判存在
        List<Map<String, Object>> exist = db.rows("SELECT assignment_id,from_classroom_id FROM edu_room_assignment "
                + "WHERE schedule_id=? AND effective_date=?", scheduleId, d);
        if (!exist.isEmpty())
        {
            Long aid = id(exist.get(0).get("assignmentId"));
            Long fromBefore = id(exist.get(0).get("fromClassroomId"));
            if (fromBefore != null && fromBefore.equals(roomId))
            {
                db.jdbc().update("DELETE FROM edu_room_assignment WHERE assignment_id=?", aid);
                return;
            }
            db.jdbc().update("UPDATE edu_room_assignment SET classroom_id=?,op_type=?,swap_schedule_id=?,"
                    + "create_by=?,create_time=? WHERE assignment_id=?",
                    roomId, opType, swapScheduleId, operator, now, aid);
            return;
        }
        db.jdbc().update("INSERT INTO edu_room_assignment(schedule_id,effective_date,classroom_id,from_classroom_id,"
                + "op_type,swap_schedule_id,create_by,create_time) VALUES(?,?,?,?,?,?,?,?)",
                scheduleId, d, roomId, fromRoomId, opType, swapScheduleId, operator, now);
    }

    private SortedSet<LocalDate> datesOf(Map<String, Object> schedule)
    {
        Map<String, Object> full = db.one("SELECT start_date,end_date,class_pattern,period_name "
                + "FROM edu_course_schedule WHERE schedule_id=?", id(schedule.get("scheduleId")));
        return ScheduleCalendar.dates(ScheduleCalendar.date(full.get("startDate")),
                ScheduleCalendar.date(full.get("endDate")), str(full.get("classPattern")), str(full.get("periodName")),
                db.rows("SELECT a.original_date,a.adjusted_date FROM edu_schedule_adjustment a "
                        + "JOIN edu_course_schedule s ON s.schedule_id=? "
                        + "WHERE a.schedule_id=s.schedule_id OR (a.schedule_id=0 AND a.term_name=s.term_name "
                        + "AND a.period_name=s.period_name AND NOT EXISTS (SELECT 1 FROM edu_schedule_adjustment x "
                        + "WHERE x.schedule_id=s.schedule_id AND x.original_date=a.original_date)) ORDER BY a.adjustment_id",
                        id(schedule.get("scheduleId"))));
    }

    /** 调课记录：日期级记录（schedule_id=0）展开到该学期期次的每门课，再叠加各课自己的单条记录 */
    private Map<String, List<Map<String, Object>>> adjustments()
    {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        for (Map<String, Object> a : db.rows("SELECT s.schedule_id AS schedule_id,a.original_date,a.adjusted_date "
                + "FROM edu_schedule_adjustment a JOIN edu_course_schedule s "
                + "ON s.term_name=a.term_name AND s.period_name=a.period_name "
                + "WHERE a.schedule_id=0 AND s.del_flag='0' AND s.status='0' "
                + "AND NOT EXISTS (SELECT 1 FROM edu_schedule_adjustment x WHERE x.schedule_id=s.schedule_id "
                + "AND x.original_date=a.original_date) "
                + "UNION ALL "
                + "SELECT schedule_id,original_date,adjusted_date FROM edu_schedule_adjustment WHERE schedule_id<>0 "
                + "ORDER BY 1"))
            map.computeIfAbsent(str(a.get("scheduleId")), k -> new ArrayList<>()).add(a);
        return map;
    }

    private Map<String, List<Map<String, Object>>> assignments()
    {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        for (Map<String, Object> a : db.rows("SELECT schedule_id,effective_date,classroom_id,from_classroom_id "
                + "FROM edu_room_assignment ORDER BY effective_date,assignment_id"))
            map.computeIfAbsent(str(a.get("scheduleId")), k -> new ArrayList<>()).add(a);
        return map;
    }

    private List<Map<String, Object>> activeRooms()
    {
        // 按教室名里的数字自然排序（W6 的 classroom_id 比 W11 大，按 id 排会插到 11 和 12 中间）
        return db.rows("SELECT classroom_id,campus_name,classroom_name,sheet_code FROM edu_classroom "
                + "WHERE status='0' "
                + "ORDER BY campus_name, CAST(REGEXP_SUBSTR(classroom_name, '[0-9]+') AS UNSIGNED), classroom_name");
    }

    /** 纵轴时段：字典固定时段 ∪ 数据里出现的时段，整体按开始时间排（同时间保持字典顺序）。 */
    private List<String> slotValues(List<Map<String, Object>> courses)
    {
        List<String> slots = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT dict_value FROM sys_dict_data WHERE dict_type='edu_time_slot' "
                + "AND status='0' ORDER BY dict_sort,dict_code"))
        {
            String v = str(r.get("dictValue"));
            if (!v.isEmpty() && !slots.contains(v)) slots.add(v);
        }
        for (Map<String, Object> c : courses)
        {
            String v = str(c.get("timeSlot"));
            if (!v.isEmpty() && !slots.contains(v)) slots.add(v);
        }
        // 字典里没有的时段（如 15:00-16:30）也要按时间插到正确位置，不能一律堆到最后
        slots.sort(Comparator.comparing(RoomAssignService::startTimeOf));
        return slots;
    }

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
            return LocalTime.MAX;   // 认不出时间的时段排到最后，不猜
        }
    }

    private static LocalDate parseDate(String text)
    {
        LocalDate d = ScheduleCalendar.date(text);
        if (d == null) throw new ServiceException("请选择有效日期");
        if (d.getYear() < 2000 || d.getYear() > 2100) throw new ServiceException("请选择有效日期");
        return d;
    }

    private static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Long id(Object v)
    {
        String s = str(v);
        if (s.isEmpty() || "null".equals(s)) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }
}
