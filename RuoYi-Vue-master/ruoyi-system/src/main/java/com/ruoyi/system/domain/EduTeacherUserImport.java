package com.ruoyi.system.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.entity.SysUser;

/** 原用户导入模板的可选业务列，不增加 sys_user 字段。 */
public class EduTeacherUserImport extends SysUser {
    @Excel(name="教师授课学科",type=Excel.Type.IMPORT)
    private String teacherSubject;
    @Excel(name="教师等级",type=Excel.Type.IMPORT,prompt="elite/精英教师；senior/资深教师")
    private String teacherLevel;
    public String getTeacherSubject() { return teacherSubject; }
    public void setTeacherSubject(String value) { teacherSubject=value; }
    public String getTeacherLevel() { return teacherLevel; }
    public void setTeacherLevel(String value) { teacherLevel=value == null ? null : switch(value.trim()) { case "精英教师" -> "elite"; case "资深教师" -> "senior"; default -> value.trim(); }; }
    public boolean hasTeacherProfile() { return teacherSubject != null && !teacherSubject.isBlank() || teacherLevel != null && !teacherLevel.isBlank(); }
}
