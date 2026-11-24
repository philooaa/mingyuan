package com.ruoyi.system.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.shop.ShopRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** Rule templates only: editing a template never changes an existing schedule. */
@RestController
@RequestMapping("/system/schedule-rules")
@PreAuthorize("@ss.hasAnyRoles('admin,business_admin,education')")
public class EduScheduleRuleController extends BaseController {
    private final ShopRepository db;
    public EduScheduleRuleController(ShopRepository db) { this.db = db; }

    @PreAuthorize("@ss.hasAnyRoles('admin,business_admin,education') or @ss.hasPermi('system:schedule:add') or @ss.hasPermi('system:courseschedule:add')")
    @GetMapping
    public AjaxResult list(@RequestParam(defaultValue = "false") boolean enabledOnly) {
        return success(db.rows("SELECT r.rule_id,r.rule_name,r.status,r.version,r.remark,r.update_time,DATE_FORMAT(MIN(d.class_date),'%Y-%m-%d') first_date,DATE_FORMAT(MAX(d.class_date),'%Y-%m-%d') last_date,COUNT(d.class_date) date_count FROM edu_schedule_rule r LEFT JOIN edu_schedule_rule_date d ON d.rule_id=r.rule_id WHERE r.del_flag='0'" + (enabledOnly ? " AND r.status='0'" : "") + " GROUP BY r.rule_id,r.rule_name,r.status,r.version,r.remark,r.update_time ORDER BY r.rule_id DESC"));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,business_admin,education') or @ss.hasPermi('system:schedule:add') or @ss.hasPermi('system:courseschedule:add')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable long id) {
        var rows = db.rows("SELECT rule_id,rule_name,status,version,remark FROM edu_schedule_rule WHERE rule_id=? AND del_flag='0'", id);
        if (rows.isEmpty()) throw new ServiceException("排课规则不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("dates", db.rows("SELECT DATE_FORMAT(class_date,'%Y-%m-%d') class_date FROM edu_schedule_rule_date WHERE rule_id=? ORDER BY class_date", id).stream().map(row -> row.get("classDate")).toList());
        return success(result);
    }

    @com.ruoyi.common.annotation.Log(title="排课规则",businessType=com.ruoyi.common.enums.BusinessType.INSERT)
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult create(@RequestBody Map<String, Object> body) {
        var values = validate(body);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rule_name", values.name()); row.put("status", values.status()); row.put("remark", values.remark());
        row.put("create_by", SecurityUtils.getUsername()); row.put("create_time", new Date()); row.put("update_by", SecurityUtils.getUsername()); row.put("update_time", new Date());
        long id = db.insert("edu_schedule_rule", row);
        saveDates(id, values.dates());
        return success(id);
    }

    @com.ruoyi.common.annotation.Log(title="排课规则",businessType=com.ruoyi.common.enums.BusinessType.UPDATE)
    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        var values = validate(body);
        Object version = body.get("version");
        if (version == null) throw new ServiceException("请刷新后重试");
        int changed = db.jdbc().update("UPDATE edu_schedule_rule SET rule_name=?,status=?,remark=?,version=version+1,update_by=?,update_time=NOW() WHERE rule_id=? AND del_flag='0' AND version=?", values.name(), values.status(), values.remark(), SecurityUtils.getUsername(), id, version);
        if (changed != 1) throw new ServiceException("规则已被修改，请刷新后重试");
        db.jdbc().update("DELETE FROM edu_schedule_rule_date WHERE rule_id=?", id);
        saveDates(id, values.dates());
        return success();
    }

    private void saveDates(long id, SortedSet<LocalDate> dates) {
        for (LocalDate date : dates) db.jdbc().update("INSERT INTO edu_schedule_rule_date(rule_id,class_date) VALUES (?,?)", id, java.sql.Date.valueOf(date));
    }
    private record Values(String name, String status, String remark, SortedSet<LocalDate> dates) {}
    private Values validate(Map<String, Object> body) {
        String name = Objects.toString(body.get("ruleName"), "").trim();
        String status = Objects.toString(body.get("status"), "0");
        String remark = Objects.toString(body.get("remark"), "").trim();
        if (name.isEmpty() || name.length() > 100 || !List.of("0", "1").contains(status) || remark.length() > 500) throw new ServiceException("规则名称或状态无效");
        if (!(body.get("dates") instanceof List<?> input) || input.isEmpty() || input.size() > 3661) throw new ServiceException("请选择1至3661个上课日期");
        SortedSet<LocalDate> dates = new TreeSet<>();
        try { for (Object value : input) dates.add(LocalDate.parse(String.valueOf(value))); }
        catch (RuntimeException e) { throw new ServiceException("上课日期格式无效"); }
        if (dates.first().getYear()<2000 || dates.last().getYear()>2100) throw new ServiceException("日期年份应为2000至2100");
        if (java.time.temporal.ChronoUnit.DAYS.between(dates.first(), dates.last()) > 3660) throw new ServiceException("排课跨度不能超过10年");
        return new Values(name, status, remark, dates);
    }
}
