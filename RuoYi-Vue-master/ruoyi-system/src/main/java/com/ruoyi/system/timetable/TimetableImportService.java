package com.ruoyi.system.timetable;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.mapper.EduCourseScheduleMapper;
import com.ruoyi.system.service.IEduClassroomService;
import com.ruoyi.system.service.IEduCourseScheduleService;
import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.ScheduleCalendar;

/**
 * 课表导入：支持两种 xlsx 版式。
 *  ① 矩阵式：行=时段、列=教室（W1/B1 等编码），单元格文本形如「初一数学尖子班管老师」；
 *     学期优先取 Sheet 名（寒假排课表 → 寒假），其次扫描表内单元格（B2「暑假」、C3「秋季」）。
 *  ② 平铺表：系统导出的课表，表头含「排课编码/学期/期次或上课日/时段」等独立列，按列直读；
 *     「学期」列为空时回退用「来源Sheet」里的学期。
 * 分两步：preview（解析+匹配校验，返回预览行）→ confirm（落库，支持按 年+学期+期次+教室+时段 覆盖更新）。
 */
@Service
public class TimetableImportService
{
    @Autowired private IEduClassroomService classroomService;
    @Autowired private IEduCourseScheduleService scheduleService;
    @Autowired private EduCourseScheduleMapper courseScheduleMapper;
    @Autowired private ISysDictDataService dictDataService;
    @Autowired private ISysRoleService roleService;
    @Autowired private ISysUserService userService;

    /**
     * 识别不到时写入的占位值。
     * 表字段 term_name/period_name/time_slot/grade_name/subject_name/course_class_name 均为 NOT NULL，
     * 且课表界面需要「看到问题格子」而不是留空，因此用固定占位文本落库，前端据此标红感叹号。
     */
    public static final String PH_TERM    = "未识别学期";
    public static final String PH_PERIOD  = "未识别期次";
    public static final String PH_SLOT    = "未识别时段";
    public static final String PH_GRADE   = "未识别年级";
    public static final String PH_SUBJECT = "未识别科目";
    public static final String PH_CLASS   = "未识别课程";

    /**
     * 自动补建教室的最大编号缺口：编码超出该校区已有教室数 3 个以内才补建
     * （B1-B4 出现 B6 → 补 B5、B6）；超出则视为源文件编码写错，留空并由课表标红提示。
     */
    private static final int MAX_AUTO_ROOM_GAP = 3;

    /** 单元格文本归一化后匹配：时段 8:00-10:00（兼容全角冒号/波浪线） */
    private static final Pattern TIME_SLOT = Pattern.compile("(\\d{1,2}[:：]\\d{2})\\s*[-~～—]+\\s*(\\d{1,2}[:：]\\d{2})");
    /** 教室编码：W1 / B4 等 */
    private static final Pattern ROOM_CODE = Pattern.compile("([A-Za-z]+)\\s*(\\d+)");
    /** 兜底教师匹配：姓+老师（取文本中最后一个「X老师」，教师名通常在课程文本末尾） */
    private static final Pattern TEACHER_FALLBACK = Pattern.compile("([\\u4e00-\\u9fa5])老师");
    private static final String TEACHER_STOP_CHARS = "的了是在有和与班级辅导主任讲";
    /** 单元格里的括号补充说明，如「幼小衔接语文许叶子（15：00~16：30）」 */
    private static final Pattern BRACKET_CONTENT = Pattern.compile("[（(][^）)]*[）)]");
    /** 字典里没有、但原表实际出现过的班型写法（含错别字），摘姓名时要一并去掉 */
    private static final Pattern CLASS_TYPE_LOOSE = Pattern.compile("(常规|尖子|培优|特长|校优|拓展|提高|基础|冲刺|衔接)[班版]");

    private static final List<String> EXTRA_CLASS_TYPES = Arrays.asList("常规班", "常规版", "培优班");

    // =================================================================
    // 预览：解析文件并匹配 教室/教师/年级/科目/班型/时段
    // 同时按「每个学期各自的起止日期」标注每行将写入的日期规则（见 annotateDatePlan）
    // =================================================================
    /**
     * 日期区间的兜底键：既没按「学期+期次」也没按「学期」设置时，用这个键的区间套用到全部课程。
     * 一份课表里常有多个工作表（如「寒假排课表」「春季排课表」），且同一学期里不同期次的上课周期也不同
     * （寒假一期/二期、春季周六/周日），所以正常走的是「按学期+期次分别设置」，这个键只用于兼容旧的「整份统一」。
     */
    public static final String RANGE_ALL = "*";

    /** 「学期+期次」的区间键，如 `寒假|一期`。期次为空时退化为学期名。 */
    public static String rangeKey(String termName, String periodName)
    {
        String term = termName == null ? "" : termName.trim();
        String period = periodName == null ? "" : periodName.trim();
        return period.isEmpty() ? term : term + "|" + period;
    }

    /**
     * 取某条排课适用的日期区间，按「越具体越优先」三级回退：
     *   ① `学期|期次`（如 `寒假|一期`，正常都用这一级）
     *   ② `学期`（同一学期里各期次共用一个区间时的简写）
     *   ③ {@link #RANGE_ALL}（整份课表统一一个区间，兼容旧用法）
     * 都没设或起止不全时返回 null —— 该排课保持「待排」，不猜日期。
     */
    public static LocalDate[] rangeOf(Map<String, LocalDate[]> ranges, String termName, String periodName)
    {
        if (ranges == null || ranges.isEmpty()) return null;
        LocalDate[] range = ranges.get(rangeKey(termName, periodName));
        if (range == null && termName != null && !termName.trim().isEmpty()) range = ranges.get(termName.trim());
        if (range == null) range = ranges.get(RANGE_ALL);
        if (range == null || range[0] == null || range[1] == null) return null;
        return range;
    }

    /** 兼容旧调用：只按学期取区间 */
    public static LocalDate[] rangeOf(Map<String, LocalDate[]> ranges, String termName)
    {
        return rangeOf(ranges, termName, null);
    }

    /**
     * 取某条排课适用的递推模式，与 {@link #rangeOf} 同样按「越具体越优先」回退：
     *   ① `学期|期次`（如 `寒假|一期`，正常都用这一级）
     *   ② `学期`（同一学期各期次共用一个模式时的简写）
     *   ③ 不指定 → 按学期默认（寒暑假=上5休1，春秋季=每周固定星期）
     * <p>
     * 寒暑假可指定 {@code DAILY}（连上无假期）、{@code DAILY_5_1}（上5休1）、
     * {@code DAILY_6_1}（上6休1）、{@code DAILY_5_2}（上5休2）；春秋季固定每周，指定日循环无效。
     */
    public static String patternOf(Map<String, String> patterns, String termName, String periodName)
    {
        String override = null;
        if (patterns != null && !patterns.isEmpty())
        {
            override = patterns.get(rangeKey(termName, periodName));
            if (override == null && termName != null && !termName.trim().isEmpty()) override = patterns.get(termName.trim());
        }
        return ScheduleCalendar.patternFor(termName, periodName, override);
    }

    /** 兼容旧调用：不指定递推模式，全部按学期默认（寒暑假=上5休1，春秋季=每周固定星期） */
    public Map<String, Object> preview(MultipartFile file, long courseYear, Map<String, LocalDate[]> ranges)
    {
        return preview(file, courseYear, ranges, Map.of());
    }

    /**
     * 解析预览。
     * <p>
     * patterns 是「学期|期次 → 递推模式」的覆盖表（键与 ranges 一致），寒暑假可指定
     * {@code DAILY}（连上无假期）、{@code DAILY_5_1}（上5休1）、{@code DAILY_6_1}（上6休1）、
     * {@code DAILY_5_2}（上5休2）；不指定则按学期默认上5休1。
     */
    public Map<String, Object> preview(MultipartFile file, long courseYear, Map<String, LocalDate[]> ranges,
                                       Map<String, String> patterns)
    {
        if (file == null || file.isEmpty()) throw new ServiceException("请选择要导入的课表文件");
        String fname = StringUtils.trimToEmpty(file.getOriginalFilename()).toLowerCase();
        if (!fname.endsWith(".xlsx") && !fname.endsWith(".xls")) throw new ServiceException("仅支持 .xlsx / .xls 课表文件");

        // 基础数据
        List<String> grades = dictLabels("edu_grade");
        List<String> subjects = dictLabels("edu_subject");
        List<String> classTypes = new ArrayList<>(dictLabels("edu_class_type"));
        EXTRA_CLASS_TYPES.forEach(t -> { if (!classTypes.contains(t)) classTypes.add(t); });
        Set<String> timeSlotDict = new HashSet<>(dictLabels("edu_time_slot"));
        List<String> terms = dictLabels("edu_term");

        // 教师候选：nick_name 与 user_name 都可匹配，长名优先
        Map<String, SysUser> teacherByName = loadTeacherNames();

        // 教室：按校区分组，组内按 classroom_id 排序；W1=第1间、B1=该校区第1间
        EduClassroom cq = new EduClassroom(); cq.setStatus("0");
        Map<String, List<EduClassroom>> roomsByCampus = classroomService.selectEduClassroomList(cq).stream()
                .filter(r -> StringUtils.isNotEmpty(r.getCampusName()))
                .collect(Collectors.groupingBy(EduClassroom::getCampusName, LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), l -> { l.sort(Comparator.comparing(EduClassroom::getClassroomId)); return l; })));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> sheetNames = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();

        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in))
        {
            for (int s = 0; s < wb.getNumberOfSheets(); s++)
            {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = StringUtils.trimToEmpty(sheet.getSheetName());
                if (sheetName.contains("说明") || sheetName.contains("注意事项")) continue;
                sheetNames.add(sheetName);

                // ① 平铺表（系统导出的课表：表头含「排课编码」+「学期/期次或上课日」）→ 按列直读
                Map<String, Integer> flatHead = findFlatHeader(sheet, fmt);
                if (flatHead != null)
                {
                    parseFlatSheet(sheet, flatHead, courseYear, terms, teacherByName, roomsByCampus, rows, fmt);
                    continue;
                }

                // ② 矩阵表：学期优先取 Sheet 名，其次取表内单元格（如 B2「暑假」、C3「秋季」）
                String termName = resolveTerm(sheetName, sheet, terms, fmt);

                int lastRow = sheet.getLastRowNum();
                String curPeriod = "";
                // 列号 -> 教室编码（第3行，索引2）
                Map<Integer, String> colRoomCode = new LinkedHashMap<>();
                // 表头定位：找「教室编号」所在行
                int headerRowIdx = -1;
                for (int r = 0; r <= Math.min(lastRow, 5); r++)
                {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    for (int c = 0; c <= row.getLastCellNum(); c++)
                    {
                        if ("教室编号".equals(cellText(row, c, fmt))) { headerRowIdx = r; break; }
                    }
                    if (headerRowIdx >= 0) break;
                }
                if (headerRowIdx < 0)
                {
                    // ③ 学校原始课表（块状版式）：学期写在单元格里（总表 B2「暑假」、各年级 C3「秋季」），
                    //    没有「教室编号」表头，故矩阵式定位失败时改走块状解析。
                    parseBlockSheet(sheet, courseYear, terms, grades, subjects, classTypes, timeSlotDict,
                            teacherByName, roomsByCampus, rows, fmt);
                    continue;
                }

                // 校区表头行（教室编号上一行）：合并单元格只在首列有值，向后延续
                Row campusRow = sheet.getRow(headerRowIdx - 1);
                // 教室编码行
                Row codeRow = sheet.getRow(headerRowIdx);
                if (codeRow != null)
                    for (int c = 2; c <= codeRow.getLastCellNum(); c++)
                    {
                        String code = cellText(codeRow, c, fmt);
                        if (StringUtils.isNotEmpty(code) && ROOM_CODE.matcher(code).matches()) colRoomCode.put(c, code.toUpperCase());
                    }

                for (int r = headerRowIdx + 1; r <= lastRow; r++)
                {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String a = cellText(row, 0, fmt);
                    String b = cellText(row, 1, fmt);
                    if (StringUtils.isNotEmpty(a))
                    {
                        String p = a;
                        if (termName != null && p.startsWith(termName)) p = p.substring(termName.length());
                        curPeriod = StringUtils.trimToEmpty(p);
                    }
                    // 时段：B列形如 第1节\n8:00-10:00
                    Matcher tm = TIME_SLOT.matcher(b.replace("\n", ""));
                    String timeSlot = tm.find() ? normSlot(tm.group(1)) + "-" + normSlot(tm.group(2)) : "";

                    // 校区按列延续：遍历本行教室列时，用编码行上方最近的非空校区表头
                    for (Map.Entry<Integer, String> en : colRoomCode.entrySet())
                    {
                        int c = en.getKey();
                        String code = en.getValue();
                        String campus = campusOfColumn(campusRow, c, fmt);
                        String text = normalize(cellText(row, c, fmt));
                        if (StringUtils.isEmpty(text)) continue;

                        Map<String, Object> item = new LinkedHashMap<>();
                        List<String> warns = new ArrayList<>();
                        if (StringUtils.isEmpty(termName)) warns.add("未识别学期（Sheet名/表内）");
                        if (StringUtils.isEmpty(curPeriod)) warns.add("未识别期次/星期");
                        if (StringUtils.isEmpty(timeSlot)) warns.add("未识别时段");
                        if (!timeSlotDict.contains(timeSlot) && StringUtils.isNotEmpty(timeSlot)) warns.add("时段不在字典");

                        EduClassroom room = matchRoom(campus, code, roomsByCampus);
                        if (room == null)
                        {
                            warns.add("未匹配教室");
                            item.put("lessonLocation", "地点：" + campus + code + "（未匹配）");
                        }

                        parseCourse(text, grades, subjects, classTypes, teacherByName, item);
                        String teacherName = (String) item.get("teacherName");
                        if (StringUtils.isEmpty(teacherName)) warns.add("未识别教师");
                        if (item.get("subjectName") == null) warns.add("未识别科目");
                        if (item.get("gradeName") == null) warns.add("未识别年级");

                        item.put("sheetName", sheetName);
                        item.put("sourceRow", (long) (r + 1));
                        item.put("sourceCol", code);
                        item.put("campusName", campus);
                        item.put("classroomCode", code);
                        item.put("sheetCode", code);
                        item.put("classroomId", room == null ? null : room.getClassroomId());
                        item.put("classroomName", room == null ? null : room.getClassroomName());
                        item.put("courseYear", courseYear);
                        item.put("termName", termName);
                        item.put("periodName", curPeriod);
                        item.put("timeSlot", timeSlot);
                        item.put("startTime", slotStart(timeSlot));
                        item.put("endTime", slotEnd(timeSlot));
                        item.put("rawText", text);
                        item.put("warning", String.join("；", warns));
                        rows.add(item);
                    }
                }
            }
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("课表文件解析失败：" + e.getMessage()); }

        if (rows.isEmpty()) throw new ServiceException("未从文件中解析出任何课程。支持两种课表：①矩阵式（行=时段、列=教室 W1/B1）；②平铺表（含「排课编码/学期/期次或上课日」表头，即系统导出的课表）");

        annotateDatePlan(rows, ranges, patterns);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courseYear", courseYear);
        out.put("sheets", sheetNames);
        out.put("total", rows.size());
        out.put("warnCount", rows.stream().filter(x -> StringUtils.isNotEmpty((String) x.get("warning"))).count());
        // datedCount：既能判定递推规则、又已设置好该学期起止日期，落库会真正写入日期的行数
        // undatedCount：其余行（学期/期次判定不出规则，或该学期还没设日期）→ 落库后保持「待排」
        out.put("datedCount", rows.stream()
                .filter(x -> x.get("plannedPattern") != null && Boolean.TRUE.equals(x.get("hasDateRange"))).count());
        out.put("undatedCount", rows.stream()
                .filter(x -> x.get("plannedPattern") == null || !Boolean.TRUE.equals(x.get("hasDateRange"))).count());
        // 按「学期」聚合出待设置日期的工作表分组，前端据此逐行给起止日期
        out.put("datePlans", datePlans(rows, ranges, patterns));
        out.put("rows", rows);
        return out;
    }

    /**
     * 按「学期 + 期次」聚合出「需要设置起止日期」的分组，供前端逐个填写。
     * <p>
     * 同一学期里不同期次的上课周期往往不同（寒假一期/二期各自的开课结课日、春季周六/周日各自的起止周），
     * 所以分组键用 {@link #rangeKey}（`学期|期次`）而不是只按学期；来源工作表一并列出便于核对。
     */
    private List<Map<String, Object>> datePlans(List<Map<String, Object>> rows, Map<String, LocalDate[]> ranges,
                                                Map<String, String> patterns)
    {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        Map<String, Set<String>> sheetsOf = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            String term = str(row.get("termName"));
            if (StringUtils.isEmpty(term)) term = PH_TERM;
            String period = str(row.get("periodName"));
            String key = rangeKey(term, period);
            String termLabel = term, periodLabel = period;
            grouped.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("termName", termLabel);
                m.put("periodName", periodLabel);
                m.put("rowCount", 0);
                return m;
            });
            grouped.get(key).put("rowCount", ((int) grouped.get(key).get("rowCount")) + 1);
            String sheet = str(row.get("sheetName"));
            if (StringUtils.isNotEmpty(sheet)) sheetsOf.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(sheet);
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Map<String, Object> plan : grouped.values())
        {
            String term = (String) plan.get("termName");
            String period = (String) plan.get("periodName");
            LocalDate[] range = rangeOf(ranges, term, period);
            String pattern = patternOf(patterns, term, period);
            plan.put("sheetNames", new ArrayList<>(sheetsOf.getOrDefault((String) plan.get("key"), new LinkedHashSet<>())));
            plan.put("startDate", range == null ? null : range[0].toString());
            plan.put("endDate", range == null ? null : range[1].toString());
            // pattern 为 null 表示该「学期+期次」判定不出上课模式（如「未识别学期」，或春秋季期次不是周一~周日），设了日期也仍是待排
            plan.put("pattern", pattern);
            plan.put("patternLabel", pattern == null ? "" : ScheduleCalendar.label(pattern));
            // 寒暑假可选「连上无假期 / 上5休1 / 上6休1 / 上5休2 / 上4休1」；春秋季固定每周；两者都可以选「其他（暂不排课）」
            List<String> patternOptions = new ArrayList<>();
            if (ScheduleCalendar.isDaily(pattern)) {
                patternOptions.addAll(List.of(ScheduleCalendar.DAILY, ScheduleCalendar.DAILY_5_1,
                        ScheduleCalendar.DAILY_6_1, ScheduleCalendar.DAILY_5_2, ScheduleCalendar.DAILY_4_1));
            } else if (ScheduleCalendar.WEEKLY.equals(pattern)) {
                patternOptions.add(ScheduleCalendar.WEEKLY);
            }
            patternOptions.add(ScheduleCalendar.OTHER);
            plan.put("dailyPatterns", patternOptions);
            plan.put("schedulable", pattern != null);
            plan.put("patternNote", pattern == null
                    ? "该学期/期次无法判定上课规则（寒假/暑假可选 连上无假期/上5休1/上6休1/上5休2/上4休1，春季/秋季=每周固定星期且期次需为周一~周日，也可选「其他」暂不排课），这些课次将保持待排"
                    : (ScheduleCalendar.WEEKLY.equals(pattern) ? "每周" + period : ScheduleCalendar.label(pattern)));
            plans.add(plan);
        }
        return plans;
    }

    /**
     * 给预览行标注「落库后将被写入的日期规则」，让用户在确认前就能核对：
     * 寒暑假 → 上5休1（DAILY_5_1）；春秋季 → 每周固定星期（WEEKLY，星期取期次/上课日）。
     * <p>
     * plannedPattern 只由「学期 + 期次」决定，与是否已设置起止日期无关 ——
     * 预览时用户往往还没填日期，但递推规则本身已经可以确定，先展示出来才便于核对；
     * 真正能否写入日期由 hasDateRange 表示（该「学期+期次」是否已设置起止日期）。
     */
    private void annotateDatePlan(List<Map<String, Object>> rows, Map<String, LocalDate[]> ranges,
                                  Map<String, String> patterns)
    {
        for (Map<String, Object> row : rows)
        {
            String term = str(row.get("termName"));
            String period = str(row.get("periodName"));
            String pattern = patternOf(patterns, term, period);
            LocalDate[] range = rangeOf(ranges, term, period);
            row.put("plannedPattern", pattern);
            row.put("plannedPatternLabel", pattern == null ? "" : ScheduleCalendar.label(pattern));
            row.put("hasDateRange", range != null);
            row.put("plannedStartDate", range == null ? null : range[0].toString());
            row.put("plannedEndDate", range == null ? null : range[1].toString());
            if (pattern == null)
            {
                row.put("datePlanNote", ScheduleCalendar.patternForTerm(term) == null
                        ? "学期无法判定上课规则（寒假/暑假可选 连上无假期/上5休1/上6休1/上5休2，春季/秋季=每周固定星期），保持待排"
                        : "期次/上课日需为周一至周日才能按每周递推");
            }
            else if (range == null)
            {
                row.put("datePlanNote", "尚未为该「学期+期次」设置起止日期，落库后保持待排");
            }
        }
    }

    /** 兼容旧调用：不指定递推模式，全部按学期默认（寒暑假=上5休1，春秋季=每周固定星期） */
    public Map<String, Object> oneClickImport(MultipartFile file, long courseYear, boolean updateSupport, String username,
                                              Map<String, LocalDate[]> ranges)
    {
        return oneClickImport(file, courseYear, updateSupport, username, ranges, Map.of());
    }

    /**
     * 一键导入：解析 + 同步主数据 + 落库（整体事务）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> oneClickImport(MultipartFile file, long courseYear, boolean updateSupport, String username,
                                              Map<String, LocalDate[]> ranges, Map<String, String> patterns)
    {
        Map<String, Object> pv = preview(file, courseYear, ranges, patterns);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) pv.get("rows");
        Map<String, Object> res = confirm(rows, updateSupport, username, ranges, patterns);
        res.put("sheets", pv.get("sheets"));
        res.put("total", pv.get("total"));
        res.put("datePlans", pv.get("datePlans"));
        return res;
    }

    // =================================================================
    // 主数据同步：缺失的教师账号 / 教室 / 期次·时段字典项 自动补建（不改表结构）
    // =================================================================
    private Map<String, Object> syncMasterData(List<Map<String, Object>> rows, String username)
    {
        Map<String, Object> stat = new LinkedHashMap<>();
        int newTeachers = 0, newRooms = 0, newDicts = 0;

        // ---- 教师：按「姓+老师」猜测名自动建号（默认密码 123456，角色=教师）----
        Long teacherRoleId = roleService.selectRoleList(new SysRole()).stream()
                .filter(r -> "teacher".equals(r.getRoleKey()))
                .map(SysRole::getRoleId).findFirst().orElse(null);
        Map<String, SysUser> existingByGuess = new HashMap<>();
        for (Map<String, Object> r : rows)
        {
            if (toLong(r.get("teacherId")) != null) continue;
            String guess = str(r.get("teacherGuess"));
            if (StringUtils.isEmpty(guess)) continue;
            SysUser created = existingByGuess.get(guess);
            if (created == null)
            {
                SysUser db = userService.selectUserByUserName(guess);
                if (db != null && "0".equals(db.getStatus()))
                {
                    created = db;
                }
                else if (teacherRoleId != null)
                {
                    created = new SysUser();
                    created.setUserName(guess);
                    created.setNickName(guess);
                    created.setPassword(com.ruoyi.common.utils.SecurityUtils.encryptPassword("123456"));
                    created.setStatus("0");
                    created.setDelFlag("0");
                    created.setCreateBy(username);
                    created.setRoleIds(new Long[] { teacherRoleId });
                    userService.insertUser(created);
                    newTeachers++;
                }
                existingByGuess.put(guess, created);
            }
            if (created != null)
            {
                r.put("teacherId", created.getUserId());
                r.put("teacherName", displayName(created));
            }
        }

        // ---- 教室：编码超出/缺失时按「校区+编码」自动新建 ----
        EduClassroom all = new EduClassroom();
        List<EduClassroom> rooms = classroomService.selectEduClassroomList(all);
        long maxCodeNum = rooms.stream().map(EduClassroom::getClassroomCode)
                .filter(c -> c != null && c.matches("ROOM\\d+"))
                .mapToLong(c -> Long.parseLong(c.substring(4))).max().orElse(0);
        Map<String, List<EduClassroom>> byCampus = rooms.stream()
                .filter(r -> StringUtils.isNotEmpty(r.getCampusName()))
                .collect(Collectors.groupingBy(EduClassroom::getCampusName, LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), l -> { l.sort(Comparator.comparing(EduClassroom::getClassroomId)); return l; })));
        Map<String, EduClassroom> createdRooms = new HashMap<>();
        for (Map<String, Object> r : rows)
        {
            if (toLong(r.get("classroomId")) != null) continue;
            String campus = str(r.get("campusName")), code = str(r.get("classroomCode"));
            if (StringUtils.isEmpty(campus) || StringUtils.isEmpty(code)) continue;
            String key = campus + "|" + code;
            EduClassroom room = createdRooms.get(key);
            if (room == null)
            {
                List<EduClassroom> list = byCampus.computeIfAbsent(campus, k -> new ArrayList<>());
                Matcher m = ROOM_CODE.matcher(code);
                boolean anyCode = list.stream().anyMatch(x -> StringUtils.isNotEmpty(x.getSheetCode()));
                if (anyCode)
                {
                    // 该校区已配置课表编码：编码就是教室的身份，按编码找，找不到才按编码新建。
                    room = findRoomBySheetCode(list, code);
                    if (room == null && m.matches()
                            && Integer.parseInt(m.group(2)) <= list.size() + MAX_AUTO_ROOM_GAP)
                    {
                        EduClassroom nc = new EduClassroom();
                        nc.setClassroomCode(String.format("ROOM%03d", ++maxCodeNum));
                        nc.setCampusName(campus);
                        nc.setSheetCode(code.toUpperCase());
                        nc.setClassroomName(campus + code.toUpperCase());
                        nc.setStatus("0");
                        nc.setCreateBy(username);
                        classroomService.insertEduClassroom(nc);
                        newRooms++;
                        EduClassroom back = new EduClassroom(); back.setCampusName(campus);
                        List<EduClassroom> fresh = classroomService.selectEduClassroomList(back);
                        fresh.sort(Comparator.comparing(EduClassroom::getClassroomId));
                        byCampus.put(campus, fresh);
                        room = findRoomBySheetCode(fresh, code);
                    }
                }
                else if (m.matches())
                {
                    int idx = Integer.parseInt(m.group(2)) - 1;
                    if (idx >= 0 && idx < list.size())
                    {
                        room = list.get(idx);
                    }
                    else if (idx - list.size() >= MAX_AUTO_ROOM_GAP)
                    {
                        // 编码明显越界（如 W99，实际只有 W1-W12）：大概率是源文件写错，
                        // 不能顺着编号一路补建出几十间垃圾教室 —— 留空，由课表红色感叹号提示人工核对。
                        continue;
                    }
                    else
                    {
                        // 补齐该校区缺失的编号教室（如 B 校区只有 B1-B4，出现 B6 则补 B5、B6）
                        for (int i = list.size(); i <= idx; i++)
                        {
                            EduClassroom nc = new EduClassroom();
                            nc.setClassroomCode(String.format("ROOM%03d", ++maxCodeNum));
                            nc.setCampusName(campus);
                            nc.setSheetCode(m.group(1).toUpperCase() + (i + 1));
                            nc.setClassroomName(campus + m.group(1).toUpperCase() + (i + 1));
                            nc.setStatus("0");
                            nc.setCreateBy(username);
                            classroomService.insertEduClassroom(nc);
                            newRooms++;
                            EduClassroom back = new EduClassroom(); back.setCampusName(campus);
                            List<EduClassroom> fresh = classroomService.selectEduClassroomList(back);
                            fresh.sort(Comparator.comparing(EduClassroom::getClassroomId));
                            byCampus.put(campus, fresh);
                            list = fresh;
                        }
                        room = list.size() > idx ? list.get(idx) : null;
                    }
                }
                createdRooms.put(key, room);
            }
            if (room != null)
            {
                r.put("classroomId", room.getClassroomId());
                r.put("classroomName", room.getClassroomName());
            }
        }

        // ---- 字典：期次 / 时段 缺失项自动补录 ----
        newDicts += syncDict("edu_term_period", rows, "periodName", username);
        newDicts += syncDict("edu_time_slot", rows, "timeSlot", username);

        stat.put("newTeachers", newTeachers);
        stat.put("newRooms", newRooms);
        stat.put("newDicts", newDicts);
        return stat;
    }

    private int syncDict(String dictType, List<Map<String, Object>> rows, String rowKey, String username)
    {
        List<String> labels = dictLabels(dictType);
        long maxSort = 0;
        SysDictData q = new SysDictData(); q.setDictType(dictType);
        List<SysDictData> items = dictDataService.selectDictDataList(q);
        for (SysDictData d : items) if (d.getDictSort() != null) maxSort = Math.max(maxSort, d.getDictSort());
        int created = 0;
        Set<String> todo = new LinkedHashSet<>();
        for (Map<String, Object> r : rows)
        {
            String v = str(r.get(rowKey));
            if (StringUtils.isNotEmpty(v) && !labels.contains(v)) todo.add(v);
        }
        for (String v : todo)
        {
            SysDictData nd = new SysDictData();
            nd.setDictType(dictType);
            nd.setDictLabel(v);
            nd.setDictValue(v);
            nd.setDictSort(++maxSort);
            nd.setStatus("0");
            nd.setCreateBy(username);
            dictDataService.insertDictData(nd);
            created++;
        }
        return created;
    }

    // =================================================================
    // 确认落库（整体事务：任一条失败则全部回滚）
    // =================================================================
    /** 兼容旧调用：不指定递推模式，全部按学期默认 */
    public Map<String, Object> confirm(List<Map<String, Object>> rows, boolean updateSupport, String username,
                                       Map<String, LocalDate[]> ranges)
    {
        return confirm(rows, updateSupport, username, ranges, Map.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(List<Map<String, Object>> rows, boolean updateSupport, String username,
                                       Map<String, LocalDate[]> ranges, Map<String, String> patterns)
    {
        if (rows == null || rows.isEmpty()) throw new ServiceException("没有可导入的数据");
        Map<String, Object> syncStat = syncMasterData(rows, username);
        int inserted = 0, updated = 0, skipped = 0, incomplete = 0, dated = 0, undated = 0, restored = 0;
        // 哪些学期没能写入日期（没设起止日期 / 学期·期次判定不出规则），导入后提示用户去补
        Set<String> undatedTerms = new LinkedHashSet<>();
        List<String> skipMsgs = new ArrayList<>();
        long seq = System.currentTimeMillis();
        for (Map<String, Object> r : rows)
        {
            Long classroomId = toLong(r.get("classroomId"));
            Long year = toLong(r.get("courseYear"));
            Long teacherId = toLong(r.get("teacherId"));
            String rawText = str(r.get("rawText"));
            String t0 = str(r.get("termName")), p0 = str(r.get("periodName")), sl0 = str(r.get("timeSlot"));
            String g0 = str(r.get("gradeName")), su0 = str(r.get("subjectName"));

            // 关键字段是否齐全：齐全走完整业务校验；不齐则宽松落库（保留问题格子，前端标红）
            boolean complete = classroomId != null && StringUtils.isNotEmpty(t0) && StringUtils.isNotEmpty(p0)
                    && StringUtils.isNotEmpty(sl0) && StringUtils.isNotEmpty(g0) && StringUtils.isNotEmpty(su0)
                    && teacherId != null;
            if (!complete) incomplete++;

            EduCourseSchedule s = new EduCourseSchedule();
            s.setCourseYear(year == null ? (long) Calendar.getInstance().get(Calendar.YEAR) : year);
            s.setTermName(StringUtils.isNotEmpty(t0) ? t0 : PH_TERM);
            s.setPeriodName(StringUtils.isNotEmpty(p0) ? p0 : PH_PERIOD);
            s.setTimeSlot(StringUtils.isNotEmpty(sl0) ? sl0 : PH_SLOT);
            s.setClassroomId(classroomId);
            s.setGradeName(StringUtils.isNotEmpty(g0) ? g0 : PH_GRADE);
            s.setSubjectName(StringUtils.isNotEmpty(su0) ? su0 : PH_SUBJECT);
            s.setClassType(str(r.get("classType")));
            s.setTeacherName(str(r.get("teacherName")));
            s.setTeacherId(teacherId);
            String ccn = str(r.get("courseClassName"));
            if (StringUtils.isEmpty(ccn)) ccn = rawText;
            if (StringUtils.isEmpty(ccn)) ccn = PH_CLASS;
            if (ccn.length() > 100) ccn = ccn.substring(0, 100);
            s.setCourseClassName(ccn);
            s.setSourceSheet(str(r.get("sheetName")));
            s.setSourceRow(toLong(r.get("sourceRow")));
            s.setSourceCol(str(r.get("sourceCol")));
            // 教室没匹配上时，把原文地点写进 lesson_location，课表卡片上能看出是哪个格子出的问题
            String loc = str(r.get("lessonLocation"));
            if (StringUtils.isEmpty(loc))
            {
                String ur = str(r.get("unmatchedRoom"));
                if (StringUtils.isNotEmpty(ur)) loc = "地点：" + ur + "（未匹配）";
            }
            if (StringUtils.isNotEmpty(loc)) s.setLessonLocation(loc);
            s.setClassMode("1");
            String st = str(r.get("startTime")), et = str(r.get("endTime"));
            if (StringUtils.isNotEmpty(st)) s.setStartTime(LocalTime.parse(st));
            if (StringUtils.isNotEmpty(et)) s.setEndTime(LocalTime.parse(et));

            // 日期规则：寒暑假「连上无假期 / 上5休1 / 上6休1 / 上5休2」、春秋季「每周固定星期」（星期取期次/上课日）。
            // 起止日期按「该行所属的 学期+期次」各自取（寒假一期/二期、春季周六/周日 的上课周期都不同）；
            // 没设日期、或学期/期次判定不出规则时保持「待排」不猜日期，并记下是哪个「学期/期次」漏了。
            LocalDate[] range = rangeOf(ranges, s.getTermName(), s.getPeriodName());
            String datePattern = range == null ? null
                    : patternOf(patterns, s.getTermName(), s.getPeriodName());
            if (datePattern != null)
            {
                s.setStartDate(java.sql.Date.valueOf(range[0]));
                s.setEndDate(java.sql.Date.valueOf(range[1]));
                s.setClassPattern(datePattern);
                dated++;
            }
            else
            {
                // 没写日期：可能是该「学期+期次」未设置起止日期，也可能是学期/期次判定不出递推规则
                undated++;
                undatedTerms.add(StringUtils.isEmpty(s.getTermName()) ? PH_TERM
                        : rangeKey(s.getTermName(), s.getPeriodName()).replace("|", " / "));
            }

            // 教室未知时不做「已存在」匹配：否则会按 学期+期次+时段 命中别人的格子
            EduCourseSchedule exist = classroomId == null ? null : findExisting(s);
            if (exist != null)
            {
                if (!updateSupport) { skipped++; if (skipMsgs.size() < 10) skipMsgs.add("已存在（未勾选覆盖）：" + s.getCourseClassName()); continue; }
                s.setScheduleId(exist.getScheduleId());
                // 教师补绑后自动解除停用：这类行当初就是因为「教师未绑定」才被置成停用，
                // 现在本次导入已经能定位到教师，就该回到正常状态——否则课表页只查 status='0'，
                // 会出现「预览识别成功、导完课表上还是看不到」的假象。
                // 只放开备注明确写着「教师未绑定」的行：冲突、原表缺信息等其它原因的停用必须留给人处理。
                if (teacherId != null && "1".equals(exist.getStatus()) && isUnboundTeacherStop(exist))
                {
                    s.setStatus("0");
                    s.setRemark("");   // 清掉已经失效的「需补充教师资料」提示
                    restored++;
                }
                s.setUpdateBy(username);
                if (complete)
                {
                    scheduleService.updateEduCourseSchedule(s);
                }
                else
                {
                    s.setUpdateTime(DateUtils.getNowDate());
                    courseScheduleMapper.updateEduCourseSchedule(s);
                }
                updated++;
            }
            else
            {
                s.setScheduleCode("IMP" + seq + (inserted + updated));
                s.setDelFlag("0"); s.setStatus("0"); s.setRecruitStatus("0"); s.setEnrolledCount(0L);
                s.setCreateBy(username);
                if (complete)
                {
                    scheduleService.insertEduCourseSchedule(s);
                }
                else
                {
                    s.setCreateTime(DateUtils.getNowDate());
                    courseScheduleMapper.insertEduCourseSchedule(s);
                }
                inserted++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("restored", restored);
        out.put("skipped", skipped);
        out.put("incomplete", incomplete);
        out.put("dated", dated);
        out.put("undated", undated);
        out.put("undatedTerms", new ArrayList<>(undatedTerms));
        out.put("skipMsgs", skipMsgs);
        out.put("sync", syncStat);

        // 覆盖导入时，以「本次文件」为准清理历史格子：
        //   同一工作表内，凡是本次文件已经不再包含的格子（学期+期次+时段+教室 四个键都没命中），
        //   就说明新课表把这些位置改成了「空课」—— 库里原有的旧行必须删掉，
        //   否则会留下「文件里明明是空的、课表上却有一节课」的幽灵课程
        //   （典型场景：改过教室映射/期次，或某节课被从课表上撤掉后重新导入）。
        // 两条安全边界：
        //   ① 只在该工作表「本次确实覆盖到的 学期+期次」范围内清理，本次没导到的期次一律不动；
        //   ② 只删「无任何关联」的行（无有效报名、无签到/考勤/调课/作业），
        //      有引用的保留原样并回报，交人工处理 —— 不会因为删不动而让整次导入回滚。
        int stale = 0, cleared = 0;
        List<String> staleMsgs = new ArrayList<>();
        List<String> keptMsgs = new ArrayList<>();
        if (updateSupport)
        {
            // 按工作表收集：本次文件覆盖到的「学期|期次」，以及文件里实际有课的格子键
            Map<String, Set<String>> importedTerms = new LinkedHashMap<>();
            Map<String, Set<String>> importedCells = new LinkedHashMap<>();
            Set<String> skipSheets = new HashSet<>();
            for (Map<String, Object> r : rows)
            {
                String sh = str(r.get("sheetName"));
                if (StringUtils.isEmpty(sh)) continue;
                String term = str(r.get("termName")), period = str(r.get("periodName"));
                if (StringUtils.isEmpty(term) || StringUtils.isEmpty(period)) { skipSheets.add(sh); continue; }
                importedTerms.computeIfAbsent(sh, k -> new HashSet<>()).add(term + "|" + period);
                importedCells.computeIfAbsent(sh, k -> new HashSet<>())
                        .add(cellKey(term, period, str(r.get("timeSlot")), toLong(r.get("classroomId"))));
            }
            Long year = toLong(rows.get(0).get("courseYear"));
            if (year == null) year = (long) Calendar.getInstance().get(Calendar.YEAR);
            List<Long> removable = new ArrayList<>();
            for (Map.Entry<String, Set<String>> en : importedTerms.entrySet())
            {
                String sheet = en.getKey();
                if (skipSheets.contains(sheet)) continue;
                EduCourseSchedule q = new EduCourseSchedule();
                q.setCourseYear(year);
                q.setSourceSheet(sheet);
                for (EduCourseSchedule e : scheduleService.selectEduCourseScheduleList(q))
                {
                    String t = str(e.getTermName()), p = str(e.getPeriodName());
                    // ① 该「学期/期次」本次文件没覆盖到 —— 可能只导了别的期次，不动它
                    if (!en.getValue().contains(t + "|" + p)) continue;
                    // ② 文件里这一格有课 —— 上面已按覆盖更新处理，不能删
                    if (importedCells.get(sheet).contains(cellKey(t, p, str(e.getTimeSlot()), e.getClassroomId()))) continue;
                    stale++;
                    String desc = String.format("%s：%s/%s %s %s —— %s（%s）", sheet, t, p, str(e.getTimeSlot()),
                            StringUtils.isNotEmpty(e.getSourceCol()) ? e.getSourceCol() + " 列" : "教室#" + e.getClassroomId(),
                            e.getCourseClassName(), e.getTeacherName());
                    if (scheduleService.isDeletable(e.getScheduleId()))
                    {
                        removable.add(e.getScheduleId());
                        cleared++;
                        if (staleMsgs.size() < 10) staleMsgs.add(desc);
                    }
                    else if (keptMsgs.size() < 10)
                    {
                        keptMsgs.add(desc);
                    }
                }
            }
            if (!removable.isEmpty())
            {
                scheduleService.deleteEduCourseScheduleByScheduleIds(removable.toArray(new Long[0]));
            }
        }
        out.put("stale", stale);
        out.put("cleared", cleared);
        out.put("keptStale", stale - cleared);
        out.put("staleMsgs", staleMsgs);
        out.put("keptMsgs", keptMsgs);
        // 导入数据涉及的「学期/期次」范围，供前端导入后自动切换到对应视图（避免"导入后页面没变化"）
        List<Map<String, Object>> scope = new ArrayList<>();
        Set<String> scopeSeen = new HashSet<>();
        for (Map<String, Object> r : rows)
        {
            String t = str(r.get("termName"));
            if (StringUtils.isEmpty(t)) continue;
            String p = str(r.get("periodName"));
            if (!scopeSeen.add(t + "|" + p)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("termName", t);
            m.put("periodName", p);
            scope.add(m);
        }
        out.put("scope", scope);
        return out;
    }

    /**
     * 匹配「已存在」的排课行。
     * <p>
     * ① 优先按「源文件位置」匹配（工作表 + 行号 + 列号）：
     * 同一个格子再次导入时，应当更新它原来那条记录，而不是另插一条。
     * 否则一旦教室映射/时段解析被修正，旧记录的匹配键（教室+时段）就和新键对不上，
     * 重导入会插入一条新行、把旧行留在库里 —— 课表上就会出现「文件里明明是空的，却有一节课」的幽灵课程。
     * <p>
     * ② 位置匹配不到时，退回按 (学年 + 学期 + 期次 + 教室 + 时段) 匹配（兼容手工新增、无来源信息的行）。
     */
    private EduCourseSchedule findExisting(EduCourseSchedule q)
    {
        if (StringUtils.isNotEmpty(q.getSourceSheet()) && q.getSourceRow() != null
                && StringUtils.isNotEmpty(q.getSourceCol()))
        {
            EduCourseSchedule byPos = new EduCourseSchedule();
            byPos.setCourseYear(q.getCourseYear());
            byPos.setSourceSheet(q.getSourceSheet());
            byPos.setSourceRow(q.getSourceRow());
            byPos.setSourceCol(q.getSourceCol());
            List<EduCourseSchedule> pl = scheduleService.selectEduCourseScheduleList(byPos);
            if (!pl.isEmpty()) return pl.get(0);
        }
        EduCourseSchedule probe = new EduCourseSchedule();
        probe.setCourseYear(q.getCourseYear());
        probe.setTermName(q.getTermName());
        probe.setPeriodName(q.getPeriodName());
        probe.setClassroomId(q.getClassroomId());
        probe.setTimeSlot(q.getTimeSlot());
        List<EduCourseSchedule> list = scheduleService.selectEduCourseScheduleList(probe);
        return list.isEmpty() ? null : list.get(0);
    }

    // =================================================================
    // 学校原始课表（块状版式）：学期/期次写在块头行的单元格里
    //   · 总表型：块头行 B=学期、C=期次，时段表头在 D/I/N/S/X…；数据行 C=教室名（万达21-1），
    //             每个时段列起 4 列 = 年级/科目/教师/班型
    //   · 各年级型：块头行 C=学期、D=期次，时段表头在 E/I/M/Q…；数据行 C=校区（万达/百家湖），
    //             一个数据行=该校区的一间教室，时段列里是整段课程文本
    // =================================================================
    private void parseBlockSheet(Sheet sheet, long courseYear, List<String> terms, List<String> grades,
                                 List<String> subjects, List<String> classTypes, Set<String> timeSlotDict,
                                 Map<String, SysUser> teacherByName, Map<String, List<EduClassroom>> roomsByCampus,
                                 List<Map<String, Object>> rows, DataFormatter fmt)
    {
        if (terms.isEmpty()) return;
        int lastRow = sheet.getLastRowNum();

        // 教室名索引：块内 C 列是教室名 → 总表型（教室可直接匹配）
        Map<String, EduClassroom> roomByName = new LinkedHashMap<>();
        for (List<EduClassroom> l : roomsByCampus.values())
            for (EduClassroom r : l)
                if (StringUtils.isNotEmpty(r.getClassroomName())) roomByName.put(normalize(r.getClassroomName()), r);

        // 预扫：哪一列（0..3）最像教室名 → 总表型；否则视为各年级型
        int roomCol = -1, best = 0;
        for (int c = 0; c <= 3; c++)
        {
            int hit = 0;
            for (int r = 0; r <= lastRow; r++)
            {
                String v = normalize(cellText(sheet.getRow(r), c, fmt));
                if (StringUtils.isNotEmpty(v) && roomByName.containsKey(v)) hit++;
            }
            if (hit > best) { best = hit; roomCol = c; }
        }
        boolean byRoom = roomCol >= 0;
        if (!byRoom) roomCol = 2;   // 各年级型：C 列是校区
        String sheetName = StringUtils.trimToEmpty(sheet.getSheetName());

        String term = null, period = null;
        List<Integer> slotCols = new ArrayList<>();
        Map<Integer, String> slotName = new LinkedHashMap<>();
        boolean inBlock = false;
        String curCampus = null;
        int campusCursor = 0;

        for (int r = 0; r <= lastRow; r++)
        {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // 块头行：0..3 列里出现学期字典项
            int termCol = -1;
            for (int c = 0; c <= 3; c++)
            {
                String v = normalize(cellText(row, c, fmt));
                if (StringUtils.isNotEmpty(v) && terms.contains(v)) { termCol = c; break; }
            }
            if (termCol >= 0)
            {
                term = normalize(cellText(row, termCol, fmt));
                period = normalize(cellText(row, termCol + 1, fmt));
                slotCols.clear(); slotName.clear();
                for (int c = termCol + 2; c < row.getLastCellNum(); c++)
                {
                    Matcher tm = TIME_SLOT.matcher(normalize(cellText(row, c, fmt)));
                    if (tm.find())
                    {
                        slotCols.add(c);
                        slotName.put(c, normSlot(tm.group(1)) + "-" + normSlot(tm.group(2)));
                    }
                }
                inBlock = !slotCols.isEmpty();
                curCampus = null; campusCursor = 0;
                continue;
            }
            if (!inBlock) continue;

            // 数据行
            String label = normalize(cellText(row, roomCol, fmt));
            if (byRoom)
            {
                EduClassroom room = StringUtils.isEmpty(label) ? null : roomByName.get(label);
                // 教室名识别不到也要保留这一格（前端标红感叹号），只有整行时段列全空才跳过
                boolean any = false;
                for (int k : slotCols) if (StringUtils.isNotEmpty(normalize(cellText(row, k, fmt)))) { any = true; break; }
                if (!any) continue;
                for (int k : slotCols)
                {
                    String g = normalize(cellText(row, k, fmt));
                    String subj = normalize(cellText(row, k + 1, fmt));
                    String tname = normalize(cellText(row, k + 2, fmt));
                    String ctype = normalize(cellText(row, k + 3, fmt));
                    // 四列全空才算空格子；只要有一列有内容就保留（哪怕识别不到，前端标红）
                    if (StringUtils.isEmpty(g) && StringUtils.isEmpty(subj) && StringUtils.isEmpty(tname) && StringUtils.isEmpty(ctype)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("gradeName", StringUtils.trimToNull(g));
                    item.put("subjectName", StringUtils.trimToNull(subj));
                    item.put("classType", StringUtils.trimToNull(ctype));
                    item.put("courseClassName", StringUtils.trimToEmpty(g) + StringUtils.trimToEmpty(subj) + StringUtils.trimToEmpty(tname));
                    fillTeacher(item, tname, teacherByName);
                    Map<String, Object> bi = buildBlockItem(item, sheetName, r, k, room, term, period, slotName.get(k), courseYear, timeSlotDict);
                    if (room == null && StringUtils.isNotEmpty(label)) markUnmatchedRoom(bi, label);
                    rows.add(bi);
                }
            }
            else
            {
                // 各年级型：C 列出现校区名 → 换校区并从头数教室；一个数据行 = 一间教室
                if (StringUtils.isNotEmpty(label))
                {
                    curCampus = campusOf(label);
                    campusCursor = 0;
                }
                boolean any = false;
                for (int k : slotCols) if (StringUtils.isNotEmpty(normalize(cellText(row, k, fmt)))) { any = true; break; }
                if (!any) continue;
                // 校区还没识别到也要保留格子（教室置空 → 落在「未指定教室」列并标红）
                List<EduClassroom> list = StringUtils.isEmpty(curCampus) ? null : roomsByCampus.get(curCampus);
                EduClassroom room = (list != null && campusCursor < list.size()) ? list.get(campusCursor) : null;
                if (StringUtils.isNotEmpty(curCampus)) campusCursor++;
                for (int k : slotCols)
                {
                    String text = normalize(cellText(row, k, fmt));
                    if (StringUtils.isEmpty(text)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    parseCourse(text, grades, subjects, classTypes, teacherByName, item);
                    Map<String, Object> bi = buildBlockItem(item, sheetName, r, k, room, term, period, slotName.get(k), courseYear, timeSlotDict);
                    if (room == null && StringUtils.isNotEmpty(label)) markUnmatchedRoom(bi, label);
                    rows.add(bi);
                }
            }
        }
    }

    /** 块状行组装成预览行（含告警） */
    private Map<String, Object> buildBlockItem(Map<String, Object> item, String sheetName, int rowIdx, int col,
                                               EduClassroom room, String term, String period, String slot,
                                               long courseYear, Set<String> timeSlotDict)
    {
        item.put("sheetName", sheetName);
        item.put("sourceRow", (long) (rowIdx + 1));
        item.put("sourceCol", colLetter(col));
        item.put("campusName", room == null ? "" : room.getCampusName());
        item.put("classroomCode", room == null ? "" : room.getClassroomCode());
        item.put("sheetCode", room == null ? null : room.getSheetCode());
        item.put("classroomId", room == null ? null : room.getClassroomId());
        item.put("classroomName", room == null ? null : room.getClassroomName());
        item.put("courseYear", courseYear);
        item.put("termName", term);
        item.put("periodName", period);
        item.put("timeSlot", slot);
        item.put("startTime", slotStart(slot));
        item.put("endTime", slotEnd(slot));
        item.put("rawText", item.get("courseClassName"));

        List<String> warns = new ArrayList<>();
        if (StringUtils.isEmpty(term)) warns.add("未识别学期（表内）");
        if (StringUtils.isEmpty(period)) warns.add("未识别期次/星期");
        if (StringUtils.isEmpty(slot)) warns.add("未识别时段");
        if (StringUtils.isNotEmpty(slot) && !timeSlotDict.contains(slot)) warns.add("时段不在字典");
        if (room == null) warns.add("未匹配教室");
        if (StringUtils.isEmpty((String) item.get("teacherName"))) warns.add("未识别教师");
        if (item.get("subjectName") == null) warns.add("未识别科目");
        if (item.get("gradeName") == null) warns.add("未识别年级");
        item.put("warning", String.join("；", warns));
        return item;
    }

    /**
     * 教室名/校区名识别不到时，把原文留在 classroomCode + lessonLocation 上：
     * · classroomCode → 预览表显示「未匹配(万达21-9)」
     * · lessonLocation → 课表卡片显示「地点：万达21-9（未匹配）」，让问题格子一眼可定位
     */
    private void markUnmatchedRoom(Map<String, Object> item, String label)
    {
        item.put("classroomCode", label);
        item.put("unmatchedRoom", label);
        item.put("lessonLocation", "地点：" + label + "（未匹配）");
        String w = (String) item.get("warning");
        item.put("warning", StringUtils.isEmpty(w) ? "未匹配教室「" + label + "」" : w + "；未匹配教室「" + label + "」");
    }

    private void fillTeacher(Map<String, Object> item, String teacherName, Map<String, SysUser> teacherByName)
    {
        SysUser t = matchTeacher(teacherName, teacherByName);
        if (t != null)
        {
            item.put("teacherName", displayName(t));
            item.put("teacherId", t.getUserId());
            item.put("teacherGuess", null);
        }
        else if (StringUtils.isNotEmpty(teacherName))
        {
            item.put("teacherName", teacherName);
            item.put("teacherId", null);
            item.put("teacherGuess", teacherName);
        }
        else
        {
            item.put("teacherName", null);
            item.put("teacherId", null);
            item.put("teacherGuess", null);
        }
    }

    /** 「万达校区（10间教室）」→ 万达 */
    private String campusOf(String v)
    {
        if (StringUtils.isEmpty(v)) return "";
        int k = v.indexOf("校区");
        return (k > 0 ? v.substring(0, k) : v).trim();
    }

    private String colLetter(int col)
    {
        StringBuilder sb = new StringBuilder();
        int n = col;
        while (n >= 0) { sb.insert(0, (char) ('A' + n % 26)); n = n / 26 - 1; }
        return sb.toString();
    }

    // =================================================================
    // 平铺表（系统导出的课表）：按列直读，含「学期」列
    // =================================================================
    /**
     * 识别平铺表表头：前 4 行内同时出现「排课编码」与「学期/期次或上课日/时段」即判定为平铺表。
     * 返回「表头文本 -> 列号」映射；不是平铺表返回 null。
     */
    private Map<String, Integer> findFlatHeader(Sheet sheet, DataFormatter fmt)
    {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 3); r++)
        {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, Integer> idx = new LinkedHashMap<>();
            for (int c = 0; c <= row.getLastCellNum(); c++)
            {
                String h = normalize(cellText(row, c, fmt));
                if (StringUtils.isNotEmpty(h)) idx.putIfAbsent(h, c);
            }
            if (idx.containsKey("排课编码") && (idx.containsKey("学期") || idx.containsKey("期次或上课日") || idx.containsKey("时段")))
                return idx;
        }
        return null;
    }

    /**
     * 平铺表逐行解析：教室/年级/科目/教师/班型/时段等已是独立列，无需再从课程文本里猜。
     * 学期列若为空，回退用「来源Sheet」里的学期（如「寒假排课表」→ 寒假）。
     */
    private void parseFlatSheet(Sheet sheet, Map<String, Integer> head, long courseYear, List<String> terms,
                                Map<String, SysUser> teacherByName, Map<String, List<EduClassroom>> roomsByCampus,
                                List<Map<String, Object>> rows, DataFormatter fmt)
    {
        Set<String> timeSlotDict = new HashSet<>(dictLabels("edu_time_slot"));
        String sheetName = StringUtils.trimToEmpty(sheet.getSheetName());
        Map<Long, EduClassroom> roomById = new LinkedHashMap<>();
        for (List<EduClassroom> l : roomsByCampus.values())
            for (EduClassroom rm : l) roomById.put(rm.getClassroomId(), rm);

        for (int r = 0; r <= sheet.getLastRowNum(); r++)
        {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String code = flatCell(row, head, "排课编码", fmt);
            String term = flatCell(row, head, "学期", fmt);
            String period = flatCell(row, head, "期次或上课日", fmt);
            String slot = flatCell(row, head, "时段", fmt);
            String grade = flatCell(row, head, "年级", fmt);
            String subject = flatCell(row, head, "科目", fmt);
            String teacherName = flatCell(row, head, "教师姓名", fmt);
            String classType = flatCell(row, head, "班型", fmt);
            String className = flatCell(row, head, "课程班名称", fmt);
            String srcSheet = flatCell(row, head, "来源Sheet", fmt);
            String srcCol = flatCell(row, head, "来源列", fmt);
            String roomIdText = flatCell(row, head, "教室ID", fmt);
            String yearText = flatCell(row, head, "课程年份", fmt);
            String st = flatCell(row, head, "开始时间", fmt);
            String et = flatCell(row, head, "结束时间", fmt);

            // 跳过表头行与空行
            if ("排课编码".equals(code)) continue;
            if (StringUtils.isEmpty(code) && StringUtils.isEmpty(term) && StringUtils.isEmpty(slot)
                    && StringUtils.isEmpty(grade) && StringUtils.isEmpty(className)) continue;

            // 学期：优先「学期」列；为空则从「来源Sheet」识别（寒假排课表 → 寒假）
            if (StringUtils.isEmpty(term) && StringUtils.isNotEmpty(srcSheet))
                term = terms.stream().filter(srcSheet::contains).findFirst().orElse(null);

            Long roomId = toLong(roomIdText);
            EduClassroom room = roomId == null ? null : roomById.get(roomId);
            Long year = toLong(yearText);

            List<String> warns = new ArrayList<>();
            if (StringUtils.isEmpty(term)) warns.add("未识别学期（学期列/来源Sheet）");
            if (StringUtils.isEmpty(period)) warns.add("未识别期次/上课日");
            if (StringUtils.isEmpty(slot)) warns.add("未识别时段");
            if (StringUtils.isNotEmpty(slot) && !timeSlotDict.contains(slot)) warns.add("时段不在字典");
            if (room == null) warns.add("未匹配教室");

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gradeName", StringUtils.trimToNull(grade));
            item.put("subjectName", StringUtils.trimToNull(subject));
            item.put("classType", StringUtils.trimToNull(classType));
            item.put("courseClassName", StringUtils.isNotEmpty(className)
                    ? className : (StringUtils.trimToEmpty(grade) + StringUtils.trimToEmpty(subject)));

            // 教师：先按姓名匹配库内账号，匹配不到则保留姓名由 syncMasterData 自动建号
            SysUser t = matchTeacher(teacherName, teacherByName);
            if (t != null)
            {
                item.put("teacherName", displayName(t));
                item.put("teacherId", t.getUserId());
                item.put("teacherGuess", null);
            }
            else if (StringUtils.isNotEmpty(teacherName))
            {
                item.put("teacherName", teacherName);
                item.put("teacherId", null);
                item.put("teacherGuess", teacherName);
            }
            else
            {
                item.put("teacherName", null);
                item.put("teacherId", null);
                item.put("teacherGuess", null);
            }
            if (StringUtils.isEmpty((String) item.get("teacherName"))) warns.add("未识别教师");
            if (item.get("subjectName") == null) warns.add("未识别科目");
            if (item.get("gradeName") == null) warns.add("未识别年级");

            item.put("sheetName", sheetName);
            item.put("sourceRow", (long) (r + 1));
            item.put("sourceCol", StringUtils.isEmpty(srcCol) ? null : srcCol);
            item.put("campusName", room == null ? "" : room.getCampusName());
            item.put("classroomCode", room == null ? (roomId == null ? "" : String.valueOf(roomId)) : room.getClassroomCode());
            item.put("classroomId", room == null ? null : room.getClassroomId());
            item.put("classroomName", room == null ? null : room.getClassroomName());
            item.put("courseYear", year != null ? year : courseYear);
            item.put("termName", term);
            item.put("periodName", period);
            item.put("timeSlot", slot);
            item.put("startTime", StringUtils.isNotEmpty(st) ? toLocalTime(st) : slotStart(slot));
            item.put("endTime", StringUtils.isNotEmpty(et) ? toLocalTime(et) : slotEnd(slot));
            item.put("rawText", StringUtils.isNotEmpty(className) ? className
                    : (StringUtils.trimToEmpty(grade) + StringUtils.trimToEmpty(subject) + StringUtils.trimToEmpty(teacherName)));
            item.put("warning", String.join("；", warns));
            rows.add(item);
        }
    }

    private String flatCell(Row row, Map<String, Integer> head, String key, DataFormatter fmt)
    {
        Integer c = head.get(key);
        return c == null ? "" : normalize(cellText(row, c, fmt));
    }

    /** 教师姓名匹配库内账号：先精确，再互相包含（长名优先） */
    private SysUser matchTeacher(String name, Map<String, SysUser> teacherByName)
    {
        if (StringUtils.isEmpty(name)) return null;
        name = teacherKey(name);
        SysUser exact = teacherByName.get(name);
        if (exact != null) return exact;
        SysUser best = null; String bestKey = null;
        for (Map.Entry<String, SysUser> en : teacherByName.entrySet())
        {
            String k = en.getKey();
            if (name.contains(k) || k.contains(name))
                if (bestKey == null || k.length() > bestKey.length()) { bestKey = k; best = en.getValue(); }
        }
        return best;
    }

    // =================================================================
    // 矩阵表学期识别：Sheet 名 → 表内单元格
    // =================================================================
    /**
     * 学期识别顺序：
     *   1) Sheet 名里包含的学期字典项（寒假排课表 → 寒假）
     *   2) 表内单元格直接就是学期字典项（B2「暑假」、C3「秋季」）
     *   3) 「学期」标签单元格的右邻 / 下邻
     */
    private String resolveTerm(String sheetName, Sheet sheet, List<String> terms, DataFormatter fmt)
    {
        String byName = terms.stream().filter(sheetName::contains).findFirst().orElse(null);
        if (byName != null) return byName;
        if (terms.isEmpty()) return null;
        String labelled = null;
        int lastRow = Math.min(sheet.getLastRowNum(), 20);
        for (int r = 0; r <= lastRow; r++)
        {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++)
            {
                String v = normalize(cellText(row, c, fmt));
                if (StringUtils.isEmpty(v)) continue;
                for (String t : terms) if (v.equals(t)) return t;
                if (v.startsWith("学期"))
                {
                    String right = normalize(cellText(row, c + 1, fmt));
                    for (String t : terms) if (right.equals(t)) labelled = t;
                    if (labelled == null)
                    {
                        String down = normalize(cellText(sheet.getRow(r + 1), c, fmt));
                        for (String t : terms) if (down.equals(t)) labelled = t;
                    }
                }
            }
        }
        return labelled;
    }

    // =================================================================
    // 单元格文本解析：年级/科目/班型/教师
    // =================================================================
    private void parseCourse(String text, List<String> grades, List<String> subjects, List<String> classTypes,
                             Map<String, SysUser> teacherByName, Map<String, Object> item)
    {
        // 年级：取文本中最早出现、且最靠前的（按长度降序避免「一年级」误配「二年级」）
        String grade = null; int gradePos = Integer.MAX_VALUE;
        for (String g : grades) { int p = text.indexOf(g); if (p >= 0 && p < gradePos) { gradePos = p; grade = g; } }
        // 科目
        String subject = null; int subjectPos = Integer.MAX_VALUE;
        for (String sb : subjects) { int p = text.indexOf(sb); if (p >= 0 && p < subjectPos) { subjectPos = p; subject = sb; } }
        // 班型
        String classType = null; int classPos = Integer.MAX_VALUE;
        for (String ct : classTypes) { int p = text.indexOf(ct); if (p >= 0 && p < classPos) { classPos = p; classType = ct; } }
        // 教师：优先库内教师名（nick/user）最长匹配
        String teacherText = teacherKey(text);
        SysUser matched = null; String matchedName = null;
        for (Map.Entry<String, SysUser> en : teacherByName.entrySet())
        {
            if (teacherText.contains(en.getKey()))
            {
                if (matchedName == null || en.getKey().length() > matchedName.length()) { matchedName = en.getKey(); matched = en.getValue(); }
            }
        }
        String teacher = null; Long teacherId = null; String teacherGuess = null;
        if (matched != null)
        {
            teacher = displayName(matched);
            teacherId = matched.getUserId();
        }
        else
        {
            // ① 旧课表：取文本中最后一个「X老师」的姓，过滤虚词
            Matcher m = TEACHER_FALLBACK.matcher(text);
            String surname = null;
            while (m.find()) surname = m.group(1);
            if (surname != null && TEACHER_STOP_CHARS.indexOf(surname) < 0)
            {
                teacherGuess = surname + "老师";
                teacher = teacherGuess;
            }
            // ② 现用课表：单元格是「初二英语常规班\n王文晶」，姓名在末尾、没有「老师」二字。
            //    这种情况以前完全兜不住：既匹配不到账号、也不会自动建号，只能人工补，于是永久「未识别教师」。
            //    这里把年级/科目/班型/括号补充说明都摘掉，剩下的纯姓名才作为建号候选。
            if (teacherGuess == null)
            {
                String cand = teacherNameCandidate(text, grade, subject, classType);
                if (cand != null) { teacherGuess = cand; teacher = cand; }
            }
        }
        item.put("gradeName", grade);
        item.put("subjectName", subject);
        item.put("classType", classType);
        item.put("teacherName", teacher);
        item.put("teacherId", teacherId);
        item.put("teacherGuess", teacherGuess);
        item.put("courseClassName", text);
    }

    /**
     * 从「年级 + 科目 + 班型 + 姓名」结构的单元格文本里摘出教师姓名，供自动建号使用。
     * <p>只在「摘干净之后正好剩下 2~4 个汉字」时才认：摘不干净（例如「幼小衔接」这种字典里没有的前缀、
     * 或原文带了备注）一律返回 null，宁可继续标红让人工补，也不能拿半截文字去建一个假教师账号。
     */
    private String teacherNameCandidate(String text, String grade, String subject, String classType)
    {
        String s = normalize(text);
        if (StringUtils.isEmpty(s)) return null;
        s = BRACKET_CONTENT.matcher(s).replaceAll("");
        if (StringUtils.isNotEmpty(grade))     s = s.replace(grade, "");
        if (StringUtils.isNotEmpty(subject))   s = s.replace(subject, "");
        if (StringUtils.isNotEmpty(classType)) s = s.replace(classType, "");
        // 字典里没有的班型写法（如原表错别字「常规版」）也要摘掉，否则会连着姓名一起留下来
        s = CLASS_TYPE_LOOSE.matcher(s).replaceAll("");
        s = s.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (s.length() < 2 || s.length() > 4) return null;
        if (TEACHER_STOP_CHARS.indexOf(s.charAt(0)) >= 0) return null;
        if (s.matches(".*[班版级科课学老].*")) return null;
        return s;
    }

    /**
     * 该排课是不是「因为教师没绑定所以被停用」。
     * <p>只有这一类停用能在补绑教师后自动恢复；冲突、原表缺信息等其它停用原因必须留给人处理。
     */
    private static boolean isUnboundTeacherStop(EduCourseSchedule exist)
    {
        return exist != null && StringUtils.trimToEmpty(exist.getRemark()).contains("教师未绑定");
    }

    // =================================================================
    // 工具
    // =================================================================
    private Map<String, SysUser> loadTeacherNames()
    {
        Map<String, SysUser> map = new LinkedHashMap<>();
        Long teacherRoleId = roleService.selectRoleList(new SysRole()).stream()
                .filter(r -> "teacher".equals(r.getRoleKey()))
                .map(SysRole::getRoleId).findFirst().orElse(null);
        if (teacherRoleId == null) return map;
        SysUser q = new SysUser(); q.setRoleId(teacherRoleId);
        // 长名优先排序，避免短名抢先匹配（按真名/昵称里较长的那个算长度）
        List<SysUser> users = userService.selectAllocatedList(q).stream()
                .filter(u -> "0".equals(u.getStatus()))
                .sorted(Comparator.comparingInt((SysUser u) ->
                        Math.max(StringUtils.length(u.getUserName()), StringUtils.length(u.getNickName()))).reversed())
                .collect(Collectors.toList());
        // 真名优先：先把全部 user_name 占位，再用 nick_name 补空缺。
        // 课表里写「张晨涵」或旧课表的「张老师」都能认到同一账号，且真名不会被昵称覆盖。
        for (SysUser u : users) if (StringUtils.isNotEmpty(u.getUserName())) map.putIfAbsent(teacherKey(u.getUserName()), u);
        for (SysUser u : users) if (StringUtils.isNotEmpty(u.getNickName())) map.putIfAbsent(teacherKey(u.getNickName()), u);
        return map;
    }

    /** 对课表和账号两端做同样的姓名归一化，不改原课程文本或账号。 */
    private String teacherKey(String name)
    {
        String normalized = normalize(name);
        String variant = variantAlias(normalized);
        return variant == null ? normalized : variant;
    }

    /**
     * 中文异体字 → 常用字。返回 null 表示不含需要归一的字。
     * <p>只登记「已经实际踩到」的异体字，不做猜测性扩散：误映射会把课挂到错误教师名下。
     */
    private static String variantAlias(String name)
    {
        String out = name;
        for (Map.Entry<Character, Character> e : CN_VARIANT_TO_COMMON.entrySet())
        {
            if (out.indexOf(e.getKey()) >= 0) out = out.replace(e.getKey(), e.getValue());
        }
        return out.equals(name) ? null : out;
    }

    /** 异体字 → 常用字（单向）。新增前必须有真实数据佐证。 */
    private static final Map<Character, Character> CN_VARIANT_TO_COMMON = Map.of(
            '\u8D07', '\u8D5F'   // 贇(U+8D07) → 赟(U+8D5F)，例：陈贇 / 陈赟
    );

    /** 登录名是手机号或自动账号时，使用教师昵称。 */
    private static String displayName(SysUser u)
    {
        if (u == null) return null;
        return com.ruoyi.system.service.impl.EduCourseScheduleServiceImpl.realNameOf(
                Map.of("userName", StringUtils.trimToEmpty(u.getUserName()), "nickName", StringUtils.trimToEmpty(u.getNickName())));
    }

    private List<String> dictLabels(String dictType)
    {
        SysDictData q = new SysDictData(); q.setDictType(dictType); q.setStatus("0");
        return dictDataService.selectDictDataList(q).stream()
                .sorted(Comparator.comparing(SysDictData::getDictSort))
                .map(SysDictData::getDictLabel)
                .collect(Collectors.toList());
    }

    /**
     * 教室编码 → 教室。
     * <p>
     * ① 该校区已配置课表编码（edu_classroom.sheet_code，如万达 W1~W12、百家湖 B1~B5）时，**按编码精确匹配**；
     * 编码对不上就返回 null，让课表标红提示人工核对 —— 绝不按「校区第 N 间」猜一个出来。
     * 位置猜测在教室增删或改序时会静默错位，把课排到别的教室去（曾导致 W6 空位里出现 W7 的课）。
     * <p>
     * ② 该校区完全没配置编码时，退回旧规则（W1 = 该校区第 1 间）作为兼容。
     */
    private EduClassroom matchRoom(String campus, String code, Map<String, List<EduClassroom>> roomsByCampus)
    {
        List<EduClassroom> list = roomsByCampus.get(campus);
        if (list == null || list.isEmpty()) return null;
        String c = StringUtils.trimToEmpty(code).toUpperCase();
        if (StringUtils.isEmpty(c)) return null;
        boolean anyCode = false;
        for (EduClassroom r : list)
        {
            if (StringUtils.isNotEmpty(r.getSheetCode())) { anyCode = true; break; }
        }
        if (anyCode) return findRoomBySheetCode(list, c);
        Matcher m = ROOM_CODE.matcher(c);
        if (!m.matches()) return null;
        int idx = Integer.parseInt(m.group(2)) - 1;
        if (idx < 0 || idx >= list.size()) return null;
        return list.get(idx);
    }

    /** 在给定教室列表里按课表编码查找（大小写不敏感、忽略空白） */
    private EduClassroom findRoomBySheetCode(List<EduClassroom> list, String code)
    {
        String c = StringUtils.trimToEmpty(code).toUpperCase();
        if (StringUtils.isEmpty(c) || list == null) return null;
        for (EduClassroom r : list)
        {
            if (c.equals(StringUtils.trimToEmpty(r.getSheetCode()).toUpperCase())) return r;
        }
        return null;
    }

    /** 列 c 的校区：从表头行 c 向左找最近的非空「XX校区」 */
    private String campusOfColumn(Row campusRow, int c, DataFormatter fmt)
    {
        if (campusRow == null) return "";
        for (int x = c; x >= 0; x--)
        {
            String v = cellText(campusRow, x, fmt);
            if (StringUtils.isNotEmpty(v))
            {
                int k = v.indexOf("校区");
                return k > 0 ? v.substring(0, k).trim() : v.trim();
            }
        }
        return "";
    }

    private String cellText(Row row, int col, DataFormatter fmt)
    {
        if (row == null) return "";
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return StringUtils.trimToEmpty(cell.getStringCellValue());
        if (cell.getCellType() == CellType.BLANK) return "";
        return StringUtils.trimToEmpty(fmt.formatCellValue(cell));
    }

    /** 去空白/换行/全角空格，统一冒号 */
    private String normalize(String s)
    {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u3000]+", "");
    }

    private String normSlot(String hhmm)
    {
        String v = hhmm.replace("：", ":");
        String[] p = v.split(":");
        if (p.length != 2) return v;
        return String.format("%d:%s", Integer.parseInt(p[0]), p[1]);
    }

    private String slotStart(String slot)
    {
        if (StringUtils.isEmpty(slot) || !slot.contains("-")) return null;
        return toLocalTime(slot.split("-")[0]);
    }

    private String slotEnd(String slot)
    {
        if (StringUtils.isEmpty(slot) || !slot.contains("-")) return null;
        return toLocalTime(slot.split("-")[1]);
    }

    /**
     * 统一成 HH:mm:ss。同时接受 H:mm、HH:mm 与 HH:mm:ss（系统导出文件就是带秒的格式，
     * 导出后再导回来必须能解析），秒位忽略。
     */
    private String toLocalTime(String hhmm)
    {
        if (StringUtils.isEmpty(hhmm)) return null;
        String v = hhmm.replace("：", ":").trim();
        String[] p = v.split(":");
        if (p.length < 2) return null;
        try
        {
            return String.format("%02d:%02d:00", Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private String str(Object o) { return o == null ? null : StringUtils.trimToNull(String.valueOf(o)); }

    /**
     * 课表格子键：`学期|期次|时段|教室`。
     * 用来回答「新课表里这一格到底有没有课」—— 库里同键的行就是这一格上的课。
     * 教室为空（未匹配到教室）时用空串占位，保证「同一格」能稳定比较。
     */
    private String cellKey(String term, String period, String slot, Long classroomId)
    {
        return StringUtils.trimToEmpty(term) + "|" + StringUtils.trimToEmpty(period) + "|"
                + StringUtils.trimToEmpty(slot) + "|" + (classroomId == null ? "" : classroomId);
    }

    private Long toLong(Object o)
    {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
