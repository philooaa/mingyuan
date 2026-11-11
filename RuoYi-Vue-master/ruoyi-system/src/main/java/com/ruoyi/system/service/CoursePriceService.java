package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.FinanceRules;
import com.ruoyi.system.shop.ShopRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程定价：单价按年级固定（一年级/二年级 150、三年级~六年级 220、初中 250、高中 280），
 * 课程总价 = 每节单价 × 本期课次数量。
 *
 * <p>课次数量 {@code lesson_count} 与 {@link ScheduleCalendar} 的展开口径一致；
 * 自动计费按当前排课日历计算，手动覆盖仅改变后续课程定价，不改历史订单。
 */
@Service
public class CoursePriceService {
    private final ShopRepository db;

    public CoursePriceService(ShopRepository db) {
        this.db = db;
    }

    /** 年级定价规则（含该年级已定价课程数，便于判断是否需要同步）。 */
    public List<Map<String, Object>> rules() {
        return db.rows(
                "SELECT r.rule_id,r.grade_name,r.unit_price,r.status,r.remark,"
                        + "(SELECT COUNT(*) FROM edu_course_schedule s WHERE s.del_flag='0' AND s.grade_name=r.grade_name) course_count,"
                        + "(SELECT COUNT(*) FROM edu_course_schedule s WHERE s.del_flag='0' AND s.grade_name=r.grade_name"
                        + "   AND s.unit_price<>r.unit_price) pending_count "
                        + "FROM edu_course_price_rule r ORDER BY r.rule_id");
    }

    /** 年级（规则顺序在前，再补上排课里出现但还没建规则的年级）与已有学科。 */
    public Map<String, Object> dims() {
        List<String> grades =
                new ArrayList<>(
                        db.jdbc().queryForList("SELECT grade_name FROM edu_course_price_rule ORDER BY rule_id", String.class));
        for (String extra :
                db.jdbc()
                        .queryForList(
                                "SELECT DISTINCT grade_name FROM edu_course_schedule WHERE del_flag='0' AND grade_name<>'' ORDER BY grade_name",
                                String.class))
            if (!grades.contains(extra)) grades.add(extra);
        List<String> subjects =
                db.jdbc()
                        .queryForList(
                                "SELECT DISTINCT subject_name FROM edu_course_schedule WHERE del_flag='0' AND subject_name<>'' ORDER BY subject_name",
                                String.class);
        return FinanceRules.values("grades", grades, "subjects", subjects);
    }

    /** 课程定价预览：按年级、学科筛选出受影响的课程。 */
    public Map<String, Object> courses(String gradeName, String subjectName, int pageNum, int pageSize) {        StringBuilder where = new StringBuilder(" WHERE s.del_flag='0'");
        List<Object> args = new ArrayList<>();
        if (!blank(gradeName)) {
            where.append(" AND s.grade_name=?");
            args.add(gradeName.trim());
        }
        if (!blank(subjectName)) {
            where.append(" AND s.subject_name=?");
            args.add(subjectName.trim());
        }
        Integer total =
                db.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM edu_course_schedule s" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        int size = Math.max(1, Math.min(pageSize, 200));
        int offset = Math.max(0, (Math.max(1, pageNum) - 1) * size);
        pageArgs.add(size);
        pageArgs.add(offset);
        var rows =
                db.rows(
                        "SELECT s.schedule_id,s.course_class_name,s.grade_name,s.subject_name,s.term_name,s.period_name,"
                                + "s.class_mode,s.lesson_count,s.lesson_count_override,s.lesson_count_override_reason,s.unit_price,s.tuition_price,"
                                + "s.start_date,s.end_date,s.class_pattern,"
                                + "COALESCE(NULLIF(s.campus_name,''),c.campus_name) campus_name "
                                + "FROM edu_course_schedule s LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id"
                                + where
                                + " ORDER BY s.grade_name,s.subject_name,s.term_name,s.period_name,s.schedule_id"
                                + " LIMIT ? OFFSET ?",
                        pageArgs.toArray());
        rows.forEach(this::decorateLessonCount);
        return FinanceRules.values("rows", rows, "total", total == null ? 0 : total);
    }

    /** 按年级+学科批量改价；syncRule 为真时同时更新该年级的定价规则。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batch(Map<String, Object> body, long actor) {
        String grade = FinanceRules.str(body, "gradeName");
        String subject = FinanceRules.str(body, "subjectName");
        FinanceRules.require(!grade.isBlank(), "请选择年级");
        BigDecimal price = FinanceRules.money(body.get("unitPrice"));
        FinanceRules.require(price.signum() > 0, "课程单价必须大于 0");
        boolean syncRule = Boolean.TRUE.equals(body.get("syncRule")) || "true".equalsIgnoreCase(FinanceRules.str(body, "syncRule"));

        String where = " WHERE del_flag='0' AND grade_name=?";
        List<Object> args = new ArrayList<>();
        args.add(grade);
        if (!subject.isBlank()) {
            where += " AND subject_name=?";
            args.add(subject);
        }
        int affected = db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_course_schedule" + where, Integer.class, args.toArray());
        FinanceRules.require(affected > 0, "没有符合条件的课程");

        refreshLessonCount(grade, subject);

        List<Object> updateArgs = new ArrayList<>();
        updateArgs.add(price);
        updateArgs.add(price);
        updateArgs.add(SecurityUtils.getUsername());
        updateArgs.addAll(args);
        int updated =
                db.jdbc()
                        .update(
                                "UPDATE edu_course_schedule SET unit_price=?,"
                                        + " tuition_price=ROUND(? * GREATEST(COALESCE(lesson_count,1),1),2),"
                                        + " update_by=?,update_time=NOW()"
                                        + where,
                                updateArgs.toArray());

        if (syncRule) {
            db.jdbc()
                    .update(
                            "UPDATE edu_course_price_rule SET unit_price=?,update_by=?,update_time=NOW() WHERE grade_name=?",
                            price,
                            SecurityUtils.getUsername(),
                            grade);
        }
        return FinanceRules.values("updated", updated, "unitPrice", price.toPlainString(), "gradeName", grade, "subjectName", subject);
    }

    /** 把某个年级的规则价套用到该年级全部课程。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRule(Map<String, Object> body, long actor) {
        String grade = FinanceRules.str(body, "gradeName");
        FinanceRules.require(!grade.isBlank(), "请选择年级");
        var rule =
                db.one(
                        "SELECT grade_name,unit_price FROM edu_course_price_rule WHERE grade_name=? AND status='0'",
                        grade);
        BigDecimal price = FinanceRules.money(rule.get("unitPrice"));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("gradeName", grade);
        req.put("unitPrice", price.toPlainString());
        req.put("syncRule", false);
        return batch(req, actor);
    }

    /** 更新年级规则单价；applyCourses 为真时同步该年级全部课程。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateRule(Map<String, Object> body, long actor) {
        String grade = FinanceRules.str(body, "gradeName");
        FinanceRules.require(!grade.isBlank(), "请选择年级");
        BigDecimal price = FinanceRules.money(body.get("unitPrice"));
        FinanceRules.require(price.signum() > 0, "课程单价必须大于 0");
        db.one("SELECT rule_id FROM edu_course_price_rule WHERE grade_name=?", grade);
        db.jdbc()
                .update(
                        "UPDATE edu_course_price_rule SET unit_price=?,update_by=?,update_time=NOW() WHERE grade_name=?",
                        price,
                        SecurityUtils.getUsername(),
                        grade);
        if (Boolean.TRUE.equals(body.get("applyCourses")) || "true".equalsIgnoreCase(FinanceRules.str(body, "applyCourses"))) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("gradeName", grade);
            req.put("unitPrice", price.toPlainString());
            req.put("syncRule", false);
            return batch(req, actor);
        }
        return FinanceRules.values("gradeName", grade, "unitPrice", price.toPlainString(), "updated", 0);
    }

    /** Auto courses follow the current calendar; explicit overrides survive later repricing. */
    private void refreshLessonCount(String grade, String subject) {
        String sql="SELECT schedule_id FROM edu_course_schedule WHERE del_flag='0' AND grade_name=?";
        List<Object> args=new ArrayList<>(); args.add(grade);
        if (!subject.isBlank()) { sql+=" AND subject_name=?"; args.add(subject); }
        for (var row:db.rows(sql,args.toArray())) syncSchedulePricing(Long.parseLong(row.get("scheduleId").toString()));
    }

    public static Integer systemLessonCount(Map<String,Object> row, List<Map<String,Object>> adjustments) {
        String pattern=text(row.get("classPattern")), period=text(row.get("periodName"));
        if (pattern.isBlank() && ScheduleCalendar.weekday(period)>0) pattern=ScheduleCalendar.WEEKLY;
        var dates=ScheduleCalendar.dates(ScheduleCalendar.date(row.get("startDate")),
            ScheduleCalendar.date(row.get("endDate")),pattern,period,adjustments);
        return dates.isEmpty()?null:dates.size();
    }

    public static Integer billingLessonCount(Map<String,Object> row,Integer calculated) {
        if (row.get("lessonCountOverride")!=null) return Integer.valueOf(row.get("lessonCountOverride").toString());
        if (calculated!=null) return calculated;
        Object saved=row.get("lessonCount");
        return saved!=null && Integer.parseInt(saved.toString())>0?Integer.valueOf(saved.toString()):null;
    }

    public static void validateCountChange(Integer chosen,Integer calculated,Map<String,Object> body) {
        FinanceRules.require(chosen!=null && chosen>0 && chosen<=3661,"计费课次数须为1至3661的整数");
        if (!Objects.equals(chosen,calculated)) {
            FinanceRules.require(Boolean.TRUE.equals(body.get("confirmMismatch")),"计费课次数与系统计算不一致，请确认后保存");
            String reason=FinanceRules.str(body,"reason");
            FinanceRules.require(!reason.isBlank() && reason.length()<=255,"请填写255字以内的修改原因");
        }
    }

    private Map<String,Object> decorateLessonCount(Map<String,Object> row) {
        long id=Long.parseLong(row.get("scheduleId").toString());
        Integer calculated=systemLessonCount(row,adjustments(id));
        try {
            StringJoiner version=new StringJoiner("|");
            for(String key:List.of("scheduleId","startDate","endDate","classPattern","periodName","lessonCountOverride","lessonCount","unitPrice","tuitionPrice"))
                version.add(Objects.toString(row.get(key),""));
            version.add(Objects.toString(calculated,""));
            row.put("lessonCountVersion",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(version.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        row.put("systemLessonCount",calculated);
        row.put("lessonCount",billingLessonCount(row,calculated));
        row.put("manualLessonCount",row.get("lessonCountOverride")!=null);
        return row;
    }

    private Map<String,Object> countRow(long id,boolean lock) {
        return db.one("SELECT schedule_id,course_class_name,class_mode,start_date,end_date,class_pattern,period_name,"
            +"lesson_count,lesson_count_override,lesson_count_override_reason,unit_price,tuition_price FROM edu_course_schedule "
            +"WHERE schedule_id=? AND del_flag='0'"+(lock?" FOR UPDATE":""),id);
    }

    public Map<String,Object> lessonCount(long id) { return decorateLessonCount(countRow(id,false)); }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> updateLessonCount(long id,Map<String,Object> body) {
        FinanceRules.require(SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin") || SecurityUtils.hasRole("business_admin") || SecurityUtils.hasRole("education"),"仅教务和管理员可修改计费课次数");
        var row=decorateLessonCount(countRow(id,true));
        FinanceRules.require(!"2".equals(text(row.get("classMode"))),"一对一课次按预约管理，请修改班课计费课次数");
        FinanceRules.require(Objects.equals(row.get("lessonCountVersion"),body.get("version")),"课程信息已变化，请重新打开后确认");
        Integer calculated=(Integer)row.get("systemLessonCount"), chosen;
        boolean automatic="AUTO".equals(FinanceRules.str(body,"mode"));
        FinanceRules.require(automatic || "MANUAL".equals(FinanceRules.str(body,"mode")),"请选择自动或手动计费方式");
        if(automatic) {
            FinanceRules.require(calculated!=null,"排课日期或规则不完整，暂时无法恢复自动计算"); chosen=calculated;
        } else {
            try { chosen=Integer.valueOf(FinanceRules.str(body,"lessonCount")); }
            catch(NumberFormatException e) { throw new ServiceException("计费课次数须为整数"); }
            validateCountChange(chosen,calculated,body);
        }
        String reason=automatic?null:FinanceRules.str(body,"reason");
        FinanceRules.require(reason==null || reason.length()<=255,"修改原因不能超过255字");
        db.jdbc().update("UPDATE edu_course_schedule SET lesson_count_override=?,lesson_count_override_reason=?,lesson_count=?,"
            +"tuition_price=CASE WHEN unit_price>0 THEN ROUND(unit_price*?,2) ELSE tuition_price END,update_by=?,update_time=NOW() WHERE schedule_id=?",
            automatic?null:chosen,reason,chosen,chosen,SecurityUtils.getUsername(),id);
        return lessonCount(id);
    }

    /** Update only course defaults; enrollment/payment/receipt snapshots are deliberately untouched. */
    public void syncSchedulePricing(long id) {
        var row=countRow(id,true);
        if("2".equals(text(row.get("classMode")))) return;
        Integer chosen=billingLessonCount(row,systemLessonCount(row,adjustments(id)));
        if(chosen==null) return;
        db.jdbc().update("UPDATE edu_course_schedule SET lesson_count=?,tuition_price=CASE WHEN unit_price>0 THEN ROUND(unit_price*?,2) ELSE tuition_price END WHERE schedule_id=?",chosen,chosen,id);
    }

    /** 与 RoomAssignService 同一口径：日期级调课记录展开后再叠加本课自己的单条记录。 */
    private List<Map<String, Object>> adjustments(long scheduleId) {
        return db.rows(
                "SELECT a.original_date,a.adjusted_date FROM edu_schedule_adjustment a "
                        + "JOIN edu_course_schedule s ON s.schedule_id=? "
                        + "WHERE a.schedule_id=s.schedule_id OR (a.schedule_id=0 AND a.term_name=s.term_name "
                        + "AND a.period_name=s.period_name AND NOT EXISTS (SELECT 1 FROM edu_schedule_adjustment x "
                        + "WHERE x.schedule_id=s.schedule_id AND x.original_date=a.original_date)) ORDER BY a.adjustment_id",
                scheduleId);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 供其它服务复用：年级默认单价。 */
    public BigDecimal rulePrice(String gradeName) {
        var rows = db.rows("SELECT unit_price FROM edu_course_price_rule WHERE grade_name=? AND status='0'", gradeName);
        if (rows.isEmpty()) throw new ServiceException("该年级还没有定价规则：" + gradeName);
        return FinanceRules.money(rows.get(0).get("unitPrice"));
    }
}
