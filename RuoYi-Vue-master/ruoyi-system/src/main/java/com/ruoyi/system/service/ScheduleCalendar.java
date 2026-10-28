package com.ruoyi.system.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 按已有日期模式展开排课；日期或每周星期不明确时返回空，不能用于推断空闲。 */
public final class ScheduleCalendar {
    private ScheduleCalendar() {}

    /** 每周固定星期上课（春秋季：按 periodName 的星期递推） */
    public static final String WEEKLY = "WEEKLY";
    /** 上5休1（寒暑假：连续 5 天上课、第 6 天休息，循环递推） */
    public static final String DAILY_5_1 = "DAILY_5_1";

    public static int weekday(String period) {
        return List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").indexOf(period == null ? "" : period) + 1;
    }

    /**
     * 学期 → 上课模式：寒暑假「上5休1」，春秋季「每周固定星期」。无法判定返回 null（不猜测）。
     * 导入时按学期自动套用，避免人工逐条选择。
     */
    public static String patternForTerm(String termName) {
        String term = termName == null ? "" : termName.trim();
        if (term.isEmpty()) return null;
        if (term.contains("寒") || term.contains("暑")) return DAILY_5_1;
        if (term.contains("春") || term.contains("秋")) return WEEKLY;
        return null;
    }

    /**
     * 该「学期 + 期次/上课日」能否套用日期规则。
     * 返回 null 表示不能：学期无法判定（如「未识别学期」），
     * 或每周模式却拿不到星期（春秋季课表的期次必须是周一~周日）。
     */
    public static String patternFor(String termName, String periodName) {
        String pattern = patternForTerm(termName);
        if (pattern == null) return null;
        if (WEEKLY.equals(pattern) && weekday(periodName) == 0) return null;
        return pattern;
    }
    public static SortedSet<LocalDate> dates(LocalDate start, LocalDate end, String pattern,
                                            String period, List<Map<String, Object>> adjustments) {
        SortedSet<LocalDate> dates = new TreeSet<>();
        if (start == null || end == null || start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 3660) return dates;
        boolean weekend = "周六".equals(period) || "周日".equals(period);
        int weekday = weekday(period);
        if (pattern == null || pattern.isBlank()) pattern = weekend ? WEEKLY : "";
        if (!DAILY_5_1.equals(pattern) && !(WEEKLY.equals(pattern) && weekday > 0)) return dates;
        Map<LocalDate, LocalDate> moved = new HashMap<>();
        for (Map<String, Object> adjustment : adjustments) {
            LocalDate original = date(adjustment.get("originalDate"));
            Object adjustedValue = adjustment.get("adjustedDate");
            LocalDate adjusted = date(adjustedValue);
            if (original == null || (adjustedValue != null && adjusted == null) || moved.containsKey(original)) return new TreeSet<>();
            moved.put(original, adjusted);
        }
        int index = 0;
        for (LocalDate current = start; !current.isAfter(end); current = current.plusDays(1), index++) {
            boolean included = WEEKLY.equals(pattern)
                ? current.getDayOfWeek().getValue() == weekday : index % 6 < 5;
            if (!included) continue;
            LocalDate actual = moved.containsKey(current) ? moved.get(current) : current;
            if (actual != null) dates.add(actual);
        }
        return dates;
    }
    public static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof java.util.Date date) return date.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        try { return LocalDate.parse(String.valueOf(value).substring(0, 10)); }
        catch (RuntimeException e) { return null; }
    }
}
