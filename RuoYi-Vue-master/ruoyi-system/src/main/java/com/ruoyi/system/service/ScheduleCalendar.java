package com.ruoyi.system.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 按已有日期模式展开排课；日期或每周星期不明确时返回空，不能用于推断空闲。 */
public final class ScheduleCalendar {
    private ScheduleCalendar() {}

    /** 每周固定星期上课（春秋季：按 periodName 的星期递推） */
    public static final String WEEKLY = "WEEKLY";
    /** 连上无假期（寒暑假：从开课日起每天上课，不安排休息日） */
    public static final String DAILY = "DAILY";
    /** 上5休1（寒暑假：连续 5 天上课、第 6 天休息，循环递推） */
    public static final String DAILY_5_1 = "DAILY_5_1";
    /** 上6休1（寒暑假：连续 6 天上课、第 7 天休息，循环递推） */
    public static final String DAILY_6_1 = "DAILY_6_1";
    /** 上5休2（寒暑假：连续 5 天上课、休息 2 天，7 天一轮） */
    public static final String DAILY_5_2 = "DAILY_5_2";

    /** 全部合法的上课模式，按前端下拉顺序排列 */
    public static final List<String> ALL = List.of(DAILY, DAILY_5_1, DAILY_6_1, DAILY_5_2, WEEKLY);

    /** 日循环类模式（寒暑假）：从开课日起按「上 N 休 M」循环递推 */
    private static final List<String> DAILY_PATTERNS = List.of(DAILY, DAILY_5_1, DAILY_6_1, DAILY_5_2);

    public static int weekday(String period) {
        return List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").indexOf(period == null ? "" : period) + 1;
    }

    /** 是否日循环模式（寒暑假那几种「上 N 休 M」） */
    public static boolean isDaily(String pattern) {
        return pattern != null && DAILY_PATTERNS.contains(pattern.trim());
    }

    public static boolean isValid(String pattern) {
        return pattern != null && ALL.contains(pattern.trim());
    }

    /** 一轮周期天数：连上无假期=1、上5休1=6、上6休1=7、上5休2=7；非日循环返回 0 */
    public static int cycle(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        switch (p) {
            case DAILY: return 1;
            case DAILY_5_1: return 6;
            case DAILY_6_1: return 7;
            case DAILY_5_2: return 7;
            default: return 0;
        }
    }

    /** 一轮里上课的天数：连上无假期=1、上5休1=5、上6休1=6、上5休2=5；非日循环返回 0 */
    public static int studyDays(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        switch (p) {
            case DAILY: return 1;
            case DAILY_5_1: return 5;
            case DAILY_6_1: return 6;
            case DAILY_5_2: return 5;
            default: return 0;
        }
    }

    /** 模式的中文名，用于导入预览与提示文案 */
    public static String label(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        switch (p) {
            case DAILY: return "连上无假期";
            case DAILY_5_1: return "上5休1";
            case DAILY_6_1: return "上6休1";
            case DAILY_5_2: return "上5休2";
            case WEEKLY: return "每周固定星期";
            default: return p;
        }
    }

    /**
     * 学期 → 默认上课模式：寒暑假「上5休1」，春秋季「每周固定星期」。无法判定返回 null（不猜测）。
     * 导入时按学期自动套用；寒暑假若要「连上无假期 / 上6休1 / 上5休2」，在导入时按期次另选即可。
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
        return patternFor(termName, periodName, null);
    }

    /**
     * 同上，但允许导入时按「学期 + 期次」指定模式（寒暑假可选 连上无假期 / 上5休1 / 上6休1 / 上5休2）。
     * 指定的模式优先；未指定或指定的模式对春秋季不适用（每周模式必须能取到星期）时回退到学期默认。
     */
    public static String patternFor(String termName, String periodName, String override) {
        String forced = override == null ? "" : override.trim();
        if (isValid(forced)) {
            if (WEEKLY.equals(forced)) return weekday(periodName) == 0 ? null : WEEKLY;
            // 日循环模式只对寒暑假开放；春秋季课表的期次是「周六/周日」，仍按每周递推
            String termPattern = patternForTerm(termName);
            if (termPattern == null) return null;
            if (!WEEKLY.equals(termPattern)) return forced;
        }
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
        boolean daily = isDaily(pattern);
        if (!daily && !(WEEKLY.equals(pattern) && weekday > 0)) return dates;
        int cycle = daily ? cycle(pattern) : 0;
        int study = daily ? studyDays(pattern) : 0;
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
            boolean included = daily
                ? (cycle <= 0 || index % cycle < study) : current.getDayOfWeek().getValue() == weekday;
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
