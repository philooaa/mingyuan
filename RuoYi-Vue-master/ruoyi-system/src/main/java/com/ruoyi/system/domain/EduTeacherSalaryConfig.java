package com.ruoyi.system.domain;

import java.math.BigDecimal;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 教师薪资标准对象 edu_teacher_salary_config
 *
 * @author ruoyi
 * @date 2026-09-04
 */
public class EduTeacherSalaryConfig extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 配置ID */
    private Long configId;

    /** 教师用户ID */
    @Excel(name = "教师ID")
    private Long teacherId;

    /** 教师姓名（冗余） */
    @Excel(name = "教师姓名")
    private String teacherName;

    /** 月底薪（元） */
    @Excel(name = "底薪(元)")
    private BigDecimal baseSalary;

    /** 小学班课（元/小时） */
    @Excel(name = "小学班课(元/小时)")
    private BigDecimal ratePrimaryClass;

    /** 初高班课（元/小时） */
    @Excel(name = "初高班课(元/小时)")
    private BigDecimal rateJhClass;

    /** 小学一对一（元/小时） */
    @Excel(name = "小学一对一(元/小时)")
    private BigDecimal ratePrimary1on1;

    /** 初中一对一（元/小时） */
    @Excel(name = "初中一对一(元/小时)")
    private BigDecimal rateJunior1on1;

    /** 高中一对一（元/小时） */
    @Excel(name = "高中一对一(元/小时)")
    private BigDecimal rateSenior1on1;

    /** 班课每次课时长（小时，默认2） */
    @Excel(name = "班课每次课时长(小时)")
    private BigDecimal classHoursPerSession;

    /** 固定提成金额（元/月） */
    @Excel(name = "提成(元/月)")
    private BigDecimal commissionAmount;

    /** 状态（0正常 1停用） */
    private String status;

    /** 删除标志（0存在 2删除） */
    private String delFlag;

    /** 负责的小学班课门数（统计值，非持久化） */
    private Integer primaryClassCount;

    /** 负责的中学班课门数（统计值，非持久化） */
    private Integer jhClassCount;

    /** 负责的一对一门数（统计值，非持久化） */
    private Integer oneOnOneClassCount;

    public void setConfigId(Long configId) { this.configId = configId; }
    public Long getConfigId() { return configId; }

    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public Long getTeacherId() { return teacherId; }

    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public String getTeacherName() { return teacherName; }

    public void setBaseSalary(BigDecimal baseSalary) { this.baseSalary = baseSalary; }
    public BigDecimal getBaseSalary() { return baseSalary; }

    public void setRatePrimaryClass(BigDecimal ratePrimaryClass) { this.ratePrimaryClass = ratePrimaryClass; }
    public BigDecimal getRatePrimaryClass() { return ratePrimaryClass; }

    public void setRateJhClass(BigDecimal rateJhClass) { this.rateJhClass = rateJhClass; }
    public BigDecimal getRateJhClass() { return rateJhClass; }

    public void setRatePrimary1on1(BigDecimal ratePrimary1on1) { this.ratePrimary1on1 = ratePrimary1on1; }
    public BigDecimal getRatePrimary1on1() { return ratePrimary1on1; }

    public void setRateJunior1on1(BigDecimal rateJunior1on1) { this.rateJunior1on1 = rateJunior1on1; }
    public BigDecimal getRateJunior1on1() { return rateJunior1on1; }

    public void setRateSenior1on1(BigDecimal rateSenior1on1) { this.rateSenior1on1 = rateSenior1on1; }
    public BigDecimal getRateSenior1on1() { return rateSenior1on1; }

    public void setClassHoursPerSession(BigDecimal classHoursPerSession) { this.classHoursPerSession = classHoursPerSession; }
    public BigDecimal getClassHoursPerSession() { return classHoursPerSession; }

    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }

    public void setStatus(String status) { this.status = status; }
    public String getStatus() { return status; }

    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public String getDelFlag() { return delFlag; }

    public void setPrimaryClassCount(Integer primaryClassCount) { this.primaryClassCount = primaryClassCount; }
    public Integer getPrimaryClassCount() { return primaryClassCount; }

    public void setJhClassCount(Integer jhClassCount) { this.jhClassCount = jhClassCount; }
    public Integer getJhClassCount() { return jhClassCount; }

    public void setOneOnOneClassCount(Integer oneOnOneClassCount) { this.oneOnOneClassCount = oneOnOneClassCount; }
    public Integer getOneOnOneClassCount() { return oneOnOneClassCount; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("configId", getConfigId())
                .append("teacherId", getTeacherId())
                .append("teacherName", getTeacherName())
                .append("baseSalary", getBaseSalary())
                .append("status", getStatus())
                .toString();
    }
}
