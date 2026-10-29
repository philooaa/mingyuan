package com.ruoyi.system.service.impl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.mapper.EduCourseScheduleMapper;
import com.ruoyi.system.mapper.MiniAppParentMapper;
import com.ruoyi.system.service.IEduCourseScheduleService;
import com.ruoyi.system.service.ScheduleCalendar;
import com.ruoyi.system.shop.ShopRepository;

/** 若依与小程序共用排课 CRUD；归属、人数与历史约束由后端维护。 */
@Service
public class EduCourseScheduleServiceImpl implements IEduCourseScheduleService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EduCourseScheduleServiceImpl.class);

    @Autowired private EduCourseScheduleMapper eduCourseScheduleMapper;
    @Autowired private MiniAppParentMapper parentMapper;
    @Autowired private ShopRepository db;
    @Autowired private com.ruoyi.system.service.OneToOneEnrollmentService singleService;
    @Autowired private com.ruoyi.system.service.ScheduleAvailabilityService availability;
    @Autowired private com.ruoyi.system.service.ISysConfigService configService;

    private boolean teacherOnly() {
        return SecurityUtils.hasRole("teacher") && !planningStaff();
    }
    private static boolean planningStaff() {
        return SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin") || SecurityUtils.hasRole("business_admin") || SecurityUtils.hasRole("education");
    }
    private static void requirePlanningStaff() { require(planningStaff(), "课程由教务或管理员统一安排，教师不能发布或修改排课"); }
    private void own(EduCourseSchedule schedule) {
        require(schedule != null, "排课不存在或已删除");
        if (teacherOnly()) require(Objects.equals(schedule.getTeacherId(), SecurityUtils.getUserId()), "无权操作其他教师的排课");
    }
    private void scope(EduCourseSchedule query) {
        // 教师身份强制只看自己的课（覆盖调用方传入的任何值）；
        // 管理员/教务保留调用方显式传入的 teacherUserId —— 这是课表页「按教师筛选」的入口。
        if (teacherOnly()) query.getParams().put("teacherUserId", SecurityUtils.getUserId());
    }
    @Override public EduCourseSchedule selectEduCourseScheduleByScheduleId(Long id) {
        EduCourseSchedule schedule = eduCourseScheduleMapper.selectEduCourseScheduleByScheduleId(id);
        own(schedule);
        return schedule;
    }
    @Override public List<EduCourseSchedule> selectEduCourseScheduleList(EduCourseSchedule query) {
        scope(query);
        return eduCourseScheduleMapper.selectEduCourseScheduleList(query);
    }
    @Override public List<Map<String, Object>> selectTermPeriodPairs() {
        return eduCourseScheduleMapper.selectTermPeriodPairs();
    }
    @Override public List<EduCourseSchedule> selectTimetableGrid(EduCourseSchedule query) {
        scope(query);
        return eduCourseScheduleMapper.selectTimetableGrid(query);
    }

    /**
     * 一次性交换两个教室在「年份 + 学期 + 期次」下的全部排课（只对调 classroom_id）。
     * 教师身份不允许做整列交换：会牵动其他教师的课，因此仅管理员/拥有编辑权限者可用（Controller 已拦截）。
     */
    @Override @Transactional(rollbackFor = Exception.class)
    public int swapClassrooms(Long courseYear, String termName, String periodName, Long classroomIdA, Long classroomIdB) {
        requirePlanningStaff();
        require(classroomIdA != null && classroomIdB != null, "请选择要交换的两个教室");
        require(!Objects.equals(classroomIdA, classroomIdB), "两个教室不能相同");
        require(!blank(termName) && !blank(periodName), "请先选择学期和期次");
        return eduCourseScheduleMapper.swapClassroom(courseYear, termName, periodName,
                classroomIdA, classroomIdB, SecurityUtils.getUsername());
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public int insertEduCourseSchedule(EduCourseSchedule schedule) {
        requirePlanningStaff();
        require(schedule != null && schedule.getScheduleId() == null, "新增排课不能指定排课ID");
        bindTeacher(schedule, null);
        if ("2".equals(schedule.getClassMode())) schedule.setSubjectName(singleService.subject(schedule.getTeacherId()));
        if (blank(schedule.getScheduleCode())) schedule.setScheduleCode("SCH" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        if (schedule.getCourseYear() == null) schedule.setCourseYear((long) LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getYear());
        if (blank(schedule.getClassMode())) schedule.setClassMode("1");
        if (blank(schedule.getStatus())) schedule.setStatus("0");
        if (blank(schedule.getRecruitStatus())) schedule.setRecruitStatus("0");
        schedule.setDelFlag("0");
        schedule.setEnrolledCount(0L);
        lockResources(List.of(schedule));
        validate(schedule);
        if ("2".equals(schedule.getClassMode())) { availability.validateSingle(schedule); schedule.setLessonLocation(""); availability.check(schedule); }
        checkConflicts(schedule);
        schedule.setCreateBy(SecurityUtils.getUsername());
        schedule.setCreateTime(DateUtils.getNowDate());
        return eduCourseScheduleMapper.insertEduCourseSchedule(schedule);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public int updateEduCourseSchedule(EduCourseSchedule input) {
        return updateEduCourseSchedule(input, false);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public int updateEduCourseSchedule(EduCourseSchedule input, boolean force) {
        requirePlanningStaff();
        require(input != null && input.getScheduleId() != null, "请选择排课");
        EduCourseSchedule before = selectEduCourseScheduleByScheduleId(input.getScheduleId());
        bindTeacher(input, before);
        if (input.getClassroomId() == null) input.setClassroomId(before.getClassroomId());
        lockResources(List.of(before, input));
        EduCourseSchedule current = eduCourseScheduleMapper.lockSchedule(input.getScheduleId());
        own(current);
        if (!force) requireNoActiveEnrollments(current, true);
        require(Objects.equals(before.getTeacherId(), current.getTeacherId()) && Objects.equals(before.getClassroomId(), current.getClassroomId()), "排课已被其他人调整，请刷新后重试");
        require(blank(input.getScheduleCode()) || input.getScheduleCode().equals(current.getScheduleCode()), "排课编码不可修改");
        if (teacherOnly() && "2".equals(current.getClassMode())) {
            require(input.getClassMode() == null || "2".equals(input.getClassMode()), "不能把一对一改成班课");
            input.setSubjectName(current.getSubjectName());
        }
        EduCourseSchedule effective = merge(current, input);
        validate(effective);
        input.setCampusName(effective.getCampusName());
        if ("2".equals(effective.getClassMode()) && (singleResourcesChanged(current,effective) || !"2".equals(current.getClassMode()) || (!"0".equals(current.getStatus()) && "0".equals(effective.getStatus())))) {
            availability.validateSingle(effective);
            input.setLessonLocation(""); effective.setLessonLocation("");
        }
        if (!force && "2".equals(current.getClassMode()) && "0".equals(current.getStatus()) && "1".equals(effective.getStatus()))
            require(db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND del_flag='0' AND enrollment_status IN ('0','1','待确认','已确认','报名成功') FOR UPDATE",current.getScheduleId()).isEmpty(),"仍有有效报名，请先处理报名后再撤回课程");
        if (hasAttendance(current.getScheduleId()) || (!force && !db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND class_date IS NOT NULL FOR UPDATE", current.getScheduleId()).isEmpty())) {
            require(Objects.equals(current.getTeacherId(), effective.getTeacherId())
                && Objects.equals(current.getClassroomId(), effective.getClassroomId())
                && Objects.equals(current.getCampusName(), effective.getCampusName())
                && Objects.equals(current.getClassMode(), effective.getClassMode())
                && Objects.equals(current.getLessonLocation(), effective.getLessonLocation())
                && Objects.equals(current.getSubjectName(), effective.getSubjectName())
                && Objects.equals(current.getStartTime(), effective.getStartTime())
                && Objects.equals(current.getEndTime(), effective.getEndTime())
                && Objects.equals(ScheduleCalendar.date(current.getStartDate()), ScheduleCalendar.date(effective.getStartDate()))
                && Objects.equals(ScheduleCalendar.date(current.getEndDate()), ScheduleCalendar.date(effective.getEndDate()))
                && Objects.equals(current.getClassPattern(), effective.getClassPattern())
                && Objects.equals(current.getPeriodName(), effective.getPeriodName()),
                "已有签到历史，不能直接改变教师、教室、授课形式或排课时间；请使用调课管理");
        }
        // 一对一只发布真实空闲时段；不能沿用班课的“教室冲突仅提示”策略。
        if ("2".equals(effective.getClassMode())) availability.check(effective);
        checkConflicts(effective);
        // 不用前端旧值覆盖报名事务维护的人数；创建信息和删除标志也不能普通编辑。
        input.setEnrolledCount(null); input.setDelFlag(null); input.setScheduleCode(null);
        input.setCreateBy(null); input.setCreateTime(null);
        input.setUpdateBy(SecurityUtils.getUsername()); input.setUpdateTime(DateUtils.getNowDate());
        return eduCourseScheduleMapper.updateEduCourseSchedule(input);
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public int deleteEduCourseScheduleByScheduleIds(Long[] ids) {
        requirePlanningStaff();
        require(ids != null && ids.length > 0 && Arrays.stream(ids).noneMatch(Objects::isNull), "请选择要删除的排课");
        List<EduCourseSchedule> courses = Arrays.stream(ids).distinct().sorted().map(this::selectEduCourseScheduleByScheduleId).toList();
        lockResources(courses);
        for (EduCourseSchedule course : courses) {
            EduCourseSchedule locked = eduCourseScheduleMapper.lockSchedule(course.getScheduleId());
            own(locked);
            requireNoActiveEnrollments(locked);
            require(Objects.equals(course.getClassroomId(), locked.getClassroomId()) && Objects.equals(course.getTeacherId(), locked.getTeacherId()), "排课已被其他人调整，请刷新后重试");
            require(referenceCount(course.getScheduleId()) == 0, "排课「" + course.getCourseClassName() + "」已有报名、考勤、调课或作业，不能删除，请改为停用");
        }
        return eduCourseScheduleMapper.deleteEduCourseScheduleByScheduleIds(courses.stream().map(EduCourseSchedule::getScheduleId).toArray(Long[]::new));
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public int deleteEduCourseScheduleByScheduleId(Long id) { return deleteEduCourseScheduleByScheduleIds(new Long[]{id}); }

    @Override @Transactional(rollbackFor = Exception.class)
    public int removeConfirmedSchedule(Long id) {
        requirePlanningStaff();
        EduCourseSchedule before = selectEduCourseScheduleByScheduleId(id);
        lockResources(List.of(before));
        EduCourseSchedule current = eduCourseScheduleMapper.lockSchedule(id);
        own(current);
        require(Objects.equals(before.getTeacherId(), current.getTeacherId())
            && Objects.equals(before.getClassroomId(), current.getClassroomId()), "排课已被其他人调整，请刷新后重试");
        // 保留主键及全部历史关联，避免物理删除造成财务、报名记录失联。
        EduCourseSchedule removed = new EduCourseSchedule();
        removed.setScheduleId(id); removed.setDelFlag("1");
        removed.setStatus("1"); removed.setRecruitStatus("1");
        removed.setUpdateBy(SecurityUtils.getUsername()); removed.setUpdateTime(DateUtils.getNowDate());
        return eduCourseScheduleMapper.updateEduCourseSchedule(removed);
    }

    /**
     * 见接口说明。判据与 {@link #deleteEduCourseScheduleByScheduleIds(Long[])} 里的校验保持一致：
     * 既不能有「有效报名」（报名保护），也不能有任何带 schedule_id 的引用（签到/考勤/调课/作业…）。
     * 只做判断、不加锁，供课表导入在删除前逐个预筛。
     */
    @Override
    public boolean isDeletable(Long scheduleId) {
        if (scheduleId == null || selectEduCourseScheduleByScheduleId(scheduleId) == null) return false;
        if (!db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND del_flag='0'"
            + " AND COALESCE(enrollment_status,'') NOT IN ('2','3','已取消','已拒绝','老师已拒绝')", scheduleId).isEmpty()) return false;
        return referenceCount(scheduleId) == 0;
    }

    private void bindTeacher(EduCourseSchedule input, EduCourseSchedule current) {
        if (teacherOnly()) {
            require(input.getTeacherId() == null || input.getTeacherId().equals(SecurityUtils.getUserId()), "只能为当前教师排课");
            input.setTeacherId(SecurityUtils.getUserId());
        } else if (input.getTeacherId() == null && current != null) {
            input.setTeacherId(current.getTeacherId());
        }
        if (input.getTeacherId() == null) {
            require(current != null && Objects.equals(input.getTeacherName(), current.getTeacherName()), "请选择明确的教师账号，不能仅填写姓名");
            return; // 保留已有未绑定课程，不按同名猜测归属。
        }
        List<Map<String,Object>> teachers = db.rows("SELECT u.nick_name,u.user_name FROM sys_user u WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0')", input.getTeacherId());
        require(!teachers.isEmpty(), "教师账号不存在、已停用或不具备教师角色");
        // teacher_name 只是冗余展示字段：登录名为手机号/自动账号时，退回教师昵称。
        // 归属判定一律以 teacher_id 为准，不要用这里的名字做匹配。
        input.setTeacherName(realNameOf(teachers.get(0)));
    }

    /**
     * 取教师展示名：真名优先，手机号/自动生成的登录名不当作教师姓名。
     * 供所有写入 teacher_name 冗余字段的地方复用，避免各处口径不一致。
     */
    public static String realNameOf(Map<String,Object> user) {
        if (user == null) return "";
        Object real = user.get("userName");
        String r = real == null ? "" : String.valueOf(real).trim();
        Object nick = user.get("nickName");
        String n = nick == null ? "" : String.valueOf(nick).trim();
        if ("null".equals(n)) n = "";
        if (!r.isEmpty() && !"null".equals(r) && !r.matches("\\d+") && !r.startsWith("mp_")) return r;
        return !n.isEmpty() ? n : ("null".equals(r) ? "" : r);
    }
    private void lockResources(List<EduCourseSchedule> courses) {
        availability.lockResources(courses);
    }
    private static boolean singleResourcesChanged(EduCourseSchedule a,EduCourseSchedule b) {
        return !Objects.equals(a.getCampusName(),b.getCampusName()) || !Objects.equals(a.getClassroomId(),b.getClassroomId()) || !Objects.equals(a.getStartTime(),b.getStartTime()) || !Objects.equals(a.getEndTime(),b.getEndTime())
            || !Objects.equals(ScheduleCalendar.date(a.getStartDate()),ScheduleCalendar.date(b.getStartDate())) || !Objects.equals(ScheduleCalendar.date(a.getEndDate()),ScheduleCalendar.date(b.getEndDate()))
            || !Objects.equals(a.getClassPattern(),b.getClassPattern()) || !Objects.equals(a.getPeriodName(),b.getPeriodName()) || !Objects.equals(a.getTimeSlot(),b.getTimeSlot()) || !Objects.equals(a.getLessonLocation(),b.getLessonLocation());
    }
    private void validate(EduCourseSchedule s) {
        required(s.getScheduleCode(), 32, "排课编码"); required(s.getTermName(), 20, "学期"); required(s.getPeriodName(), 20, "期次或上课日");
        required(s.getTimeSlot(), 30, "时段"); required(s.getGradeName(), 20, "年级"); required(s.getSubjectName(), 20, "学科");
        required(s.getCourseClassName(), 100, "课程班名称");
        if (s.getClassroomId() == null) {
            required(s.getCampusName(), 50, "校区");
            s.setCampusName(s.getCampusName().trim());
            require(!db.rows("SELECT DISTINCT campus_name FROM edu_classroom WHERE campus_name=? AND status='0'",s.getCampusName()).isEmpty(), "请选择启用的校区");
        }
        require(s.getCourseYear() != null && s.getCourseYear() >= 2000 && s.getCourseYear() <= 2100, "课程年份应为2000至2100");
        require(s.getStartTime() != null && s.getEndTime() != null && s.getStartTime().isBefore(s.getEndTime()), "请填写正确的开始、结束时间，结束必须晚于开始");
        require(List.of("1", "2").contains(s.getClassMode()), "授课形式无效");
        require(List.of("0", "1").contains(s.getStatus()), "排课状态无效");
        require(List.of("0", "1", "2").contains(s.getRecruitStatus()), "招生状态无效");
        if ("2".equals(s.getClassMode())) require(Duration.between(s.getStartTime(), s.getEndTime()).toMinutes() == 120 && s.getStartTime().getSecond() == s.getEndTime().getSecond(), "一对一每次固定2小时");
        // 「其他（暂不排课）」：允许起止日期同时留空，不展开上课日、课次按 0 计，也不参与教师冲突检测
        require((s.getStartDate() == null) == (s.getEndDate() == null), "开课、结课日期需同时填写，或同时保留待排");
        if (s.getStartDate() != null) {
            LocalDate start = ScheduleCalendar.date(s.getStartDate()), end = ScheduleCalendar.date(s.getEndDate());
            require(start != null && end != null && !start.isAfter(end), "结课日期不能早于开课日期");
            require(java.time.temporal.ChronoUnit.DAYS.between(start, end) <= 3660, "排课跨度不能超过10年");
            require(ScheduleCalendar.isValid(s.getClassPattern()), "请明确上课模式");
            if ("WEEKLY".equals(s.getClassPattern())) require(ScheduleCalendar.weekday(s.getPeriodName()) > 0, "每周排课需明确周一至周日的上课星期");
        }
        Map<String,Object> classroom = s.getClassroomId() == null ? Map.of("status","0") : db.one("SELECT status,campus_name FROM edu_classroom WHERE classroom_id=?", s.getClassroomId());
        if (s.getClassroomId() != null && classroom.get("campusName") != null) s.setCampusName(String.valueOf(classroom.get("campusName")));
        if ("0".equals(s.getStatus())) require("0".equals(classroom.get("status")), "所选教室已停用");
        long enrolled = s.getScheduleId() == null ? 0 : db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND del_flag='0' AND class_date IS NULL AND enrollment_status NOT IN('2','3','已取消') FOR UPDATE", s.getScheduleId()).size();
        // 教室容量不作为排课硬约束（报名不按教室容量卡名额），仅保留一对一「最多 1 名有效学员」的校验。
        if ("2".equals(s.getClassMode())) require(enrolled <= 1, "一对一课程不能保留多名有效报名学员");
    }
    private void checkConflicts(EduCourseSchedule input) {
        long id = input.getScheduleId() == null ? -1 : input.getScheduleId();
        long duplicates = db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_course_schedule WHERE schedule_id<>? AND schedule_code=?", Long.class, id, input.getScheduleCode());
        require(duplicates == 0, "排课编码已存在");
        // 说明：自 2026-10-12 起，同一教室+同年+学期+期次+时段 允许存在多条排课（不再拒绝），
        // 由可视化课表前端以红色感叹号提示冲突（数据库唯一索引 uk_course_timetable_cell 已降级为普通索引）。
        if (!"0".equals(input.getStatus())) return;
        SortedSet<LocalDate> dates = ScheduleCalendar.dates(ScheduleCalendar.date(input.getStartDate()), ScheduleCalendar.date(input.getEndDate()), input.getClassPattern(), input.getPeriodName(), input.getScheduleId() == null ? List.of() : parentMapper.selectScheduleAdjustments(input.getScheduleId()));
        if (dates.isEmpty()) return;
        // 教室维度的重复排课同样允许（前端提示冲突），这里只保留「同一教师同一时间被占用」的硬校验。
        // 带上教师姓名与教室名，冲突时才能直接说清是哪位老师的哪门课。
        var candidates = db.rows("SELECT s.schedule_id,s.teacher_name,s.start_time,s.end_time,s.start_date,s.end_date,"
            + "s.class_pattern,s.period_name,s.course_class_name,c.classroom_name,c.campus_name "
            + "FROM edu_course_schedule s LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id "
            + "WHERE s.schedule_id<>? AND s.del_flag='0' AND s.status='0' AND s.teacher_id=? FOR UPDATE", id, input.getTeacherId());
        for (var other : candidates) {
            if (other.get("startTime") == null || other.get("endTime") == null) continue;
            LocalTime start = LocalTime.parse(String.valueOf(other.get("startTime"))), end = LocalTime.parse(String.valueOf(other.get("endTime")));
            if (!input.getStartTime().isBefore(end) || !start.isBefore(input.getEndTime())) continue;
            var otherDates = ScheduleCalendar.dates(ScheduleCalendar.date(other.get("startDate")), ScheduleCalendar.date(other.get("endDate")), (String) other.get("classPattern"), (String) other.get("periodName"), parentMapper.selectScheduleAdjustments(Long.valueOf(String.valueOf(other.get("scheduleId")))));
            // 逐个日期比对，命中即用「最早的一天 + 冲突总天数」报错，便于用户直接定位到课表上哪一格
            List<LocalDate> conflictDates = dates.stream().filter(otherDates::contains).toList();
            if (conflictDates.isEmpty()) continue;
            LocalDate first = conflictDates.get(0);
            String message = conflictMessage(input, other, first, conflictDates.size());
            // ⚠️ 测试开关：导入时若因「同姓教师被 matchTeacher 子串匹配并成一个账号」而误报冲突，
            // 打开开关可只记告警不阻断，方便先把课表导进来核对；生产环境请改回 false。
            if (allowTeacherConflict()) { log.warn("【测试开关已开启】忽略教师时间冲突：{}", message); continue; }
            throw new ServiceException(message);
        }
    }

    /** 测试开关：sys_config `edu.schedule.allowTeacherConflict` = true 时，教师时间冲突只记告警不阻断（默认关闭）。 */
    private boolean allowTeacherConflict() {
        try {
            String value = configService.selectConfigByKey("edu.schedule.allowTeacherConflict");
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (Exception e) {
            log.warn("读取参数 edu.schedule.allowTeacherConflict 失败，按关闭处理：{}", e.getMessage());
            return false;
        }
    }

    /** 拼装教师时间冲突的报错文案：点名教师、冲突日期（含星期）、已被占用的课程与教室，以及本次要排的课程。 */
    private static String conflictMessage(EduCourseSchedule input, Map<String,Object> other, LocalDate first, int days) {
        String teacher = blank(input.getTeacherName()) ? text(other.get("teacherName")) : input.getTeacherName();
        if (blank(teacher)) teacher = "该教师";
        StringBuilder msg = new StringBuilder("教师「").append(teacher).append("」")
            .append(first).append("（").append(weekdayText(first)).append("）")
            .append("已被排课「").append(text(other.get("courseClassName"))).append("」占用")
            .append("（").append(hhmm(other.get("startTime"))).append("-").append(hhmm(other.get("endTime")));
        String room = roomText(other);
        if (!room.isEmpty()) msg.append("，").append(room);
        msg.append("）");
        if (days > 1) msg.append("，共 ").append(days).append(" 天冲突");
        String mine = text(input.getCourseClassName());
        msg.append("，与本课程").append(blank(mine) ? "" : "「" + mine + "」").append("时间冲突。请调整时间或改由其他教师上课");
        return msg.toString();
    }

    /** 「周一」~「周日」 */
    private static String weekdayText(LocalDate date) {
        return "周" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1);
    }

    /** LocalTime / java.sql.Time / 字符串 → "HH:mm"；解析不出原样返回 */
    private static String hhmm(Object value) {
        if (value == null) return "";
        try { return LocalTime.parse(String.valueOf(value)).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")); }
        catch (RuntimeException e) { String s = String.valueOf(value); return s.length() >= 5 ? s.substring(0, 5) : s; }
    }

    /** 「万达·W1」之类；教室缺失返回空串 */
    private static String roomText(Map<String,Object> row) {
        String name = text(row.get("classroomName"));
        if (blank(name)) return "";
        String campus = text(row.get("campusName"));
        return "教室 " + (blank(campus) ? name : campus + "·" + name);
    }

    private static String text(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value).trim();
        return "null".equals(s) ? "" : s;
    }
    private boolean hasAttendance(Long id) {
        return !db.rows("SELECT sign_in_id FROM edu_class_sign_in WHERE schedule_id=? FOR UPDATE", id).isEmpty()
            || !db.rows("SELECT attendance_id FROM edu_attendance WHERE schedule_id=? AND attendance_status='1' FOR UPDATE", id).isEmpty();
    }
    /**
     * 报名保护：已有有效报名的排课禁止被修改/删除，避免改课把学员的班次改没了。
     * <p>
     * ⚠️ 测试开关：`sys_config.edu.schedule.allowUpdateEnrolled = true` 时，**只对「修改排课」放行**
     * （课表导入/覆盖更新会走这里），删除排课仍然一律拦截。
     * 用途：现有课表上已有学员报名，导致无法用新文件覆盖更新、无法验证导入日期。
     * 验证完请把该参数改回 false（系统管理 → 参数设置），否则有改动学员班次的风险。
     */
    private void requireNoActiveEnrollments(EduCourseSchedule course) {
        requireNoActiveEnrollments(course, false);
    }

    private void requireNoActiveEnrollments(EduCourseSchedule course, boolean forUpdate) {
        // 一对一预约按具体日期占位，不能通过班课导入测试开关绕开预约保护。
        if (forUpdate && !"2".equals(course.getClassMode()) && allowUpdateEnrolled()) {
            log.warn("【测试开关已开启】跳过报名保护，允许覆盖更新已有报名的排课：{} {}（schedule_id={}）",
                    course.getCourseClassName(), course.getTeacherName(), course.getScheduleId());
            return;
        }
        // Check actual registrations after locking the schedule shared by enrollment transactions.
        require(db.rows("SELECT enrollment_id FROM edu_enrollment WHERE schedule_id=? AND del_flag='0'"
            + " AND COALESCE(enrollment_status,'') NOT IN ('2','3','已取消','已拒绝','老师已拒绝') FOR UPDATE", course.getScheduleId()).isEmpty(),
            "排课「" + course.getCourseClassName() + "」已有学员报名，禁止修改或删除。请先与学员沟通并处理报名，清空班次后再操作。");
    }

    /** 测试开关：sys_config `edu.schedule.allowUpdateEnrolled` = true 时，允许覆盖更新已有报名的排课（默认关闭）。 */
    private boolean allowUpdateEnrolled() {
        try {
            String value = configService.selectConfigByKey("edu.schedule.allowUpdateEnrolled");
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (Exception e) {
            log.warn("读取参数 edu.schedule.allowUpdateEnrolled 失败，按关闭处理：{}", e.getMessage());
            return false;
        }
    }
    private long referenceCount(Long id) {
        // 包含无外键但引用 schedule_id 的历史表，例如签到、调课和作业。
        long count = 0;
        for (Map<String,Object> row : db.rows("SELECT TABLE_NAME AS table_name FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='schedule_id' AND table_name<>'edu_course_schedule'")) {
            String table = String.valueOf(row.get("tableName"));
            require(table.matches("[A-Za-z0-9_]+"), "关联表名异常");
            count += db.rows("SELECT schedule_id FROM `" + table + "` WHERE schedule_id=? FOR UPDATE", id).size();
        }
        return count;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static void required(String value, int max, String label) { require(!blank(value) && value.length() <= max, label + "不能为空且不能超过" + max + "字"); }
    private static void require(boolean condition, String message) { if (!condition) throw new ServiceException(message); }
    private EduCourseSchedule merge(EduCourseSchedule old, EduCourseSchedule in) {
        EduCourseSchedule s = new EduCourseSchedule(); s.setScheduleId(old.getScheduleId()); s.setScheduleCode(old.getScheduleCode());
        s.setLessonLocation(in.getLessonLocation() == null ? old.getLessonLocation() : in.getLessonLocation());
        s.setCampusName(in.getCampusName() == null ? old.getCampusName() : in.getCampusName());
        s.setTeacherId(in.getTeacherId()); s.setTeacherName(in.getTeacherName()); s.setClassroomId(in.getClassroomId());
        s.setCourseYear(in.getCourseYear() == null ? old.getCourseYear() : in.getCourseYear());
        s.setTermName(in.getTermName() == null ? old.getTermName() : in.getTermName()); s.setPeriodName(in.getPeriodName() == null ? old.getPeriodName() : in.getPeriodName());
        s.setTimeSlot(in.getTimeSlot() == null ? old.getTimeSlot() : in.getTimeSlot()); s.setGradeName(in.getGradeName() == null ? old.getGradeName() : in.getGradeName()); s.setSubjectName(in.getSubjectName() == null ? old.getSubjectName() : in.getSubjectName());
        s.setCourseClassName(in.getCourseClassName() == null ? old.getCourseClassName() : in.getCourseClassName());
        s.setStartTime(in.getStartTime() == null ? old.getStartTime() : in.getStartTime()); s.setEndTime(in.getEndTime() == null ? old.getEndTime() : in.getEndTime());
        s.setStartDate(in.getStartDate() == null ? old.getStartDate() : in.getStartDate()); s.setEndDate(in.getEndDate() == null ? old.getEndDate() : in.getEndDate());
        s.setClassPattern(in.getClassPattern() == null ? old.getClassPattern() : in.getClassPattern());
        s.setClassMode(blank(in.getClassMode()) ? (blank(old.getClassMode()) ? "1" : old.getClassMode()) : in.getClassMode());
        s.setStatus(in.getStatus() == null ? old.getStatus() : in.getStatus()); s.setRecruitStatus(in.getRecruitStatus() == null ? old.getRecruitStatus() : in.getRecruitStatus());
        return s;
    }
}
