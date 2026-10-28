package com.ruoyi.system.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.domain.EduTeacherCheckIn;
import com.ruoyi.system.domain.EduWifiConfig;
import com.ruoyi.system.mapper.EduClassroomMapper;
import com.ruoyi.system.mapper.EduTeacherCheckInMapper;
import com.ruoyi.system.mapper.EduTeacherSalaryRecordMapper;
import com.ruoyi.system.mapper.EduWifiConfigMapper;
import com.ruoyi.system.mapper.SysUserMapper;

/**
 * 小程序 教师WiFi上下班打卡（每日考勤，不绑定课次）
 */
@RestController
@RequestMapping("/miniapp/teacher/checkin")
public class MiniAppTeacherCheckInController extends BaseController
{
    @Autowired
    private EduClassroomMapper classroomMapper;

    @Autowired
    private EduWifiConfigMapper wifiConfigMapper;

    @Autowired
    private EduTeacherCheckInMapper checkInMapper;

    @Autowired
    private EduTeacherSalaryRecordMapper recordMapper;

    @Autowired
    private SysUserMapper userMapper;

    /**
     * 教师今日打卡状态（上班/下班）
     */
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @GetMapping("/status")
    public AjaxResult status()
    {
        Long teacherId = SecurityUtils.getUserId();
        String today = LocalDate.now().toString();
        EduTeacherCheckIn in = null;
        EduTeacherCheckIn out = null;
        for (EduTeacherCheckIn record : checkInMapper.selectByTeacherAndDate(teacherId, today))
        {
            if ("out".equals(record.getCheckType())) out = record;
            else in = record;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("date", today);
        result.put("in", in);
        result.put("out", out);
        return success(result);
    }

    /**
     * 教师WiFi上下班打卡（type=in上班/out下班，校区按WiFi白名单自动匹配）
     */
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @PostMapping
    public AjaxResult checkIn(@RequestBody Map<String, Object> body)
    {
        String type = toText(body == null ? null : body.get("type"));
        if (!"in".equals(type) && !"out".equals(type))
        {
            throw new ServiceException("打卡类型不正确");
        }
        String ssid = toText(body.get("ssid"));
        String bssid = toText(body.get("bssid"));
        if (StringUtils.isEmpty(ssid))
        {
            throw new ServiceException("未获取到WiFi名称，请先连接校区WiFi后重试");
        }
        Long teacherId = SecurityUtils.getUserId();
        String today = LocalDate.now().toString();

        List<EduTeacherCheckIn> existing = checkInMapper.selectByTeacherAndDate(teacherId, today);
        EduTeacherCheckIn in = null;
        EduTeacherCheckIn out = null;
        for (EduTeacherCheckIn record : existing)
        {
            if ("out".equals(record.getCheckType())) out = record;
            else in = record;
        }
        if ("in".equals(type) && in != null)
        {
            throw new ServiceException("今日已打过上班卡");
        }
        if ("out".equals(type))
        {
            if (in == null)
            {
                throw new ServiceException("请先打上班卡");
            }
            if (out != null)
            {
                throw new ServiceException("今日已打过下班卡");
            }
        }

        EduWifiConfig matched = matchWifi(ssid, bssid);
        if (matched == null)
        {
            throw new ServiceException("当前WiFi不在校区白名单内，无法打卡");
        }

        EduTeacherCheckIn record = new EduTeacherCheckIn();
        record.setClassDate(java.sql.Date.valueOf(LocalDate.now()));
        record.setCheckType(type);
        record.setTeacherId(teacherId);
        record.setTeacherName(realNameOf(SecurityUtils.getLoginUser().getUser()));
        record.setCampusName(matched.getCampusName());
        record.setSsid(ssid);
        record.setBssid(StringUtils.isEmpty(bssid) ? null : bssid);
        record.setCheckInTime(java.sql.Timestamp.valueOf(LocalDateTime.now()));
        record.setCreateBy(SecurityUtils.getUsername());
        record.setCreateTime(new java.util.Date());
        checkInMapper.insert(record);
        return success(record);
    }

    /**
     * 管理端：某日全部教师上下班打卡汇总
     */
    @PreAuthorize("@ss.hasAnyRoles('admin')")
    @GetMapping("/admin/summary")
    public AjaxResult adminSummary(@RequestParam(value = "date", required = false) String date)
    {
        String classDate = StringUtils.isEmpty(date) ? LocalDate.now().toString() : date;
        Map<Long, Map<String, Object>> byTeacher = new LinkedHashMap<>();
        for (Map<String, Object> teacher : recordMapper.selectTeacherUsers())
        {
            Long teacherId = toLong(teacher.get("teacherId"));
            Map<String, Object> row = new HashMap<>();
            row.put("teacherId", teacherId);
            row.put("teacherName", displayNameOf(teacher));
            row.put("inId", null);
            row.put("outId", null);
            row.put("inTime", null);
            row.put("outTime", null);
            row.put("inCampus", null);
            row.put("outCampus", null);
            row.put("inSsid", null);
            row.put("outSsid", null);
            row.put("inRemark", null);
            row.put("outRemark", null);
            byTeacher.put(teacherId, row);
        }
        for (EduTeacherCheckIn record : checkInMapper.selectByDate(classDate, null))
        {
            Map<String, Object> row = byTeacher.get(record.getTeacherId());
            if (row == null)
            {
                row = new HashMap<>();
                row.put("teacherId", record.getTeacherId());
                row.put("teacherName", record.getTeacherName());
                row.put("inId", null); row.put("outId", null);
                row.put("inTime", null); row.put("outTime", null);
                row.put("inCampus", null); row.put("outCampus", null);
                row.put("inSsid", null); row.put("outSsid", null);
                row.put("inRemark", null); row.put("outRemark", null);
                byTeacher.put(record.getTeacherId(), row);
            }
            if ("out".equals(record.getCheckType()))
            {
                row.put("outId", record.getCheckInId());
                row.put("outTime", record.getCheckInTime());
                row.put("outCampus", record.getCampusName());
                row.put("outSsid", record.getSsid());
                row.put("outRemark", record.getRemark());
            }
            else
            {
                row.put("inId", record.getCheckInId());
                row.put("inTime", record.getCheckInTime());
                row.put("inCampus", record.getCampusName());
                row.put("inSsid", record.getSsid());
                row.put("inRemark", record.getRemark());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(byTeacher.values());
        for (Map<String, Object> row : rows)
        {
            boolean hasIn = row.get("inId") != null;
            boolean hasOut = row.get("outId") != null;
            row.put("status", hasIn && hasOut ? "normal" : (hasIn ? "missing_out" : (hasOut ? "missing_in" : "absent")));
        }
        rows.sort((a, b) -> {
            int sa = statusOrder(String.valueOf(a.get("status")));
            int sb = statusOrder(String.valueOf(b.get("status")));
            if (sa != sb) return Integer.compare(sa, sb);
            return String.valueOf(a.get("teacherName")).compareTo(String.valueOf(b.get("teacherName")));
        });
        return success(rows);
    }

    private int statusOrder(String status)
    {
        switch (status)
        {
            case "normal": return 0;
            case "missing_out": return 1;
            case "missing_in": return 2;
            default: return 3;
        }
    }

    /**
     * 管理端：打卡统计表（按月统计1号至今/月末，应打卡日=工作日：周一至周五，
     * 扣除国家法定节假日，含周末调休上班日；周六日本身不计缺卡）
     */
    @PreAuthorize("@ss.hasAnyRoles('admin')")
    @GetMapping("/admin/statistics")
    public AjaxResult statistics(@RequestParam("month") String month)
    {
        if (StringUtils.isEmpty(month) || !month.matches("\\d{4}-\\d{2}"))
        {
            throw new ServiceException("请选择统计月份（yyyy-MM）");
        }
        java.time.YearMonth ym = java.time.YearMonth.parse(month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        LocalDate today = LocalDate.now();
        // 统计范围：每月1号到今天（历史月则到月末）
        LocalDate statEnd = monthEnd.isAfter(today) ? today : monthEnd;
        int requiredDays = 0;
        if (!statEnd.isBefore(monthStart))
        {
            for (LocalDate d = monthStart; !d.isAfter(statEnd); d = d.plusDays(1))
            {
                if (isWorkDay(d)) requiredDays++;
            }
        }
        String start = monthStart.toString();
        String end = statEnd.isBefore(monthStart) ? monthStart.toString() : statEnd.toString();
        List<EduTeacherCheckIn> records = checkInMapper.selectByRange(start, end, null);
        Map<Long, Map<String, Object>> byTeacher = new LinkedHashMap<>();
        for (Map<String, Object> teacher : recordMapper.selectTeacherUsers())
        {
            Long teacherId = toLong(teacher.get("teacherId"));
            Map<String, Object> row = new HashMap<>();
            row.put("teacherId", teacherId);
            row.put("teacherName", displayNameOf(teacher));
            row.put("inCount", 0);
            row.put("outCount", 0);
            row.put("days", new java.util.HashSet<String>());
            byTeacher.put(teacherId, row);
        }
        for (EduTeacherCheckIn record : records)
        {
            LocalDate recordDate = new java.sql.Date(record.getClassDate().getTime()).toLocalDate();
            if (!isWorkDay(recordDate)) continue; // 周末/节假日的打卡不计入应打卡口径
            Map<String, Object> row = byTeacher.computeIfAbsent(record.getTeacherId(), id -> {
                Map<String, Object> r = new HashMap<>();
                r.put("teacherId", id);
                r.put("teacherName", record.getTeacherName());
                r.put("inCount", 0);
                r.put("outCount", 0);
                r.put("days", new java.util.HashSet<String>());
                return r;
            });
            if ("out".equals(record.getCheckType())) row.put("outCount", (Integer) row.get("outCount") + 1);
            else row.put("inCount", (Integer) row.get("inCount") + 1);
            @SuppressWarnings("unchecked")
            java.util.Set<String> days = (java.util.Set<String>) row.get("days");
            days.add(recordDate.toString());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : byTeacher.values())
        {
            @SuppressWarnings("unchecked")
            java.util.Set<String> days = (java.util.Set<String>) row.get("days");
            Map<String, Object> item = new HashMap<>();
            item.put("teacherId", row.get("teacherId"));
            item.put("teacherName", row.get("teacherName"));
            item.put("workDays", days.size());
            item.put("inCount", row.get("inCount"));
            item.put("outCount", row.get("outCount"));
            result.add(item);
        }
        result.sort((a, b) -> String.valueOf(a.get("teacherName")).compareTo(String.valueOf(b.get("teacherName"))));
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("requiredDays", requiredDays);
        wrapper.put("rows", result);
        return success(wrapper);
    }

    /**
     * 管理端：某教师某月打卡明细（每次打卡的具体时间）
     */
    @PreAuthorize("@ss.hasAnyRoles('admin')")
    @GetMapping("/admin/records")
    public AjaxResult teacherRecords(@RequestParam("teacherId") Long teacherId, @RequestParam("month") String month)
    {
        if (StringUtils.isEmpty(month) || !month.matches("\\d{4}-\\d{2}"))
        {
            throw new ServiceException("请选择统计月份（yyyy-MM）");
        }
        java.time.YearMonth ym = java.time.YearMonth.parse(month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        LocalDate today = LocalDate.now();
        LocalDate statEnd = monthEnd.isAfter(today) ? today : monthEnd;
        String start = monthStart.toString();
        String end = statEnd.isBefore(monthStart) ? monthStart.toString() : statEnd.toString();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EduTeacherCheckIn record : checkInMapper.selectByRange(start, end, teacherId))
        {
            Map<String, Object> item = new HashMap<>();
            item.put("classDate", record.getClassDate());
            item.put("checkType", "out".equals(record.getCheckType()) ? "out" : "in");
            item.put("checkInTime", record.getCheckInTime());
            item.put("campusName", record.getCampusName());
            item.put("ssid", record.getSsid());
            item.put("remark", record.getRemark());
            rows.add(item);
        }
        return success(rows);
    }

    /** 2026年法定节假日（放假日期，国办发明电〔2025〕7号） */
    private static final java.util.Set<LocalDate> HOLIDAYS_2026 = buildHolidays2026();
    /** 2026年调休上班日（周末需上班） */
    private static final java.util.Set<LocalDate> MAKEUP_2026 = buildMakeup2026();

    private static java.util.Set<LocalDate> buildHolidays2026()
    {
        java.util.Set<LocalDate> s = new java.util.HashSet<>();
        addRange(s, "2026-01-01", "2026-01-03"); // 元旦
        addRange(s, "2026-02-15", "2026-02-23"); // 春节
        addRange(s, "2026-04-04", "2026-04-06"); // 清明
        addRange(s, "2026-05-01", "2026-05-05"); // 劳动节
        addRange(s, "2026-06-19", "2026-06-21"); // 端午
        addRange(s, "2026-09-25", "2026-09-27"); // 中秋
        addRange(s, "2026-10-01", "2026-10-07"); // 国庆
        return s;
    }

    private static java.util.Set<LocalDate> buildMakeup2026()
    {
        java.util.Set<LocalDate> s = new java.util.HashSet<>();
        s.add(LocalDate.parse("2026-01-04")); // 元旦调休（周日）
        s.add(LocalDate.parse("2026-02-14")); // 春节调休（周六）
        s.add(LocalDate.parse("2026-02-28")); // 春节调休（周六）
        s.add(LocalDate.parse("2026-05-09")); // 劳动节调休（周六）
        s.add(LocalDate.parse("2026-09-20")); // 国庆调休（周日）
        s.add(LocalDate.parse("2026-10-10")); // 国庆调休（周六）
        return s;
    }

    private static void addRange(java.util.Set<LocalDate> set, String from, String to)
    {
        LocalDate a = LocalDate.parse(from);
        LocalDate b = LocalDate.parse(to);
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) set.add(d);
    }

    /** 是否应打卡工作日：调休上班的周末计为工作日；节假日不计；其余按周一至周五 */
    private boolean isWorkDay(LocalDate d)
    {
        if (d.getYear() == 2026)
        {
            if (MAKEUP_2026.contains(d)) return true;
            if (HOLIDAYS_2026.contains(d)) return false;
        }
        java.time.DayOfWeek dow = d.getDayOfWeek();
        return dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY;
    }

    /**
     * 管理端：修改打卡记录（时间/备注）
     */
    @PreAuthorize("@ss.hasAnyRoles('admin')")
    @PutMapping("/admin")
    public AjaxResult adminUpdate(@RequestBody Map<String, Object> body)
    {
        if (body == null || body.get("checkInId") == null)
        {
            throw new ServiceException("缺少打卡ID");
        }
        EduTeacherCheckIn record = new EduTeacherCheckIn();
        record.setCheckInId(toLong(body.get("checkInId")));
        String checkInTime = toText(body.get("checkInTime"));
        if (StringUtils.isNotEmpty(checkInTime))
        {
            record.setCheckInTime(parseTimestamp(checkInTime));
        }
        String remark = toText(body.get("remark"));
        if (remark != null)
        {
            record.setRemark(remark);
        }
        return toAjax(checkInMapper.update(record));
    }

    /**
     * 管理端：删除打卡记录（转为缺卡）
     */
    @PreAuthorize("@ss.hasAnyRoles('admin')")
    @DeleteMapping("/admin/{checkInId}")
    public AjaxResult adminDelete(@PathVariable("checkInId") Long checkInId)
    {
        return toAjax(checkInMapper.deleteById(checkInId));
    }

    /**
     * 管理端：手动补卡（教师+日期+类型+时间，无需WiFi）
     */
    @PreAuthorize("@ss.hasAnyRoles('admin')")
    @PostMapping("/admin")
    public AjaxResult adminManualCheckIn(@RequestBody Map<String, Object> body)
    {
        if (body == null || body.get("teacherId") == null
                || StringUtils.isEmpty(toText(body.get("classDate")))
                || StringUtils.isEmpty(toText(body.get("checkInTime"))))
        {
            throw new ServiceException("教师、日期、打卡时间均不能为空");
        }
        String type = toText(body.get("type"));
        if (!"in".equals(type) && !"out".equals(type))
        {
            throw new ServiceException("打卡类型不正确");
        }
        Long teacherId = toLong(body.get("teacherId"));
        String classDate = toText(body.get("classDate"));
        SysUser teacher = userMapper.selectUserById(teacherId);
        if (teacher == null || "2".equals(teacher.getDelFlag()))
        {
            throw new ServiceException("教师不存在");
        }
        if (checkInMapper.countByTeacherDateType(teacherId, classDate, type) > 0)
        {
            throw new ServiceException("该教师当日已有" + ("in".equals(type) ? "上班" : "下班") + "打卡记录");
        }
        String campusName = toText(body.get("campusName"));

        EduTeacherCheckIn record = new EduTeacherCheckIn();
        record.setClassDate(java.sql.Date.valueOf(LocalDate.parse(classDate)));
        record.setCheckType(type);
        record.setTeacherId(teacherId);
        record.setTeacherName(realNameOf(teacher));
        record.setCampusName(StringUtils.isEmpty(campusName) ? null : campusName);
        record.setCheckInTime(parseTimestamp(toText(body.get("checkInTime"))));
        String remark = toText(body.get("remark"));
        record.setRemark(StringUtils.isEmpty(remark) ? "教务补卡" : remark);
        record.setCreateBy(SecurityUtils.getUsername());
        record.setCreateTime(new java.util.Date());
        checkInMapper.insert(record);
        return success(record);
    }

    /** 按SSID/BSSID匹配全部校区白名单，返回命中的配置（含校区） */
    private EduWifiConfig matchWifi(String ssid, String bssid)
    {
        EduWifiConfig query = new EduWifiConfig();
        query.setStatus("0");
        List<EduWifiConfig> whitelist = wifiConfigMapper.selectList(query);
        for (EduWifiConfig wifi : whitelist)
        {
            if (StringUtils.isNotEmpty(bssid) && StringUtils.isNotEmpty(wifi.getBssid())
                    && bssid.equalsIgnoreCase(wifi.getBssid()))
            {
                return wifi;
            }
            if (ssid.equals(wifi.getSsid()))
            {
                return wifi;
            }
        }
        return null;
    }

    private java.sql.Timestamp parseTimestamp(String text)
    {
        try
        {
            return java.sql.Timestamp.valueOf(text);
        }
        catch (IllegalArgumentException e)
        {
            throw new ServiceException("打卡时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private Long toLong(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        try
        {
            return Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("ID格式不正确");
        }
    }

    private String toText(Object value)
    {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 教师展示名：真名(user_name) 优先，空则退回昵称。
     * teacher_name 只是冗余展示字段，归属判定一律用 teacher_id。
     */
    private static String realNameOf(com.ruoyi.common.core.domain.entity.SysUser user)
    {
        if (user == null) return "";
        String real = StringUtils.trimToEmpty(user.getUserName());
        return real.isEmpty() ? StringUtils.trimToEmpty(user.getNickName()) : real;
    }

    /**
     * 同上，但入参是 selectTeacherUsers() 返回的行：
     * 该查询同时给了 teacherName(昵称) 与 realName(真名)，展示一律取真名。
     */
    private static Object displayNameOf(Map<String, Object> row)
    {
        if (row == null) return "";
        Object real = row.get("realName");
        if (real != null && !String.valueOf(real).isBlank() && !"null".equals(String.valueOf(real))) return real;
        return row.get("teacherName");
    }
}
