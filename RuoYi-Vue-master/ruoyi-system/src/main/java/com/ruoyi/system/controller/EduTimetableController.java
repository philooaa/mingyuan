package com.ruoyi.system.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.domain.EduScheduleAdjustment;
import com.ruoyi.system.service.IEduClassroomService;
import com.ruoyi.system.service.IEduCourseScheduleService;
import com.ruoyi.system.service.IEduScheduleAdjustmentService;
import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.ScheduleAvailabilityService;
import com.ruoyi.system.service.ScheduleCalendar;
import com.ruoyi.system.timetable.TimetableCalendarService;
import com.ruoyi.system.timetable.TimetableExportService;
import com.ruoyi.system.timetable.TimetableImportService;

/**
 * 可视化课表 Controller（矩阵式：纵向教室 × 横向时段）
 * 与 EduCourseScheduleController 共用同一张 edu_course_schedule 表；
 * 此处负责：维度数据（学期/期次/时段/教室/教师/字典）、网格批量查询、以及带角色校验的 CRUD 封装。
 */
@RestController
@RequestMapping("/system/courseTimetable")
public class EduTimetableController extends BaseController
{
    @Autowired
    private IEduCourseScheduleService scheduleService;

    @Autowired
    private IEduClassroomService classroomService;

    @Autowired
    private ISysDictDataService dictDataService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private TimetableImportService timetableImportService;

    @Autowired
    private TimetableExportService exportService;

    @Autowired
    private ScheduleAvailabilityService availability;

    @Autowired
    private IEduScheduleAdjustmentService adjustmentService;

    @Autowired
    private TimetableCalendarService calendarService;

    /**
     * 维度数据：返回页面初始化需要的下拉框 + 固定时段 + 校区分组教室
     * 菜单权限：system:courseschedule:list（与列表页共用同一权限标识，减少额外菜单配置）
     * 同时允许 teacher 角色直接调用（hasAnyRoles）
     */
    @PreAuthorize("@ss.hasPermi('system:courseschedule:list') or @ss.hasAnyPermi('system:schedule:list,system:schedule:add,system:schedule:edit') or @ss.hasAnyRoles('admin,education,teacher')")
    @GetMapping("/dimensions")
    public AjaxResult dimensions()
    {
        Map<String, Object> data = new HashMap<>();

        // 1. 学期字典 + 数据里实际出现过的学期/期次（导入未识别时写入的占位值不在字典里，必须补齐，
        //    否则「未识别学期 / 未识别期次 / 未识别时段」的排课在课表上无法查看）
        List<Map<String, String>> terms = dictLabelValueList("edu_term");
        Map<String, List<String>> periodMap = new LinkedHashMap<>();
        Set<String> knownTerms = new LinkedHashSet<>();
        terms.forEach(m -> knownTerms.add(m.get("value")));
        for (Map<String, Object> p : scheduleService.selectTermPeriodPairs())
        {
            String t = p.get("termName") == null ? null : String.valueOf(p.get("termName")).trim();
            if (t == null || t.isEmpty()) continue;
            List<String> ps = periodMap.computeIfAbsent(t, k -> new ArrayList<>());
            String pv = p.get("periodName") == null ? null : String.valueOf(p.get("periodName")).trim();
            if (pv != null && !pv.isEmpty() && !ps.contains(pv)) ps.add(pv);
            if (knownTerms.add(t))
            {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("label", t);
                m.put("value", t);
                m.put("isDefault", "N");
                m.put("extra", "Y");
                terms.add(m);
            }
        }
        data.put("terms", terms);
        data.put("periodMap", periodMap);
        // 2. 期次字典
        data.put("periods",     dictLabelValueList("edu_term_period"));
        // 3. 时段字典（Excel 对齐的 6 个固定时段）
        data.put("timeSlots",   dictLabelValueList("edu_time_slot"));
        // 4. 班型字典
        data.put("classTypes",  dictLabelValueList("edu_class_type"));
        // 5. 年级字典
        data.put("grades",      dictLabelValueList("edu_grade"));
        // 6. 学科字典
        data.put("subjects",    dictLabelValueList("edu_subject"));

        // 7. 教室列表（按校区分组），仅包含状态正常的教室
        EduClassroom q = new EduClassroom();
        q.setStatus("0");
        List<EduClassroom> allClassrooms = classroomService.selectEduClassroomList(q);
        Map<String, List<EduClassroom>> grouped = allClassrooms.stream()
                .collect(Collectors.groupingBy(
                        c -> StringUtils.isEmpty(c.getCampusName()) ? "未分配校区" : c.getCampusName(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        data.put("classrooms", grouped);
        data.put("classroomList", allClassrooms);

        // 8. 教师下拉：role_key = teacher 的启用用户
        // selectUserList 不联角色表，getRoles() 恒为 null，导致原过滤全部失效、教师下拉为空；
        // 改为：先查 teacher 角色的 roleId，再用 selectAllocatedList 查该角色下的用户（联 sys_user_role/sys_role）
        List<Map<String, Object>> teachers = new ArrayList<>();
        Long teacherRoleId = roleService.selectRoleList(new SysRole()).stream()
                .filter(r -> "teacher".equals(r.getRoleKey()))
                .map(SysRole::getRoleId)
                .findFirst().orElse(null);
        if (teacherRoleId != null)
        {
            SysUser teacherQuery = new SysUser();
            teacherQuery.setRoleId(teacherRoleId);
            List<SysUser> teacherUsers = userService.selectAllocatedList(teacherQuery);
            teachers = teacherUsers.stream()
                    .filter(u -> "0".equals(u.getStatus()))
                    .sorted(Comparator.comparing((SysUser u) -> StringUtils.isNotEmpty(u.getUserName()) ? u.getUserName() : u.getNickName(),
                            Comparator.nullsLast(String::compareTo)))
                    .map(u -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("userId", u.getUserId());
                        m.put("nickName", u.getNickName());
                        m.put("userName", u.getUserName());
                        // 与导入和保存排课使用相同显示名，避免手机号盖过教师姓名。
                        m.put("realName", com.ruoyi.system.service.impl.EduCourseScheduleServiceImpl.realNameOf(m));
                        return m;
                    })
                    .collect(Collectors.toList());
        }
        data.put("teachers", teachers);

        // 9. 当前年份、当前角色是否 admin、当前用户昵称（给前端做默认值）
        data.put("currentYear", Calendar.getInstance().get(Calendar.YEAR));
        data.put("isAdmin", SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin"));
        data.put("isTeacher", SecurityUtils.hasRole("teacher"));
        SysUser currentUser = SecurityUtils.getLoginUser().getUser();
        data.put("currentUserNick", currentUser.getNickName());
        // 真名：teacher_name 统一存真名，前端新建排课的默认教师名要用这个，不要用昵称
        data.put("currentUserName", StringUtils.isNotEmpty(currentUser.getUserName())
                ? currentUser.getUserName() : currentUser.getNickName());
        data.put("currentUserId", SecurityUtils.getUserId());

        return success(data);
    }

    /**
     * 网格查询：按 年+学期+期次 取出所有排课，前端按 classroomId + timeSlot 自行放单元格
     *
     * 请求参数：
     *   courseYear   (必)  Integer
     *   termName     (必)  String  暑假/秋季/春季
     *   periodName   (必)  String  一期/二期/三期
     *   campusName   (可选) String  校区（万达/百家湖），传空不过滤
     *   gradeName    (可选)
     *   subjectName  (可选)
     *   teacherId    (可选) 教师账号ID —— 教师筛选请优先用这个（精确、改名不受影响）
     *   teacherName  (可选) 教师姓名，仅作兼容旧调用；teacherId 传了就不生效
     *   classType    (可选)
     *   classroomId  (可选)
     */
    @PreAuthorize("@ss.hasPermi('system:courseschedule:list') or @ss.hasAnyRoles('admin,teacher')")
    @GetMapping("/grid")
    public AjaxResult grid(
            @RequestParam(value = "courseYear") Integer courseYear,
            @RequestParam(value = "termName")   String termName,
            @RequestParam(value = "periodName") String periodName,
            @RequestParam(value = "campusName",  required = false) String campusName,
            @RequestParam(value = "gradeName",   required = false) String gradeName,
            @RequestParam(value = "subjectName", required = false) String subjectName,
            @RequestParam(value = "teacherId",   required = false) Long teacherId,
            @RequestParam(value = "teacherName", required = false) String teacherName,
            @RequestParam(value = "classType",   required = false) String classType,
            @RequestParam(value = "classroomId", required = false) Long classroomId)
    {
        EduCourseSchedule q = buildGridQuery(courseYear, termName, periodName, campusName,
                gradeName, subjectName, teacherId, teacherName, classType, classroomId);

        List<EduCourseSchedule> rows = scheduleService.selectTimetableGrid(q);
        Map<String, Object> data = new HashMap<>();
        // 前端用 "classroomId@timeSlot" 做 key 快速索引；同一格允许多条排课（冲突由前端红色感叹号提示）
        // 教室或时段缺失的排课也能取回：缺失部分用 "-" 占位（前端分别落在「未指定教室」列 / 「未指定时段」行）
        Map<String, List<EduCourseSchedule>> map = new LinkedHashMap<>();
        for (EduCourseSchedule s : rows) {
            String cid = s.getClassroomId() == null ? "-" : String.valueOf(s.getClassroomId());
            String slot = StringUtils.isEmpty(s.getTimeSlot()) ? "-" : s.getTimeSlot();
            map.computeIfAbsent(cid + "@" + slot, k -> new ArrayList<>()).add(s);
        }
        data.put("rows", rows);
        data.put("cellMap", map);
        return success(data);
    }

    /**
     * 课表日历：把排课展开成具体日期，供页面日历高亮「有课的日期」。
     *
     * <p>和 {@link #grid} 不同，这里 <b>不按学年 / 学期 / 期次过滤</b>：一个日历看全部课，
     * 只要那天有课就标出来，日期清单里用学期标签区分是哪一期。
     * 校区 / 年级 / 科目 / 教师 / 教室这些筛选照旧生效。
     */
    @PreAuthorize("@ss.hasPermi('system:courseschedule:list') or @ss.hasAnyRoles('admin,teacher')")
    @GetMapping("/calendar")
    public AjaxResult calendar(
            @RequestParam(value = "courseYear",  required = false) Integer courseYear,
            @RequestParam(value = "termName",    required = false) String termName,
            @RequestParam(value = "periodName",  required = false) String periodName,
            @RequestParam(value = "campusName",  required = false) String campusName,
            @RequestParam(value = "gradeName",   required = false) String gradeName,
            @RequestParam(value = "subjectName", required = false) String subjectName,
            @RequestParam(value = "teacherId",   required = false) Long teacherId,
            @RequestParam(value = "teacherName", required = false) String teacherName,
            @RequestParam(value = "classType",   required = false) String classType,
            @RequestParam(value = "classroomId", required = false) Long classroomId)
    {
        EduCourseSchedule q = buildGridQuery(courseYear, termName, periodName, campusName,
                gradeName, subjectName, teacherId, teacherName, classType, classroomId);
        return success(calendarService.build(scheduleService.selectTimetableGrid(q)));
    }

    /**
     * 课表导出：把当前筛选条件下的课表导出为 xlsx。
     * <p>第一张表「课表」与页面版式一致（纵轴时段、横轴教室、表头按校区合并），
     * 第二张表「排课明细」是同一批数据的平表，便于筛选与统计。
     * <p>筛选口径与 {@link #grid} 完全一致，导出的就是页面上看到的那些课。
     */
    @PreAuthorize("@ss.hasPermi('system:courseschedule:list') or @ss.hasAnyRoles('admin,teacher')")
    @Log(title = "可视化课表-导出", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response,
            @RequestParam(value = "courseYear") Integer courseYear,
            @RequestParam(value = "termName")   String termName,
            @RequestParam(value = "periodName") String periodName,
            @RequestParam(value = "campusName",  required = false) String campusName,
            @RequestParam(value = "gradeName",   required = false) String gradeName,
            @RequestParam(value = "subjectName", required = false) String subjectName,
            @RequestParam(value = "teacherId",   required = false) Long teacherId,
            @RequestParam(value = "teacherName", required = false) String teacherName,
            @RequestParam(value = "classType",   required = false) String classType,
            @RequestParam(value = "classroomId", required = false) Long classroomId) throws IOException
    {
        EduCourseSchedule q = buildGridQuery(courseYear, termName, periodName, campusName,
                gradeName, subjectName, teacherId, teacherName, classType, classroomId);
        List<EduCourseSchedule> rows = scheduleService.selectTimetableGrid(q);

        // 横轴教室：与页面同一份数据（只含启用教室）；按校区筛选时只导出该校区
        EduClassroom cq = new EduClassroom();
        cq.setStatus("0");
        List<EduClassroom> rooms = classroomService.selectEduClassroomList(cq).stream()
                .filter(c -> StringUtils.isEmpty(campusName) || campusName.equals(c.getCampusName()))
                .collect(Collectors.toList());

        // 纵轴时段：字典固定时段 ∪ 数据里实际出现的时段（与页面 rowSlots 一致，
        // 否则非标准时段（如 8:00-9:30）的课会在导出里整行消失）
        List<String> slots = new ArrayList<>();
        for (Map<String, String> m : dictLabelValueList("edu_time_slot"))
        {
            String v = m.get("value");
            if (StringUtils.isNotEmpty(v) && !slots.contains(v)) slots.add(v);
        }
        List<String> extras = rows.stream().map(EduCourseSchedule::getTimeSlot)
                .filter(StringUtils::isNotEmpty).distinct().filter(s -> !slots.contains(s))
                .sorted().collect(Collectors.toList());
        slots.addAll(extras);

        String title = String.join(" ", Arrays.asList(
                courseYear == null ? "" : courseYear + "年",
                StringUtils.trimToEmpty(termName), StringUtils.trimToEmpty(periodName), "课表")).replaceAll("\\s+", " ").trim();

        List<String> conds = new ArrayList<>();
        if (StringUtils.isNotEmpty(campusName))  conds.add("校区=" + campusName);
        if (StringUtils.isNotEmpty(gradeName))   conds.add("年级=" + gradeName);
        if (StringUtils.isNotEmpty(subjectName)) conds.add("科目=" + subjectName);
        if (teacherId != null)
        {
            String tn = dimsTeacherName(teacherId);
            conds.add("教师=" + (StringUtils.isEmpty(tn) ? String.valueOf(teacherId) : tn));
        }
        else if (StringUtils.isNotEmpty(teacherName))
        {
            conds.add("教师=" + teacherName);
        }
        String subtitle = (conds.isEmpty() ? "筛选条件：全部" : "筛选条件：" + String.join("，", conds))
                + "；共 " + rows.size() + " 门课；导出时间："
                + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());

        String base = TimetableExportService.buildFileBaseName(courseYear, termName, periodName, campusName);
        exportService.export(response, base, title, subtitle, rooms, slots, rows);
    }

    /** 按 teacher_id 取展示名，仅用于导出副标题里的「教师=xxx」。 */
    private String dimsTeacherName(Long teacherId)
    {
        SysUser u = userService.selectUserById(teacherId);
        if (u == null) return null;
        return com.ruoyi.system.service.impl.EduCourseScheduleServiceImpl.realNameOf(
                Map.of("userName", StringUtils.trimToEmpty(u.getUserName()),
                       "nickName", StringUtils.trimToEmpty(u.getNickName())));
    }

    /**
     * 组装课表网格查询条件，供 {@link #grid} 与 {@link #export} 共用，避免两处口径走偏。
     */
    private EduCourseSchedule buildGridQuery(Integer courseYear, String termName, String periodName, String campusName,
                                             String gradeName, String subjectName, Long teacherId, String teacherName,
                                             String classType, Long classroomId)
    {
        EduCourseSchedule q = new EduCourseSchedule();
        q.setCourseYear(courseYear == null ? null : courseYear.longValue());
        q.setTermName(termName);
        q.setPeriodName(periodName);
        // remark 字段在 XML 里被借来当 campusName 条件（不入库，仅用在联表教室的 and c.campus_name = #{remark}）
        q.setRemark(StringUtils.trimToNull(campusName));
        q.setGradeName(gradeName);
        q.setSubjectName(subjectName);
        // 教师筛选：一律按 teacher_id 执行。
        // 旧调用只传了 teacherName 时，先把它解析成唯一账号ID，再按 id 查 —— 姓名不参与 SQL 的归属判定，
        // 这样即使两个教师重名也不会把别人的课查出来。
        if (teacherId != null)
        {
            q.getParams().put("teacherUserId", teacherId);
        }
        else
        {
            Long resolved = resolveTeacherIdByName(teacherName);
            if (resolved != null) q.getParams().put("teacherUserId", resolved);
        }
        q.setClassType(classType);
        q.setClassroomId(classroomId);
        return q;
    }

    /**
     * 兼容旧调用：把「教师姓名」解析成唯一的 teacher_id。
     * <p>只用于把旧参数翻译成 id，最终 SQL 仍按 teacher_id 过滤。
     * <ul>
     *   <li>解析不到 → 返回 -1（查不到任何数据，绝不退化成"不过滤"）</li>
     *   <li>解析到多个同名账号 → 直接报错，不猜</li>
     * </ul>
     */
    private Long resolveTeacherIdByName(String teacherName)
    {
        String name = StringUtils.trimToNull(teacherName);
        if (name == null) return null;
        Long teacherRoleId = roleService.selectRoleList(new SysRole()).stream()
                .filter(r -> "teacher".equals(r.getRoleKey()))
                .map(SysRole::getRoleId).findFirst().orElse(null);
        if (teacherRoleId == null) return -1L;
        SysUser query = new SysUser();
        query.setRoleId(teacherRoleId);
        List<SysUser> matched = userService.selectAllocatedList(query).stream()
                .filter(u -> "0".equals(u.getStatus()))
                .filter(u -> name.equals(StringUtils.trimToEmpty(u.getUserName()))
                          || name.equals(StringUtils.trimToEmpty(u.getNickName())))
                .collect(Collectors.toList());
        if (matched.size() > 1)
        {
            throw new ServiceException("教师「" + name + "」对应多个账号，请改用 teacherId 指定");
        }
        return matched.isEmpty() ? -1L : matched.get(0).getUserId();
    }

    /**
     * 单条排课详情（给单元格点击"编辑"弹窗回填）
     */
    @PreAuthorize("@ss.hasPermi('system:courseschedule:query') or @ss.hasAnyRoles('admin,teacher')")
    @GetMapping("/{scheduleId}")
    public AjaxResult getInfo(@PathVariable Long scheduleId)
    {
        return success(scheduleService.selectEduCourseScheduleByScheduleId(scheduleId));
    }

    /**
     * 新增排课
     * 权限：仅教务、管理员可新增排课。
     * 落库的 teacher_name 统一写真名(user_name)，昵称只用于显示层兜底，不参与权限判定。
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-新增", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody EduCourseSchedule schedule)
    {
        fillBaseForCreate(schedule);
        checkEditablePermission(schedule, null, true);
        int rows = scheduleService.insertEduCourseSchedule(schedule);
        if (rows == 0) {
            throw new ServiceException("新增排课失败，请检查必填参数（同教室同时段已有排课现在允许并存，会在课表上用红色感叹号提示冲突）");
        }
        return success(schedule.getScheduleId());
    }

    /**
     * 修改排课
     * 权限：仅教务、管理员可修改排课。
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-修改", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EduCourseSchedule schedule)
    {
        EduCourseSchedule old = scheduleService.selectEduCourseScheduleByScheduleId(schedule.getScheduleId());
        if (old == null) {
            return error("排课不存在，可能已被删除");
        }
        checkEditablePermission(schedule, old, false);
        schedule.setUpdateBy(SecurityUtils.getUsername());
        return toAjax(scheduleService.updateEduCourseSchedule(schedule));
    }

    /**
     * 删除排课
     * 权限：仅教务、管理员可删除无报名或历史关联的排课。
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{scheduleIds}")
    public AjaxResult remove(@PathVariable Long[] scheduleIds)
    {
        return toAjax(scheduleService.deleteEduCourseScheduleByScheduleIds(scheduleIds));
    }

    /**
     * 可视化课表：一次性交换两个教室在指定「年份 + 学期 + 期次」下的全部课程。
     *
     * 典型场景：学校原始课表里两间教室整列录反了，逐格改太慢，这里整列对调一次搞定。
     * 只改 classroom_id（A↔B），时段/教师/课程等一律不动 —— 整列对调不改变每格课程数，
     * 因此不会凭空产生冲突。属于批量数据操作，仅管理员或有编辑权限者可用。
     *
     * 请求体：{ courseYear, termName, periodName, classroomIdA, classroomIdB }
     * 返回：{ swapped: 受影响条数, classroomIdA, classroomIdB, termName, periodName }
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-交换教室列", businessType = BusinessType.UPDATE)
    @PostMapping("/swapClassrooms")
    public AjaxResult swapClassrooms(@RequestBody Map<String, Object> body)
    {
        Long courseYear   = toLong(body.get("courseYear"));
        String termName   = trim(body.get("termName"));
        String periodName = trim(body.get("periodName"));
        Long classroomIdA = toLong(body.get("classroomIdA"));
        Long classroomIdB = toLong(body.get("classroomIdB"));

        int swapped = scheduleService.swapClassrooms(courseYear, termName, periodName, classroomIdA, classroomIdB);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("swapped", swapped);
        data.put("courseYear", courseYear);
        data.put("termName", termName);
        data.put("periodName", periodName);
        data.put("classroomIdA", classroomIdA);
        data.put("classroomIdB", classroomIdB);
        return success(data);
    }

    /** 兼容前端 JSON 里数字可能是 Number 或 String 的情况 */
    private static Long toLong(Object v)
    {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static String trim(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /**
     * 课表导入-预览：上传矩阵式课表 xlsx，解析并匹配教室/教师/年级/科目/班型，不落库。
     * <p>
     * 一份课表常含多个工作表（如「寒假排课表」「春季排课表」），每个工作表的上课起止日期不同，
     * 因此日期按「学期」分别设置，通过 dateRanges 传入（JSON 数组，见 parseDateRanges）。
     * 不传日期也能预览：此时返回 datePlans，由前端逐学期补填后再确认导入。
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-导入预览", businessType = BusinessType.IMPORT)
    @PostMapping("/importPreview")
    public AjaxResult importPreview(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "courseYear", required = false) Long courseYear,
                                    @RequestParam(value = "dateRanges", required = false) String dateRanges,
                                    @RequestParam(value = "startDate", required = false) String startDate,
                                    @RequestParam(value = "endDate", required = false) String endDate)
    {
        if (courseYear == null) courseYear = (long) Calendar.getInstance().get(Calendar.YEAR);
        return success(timetableImportService.preview(file, courseYear, parseDateRanges(dateRanges, startDate, endDate)));
    }

    /**
     * 课表导入-确认：将预览行落库。
     * <p>
     * 默认「以新课表为准」（updateSupport=true）：同位置已有课程覆盖更新，新课表里已无课的格子清空。
     * 只有显式传 updateSupport=false 才退回旧行为（跳过已存在的格子、且不做清空）。
     * dateRanges 形如 [{ termName: "寒假", startDate: "2027-01-20", endDate: "2027-02-05" }, ...]
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-导入", businessType = BusinessType.IMPORT)
    @PostMapping("/importConfirm")
    public AjaxResult importConfirm(@RequestBody Map<String, Object> body)
    {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        Object us = body.get("updateSupport");
        boolean updateSupport = us == null || !"false".equalsIgnoreCase(String.valueOf(us).trim());
        Map<String, LocalDate[]> ranges = parseDateRanges(body.get("dateRanges"), body.get("startDate"), body.get("endDate"));
        return success(timetableImportService.confirm(rows, updateSupport, SecurityUtils.getUsername(), ranges));
    }

    /**
     * 课表导入-一键：上传矩阵式 xlsx，自动同步教师/教室/字典并落库（整体事务）。
     * 日期同样按学期分别设置（dateRanges），未设置日期的学期落库后保持「待排」。
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-一键导入", businessType = BusinessType.IMPORT)
    @PostMapping("/importOneClick")
    public AjaxResult importOneClick(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "courseYear", required = false) Long courseYear,
                                     @RequestParam(value = "updateSupport", required = false, defaultValue = "true") boolean updateSupport,
                                     @RequestParam(value = "dateRanges", required = false) String dateRanges,
                                     @RequestParam(value = "startDate", required = false) String startDate,
                                     @RequestParam(value = "endDate", required = false) String endDate)
    {
        if (courseYear == null) courseYear = (long) Calendar.getInstance().get(Calendar.YEAR);
        return success(timetableImportService.oneClickImport(file, courseYear, updateSupport, SecurityUtils.getUsername(),
                parseDateRanges(dateRanges, startDate, endDate)));
    }

    /**
     * 课次展开：把一条排课按「起始/结课日期 + 上课模式」展开成一个个具体上课日。
     *
     * 这是"课表 → 具体日期"的落地视图，用于法定节假日冲课时逐日核对。
     * 返回的每一项都带 status：
     *   NORMAL    正常上课
     *   CANCELLED 已停课（调课记录 adjusted_date 为空，不占用、不顺延）
     *   MOVED     已调到别的日期（adjustedDate 给出新日期）
     * 已停课的课次同样会列出，方便前端提供「恢复」入口。
     */
    @PreAuthorize("@ss.hasPermi('system:courseschedule:list') or @ss.hasAnyRoles('admin,teacher')")
    @GetMapping("/{scheduleId}/occurrences")
    public AjaxResult occurrences(@PathVariable Long scheduleId)
    {
        EduCourseSchedule s = scheduleService.selectEduCourseScheduleByScheduleId(scheduleId);
        if (s == null) throw new ServiceException("排课不存在");

        LocalDate start = ScheduleCalendar.date(s.getStartDate());
        LocalDate end   = ScheduleCalendar.date(s.getEndDate());

        // 调课记录按原日期索引（同一课次只会有一条，由 Service 保证）
        Map<String, Map<String, Object>> adjusted = new HashMap<>();
        for (Map<String, Object> a : availability.adjustments(scheduleId, false))
        {
            LocalDate original = ScheduleCalendar.date(a.get("originalDate"));
            if (original != null) adjusted.put(original.toString(), a);
        }

        // 原始课次（不叠加调课），保证停课日仍然出现在列表里
        SortedSet<LocalDate> base = ScheduleCalendar.dates(start, end, s.getClassPattern(), s.getPeriodName(), List.of());
        List<Map<String, Object>> items = new ArrayList<>();
        int cancelled = 0;
        for (LocalDate d : base)
        {
            Map<String, Object> a = adjusted.get(d.toString());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", d.toString());
            item.put("weekday", "周" + "一二三四五六日".charAt(d.getDayOfWeek().getValue() - 1));
            if (a == null)
            {
                item.put("status", "NORMAL");
                item.put("reason", null);
                item.put("adjustmentId", null);
            }
            else
            {
                LocalDate moved = ScheduleCalendar.date(a.get("adjustedDate"));
                boolean isCancelled = a.get("adjustedDate") == null;
                if (isCancelled) cancelled++;
                item.put("status", isCancelled ? "CANCELLED" : "MOVED");
                item.put("adjustedDate", moved == null ? null : moved.toString());
                item.put("reason", a.get("reason"));
                item.put("adjustmentId", toLong(a.get("adjustmentId")));
            }
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduleId", scheduleId);
        data.put("startDate", start == null ? null : start.toString());
        data.put("endDate", end == null ? null : end.toString());
        data.put("classPattern", s.getClassPattern());
        data.put("periodName", s.getPeriodName());
        data.put("total", items.size());
        data.put("cancelledCount", cancelled);
        data.put("pending", start == null || end == null);
        if (start == null || end == null) data.put("message", "该排课尚未配置开课/结课日期，无法展开具体课次，请先在课表导入时填写日期或编辑该排课补充日期");
        data.put("items", items);
        return success(data);
    }

    /**
     * 停课（取消某一天的课次，不顺延）。
     *
     * 解决法定节假日冲课：写入一条 edu_schedule_adjustment，adjusted_date 留空表示停课。
     * 停课后该日期不再被占用，也不顺延到后面的日期——这正是"取消而非顺延"的语义。
     *
     * 请求体：{ date: "yyyy-MM-dd", reason: "国庆节" }
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-停课", businessType = BusinessType.UPDATE)
    @PostMapping("/{scheduleId}/occurrences/cancel")
    public AjaxResult cancelOccurrence(@PathVariable Long scheduleId, @RequestBody Map<String, Object> body)
    {
        EduCourseSchedule s = scheduleService.selectEduCourseScheduleByScheduleId(scheduleId);
        if (s == null) throw new ServiceException("排课不存在");
        checkScheduleEditPermission(s);

        LocalDate date = toDate(body.get("date"));
        if (date == null) throw new ServiceException("请选择要取消的课次日期");
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason")).trim();
        if (StringUtils.isEmpty(reason)) reason = "停课（法定节假日冲课）";

        EduScheduleAdjustment adj = new EduScheduleAdjustment();
        adj.setScheduleId(scheduleId);
        adj.setOriginalDate(java.sql.Date.valueOf(date));
        adj.setAdjustedDate(null); // 停课：不占用新日期、不顺延
        adj.setReason(reason);
        adj.setCreateBy(SecurityUtils.getUsername());
        // Service 会校验该日期确实是本课程的有效课次，并做重叠/一对一报名保护
        adjustmentService.insertEduScheduleAdjustment(adj);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduleId", scheduleId);
        data.put("date", date.toString());
        data.put("status", "CANCELLED");
        data.put("reason", reason);
        return success(data);
    }

    /**
     * 恢复课次：删除该日期的调课/停课记录，让这一天重新正常上课。
     *
     * 请求体：{ date: "yyyy-MM-dd" }
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,education')")
    @Log(title = "可视化课表-恢复课次", businessType = BusinessType.UPDATE)
    @PostMapping("/{scheduleId}/occurrences/restore")
    public AjaxResult restoreOccurrence(@PathVariable Long scheduleId, @RequestBody Map<String, Object> body)
    {
        EduCourseSchedule s = scheduleService.selectEduCourseScheduleByScheduleId(scheduleId);
        if (s == null) throw new ServiceException("排课不存在");
        checkScheduleEditPermission(s);

        LocalDate date = toDate(body.get("date"));
        if (date == null) throw new ServiceException("请选择要恢复的课次日期");

        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> a : availability.adjustments(scheduleId, false))
        {
            LocalDate original = ScheduleCalendar.date(a.get("originalDate"));
            // 注意：ShopRepository.rows 会把 *Id 列转成字符串，这里统一用 toLong 兼容
            Long adjustmentId = toLong(a.get("adjustmentId"));
            if (date.equals(original) && adjustmentId != null) ids.add(adjustmentId);
        }
        if (ids.isEmpty()) throw new ServiceException("该课次没有停课/调课记录，无需恢复");
        // Service 内部会加锁、校验并做一对一按日期报名保护
        adjustmentService.deleteEduScheduleAdjustmentByIds(ids.toArray(new Long[0]));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduleId", scheduleId);
        data.put("date", date.toString());
        data.put("status", "NORMAL");
        return success(data);
    }

    /**
     * 调课/停课权限：admin 放行；teacher 仅能调整本人负责的排课（与排课编辑口径一致）
     */
    private void checkScheduleEditPermission(EduCourseSchedule s)
    {
        if (SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin") || SecurityUtils.hasRole("education")) return;
        if (SecurityUtils.hasPermi(Constants.ALL_PERMISSION)) return;
        if (!SecurityUtils.hasRole("teacher")) throw new ServiceException("当前用户不具备调课权限");
        if (!Objects.equals(s.getTeacherId(), SecurityUtils.getUserId())) throw new ServiceException("教师仅能调整本人负责的排课");
    }

    /**
     * 解析「按学期+期次分别设置」的起止日期。
     * <p>
     * 支持两种传参：
     * ① dateRanges —— JSON 数组字符串（multipart 表单）或 List&lt;Map&gt;（JSON 请求体），
     *    元素形如 { "termName": "寒假", "periodName": "一期", "startDate": "2027-01-20", "endDate": "2027-02-05" }；
     *    periodName 可省略（省略即该学期全部期次共用一个区间，兼容旧的前端）；
     * ② 兼容更旧的 startDate/endDate 单区间 —— 套用到文件里全部课程（兜底键 {@code RANGE_ALL}）。
     * <p>
     * 每项要么起止都填、要么都不填；只填一个或结课早于开课直接拒绝，避免落库出半截日期。
     */
    private static Map<String, LocalDate[]> parseDateRanges(Object dateRanges, Object legacyStart, Object legacyEnd)
    {
        Map<String, LocalDate[]> out = new LinkedHashMap<>();
        List<?> list = null;
        if (dateRanges instanceof String s)
        {
            if (StringUtils.isNotEmpty(s.trim()))
            {
                try { list = com.alibaba.fastjson2.JSON.parseArray(s, Map.class); }
                catch (RuntimeException e) { throw new ServiceException("按学期设置的日期格式不正确：" + e.getMessage()); }
            }
        }
        else if (dateRanges instanceof List<?> l)
        {
            list = l;
        }
        if (list != null)
        {
            for (Object o : list)
            {
                if (!(o instanceof Map<?, ?> m)) continue;
                String term = m.get("termName") == null ? "" : String.valueOf(m.get("termName")).trim();
                if (term.isEmpty()) continue;
                String period = m.get("periodName") == null ? "" : String.valueOf(m.get("periodName")).trim();
                String label = period.isEmpty() ? "学期「" + term + "」" : "「" + term + " / " + period + "」";
                LocalDate[] range = requireRange(m.get("startDate"), m.get("endDate"), label);
                if (range != null) out.put(TimetableImportService.rangeKey(term, period), range);
            }
        }
        // 兼容旧的整份统一区间
        if (out.isEmpty())
        {
            LocalDate[] range = requireRange(legacyStart, legacyEnd, "上课日期");
            if (range != null) out.put(TimetableImportService.RANGE_ALL, range);
        }
        return out;
    }

    /**
     * 校验一对起止日期：都为空返回 null（该学期保持待排）；只填一个或顺序颠倒直接拒绝。
     */
    private static LocalDate[] requireRange(Object startDate, Object endDate, String label)
    {
        LocalDate from = toDate(startDate), to = toDate(endDate);
        if (from == null && to == null) return null;
        if (from == null || to == null) throw new ServiceException(label + "的起始日期与结课日期需同时填写，或同时留空");
        if (from.isAfter(to)) throw new ServiceException(label + "的结课日期不能早于起始日期");
        return new LocalDate[] { from, to };
    }

    /** 兼容 yyyy-MM-dd 字符串 / Date / 时间戳；非法值返回 null */
    private static LocalDate toDate(Object v)
    {
        if (v == null) return null;
        if (v instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (RuntimeException e) { return null; }
    }

    // =================================================================
    // 内部工具方法
    // =================================================================

    /** 字典 → [{label, value}] 列表 */
    private List<Map<String, String>> dictLabelValueList(String dictType)
    {
        SysDictData q = new SysDictData();
        q.setDictType(dictType);
        q.setStatus("0");
        return dictDataService.selectDictDataList(q).stream()
                .sorted(Comparator.comparing(SysDictData::getDictSort))
                .map(d -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("label", d.getDictLabel());
                    m.put("value", Set.of("edu_grade", "edu_subject", "edu_class_type").contains(dictType) ? d.getDictLabel() : d.getDictValue());
                    m.put("isDefault", d.getIsDefault());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /** 新增时填充 schedule_code / del_flag / status / recruit_status 默认值 */
    private void fillBaseForCreate(EduCourseSchedule s)
    {
        if (StringUtils.isEmpty(s.getScheduleCode())) {
            s.setScheduleCode("SCH" + System.currentTimeMillis() + (int)(Math.random() * 100));
        }
        if (StringUtils.isEmpty(s.getDelFlag())) s.setDelFlag("0");
        if (StringUtils.isEmpty(s.getStatus()))   s.setStatus("0");
        if (StringUtils.isEmpty(s.getRecruitStatus())) s.setRecruitStatus("0");
        if (s.getEnrolledCount() == null) s.setEnrolledCount(0L);
        if (s.getCourseYear() == null) {
            s.setCourseYear((long) Calendar.getInstance().get(Calendar.YEAR));
        }
        // create_by 自动赋值
        if (StringUtils.isEmpty(s.getCreateBy())) {
            s.setCreateBy(SecurityUtils.getUsername());
        }
    }

    /**
     * 新增/修改前检查教务、管理员身份
     * @param input  本次提交的排课对象
     * @param old    数据库里已有对象（新增时为 null）
     * @param isNew  true=新增 / false=修改
     */
    private void checkEditablePermission(EduCourseSchedule input, EduCourseSchedule old, boolean isNew)
    {
        if (SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin") || SecurityUtils.hasRole("education")) return;
        throw new ServiceException("课程由教务或管理员统一安排，教师不能发布或修改排课");
    }
}
