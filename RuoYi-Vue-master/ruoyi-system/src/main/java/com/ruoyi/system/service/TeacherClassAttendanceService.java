package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.EduTeacherSalaryRecordMapper;
import com.ruoyi.system.shop.ShopRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Monthly class attendance counts are statistics only, never money or a sign-in rewrite. */
@Service
public class TeacherClassAttendanceService {
    private final ShopRepository db;
    private final EduTeacherSalaryRecordMapper sessions;
    public TeacherClassAttendanceService(ShopRepository db, EduTeacherSalaryRecordMapper sessions) { this.db=db;this.sessions=sessions; }
    private String month(String value) {
        try { if(value==null || !value.matches("\\d{4}-\\d{2}"))throw new IllegalArgumentException();
            YearMonth m=YearMonth.parse(value);if(m.getYear()<2000||m.getYear()>2100)throw new IllegalArgumentException();return m.toString();
        } catch(RuntimeException e) {throw new ServiceException("请选择有效统计月份");}
    }
    private void teacher(long id) {
        if(db.rows("SELECT u.user_id FROM sys_user u WHERE u.user_id=? AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0')",id).isEmpty())throw new ServiceException("教师不存在或未分配教师角色");
    }
    public Map<String,Object> summary(long id,String salaryMonth) {
        String m=month(salaryMonth);teacher(id);
        long automatic=0;
        for(var row:sessions.selectMonthlySessions(m)) {
            if(!String.valueOf(id).equals(String.valueOf(row.get("teacherId"))) || "2".equals(String.valueOf(row.get("classMode"))))continue;
            Object value=row.get("attendedRows");if(value!=null)automatic=Math.addExact(automatic,Long.parseLong(value.toString()));
        }
        var overrides=db.rows("SELECT attendance_count,update_by,update_time FROM edu_teacher_class_attendance_adjustment WHERE teacher_id=? AND salary_month=?",id,m);
        var result=new LinkedHashMap<String,Object>();result.put("teacherId",id);result.put("salaryMonth",m);result.put("automaticCount",automatic);
        result.put("count",overrides.isEmpty()?automatic:Long.parseLong(overrides.get(0).get("attendanceCount").toString()));result.put("manual",!overrides.isEmpty());
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
