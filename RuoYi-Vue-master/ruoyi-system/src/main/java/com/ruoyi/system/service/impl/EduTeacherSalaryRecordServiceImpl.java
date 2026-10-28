package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduTeacherSalaryConfig;
import com.ruoyi.system.domain.EduTeacherSalaryRecord;
import com.ruoyi.system.mapper.EduTeacherSalaryConfigMapper;
import com.ruoyi.system.mapper.EduTeacherSalaryRecordMapper;
import com.ruoyi.system.service.IEduTeacherSalaryRecordService;

/**
 * 教师薪资结算 服务实现
 *
 * 课时口径：
 * - 班课：老师提交签到且该课次实到人数（到课1/试听4/调课5）≥1，计 1 次课，时长取排课时长（默认2小时）
 * - 一对一：按实到学员人次计，每人次 1 次课，时长取排课时长（默认配置时长）
 * - 学段：初一~初三=初中，高一~高三=高中，其余=小学
 *
 * @author ruoyi
 * @date 2026-09-04
 */
@Service
public class EduTeacherSalaryRecordServiceImpl implements IEduTeacherSalaryRecordService
{
    @Autowired
    private EduTeacherSalaryRecordMapper recordMapper;

    @Autowired
    private EduTeacherSalaryConfigMapper configMapper;

    /** 五类费用下标：0小学班课 1初高班课 2小学一对一 3初中一对一 4高中一对一 */
    private static final int PRIMARY_CLASS = 0;
    private static final int JH_CLASS = 1;
    private static final int PRIMARY_1ON1 = 2;
    private static final int JUNIOR_1ON1 = 3;
    private static final int SENIOR_1ON1 = 4;

    @Override
    public List<EduTeacherSalaryRecord> selectRecordList(EduTeacherSalaryRecord query)
    {
        return recordMapper.selectRecordList(query);
    }

    @Override
    public EduTeacherSalaryRecord selectRecordById(Long recordId)
    {
        return recordMapper.selectRecordById(recordId);
    }

    @Override
    public List<EduTeacherSalaryRecord> previewMonth(String salaryMonth)
    {
        checkMonth(salaryMonth);
        List<EduTeacherSalaryRecord> list = computeMonth(salaryMonth);
        // 标注已结算状态
        for (EduTeacherSalaryRecord r : list)
        {
            EduTeacherSalaryRecord saved = recordMapper.selectRecordByTeacherMonth(r.getTeacherId(), salaryMonth);
            if (saved != null)
            {
                r.setRecordId(saved.getRecordId());
                r.setConfirmStatus(saved.getConfirmStatus());
                r.setConfirmBy(saved.getConfirmBy());
                r.setConfirmTime(saved.getConfirmTime());
            }
            else
            {
                r.setConfirmStatus("0");
            }
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int generateMonth(String salaryMonth)
    {
        checkMonth(salaryMonth);
        // 已确认结算单锁定：存在已确认记录则拒绝整月重算
        EduTeacherSalaryRecord q = new EduTeacherSalaryRecord();
        q.setSalaryMonth(salaryMonth);
        List<EduTeacherSalaryRecord> existing = recordMapper.selectRecordList(q);
        for (EduTeacherSalaryRecord e : existing)
        {
            if ("1".equals(e.getConfirmStatus()))
            {
                throw new ServiceException(salaryMonth + " 存在已确认的结算单（" + e.getTeacherName()
                        + "），请先删除已确认单据再重新生成");
            }
        }
        List<EduTeacherSalaryRecord> list = computeMonth(salaryMonth);
        String username = SecurityUtils.getUsername();
        int count = 0;
        for (EduTeacherSalaryRecord r : list)
        {
            r.setCreateBy(username);
            EduTeacherSalaryRecord saved = recordMapper.selectRecordByTeacherMonth(r.getTeacherId(), salaryMonth);
            if (saved == null)
            {
                recordMapper.insertRecord(r);
            }
            else
            {
                r.setRecordId(saved.getRecordId());
                r.setUpdateBy(username);
                // 重算覆盖统计数据，保持待确认
                r.setConfirmStatus("0");
                recordMapper.updateRecord(r);
            }
            count++;
        }
        return count;
    }

    @Override
    public int confirmRecord(Long recordId)
    {
        EduTeacherSalaryRecord r = recordMapper.selectRecordById(recordId);
        if (r == null || "2".equals(r.getDelFlag()))
        {
            throw new ServiceException("结算单不存在或已删除");
        }
        if ("1".equals(r.getConfirmStatus()))
        {
            throw new ServiceException("该结算单已确认，无需重复确认");
        }
        EduTeacherSalaryRecord up = new EduTeacherSalaryRecord();
        up.setRecordId(recordId);
        up.setConfirmStatus("1");
        up.setConfirmBy(SecurityUtils.getUsername());
        up.setConfirmTime(DateUtils.getNowDate());
        up.setUpdateBy(SecurityUtils.getUsername());
        return recordMapper.updateRecord(up);
    }

    @Override
    public int deleteRecordByIds(Long[] recordIds)
    {
        for (Long id : recordIds)
        {
            EduTeacherSalaryRecord r = recordMapper.selectRecordById(id);
            if (r != null && "1".equals(r.getConfirmStatus()))
            {
                throw new ServiceException((r.getTeacherName() == null ? "" : r.getTeacherName())
                        + " 的结算单已确认，请先取消确认再删除");
            }
        }
        return recordMapper.deleteRecordByIds(recordIds);
    }

    /**
     * 按月实时计算每个教师的课时与费用（未入库）。
     * 范围：有薪资标准或当月有签到课次的教师。
     */
    private List<EduTeacherSalaryRecord> computeMonth(String salaryMonth)
    {
        // 教师账号
        List<Map<String, Object>> teacherUsers = recordMapper.selectTeacherUsers();
        Map<Long, String> teacherNames = new HashMap<>();
        for (Map<String, Object> u : teacherUsers)
        {
            // 优先真名(realName)，老接口没返回时退回 teacherName；teacher_name 冗余字段统一存真名
            Object real = u.get("realName");
            String name = real == null || String.valueOf(real).isBlank() || "null".equals(String.valueOf(real))
                    ? String.valueOf(u.get("teacherName")) : String.valueOf(real);
            teacherNames.put(toLong(u.get("teacherId")), name);
        }
        // 薪资标准
        Map<Long, EduTeacherSalaryConfig> configMap = new HashMap<>();
        for (EduTeacherSalaryConfig c : configMapper.selectConfigList(new EduTeacherSalaryConfig()))
        {
            configMap.put(c.getTeacherId(), c);
        }
        // 当月课次：按寒假 / 春季排课的起止日期与上课模式推算，不再依赖考勤记录
        // （暑假、秋季为历史数据，不参与薪资计算）
        Map<Long, double[]> hoursMap = new HashMap<>();   // [5] 小时
        Map<Long, long[]> countMap = new HashMap<>();     // [5] 节数
        Set<Long> involved = new HashSet<>();
        YearMonth ym = YearMonth.parse(salaryMonth);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        for (Map<String, Object> s : recordMapper.selectTermSchedules())
        {
            Long teacherId = toLong(s.get("teacherId"));
            if (teacherId == null)
            {
                continue;
            }
            LocalDate from = toLocalDate(s.get("startDate"));
            LocalDate to = toLocalDate(s.get("endDate"));
            if (from == null || to == null)
            {
                continue;
            }
            // 排课区间与本月的交集
            LocalDate begin = from.isAfter(monthStart) ? from : monthStart;
            LocalDate finish = to.isBefore(monthEnd) ? to : monthEnd;
            if (begin.isAfter(finish))
            {
                continue;
            }
            int times = sessionTimes(s, begin, finish);
            if (times <= 0)
            {
                continue;
            }
            EduTeacherSalaryConfig cfg = configMap.get(teacherId);
            double defHours = cfg != null && cfg.getClassHoursPerSession() != null
                    ? cfg.getClassHoursPerSession().doubleValue() : 2.0;
            double sessionHours = sessionHours(s.get("startTime"), s.get("endTime"), defHours);
            String classMode = s.get("classMode") == null ? "1" : String.valueOf(s.get("classMode"));
            String stage = stage(s.get("gradeName"));

            int idx;
            if ("2".equals(classMode))
            {
                idx = "junior".equals(stage) ? JUNIOR_1ON1 : ("senior".equals(stage) ? SENIOR_1ON1 : PRIMARY_1ON1);
            }
            else
            {
                idx = "primary".equals(stage) ? PRIMARY_CLASS : JH_CLASS;
            }
            involved.add(teacherId);
            hoursMap.computeIfAbsent(teacherId, k -> new double[5]);
            countMap.computeIfAbsent(teacherId, k -> new long[5]);
            hoursMap.get(teacherId)[idx] += sessionHours * times;
            countMap.get(teacherId)[idx] += times;
        }

        // 汇总：覆盖全部教师账号。未配置薪资标准、当月无签到课次的教师同样列出（金额为 0），
        // 避免薪资表因为「没有考勤」而漏人。
        Set<Long> all = new LinkedHashSet<>(teacherNames.keySet());
        all.addAll(configMap.keySet());
        all.addAll(involved);
        List<EduTeacherSalaryRecord> result = new ArrayList<>();
        for (Long teacherId : all)
        {
            EduTeacherSalaryConfig cfg = configMap.get(teacherId);
            double[] hours = hoursMap.getOrDefault(teacherId, new double[5]);
            long[] counts = countMap.getOrDefault(teacherId, new long[5]);
            BigDecimal[] rates = new BigDecimal[] {
                rate(cfg, c -> c.getRatePrimaryClass()),
                rate(cfg, c -> c.getRateJhClass()),
                rate(cfg, c -> c.getRatePrimary1on1()),
                rate(cfg, c -> c.getRateJunior1on1()),
                rate(cfg, c -> c.getRateSenior1on1())
            };
            BigDecimal[] hoursBd = new BigDecimal[5];
            BigDecimal[] amountBd = new BigDecimal[5];
            long totalLessons = 0;
            BigDecimal totalHours = BigDecimal.ZERO;
            BigDecimal lessonAmount = BigDecimal.ZERO;
            for (int i = 0; i < 5; i++)
            {
                hoursBd[i] = BigDecimal.valueOf(hours[i]).setScale(1, RoundingMode.HALF_UP);
                amountBd[i] = hoursBd[i].multiply(rates[i]).setScale(2, RoundingMode.HALF_UP);
                totalLessons += counts[i];
                totalHours = totalHours.add(hoursBd[i]);
                lessonAmount = lessonAmount.add(amountBd[i]);
            }
            BigDecimal base = cfg != null && cfg.getBaseSalary() != null ? cfg.getBaseSalary() : BigDecimal.ZERO;
            // 提成：薪资标准中直接配置的固定金额（元/月），不随课时费浮动
            BigDecimal commission = cfg != null && cfg.getCommissionAmount() != null
                    ? cfg.getCommissionAmount().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            EduTeacherSalaryRecord r = new EduTeacherSalaryRecord();
            r.setTeacherId(teacherId);
            r.setTeacherName(teacherNames.getOrDefault(teacherId, cfg != null ? cfg.getTeacherName() : ("ID:" + teacherId)));
            r.setSalaryMonth(salaryMonth);
            r.setBaseSalary(base.setScale(2, RoundingMode.HALF_UP));
            r.setPrimaryClassCount(counts[PRIMARY_CLASS]);
            r.setPrimaryClassHours(hoursBd[PRIMARY_CLASS]);
            r.setPrimaryClassAmount(amountBd[PRIMARY_CLASS]);
            r.setJhClassCount(counts[JH_CLASS]);
            r.setJhClassHours(hoursBd[JH_CLASS]);
            r.setJhClassAmount(amountBd[JH_CLASS]);
            r.setPrimary1on1Count(counts[PRIMARY_1ON1]);
            r.setPrimary1on1Hours(hoursBd[PRIMARY_1ON1]);
            r.setPrimary1on1Amount(amountBd[PRIMARY_1ON1]);
            r.setJunior1on1Count(counts[JUNIOR_1ON1]);
            r.setJunior1on1Hours(hoursBd[JUNIOR_1ON1]);
            r.setJunior1on1Amount(amountBd[JUNIOR_1ON1]);
            r.setSenior1on1Count(counts[SENIOR_1ON1]);
            r.setSenior1on1Hours(hoursBd[SENIOR_1ON1]);
            r.setSenior1on1Amount(amountBd[SENIOR_1ON1]);
            r.setTotalLessons(totalLessons);
            r.setTotalHours(totalHours.setScale(1, RoundingMode.HALF_UP));
            r.setLessonAmount(lessonAmount);
            r.setCommissionAmount(commission);
            r.setTotalAmount(base.add(lessonAmount).add(commission).setScale(2, RoundingMode.HALF_UP));
            r.setDelFlag("0");
            result.add(r);
        }
        result.sort((a, b) -> a.getTeacherId().compareTo(b.getTeacherId()));
        return result;
    }

    /**
     * 排课在 [begin, finish] 内的上课次数。
     * WEEKLY：period_name 指明「周六」「周日」，区间内该星期几出现的天数；
     * DAILY_5_1：从排课 start_date 起「上 5 天休 1 天」循环，落在区间内的上课天数。
     */
    private int sessionTimes(Map<String, Object> s, LocalDate begin, LocalDate finish)
    {
        String pattern = s.get("classPattern") == null ? "" : String.valueOf(s.get("classPattern"));
        if ("WEEKLY".equals(pattern))
        {
            DayOfWeek dow = weekDay(String.valueOf(s.get("periodName")));
            if (dow == null)
            {
                return 0;
            }
            int n = 0;
            for (LocalDate d = begin; !d.isAfter(finish); d = d.plusDays(1))
            {
                if (d.getDayOfWeek() == dow)
                {
                    n++;
                }
            }
            return n;
        }
        if ("DAILY_5_1".equals(pattern))
        {
            LocalDate from = toLocalDate(s.get("startDate"));
            if (from == null)
            {
                return 0;
            }
            int n = 0;
            for (LocalDate d = begin; !d.isAfter(finish); d = d.plusDays(1))
            {
                // 每 6 天为一轮：第 1~5 天上课，第 6 天休息
                if (ChronoUnit.DAYS.between(from, d) % 6 != 5)
                {
                    n++;
                }
            }
            return n;
        }
        return 0;
    }

    /** 「周六」「周日」等中文星期标签转 DayOfWeek */
    private DayOfWeek weekDay(String periodName)
    {
        if (periodName == null)
        {
            return null;
        }
        switch (periodName.trim())
        {
            case "周一": return DayOfWeek.MONDAY;
            case "周二": return DayOfWeek.TUESDAY;
            case "周三": return DayOfWeek.WEDNESDAY;
            case "周四": return DayOfWeek.THURSDAY;
            case "周五": return DayOfWeek.FRIDAY;
            case "周六": return DayOfWeek.SATURDAY;
            case "周日":
            case "周天": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    /** 学段：初一~初三=junior，高一~高三=senior，其余=primary */
    private String stage(Object gradeName)
    {
        String g = gradeName == null ? "" : String.valueOf(gradeName).trim();
        if ("初一".equals(g) || "初二".equals(g) || "初三".equals(g))
        {
            return "junior";
        }
        if ("高一".equals(g) || "高二".equals(g) || "高三".equals(g))
        {
            return "senior";
        }
        return "primary";
    }

    private LocalDate toLocalDate(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof LocalDate)
        {
            return (LocalDate) o;
        }
        if (o instanceof java.sql.Date)
        {
            return ((java.sql.Date) o).toLocalDate();
        }
        if (o instanceof Date)
        {
            return ((Date) o).toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        String v = String.valueOf(o).trim();
        return v.length() < 10 ? null : LocalDate.parse(v.substring(0, 10));
    }

    /** 课次时长（小时）：优先取排课起止时间差，异常时用默认时长 */
    private double sessionHours(Object startObj, Object endObj, double defaultHours)
    {
        if (startObj instanceof Date && endObj instanceof Date)
        {
            long ms = ((Date) endObj).getTime() - ((Date) startObj).getTime();
            double h = ms / 3600000.0;
            if (h > 0 && h <= 6)
            {
                return h;
            }
        }
        return defaultHours;
    }

    private BigDecimal rate(EduTeacherSalaryConfig cfg, java.util.function.Function<EduTeacherSalaryConfig, BigDecimal> getter)
    {
        BigDecimal v = cfg == null ? null : getter.apply(cfg);
        return v == null ? BigDecimal.ZERO : v;
    }

    private Long toLong(Object o)
    {
        if (o == null)
        {
            return null;
        }
        if (o instanceof Number)
        {
            return ((Number) o).longValue();
        }
        return Long.valueOf(String.valueOf(o));
    }

    private void checkMonth(String salaryMonth)
    {
        if (salaryMonth == null || !salaryMonth.matches("^\\d{4}-\\d{2}$"))
        {
            throw new ServiceException("月份格式应为 yyyy-MM");
        }
    }
}
