package com.ruoyi.system.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.shop.ShopRepository;

/** 教师业务资料独立于若依账号存储，两端共用这一读写入口。 */
@Service
public class EduTeacherProfileService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired private ShopRepository db;

    /** 用户编辑页允许查看尚未配置的资料，字段为空时交给管理员填写。 */
    public Map<String, Object> get(Long userId) {
        return db.one("SELECT p.teacher_subject,p.teacher_level FROM sys_user u "
            + "LEFT JOIN edu_teacher_profile p ON p.user_id=u.user_id "
            + "WHERE u.user_id=? AND u.del_flag='0'", userId);
    }

    /** 外键只验证账号存在，教师业务仍须检查有效账号及教师角色。 */
    public Map<String, Object> getAvailableTeacher(Long userId) {
        var rows = db.rows("SELECT u.user_id,u.nick_name,p.teacher_subject,p.teacher_level FROM sys_user u "
            + "LEFT JOIN edu_teacher_profile p ON p.user_id=u.user_id "
            + "WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' "
            + "AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id "
            + "WHERE ur.user_id=u.user_id AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0')", userId);
        if (rows.isEmpty()) throw new ServiceException("教师账号不可用");
        return rows.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(Long userId, String subject, String level, String operator) {
        if (userId == null || subject == null || subject.isBlank() || subject.length() > 20
            || !List.of("elite", "senior").contains(level == null ? "" : level)) {
            throw new ServiceException("请选择学科和教师等级");
        }
        // 与原排课服务沿用同一教师账号锁；不修改账号本身。
        db.one("SELECT user_id FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE", userId);
        if (db.rows("SELECT ur.user_id FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id "
            + "WHERE ur.user_id=? AND r.role_key='teacher' AND r.status='0' AND r.del_flag='0'", userId).isEmpty()) {
            throw new ServiceException("此账号没有教师角色");
        }
        if (db.rows("SELECT dict_code FROM sys_dict_data WHERE dict_type='edu_subject' "
            + "AND dict_label=? AND status='0'", subject).isEmpty()) {
            throw new ServiceException("请选择后台启用的授课学科");
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        db.jdbc().update("INSERT INTO edu_teacher_profile "
            + "(user_id,teacher_subject,teacher_level,create_by,create_time,update_by,update_time) "
            + "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
            + "teacher_subject=?,teacher_level=?,update_by=?,update_time=?",
            userId, subject, level, operator, now, operator, now, subject, level, operator, now);
    }
}
