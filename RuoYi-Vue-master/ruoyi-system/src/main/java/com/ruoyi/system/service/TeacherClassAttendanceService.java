package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.EduTeacherSalaryRecordMapper;
import com.ruoyi.system.shop.ShopRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared attendance/commission calculation; manual counts never rewrite sign-in records. */
@Service
public class TeacherClassAttendanceService {
    private final ShopRepository db;
    private final EduTeacherSalaryRecordMapper sessions;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    public TeacherClassAttendanceService(ShopRepository db, EduTeacherSalaryRecordMapper sessions) { this.db=db;this.sessions=sessions; }
    private String month(String value) {
        try { if(value==null || !value.matches("\\d{4}-\\d{2}"))throw new IllegalArgumentException();
            YearMonth m=YearMonth.parse(value);if(m.getYear()<2000||m.getYear()>2100)throw new IllegalArgumentException();return m.toString();
        } catch(RuntimeException e) {throw new ServiceException("请选择有效统计月份");}
    }
    private void teacher(long id) {
        if(db.rows("SELECT u.user_id FROM sys_user u WHERE u.user_id=? AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0')",id).isEmpty())throw new ServiceException("教师不存在或未分配教师角色");
    }
    public static BigDecimal unitPrice(BigDecimal value) {
        if (value == null) return new BigDecimal("0.00");
        if (value.signum()<0 || value.stripTrailingZeros().scale()>2 || value.compareTo(MAX_AMOUNT)>0)
            throw new ServiceException("每人次单价须为0至99999999.99元且最多两位小数");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
    public static BigDecimal commission(long count, BigDecimal price) {
        if (count<0) throw new ServiceException("班课人次不能为负数");
        BigDecimal amount=unitPrice(price).multiply(BigDecimal.valueOf(count)).setScale(2);
        if (amount.compareTo(MAX_AMOUNT)>0) throw new ServiceException("班课人次总提成不能超过99999999.99元，请核对人次和单价");
        return amount;
    }
    public static Map<Long,Long> automaticCounts(List<Map<String,Object>> rows) {
        Map<Long,Long> result=new HashMap<>();
        for (var row:rows) {
            if (row.get("teacherId")==null || "2".equals(String.valueOf(row.get("classMode")))) continue;
            long id=Long.parseLong(row.get("teacherId").toString());
            long attended=row.get("attendedRows")==null?0:Long.parseLong(row.get("attendedRows").toString());
            result.merge(id,attended,Math::addExact);
        }
        return result;
    }
    public static Map<Long,Long> effectiveCounts(Map<Long,Long> automatic,List<Map<String,Object>> overrides) {
        Map<Long,Long> result=new HashMap<>(automatic);
        for (var row:overrides) result.put(Long.parseLong(row.get("teacherId").toString()),Long.parseLong(row.get("attendanceCount").toString()));
        return result;
    }
    public Map<String,Object> summary(long id,String salaryMonth) {
        String m=month(salaryMonth);teacher(id);
        var automatic=automaticCounts(sessions.selectMonthlySessions(m));
        var overrides=sessions.selectMonthlyAttendanceOverrides(m);
        long count=effectiveCounts(automatic,overrides).getOrDefault(id,0L);
        var configs=db.rows("SELECT class_attendance_unit_price FROM edu_teacher_salary_config WHERE teacher_id=? AND del_flag='0'",id);
        BigDecimal price=unitPrice(configs.isEmpty()?null:new BigDecimal(configs.get(0).get("classAttendanceUnitPrice").toString()));
        var result=new LinkedHashMap<String,Object>();result.put("teacherId",id);result.put("salaryMonth",m);result.put("automaticCount",automatic.getOrDefault(id,0L));
        result.put("count",count);result.put("manual",overrides.stream().anyMatch(row->String.valueOf(id).equals(String.valueOf(row.get("teacherId")))));
        result.put("unitPrice",price);result.put("totalCommission",commission(count,price));
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> adjust(long id,String salaryMonth,BigDecimal count) {
        String m=month(salaryMonth);teacher(id);
        if(count==null)db.jdbc().update("DELETE FROM edu_teacher_class_attendance_adjustment WHERE teacher_id=? AND salary_month=?",id,m);
        else {
            long value;
            try {value=count.longValueExact();if(value<0||value>1000000000L)throw new ArithmeticException();}
            catch(ArithmeticException e){throw new ServiceException("班课人次须为0至10亿的整数");}
            db.jdbc().update("INSERT INTO edu_teacher_class_attendance_adjustment(teacher_id,salary_month,attendance_count,update_by,update_time) VALUES(?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE attendance_count=VALUES(attendance_count),update_by=VALUES(update_by),update_time=NOW()",id,m,value,SecurityUtils.getUsername());
        }
        return summary(id,m);
    }
}
