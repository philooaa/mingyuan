package com.ruoyi.system.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 课表导入的「学期 → 上课模式」递推规则，以及具体日期展开、停课（取消而非顺延）的纯逻辑验证。
 * ScheduleCalendar 是无依赖的静态工具类，这里不连数据库。
 */
public class ScheduleDateRuleTest {

    // ---------- 学期 → 上课模式 ----------

    @Test
    void winterAndSummerUseFiveOnOneOff() {
        assertEquals(ScheduleCalendar.DAILY_5_1, ScheduleCalendar.patternForTerm("寒假"));
        assertEquals(ScheduleCalendar.DAILY_5_1, ScheduleCalendar.patternForTerm("暑假"));
        assertEquals(ScheduleCalendar.DAILY_5_1, ScheduleCalendar.patternForTerm("2026年寒假班"));
    }

    @Test
    void springAndAutumnUseWeekly() {
        assertEquals(ScheduleCalendar.WEEKLY, ScheduleCalendar.patternForTerm("春季"));
        assertEquals(ScheduleCalendar.WEEKLY, ScheduleCalendar.patternForTerm("秋季"));
        assertEquals(ScheduleCalendar.WEEKLY, ScheduleCalendar.patternForTerm("2026秋季"));
    }

    @Test
    void unknownTermIsNotGuessed() {
        assertNull(ScheduleCalendar.patternForTerm(null));
        assertNull(ScheduleCalendar.patternForTerm(""));
        assertNull(ScheduleCalendar.patternForTerm("   "));
        assertNull(ScheduleCalendar.patternForTerm("未识别学期"));
    }

    // ---------- 学期 + 期次 → 能否套用规则 ----------

    @Test
    void springAutumnNeedAConcreteWeekday() {
        assertEquals(ScheduleCalendar.WEEKLY, ScheduleCalendar.patternFor("秋季", "周六"));
        assertEquals(ScheduleCalendar.WEEKLY, ScheduleCalendar.patternFor("春季", "周日"));
        // 春秋季课表按「周六课表 / 周日课表」分类；期次不是星期就无法递推，返回 null（保持待排）
        assertNull(ScheduleCalendar.patternFor("秋季", "一期"));
        assertNull(ScheduleCalendar.patternFor("春季", "未识别期次"));
    }

    @Test
    void winterSummerIgnorePeriodName() {
        assertEquals(ScheduleCalendar.DAILY_5_1, ScheduleCalendar.patternFor("暑假", "一期"));
        assertEquals(ScheduleCalendar.DAILY_5_1, ScheduleCalendar.patternFor("寒假", "二期"));
        assertEquals(ScheduleCalendar.DAILY_5_1, ScheduleCalendar.patternFor("暑假", "周六"));
    }

    @Test
    void unknownTermCannotBeScheduled() {
        assertNull(ScheduleCalendar.patternFor("未识别学期", "周六"));
    }

    // ---------- 具体日期展开 ----------

    @Test
    void weeklyExpandsToEveryMatchingWeekday() {
        // 2026-03-02 是周一
        LocalDate start = LocalDate.of(2026, 3, 2);
        LocalDate end = LocalDate.of(2026, 3, 31);
        var dates = ScheduleCalendar.dates(start, end, ScheduleCalendar.WEEKLY, "周六", List.of());
        assertEquals(List.of(
                LocalDate.of(2026, 3, 7),
                LocalDate.of(2026, 3, 14),
                LocalDate.of(2026, 3, 21),
                LocalDate.of(2026, 3, 28)), new ArrayList<>(dates));
        dates.forEach(d -> assertEquals(DayOfWeek.SATURDAY, d.getDayOfWeek()));
    }

    @Test
    void fiveOnOneOffRepeatsEverySixDays() {
        LocalDate start = LocalDate.of(2026, 1, 1);   // 周四
        LocalDate end = LocalDate.of(2026, 1, 12);    // 共 12 天
        var dates = ScheduleCalendar.dates(start, end, ScheduleCalendar.DAILY_5_1, "一期", List.of());
        // index%6<5 上课：0-4 上、5 休、6-10 上、11 休
        assertEquals(List.of(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3),
                LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 8), LocalDate.of(2026, 1, 9),
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11)), new ArrayList<>(dates));
    }

    @Test
    void missingOrReversedRangeYieldsNothing() {
        LocalDate d = LocalDate.of(2026, 3, 2);
        assertTrue(ScheduleCalendar.dates(null, d, ScheduleCalendar.WEEKLY, "周六", List.of()).isEmpty());
        assertTrue(ScheduleCalendar.dates(d, null, ScheduleCalendar.WEEKLY, "周六", List.of()).isEmpty());
        assertTrue(ScheduleCalendar.dates(d, d.minusDays(1), ScheduleCalendar.WEEKLY, "周六", List.of()).isEmpty());
        // 每周模式拿不到星期 → 不猜测，返回空
        assertTrue(ScheduleCalendar.dates(d, d.plusDays(30), ScheduleCalendar.WEEKLY, "一期", List.of()).isEmpty());
    }

    // ---------- 停课 = 取消而非顺延 ----------

    @Test
    void cancelRemovesThatDayWithoutPostponing() {
        LocalDate start = LocalDate.of(2026, 3, 2);
        LocalDate end = LocalDate.of(2026, 3, 31);
        // 停掉 2026-03-14（国庆/清明之类的冲课日），adjustedDate 留空
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> cancel = new HashMap<>();
        cancel.put("originalDate", java.sql.Date.valueOf(LocalDate.of(2026, 3, 14)));
        cancel.put("adjustedDate", null);
        changes.add(cancel);

        var dates = ScheduleCalendar.dates(start, end, ScheduleCalendar.WEEKLY, "周六", changes);
        assertEquals(List.of(
                LocalDate.of(2026, 3, 7),
                LocalDate.of(2026, 3, 21),
                LocalDate.of(2026, 3, 28)), new ArrayList<>(dates));
        // 关键：取消不产生新的日期，总课次由 4 减为 3（不顺延）
        assertFalse(dates.contains(LocalDate.of(2026, 3, 14)));
        assertEquals(3, dates.size());
    }

    @Test
    void movedDateReplacesOriginal() {
        LocalDate start = LocalDate.of(2026, 3, 2);
        LocalDate end = LocalDate.of(2026, 3, 31);
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> move = new HashMap<>();
        move.put("originalDate", java.sql.Date.valueOf(LocalDate.of(2026, 3, 14)));
        move.put("adjustedDate", java.sql.Date.valueOf(LocalDate.of(2026, 3, 15)));
        changes.add(move);

        var dates = ScheduleCalendar.dates(start, end, ScheduleCalendar.WEEKLY, "周六", changes);
        assertTrue(dates.contains(LocalDate.of(2026, 3, 15)));
        assertFalse(dates.contains(LocalDate.of(2026, 3, 14)));
        assertEquals(4, dates.size()); // 顺延：总数不变
    }

    @Test
    void duplicateOrBrokenAdjustmentBlocksExpansion() {
        LocalDate start = LocalDate.of(2026, 3, 2);
        LocalDate end = LocalDate.of(2026, 3, 31);
        // 同一课次两条调课记录 → 数据异常，返回空而不是给出可能错误的日期
        List<Map<String, Object>> dup = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> a = new HashMap<>();
            a.put("originalDate", java.sql.Date.valueOf(LocalDate.of(2026, 3, 14)));
            a.put("adjustedDate", null);
            dup.add(a);
        }
        assertTrue(ScheduleCalendar.dates(start, end, ScheduleCalendar.WEEKLY, "周六", dup).isEmpty());

        // 调整后日期不是合法日期（非空但解析失败）→ 同样返回空
        List<Map<String, Object>> broken = new ArrayList<>();
        Map<String, Object> bad = new HashMap<>();
        bad.put("originalDate", java.sql.Date.valueOf(LocalDate.of(2026, 3, 14)));
        bad.put("adjustedDate", "not-a-date");
        broken.add(bad);
        assertTrue(ScheduleCalendar.dates(start, end, ScheduleCalendar.WEEKLY, "周六", broken).isEmpty());
    }

    // ---------- 导入规则与展开结果保持一致 ----------

    @Test
    void importedPatternAlwaysProducesTheAdvertisedRule() {
        // 春秋季：写入 WEEKLY 后展开出来的日期，必须全部落在该期次对应的星期上
        for (String period : List.of("周六", "周日")) {
            String pattern = ScheduleCalendar.patternFor("秋季", period);
            assertNotNull(pattern, period + " 应能套用每周规则");
            var dates = ScheduleCalendar.dates(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 31), pattern, period, List.of());
            assertFalse(dates.isEmpty());
            int expected = ScheduleCalendar.weekday(period);
            dates.forEach(d -> assertEquals(expected, d.getDayOfWeek().getValue()));
        }
        // 寒暑假：写入 DAILY_5_1 后，任何连续 6 天里恰好上 5 天
        String pattern = ScheduleCalendar.patternFor("寒假", "一期");
        assertEquals(ScheduleCalendar.DAILY_5_1, pattern);
        var dates = ScheduleCalendar.dates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6), pattern, "一期", List.of());
        assertEquals(5, dates.size());
    }
}
