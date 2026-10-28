package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 教室信息对象 edu_classroom
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
public class EduClassroom extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 教室ID */
    private Long classroomId;

    /** 教室编码 */
    @Excel(name = "教室编码")
    private String classroomCode;

    /** 校区名称 */
    @Excel(name = "校区名称")
    private String campusName;

    /** 教室名称 */
    @Excel(name = "教室名称")
    private String classroomName;

    /**
     * 课表编码：学校课表里那一列的编号（万达 W1~W12、百家湖 B1~B5）。
     * 导入课表时按此编码精确匹配教室，不再依赖「校区教室列表的第 N 个」这种位置猜测。
     */
    @Excel(name = "课表编码")
    private String sheetCode;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    public void setClassroomId(Long classroomId) 
    {
        this.classroomId = classroomId;
    }

    public Long getClassroomId() 
    {
        return classroomId;
    }

    public void setClassroomCode(String classroomCode) 
    {
        this.classroomCode = classroomCode;
    }

    public String getClassroomCode() 
    {
        return classroomCode;
    }

    public void setCampusName(String campusName) 
    {
        this.campusName = campusName;
    }

    public String getCampusName() 
    {
        return campusName;
    }

    public void setClassroomName(String classroomName) 
    {
        this.classroomName = classroomName;
    }

    public String getClassroomName() 
    {
        return classroomName;
    }

    public void setSheetCode(String sheetCode) 
    {
        this.sheetCode = sheetCode;
    }

    public String getSheetCode() 
    {
        return sheetCode;
    }

    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("classroomId", getClassroomId())
            .append("classroomCode", getClassroomCode())
            .append("campusName", getCampusName())
            .append("classroomName", getClassroomName())
            .append("sheetCode", getSheetCode())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
