package com.ruoyi.system.timetable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.domain.EduCourseSchedule;

/**
 * 可视化课表导出。
 * <p>第一张表「课表」按屏幕上的版式原样导出：纵轴=时段、横轴=教室、表头按校区合并；
 * 第二张表「排课明细」是同一批数据的平表，方便教务筛选、透视、发群。
 * <p>只导出当前筛选条件下的数据（年份/学期/期次/校区/年级/科目/教师），与页面所见一致。
 */
@Service
public class TimetableExportService
{
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String PH_UNKNOWN_ROOM = "未指定教室";
    private static final String PH_UNKNOWN_SLOT = "未指定时段";

    public void export(HttpServletResponse response, String fileBaseName, String title, String subtitle,
                       List<EduClassroom> rooms, List<String> slots, List<EduCourseSchedule> rows) throws IOException
    {
        try (XSSFWorkbook wb = new XSSFWorkbook())
        {
            Map<Long, EduClassroom> roomById = new LinkedHashMap<>();
            for (EduClassroom r : rooms) if (r.getClassroomId() != null) roomById.put(r.getClassroomId(), r);

            buildMatrixSheet(wb, title, subtitle, rooms, slots, rows);
            buildDetailSheet(wb, rows, roomById);

            String fileName = URLEncoder.encode(fileBaseName, "UTF-8").replaceAll("\\+", "%20");
            response.reset();
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
            OutputStream out = response.getOutputStream();
            wb.write(out);
            out.flush();
        }
    }

    // =================================================================
    // 第一张表：矩阵课表（与页面版式一致）
    // =================================================================
    private void buildMatrixSheet(Workbook wb, String title, String subtitle, List<EduClassroom> rooms,
                                  List<String> slots, List<EduCourseSchedule> rows)
    {
        Sheet sheet = wb.createSheet("课表");
        StyleKit kit = new StyleKit(wb);

        // 横轴：按校区分组，顺序沿用教室列表本身的顺序（与页面一致）
        Map<String, List<EduClassroom>> byCampus = new LinkedHashMap<>();
        for (EduClassroom r : rooms)
        {
            String campus = StringUtils.isEmpty(r.getCampusName()) ? "未分配校区" : r.getCampusName();
            byCampus.computeIfAbsent(campus, k -> new ArrayList<>()).add(r);
        }
        boolean hasUnspecRoom = rows.stream().anyMatch(s -> s.getClassroomId() == null);
        boolean hasUnspecSlot = rows.stream().anyMatch(s -> StringUtils.isEmpty(s.getTimeSlot()));

        int totalCols = 1 + rooms.size() + (hasUnspecRoom ? 1 : 0);

        // ① 标题 + 副标题（筛选条件、导出时间）
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(26);
        Cell tc = titleRow.createCell(0);
        tc.setCellValue(title);
        tc.setCellStyle(kit.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.max(0, totalCols - 1)));

        Row subRow = sheet.createRow(1);
        Cell sc = subRow.createCell(0);
        sc.setCellValue(subtitle);
        sc.setCellStyle(kit.subtitle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, Math.max(0, totalCols - 1)));

        // ② 表头两行：校区（跨列） + 教室
        Row campusRow = sheet.createRow(2);
        Row roomRow = sheet.createRow(3);
        campusRow.setHeightInPoints(20);
        roomRow.setHeightInPoints(32);

        Cell corner = campusRow.createCell(0);
        corner.setCellValue("时段 ↓ / 教室 →");
        corner.setCellStyle(kit.header);
        sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));

        int col = 1;
        for (Map.Entry<String, List<EduClassroom>> en : byCampus.entrySet())
        {
            int span = en.getValue().size();
            Cell h = campusRow.createCell(col);
            h.setCellValue("— " + en.getKey() + "（" + span + " 间）—");
            h.setCellStyle(kit.header);
            if (span > 1) sheet.addMergedRegion(new CellRangeAddress(2, 2, col, col + span - 1));
            else if (span == 0) continue;
            for (int i = 0; i < span; i++)
            {
                EduClassroom r = en.getValue().get(i);
                Cell rc = roomRow.createCell(col + i);
                String roomName = nvl(r.getClassroomName());
                String code = StringUtils.trimToEmpty(r.getSheetCode());
                // 教室名里通常已经带了编码（万达W1），只有没带时才补一行编码，避免出现「万达W1 / W1」
                rc.setCellValue(StringUtils.isEmpty(code) || roomName.contains(code)
                        ? roomName : roomName + "\n" + code);
                rc.setCellStyle(kit.header);
            }
            col += span;
        }
        if (hasUnspecRoom)
        {
            Cell u = campusRow.createCell(col);
            u.setCellValue(PH_UNKNOWN_ROOM);
            u.setCellStyle(kit.headerWarn);
            sheet.addMergedRegion(new CellRangeAddress(2, 3, col, col));
        }

        // ③ 数据行：一格可有多门课（冲突），逐条换行输出
        Map<String, List<EduCourseSchedule>> cellMap = new LinkedHashMap<>();
        for (EduCourseSchedule s : rows)
        {
            String key = key(s.getClassroomId(), s.getTimeSlot());
            cellMap.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        int r = 4;
        for (String slot : slots)
        {
            r = writeSlotRow(sheet, kit, r, slot, rooms, hasUnspecRoom, cellMap);
        }
        if (hasUnspecSlot)
        {
            writeSlotRow(sheet, kit, r, null, rooms, hasUnspecRoom, cellMap);
        }

        // ④ 列宽 / 冻结
        sheet.setColumnWidth(0, 16 * 256);
        for (int i = 1; i < totalCols; i++) sheet.setColumnWidth(i, 18 * 256);
        sheet.createFreezePane(1, 4);
    }

    /** 写一行「时段 + 各教室格子」，返回下一行行号。 */
    private int writeSlotRow(Sheet sheet, StyleKit kit, int rowIdx, String slot, List<EduClassroom> rooms,
                             boolean hasUnspecRoom, Map<String, List<EduCourseSchedule>> cellMap)
    {
        Row row = sheet.createRow(rowIdx);
        row.setHeightInPoints(46);

        Cell head = row.createCell(0);
        head.setCellValue(slot == null ? PH_UNKNOWN_SLOT : slot);
        head.setCellStyle(slot == null ? kit.headerWarn : kit.slotHead);

        int c = 1;
        for (EduClassroom room : rooms)
        {
            Cell cell = row.createCell(c++);
            List<EduCourseSchedule> list = cellMap.get(key(room.getClassroomId(), slot));
            cell.setCellValue(list == null ? "" : cellText(list));
            cell.setCellStyle(kit.cell);
        }
        if (hasUnspecRoom)
        {
            Cell cell = row.createCell(c);
            List<EduCourseSchedule> list = cellMap.get(key(null, slot));
            cell.setCellValue(list == null ? "" : cellText(list));
            cell.setCellStyle(kit.cellWarn);
        }
        return rowIdx + 1;
    }

    /** 一格内的文本：课程一行、教师一行；同格多门课之间用分隔线隔开。 */
    private String cellText(List<EduCourseSchedule> list)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++)
        {
            EduCourseSchedule s = list.get(i);
            if (i > 0) sb.append("\n— — —\n");
            sb.append(courseLabel(s)).append('\n').append(teacherLabel(s));
        }
        return sb.toString();
    }

    private String courseLabel(EduCourseSchedule s)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.isEmpty(s.getGradeName()) ? "年级未填" : s.getGradeName());
        sb.append(' ').append(StringUtils.isEmpty(s.getSubjectName()) ? "科目未填" : s.getSubjectName());
        if (StringUtils.isNotEmpty(s.getClassType())) sb.append(' ').append(s.getClassType());
        return sb.toString();
    }

    private String teacherLabel(EduCourseSchedule s)
    {
        if (StringUtils.isNotEmpty(s.getTeacherName())) return s.getTeacherName();
        // 教师未绑定账号时，至少把原文里的地点线索带出来，方便人工定位是哪一格
        return "教师未填";
    }

    // =================================================================
    // 第二张表：排课明细（平表）
    // =================================================================
    private void buildDetailSheet(Workbook wb, List<EduCourseSchedule> rows, Map<Long, EduClassroom> roomById)
    {
        Sheet sheet = wb.createSheet("排课明细");
        StyleKit kit = new StyleKit(wb);
        String[] heads = { "学期", "期次", "时段", "开始时间", "结束时间", "校区", "教室", "年级", "科目", "班型",
                           "课程班名称", "教师", "报名数", "招生状态", "状态" };
        Row head = sheet.createRow(0);
        head.setHeightInPoints(22);
        for (int i = 0; i < heads.length; i++)
        {
            Cell c = head.createCell(i);
            c.setCellValue(heads[i]);
            c.setCellStyle(kit.header);
        }
        int r = 1;
        for (EduCourseSchedule s : rows)
        {
            EduClassroom room = s.getClassroomId() == null ? null : roomById.get(s.getClassroomId());
            Row row = sheet.createRow(r++);
            int c = 0;
            set(row, c++, nvl(s.getTermName()), kit.cell);
            set(row, c++, nvl(s.getPeriodName()), kit.cell);
            set(row, c++, nvl(s.getTimeSlot()), kit.cell);
            set(row, c++, s.getStartTime() == null ? "" : s.getStartTime().format(HH_MM), kit.cell);
            set(row, c++, s.getEndTime() == null ? "" : s.getEndTime().format(HH_MM), kit.cell);
            set(row, c++, room == null ? "" : nvl(room.getCampusName()), kit.cell);
            set(row, c++, room == null ? (StringUtils.isEmpty(s.getLessonLocation()) ? "" : s.getLessonLocation())
                                        : nvl(room.getClassroomName()), kit.cell);
            set(row, c++, nvl(s.getGradeName()), kit.cell);
            set(row, c++, nvl(s.getSubjectName()), kit.cell);
            set(row, c++, nvl(s.getClassType()), kit.cell);
            set(row, c++, nvl(s.getCourseClassName()), kit.cell);
            set(row, c++, teacherLabel(s), kit.cell);
            set(row, c++, s.getEnrolledCount() == null ? "" : String.valueOf(s.getEnrolledCount()), kit.cell);
            set(row, c++, recruitLabel(s.getRecruitStatus()), kit.cell);
            set(row, c++, "1".equals(s.getStatus()) ? "停用" : "正常", kit.cell);
        }
        int[] widths = { 8, 10, 14, 10, 10, 8, 16, 10, 10, 10, 26, 12, 8, 10, 8 };
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        sheet.createFreezePane(0, 1);
    }

    private void set(Row row, int col, String value, CellStyle style)
    {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    private static String recruitLabel(String s)
    {
        if ("1".equals(s)) return "停招";
        if ("2".equals(s)) return "满班";
        return "可报";
    }

    private static String key(Long classroomId, String timeSlot)
    {
        return (classroomId == null ? "-" : String.valueOf(classroomId)) + "@"
                + (StringUtils.isEmpty(timeSlot) ? "-" : timeSlot);
    }

    private static String nvl(String v)
    {
        return v == null ? "" : v;
    }

    /** 复用的样式集合：同一份工作簿里字体/样式对象数量有上限，统一定义一次。 */
    private static final class StyleKit
    {
        final CellStyle title, subtitle, header, headerWarn, slotHead, cell, cellWarn;

        StyleKit(Workbook wb)
        {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            Font subFont = wb.createFont();
            subFont.setFontHeightInPoints((short) 10);
            subFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setFontHeightInPoints((short) 10);

            Font cellFont = wb.createFont();
            cellFont.setFontHeightInPoints((short) 10);

            title = wb.createCellStyle();
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            subtitle = wb.createCellStyle();
            subtitle.setFont(subFont);
            subtitle.setAlignment(HorizontalAlignment.LEFT);
            subtitle.setVerticalAlignment(VerticalAlignment.CENTER);

            header = base(wb, headFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setWrapText(true);

            headerWarn = base(wb, headFont);
            headerWarn.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            headerWarn.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerWarn.setWrapText(true);

            slotHead = base(wb, headFont);
            slotHead.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            slotHead.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            slotHead.setWrapText(true);

            cell = base(wb, cellFont);
            cell.setWrapText(true);
            cell.setVerticalAlignment(VerticalAlignment.CENTER);

            cellWarn = base(wb, cellFont);
            cellWarn.setWrapText(true);
            cellWarn.setVerticalAlignment(VerticalAlignment.CENTER);
            cellWarn.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            cellWarn.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static CellStyle base(Workbook wb, Font font)
        {
            CellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setAlignment(HorizontalAlignment.CENTER);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setBorderTop(BorderStyle.THIN);
            st.setBorderBottom(BorderStyle.THIN);
            st.setBorderLeft(BorderStyle.THIN);
            st.setBorderRight(BorderStyle.THIN);
            st.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            st.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            st.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            st.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            return st;
        }
    }

    /** 供 Controller 拼文件名用：把筛选条件写成人能看懂的一段。 */
    public static String buildFileBaseName(Integer year, String term, String period, String campus)
    {
        StringBuilder sb = new StringBuilder("课表");
        if (year != null) sb.append('_').append(year);
        if (StringUtils.isNotEmpty(term)) sb.append('_').append(term);
        if (StringUtils.isNotEmpty(period)) sb.append('_').append(period);
        if (StringUtils.isNotEmpty(campus)) sb.append('_').append(campus);
        return sb.toString();
    }

    /** LocalTime 转 HH:mm（供 Controller 组装副标题时复用，避免各处口径不一致）。 */
    public static String hhmm(LocalTime t)
    {
        return t == null ? "" : t.format(HH_MM);
    }
}
