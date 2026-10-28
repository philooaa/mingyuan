package com.ruoyi.system.service.impl;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.mapper.EduScheduleAdjustmentMapper;
import com.ruoyi.system.domain.EduScheduleAdjustment;
import com.ruoyi.system.domain.EduScheduleAdjustmentBatch;
import com.ruoyi.system.service.IEduScheduleAdjustmentService;

/**
 * 排课调课记录Service业务层处理
 *
 * @author ruoyi
 * @date 2026-08-26
 */
@Service
public class EduScheduleAdjustmentServiceImpl implements IEduScheduleAdjustmentService
{
    @Autowired
    private EduScheduleAdjustmentMapper eduScheduleAdjustmentMapper;
    @Autowired private com.ruoyi.system.mapper.EduCourseScheduleMapper singleSchedules;
    @Autowired private com.ruoyi.system.shop.ShopRepository singleDb;
    @Autowired private com.ruoyi.system.service.ScheduleAvailabilityService availability;

    private void protectSingle(Long scheduleId) {
        var before = singleSchedules.selectEduCourseScheduleByScheduleId(scheduleId);
        if (before == null) throw new ServiceException("排课不存在");
        availability.lockResources(List.of(before));
        var schedule = singleSchedules.lockSchedule(scheduleId);
        if (!java.util.Objects.equals(before.getTeacherId(),schedule.getTeacherId()) || !java.util.Objects.equals(before.getClassroomId(),schedule.getClassroomId())) throw new ServiceException("排课已调整，请刷新");
        if (schedule != null && "2".equals(schedule.getClassMode()) && !singleDb.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND class_date IS NOT NULL FOR UPDATE",scheduleId).isEmpty()) throw new ServiceException("一对一已有按日期报名，不能通过批量调课改变原课次；请先核对处理历史");
    }
    private void checkResult(Long scheduleId) { availability.check(singleSchedules.lockSchedule(scheduleId)); }
    private void validateChange(EduScheduleAdjustment change,Long excludeId) {
        var schedule=singleSchedules.lockSchedule(change.getScheduleId());
        LocalDate original=com.ruoyi.system.service.ScheduleCalendar.date(change.getOriginalDate());
        if(original == null || !availability.dates(schedule,List.of()).contains(original)) throw new ServiceException("原日期不是该课程的有效课次");
        // 同一课次只能有一条记录：该课自己的单条记录，或管整天的日期级记录都算
        if(!singleDb.rows("SELECT adjustment_id FROM edu_schedule_adjustment WHERE original_date=? AND adjustment_id<>? "
                        + "AND (schedule_id=? OR (schedule_id=0 AND term_name=? AND period_name=?)) FOR UPDATE",
                change.getOriginalDate(), excludeId == null ? -1 : excludeId, change.getScheduleId(),
                schedule == null ? null : schedule.getTermName(), schedule == null ? null : schedule.getPeriodName()).isEmpty())
            throw new ServiceException("该课次已有调课记录，请修改已有记录");
        // 结课日期之后不允许有课：调课只能在本课程的起止日期内换日期，停课（调整后日期为空）不受此限
        LocalDate adjusted = change.getAdjustedDate() == null ? null : com.ruoyi.system.service.ScheduleCalendar.date(change.getAdjustedDate());
        LocalDate endDate = schedule == null ? null : com.ruoyi.system.service.ScheduleCalendar.date(schedule.getEndDate());
        if(adjusted != null && endDate != null && adjusted.isAfter(endDate)) throw new ServiceException("调课后的日期 "+adjusted+" 晚于结课日期 "+endDate+"，结课之后不能排课；如确实要上，请先改排课的结课日期");
    }

    /**
     * 查询排课调课记录
     *
     * @param adjustmentId 排课调课记录主键
     * @return 排课调课记录
     */
    @Override
    public EduScheduleAdjustment selectEduScheduleAdjustmentByAdjustmentId(Long adjustmentId)
    {
        return eduScheduleAdjustmentMapper.selectEduScheduleAdjustmentByAdjustmentId(adjustmentId);
    }

    /**
     * 查询排课调课记录列表
     *
     * @param eduScheduleAdjustment 排课调课记录
     * @return 排课调课记录
     */
    @Override
    public List<EduScheduleAdjustment> selectEduScheduleAdjustmentList(EduScheduleAdjustment eduScheduleAdjustment)
    {
        return eduScheduleAdjustmentMapper.selectEduScheduleAdjustmentList(eduScheduleAdjustment);
    }

    /**
     * 新增排课调课记录
     *
     * @param eduScheduleAdjustment 排课调课记录
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public int insertEduScheduleAdjustment(EduScheduleAdjustment eduScheduleAdjustment)
    {
        protectSingle(eduScheduleAdjustment.getScheduleId());
        validateChange(eduScheduleAdjustment,null);
        eduScheduleAdjustment.setCreateTime(DateUtils.getNowDate());
        int count = eduScheduleAdjustmentMapper.insertEduScheduleAdjustment(eduScheduleAdjustment);
        checkResult(eduScheduleAdjustment.getScheduleId());
        return count;
    }

    /**
     * 修改排课调课记录
     *
     * @param eduScheduleAdjustment 排课调课记录
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public int updateEduScheduleAdjustment(EduScheduleAdjustment eduScheduleAdjustment)
    {
        var current = eduScheduleAdjustmentMapper.selectEduScheduleAdjustmentByAdjustmentId(eduScheduleAdjustment.getAdjustmentId());
        if (current == null) throw new ServiceException("调课记录不存在");
        if (current.getScheduleId() != null && current.getScheduleId() == 0L)
        {
            throw new ServiceException("这条记录管该期次当天全部课程，请先删除再重新调课");
        }
        protectSingle(current.getScheduleId());
        if (eduScheduleAdjustment.getScheduleId() != null && !eduScheduleAdjustment.getScheduleId().equals(current.getScheduleId())) throw new ServiceException("调课记录不能改绑其他排课");
        eduScheduleAdjustment.setScheduleId(current.getScheduleId());
        if(eduScheduleAdjustment.getOriginalDate() == null) eduScheduleAdjustment.setOriginalDate(current.getOriginalDate());
        validateChange(eduScheduleAdjustment,current.getAdjustmentId());
        eduScheduleAdjustment.setUpdateTime(DateUtils.getNowDate());
        int count = eduScheduleAdjustmentMapper.updateEduScheduleAdjustment(eduScheduleAdjustment);
        checkResult(current.getScheduleId());
        return count;
    }

    /**
     * 批量删除排课调课记录
     *
     * @param adjustmentIds 需要删除的排课调课记录主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public int deleteEduScheduleAdjustmentByIds(Long[] adjustmentIds)
    {
        Set<Long> schedules = new java.util.TreeSet<>();
        for (Long id : java.util.Arrays.stream(adjustmentIds).distinct().sorted().toList())
        {
            var record = eduScheduleAdjustmentMapper.selectEduScheduleAdjustmentByAdjustmentId(id);
            if (record == null) continue;
            if (record.getScheduleId() != null && record.getScheduleId() == 0L)
            {
                // 日期级记录：影响该学期期次下的全部排课，都要重新校验
                for (var row : singleDb.rows("SELECT schedule_id FROM edu_course_schedule WHERE term_name=? AND period_name=? AND del_flag='0'",
                        record.getTermName(), record.getPeriodName()))
                {
                    // ShopRepository 会把以 Id 结尾的列转成字符串，这里统一按字符串再转数字
                    Object value = row.get("scheduleId");
                    if (value != null) schedules.add(Long.valueOf(String.valueOf(value)));
                }
            }
            else
            {
                schedules.add(record.getScheduleId());
            }
        }
        availability.lockResources(schedules.stream().map(singleSchedules::selectEduCourseScheduleByScheduleId).toList());
        schedules.forEach(this::protectSingle);
        int count = eduScheduleAdjustmentMapper.deleteEduScheduleAdjustmentByIds(adjustmentIds);
        schedules.forEach(this::checkResult);
        return count;
    }

    /**
     * 删除排课调课记录信息
     *
     * @param adjustmentId 排课调课记录主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public int deleteEduScheduleAdjustmentByAdjustmentId(Long adjustmentId)
    {
        return deleteEduScheduleAdjustmentByIds(new Long[] {adjustmentId});
    }

    /**
     * 批量调课：按学期+期次（可叠加年级/科目筛选）把原日期范围内的全部上课日
     * 统一调整到新日期或停课，逐排课×逐上课日生成调课记录
     *
     * @param batch 批量调课参数
     * @return 生成的调课记录条数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAdjust(EduScheduleAdjustmentBatch batch)
    {
        if (batch == null || StringUtils.isEmpty(batch.getTermName()) || StringUtils.isEmpty(batch.getPeriodName()))
        {
            throw new ServiceException("请选择学期和期次");
        }
        if (batch.getOriginalStartDate() == null || batch.getOriginalEndDate() == null)
        {
            throw new ServiceException("请选择原上课日期范围");
        }
        if (batch.getOriginalStartDate().after(batch.getOriginalEndDate()))
        {
            throw new ServiceException("原上课日期范围不正确：开始日期晚于结束日期");
        }
        String mode = batch.getAdjustMode();
        if (!"MOVE_TO".equals(mode) && !"POSTPONE".equals(mode) && !"CANCEL".equals(mode))
        {
            throw new ServiceException("请选择调整方式");
        }
        if ("MOVE_TO".equals(mode) && batch.getTargetDate() == null)
        {
            throw new ServiceException("请选择调整后的目标日期");
        }
        if ("POSTPONE".equals(mode) && (batch.getOffsetDays() == null || batch.getOffsetDays() < 1))
        {
            throw new ServiceException("请填写顺延天数（至少1天）");
        }

        List<Map<String, Object>> schedules = eduScheduleAdjustmentMapper.selectSchedulesForBatch(
                batch.getTermName(), batch.getPeriodName(), batch.getGradeName(), batch.getSubjectName());
        if (schedules == null || schedules.isEmpty())
        {
            throw new ServiceException("未找到符合条件的排课，请检查学期/期次/年级/科目");
        }

        LocalDate rangeStart = toLocalDate(batch.getOriginalStartDate());
        LocalDate rangeEnd = toLocalDate(batch.getOriginalEndDate());
        String username = SecurityUtils.getUsername();
        Date now = DateUtils.getNowDate();
        int count = 0;
        availability.lockResources(schedules.stream().map(s -> singleSchedules.selectEduCourseScheduleByScheduleId(((Number)s.get("scheduleId")).longValue())).toList());

        LocalDate target = "MOVE_TO".equals(mode) ? toLocalDate(batch.getTargetDate()) : null;
        // 不带年级/科目筛选时，调课就是「整天整期次」的操作，可以按日期只记一条
        boolean termLevel = StringUtils.isEmpty(batch.getGradeName()) && StringUtils.isEmpty(batch.getSubjectName());

        // 第一遍：算出每个排课的「原上课日 → 调整后日期」计划，并做结课日期校验
        Map<Long, Set<LocalDate>> classDatesMap = new LinkedHashMap<>();
        Map<Long, TreeMap<LocalDate, Date>> plans = new LinkedHashMap<>();
        for (Map<String, Object> s : schedules)
        {
            Long scheduleId = ((Number) s.get("scheduleId")).longValue();
            protectSingle(scheduleId);
            Set<LocalDate> classDates = computeClassDates(
                    toLocalDate((Date) s.get("startDate")),
                    toLocalDate((Date) s.get("endDate")),
                    (String) s.get("classPattern"),
                    (String) s.get("periodName"));
            if (classDates.isEmpty())
            {
                continue;
            }
            classDatesMap.put(scheduleId, classDates);
            // 已被调过的原日期自动跳过，避免重复调课
            Set<LocalDate> alreadyAdjusted = eduScheduleAdjustmentMapper.selectAdjustedOriginalDates(scheduleId)
                    .stream().map(this::toLocalDate).collect(Collectors.toSet());
            List<LocalDate> affected = classDates.stream()
                    .filter(d -> !d.isBefore(rangeStart) && !d.isAfter(rangeEnd))
                    .filter(d -> !alreadyAdjusted.contains(d))
                    .sorted()
                    .collect(Collectors.toList());
            if (affected.isEmpty())
            {
                continue;
            }
            LocalDate first = affected.get(0);
            // 结课日期之后不允许有课：先算出这次调完的最后一天，超出结课日期就整批拒绝（事务会回滚）
            LocalDate lastDay = affected.get(affected.size() - 1);
            LocalDate lastAdjusted = "POSTPONE".equals(mode) ? lastDay.plusDays(batch.getOffsetDays())
                    : ("CANCEL".equals(mode) ? null : target.plusDays(ChronoUnit.DAYS.between(first, lastDay)));
            LocalDate endDate = s.get("endDate") == null ? null : toLocalDate((Date) s.get("endDate"));
            if (lastAdjusted != null && endDate != null && lastAdjusted.isAfter(endDate))
            {
                throw new ServiceException("调课后的最后一天 " + lastAdjusted + " 晚于结课日期 " + endDate
                        + "，结课之后不能排课；如确实要上，请先把这批排课的结课日期改到 " + lastAdjusted);
            }
            TreeMap<LocalDate, Date> plan = new TreeMap<>();
            for (LocalDate d : affected)
            {
                Date adjusted = "CANCEL".equals(mode) ? null
                        : ("POSTPONE".equals(mode) ? toDate(d.plusDays(batch.getOffsetDays()))
                                : toDate(target.plusDays(ChronoUnit.DAYS.between(first, d))));
                plan.put(d, adjusted);
            }
            plans.put(scheduleId, plan);
        }

        if (plans.isEmpty())
        {
            throw new ServiceException("日期范围内没有需要调整的上课日（可能均已被调整过）");
        }

        // 第二遍：落库。能整期次统一的日期合并成一条（schedule_id = 0），其余仍按排课单独记录
        Set<LocalDate> dateLevel = new HashSet<>();
        if (termLevel)
        {
            Map<LocalDate, Date> merged = new HashMap<>();
            for (TreeMap<LocalDate, Date> plan : plans.values())
            {
                plan.forEach(merged::putIfAbsent);
            }
            for (Map.Entry<LocalDate, Date> e : merged.entrySet())
            {
                LocalDate d = e.getKey();
                // 该日期必须对本期次所有排课都是上课日，且没有任何一门课单独调过这一天
                boolean allCourses = classDatesMap.size() == schedules.size()
                        && classDatesMap.values().stream().allMatch(set -> set.contains(d));
                boolean noSingleRecord = singleDb.rows(
                        "SELECT adjustment_id FROM edu_schedule_adjustment WHERE schedule_id<>0 AND term_name=? AND period_name=? AND original_date=? FOR UPDATE",
                        batch.getTermName(), batch.getPeriodName(), java.sql.Date.valueOf(d)).isEmpty();
                if (!allCourses || !noSingleRecord)
                {
                    continue;
                }
                count += insertRecord(0L, batch.getTermName(), batch.getPeriodName(), d, e.getValue(),
                        schedules.size(), batch.getReason(), username, now);
                dateLevel.add(d);
            }
        }
        for (Map.Entry<Long, TreeMap<LocalDate, Date>> en : plans.entrySet())
        {
            for (Map.Entry<LocalDate, Date> e : en.getValue().entrySet())
            {
                if (dateLevel.contains(e.getKey()))
                {
                    continue;
                }
                count += insertRecord(en.getKey(), batch.getTermName(), batch.getPeriodName(), e.getKey(), e.getValue(),
                        null, batch.getReason(), username, now);
            }
        }

        for (var s : schedules) checkResult(((Number)s.get("scheduleId")).longValue());
        return count;
    }

    /** 写一条调课记录；scheduleId=0 表示整期次的日期级记录，courseCount 记当天课程数 */
    private int insertRecord(Long scheduleId, String termName, String periodName, LocalDate originalDate,
                             Date adjustedDate, Integer courseCount, String reason, String username, Date now)
    {
        EduScheduleAdjustment adj = new EduScheduleAdjustment();
        adj.setScheduleId(scheduleId);
        adj.setTermName(termName);
        adj.setPeriodName(periodName);
        adj.setOriginalDate(toDate(originalDate));
        adj.setAdjustedDate(adjustedDate);
        adj.setCourseCount(courseCount);
        adj.setReason(reason);
        adj.setCreateBy(username);
        adj.setCreateTime(now);
        return eduScheduleAdjustmentMapper.insertEduScheduleAdjustment(adj);
    }

    /**
     * 计算某排课在开课~结课日期内的全部上课日
     * 规则与小程序端保持一致：WEEKLY 按明确的周一至周日对齐；
     * 模式为空时期次为周六/周日按 WEEKLY，否则按 DAILY_5_1（上5天休1天）
     */
    private Set<LocalDate> computeClassDates(LocalDate start, LocalDate end, String classPattern, String periodName)
    {
        Set<LocalDate> dates = new HashSet<>();
        if (start == null || end == null || start.isAfter(end))
        {
            return dates;
        }
        String pattern = classPattern;
        if (StringUtils.isEmpty(pattern))
        {
            pattern = ("周六".equals(periodName) || "周日".equals(periodName)) ? "WEEKLY" : "DAILY_5_1";
        }
        if ("WEEKLY".equals(pattern))
        {
            int weekday = com.ruoyi.system.service.ScheduleCalendar.weekday(periodName);
            if (weekday == 0) return dates;
            DayOfWeek targetDay = DayOfWeek.of(weekday);
            LocalDate cur = start;
            while (!cur.isAfter(end) && cur.getDayOfWeek() != targetDay)
            {
                cur = cur.plusDays(1);
            }
            while (!cur.isAfter(end))
            {
                dates.add(cur);
                cur = cur.plusWeeks(1);
            }
        }
        else
        {
            LocalDate cur = start;
            int i = 0;
            while (!cur.isAfter(end))
            {
                if (i % 6 < 5)
                {
                    dates.add(cur);
                }
                cur = cur.plusDays(1);
                i++;
            }
        }
        return dates;
    }

    private LocalDate toLocalDate(Date date)
    {
        // 注意：MyBatis返回的DATE字段是java.sql.Date，其toInstant()会抛异常，统一改用毫秒值转换
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private Date toDate(LocalDate localDate)
    {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
