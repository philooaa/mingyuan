package com.ruoyi.system.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.domain.EduTeacherUserImport;
import com.ruoyi.system.mapper.SysUserMapper;

/** 原用户导入的单行事务：业务资料失败时不留下半行用户更新。 */
@Service
public class EduTeacherImportService {
    @Autowired private EduTeacherProfileService profiles;
    @Autowired private SysUserMapper users;
    @Transactional(rollbackFor=Exception.class)
    public int update(EduTeacherUserImport row,String operator) {
        LessonCreditService.admin();
        var previous=profiles.get(row.getUserId());
        String subject=row.getTeacherSubject(),level=row.getTeacherLevel();
        if(subject == null || subject.isBlank()) subject=(String)previous.get("teacherSubject");
        if(level == null || level.isBlank()) level=(String)previous.get("teacherLevel");
        profiles.save(row.getUserId(),subject,level,operator);
        return users.updateUser(row);
    }
}
