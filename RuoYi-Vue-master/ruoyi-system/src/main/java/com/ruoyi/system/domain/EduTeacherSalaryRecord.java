package com.ruoyi.system.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 教师月度薪资结算对象 edu_teacher_salary_record
 *
 * @author ruoyi
 * @date 2026-09-04
 */
public class EduTeacherSalaryRecord extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 结算ID */
    private Long recordId;

    /** 教师用户ID */
    private Long teacherId;

    /** 教师姓名（冗余） */
    @Excel(name = "教师姓名")
    private String teacherName;

    /** 结算月份（yyyy-MM） */
    @Excel(name = "结算月份")
    private String salaryMonth;

    /** 底薪（快照） */
    @Excel(name = "底薪(元)")
    private BigDecimal baseSalary;

    private Long primaryClassCount;
    private BigDecimal primaryClassHours;
    private BigDecimal primaryClassAmount;
    private Long jhClassCount;
    private BigDecimal jhClassHours;
    private BigDecimal jhClassAmount;
    private Long primary1on1Count;
    private BigDecimal primary1on1Hours;
    private BigDecimal primary1on1Amount;
    private Long junior1on1Count;
    private BigDecimal junior1on1Hours;
    private BigDecimal junior1on1Amount;
    private Long senior1on1Count;
    private BigDecimal senior1on1Hours;
    private BigDecimal senior1on1Amount;

    /** 总结课节数/人次 */
    @Excel(name = "总节数/人次")
    private Long totalLessons;

    /** 总小时数 */
    @Excel(name = "总小时数")
    private BigDecimal totalHours;

    /** 课时费合计 */
    @Excel(name = "课时费合计(元)")
    private BigDecimal lessonAmount;

    /** 提成金额（元，取自薪资标准中配置的固定提成） */
    @Excel(name = "提成(元)")
    private BigDecimal commissionAmount;

    /** 应发合计（底薪+课时费+提成） */
    @Excel(name = "应发合计(元)")
    private BigDecimal totalAmount;

    /** 确认状态（0待确认 1已确认） */
    @Excel(name = "确认状态", readConverterExp = "0=待确认,1=已确认")
    private String confirmStatus;

    /** 确认人 */
    private String confirmBy;

    /** 确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date confirmTime;

    /** 删除标志（0存在 2删除） */
    private String delFlag;

    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public String getSalaryMonth() { return salaryMonth; }
    public void setSalaryMonth(String salaryMonth) { this.salaryMonth = salaryMonth; }
    public BigDecimal getBaseSalary() { return baseSalary; }
    public void setBaseSalary(BigDecimal baseSalary) { this.baseSalary = baseSalary; }

    public Long getPrimaryClassCount() { return primaryClassCount; }
    public void setPrimaryClassCount(Long v) { this.primaryClassCount = v; }
    public BigDecimal getPrimaryClassHours() { return primaryClassHours; }
    public void setPrimaryClassHours(BigDecimal v) { this.primaryClassHours = v; }
    public BigDecimal getPrimaryClassAmount() { return primaryClassAmount; }
    public void setPrimaryClassAmount(BigDecimal v) { this.primaryClassAmount = v; }
    public Long getJhClassCount() { return jhClassCount; }
    public void setJhClassCount(Long v) { this.jhClassCount = v; }
    public BigDecimal getJhClassHours() { return jhClassHours; }
    public void setJhClassHours(BigDecimal v) { this.jhClassHours = v; }
    public BigDecimal getJhClassAmount() { return jhClassAmount; }
    public void setJhClassAmount(BigDecimal v) { this.jhClassAmount = v; }
    public Long getPrimary1on1Count() { return primary1on1Count; }
    public void setPrimary1on1Count(Long v) { this.primary1on1Count = v; }
    public BigDecimal getPrimary1on1Hours() { return primary1on1Hours; }
    public void setPrimary1on1Hours(BigDecimal v) { this.primary1on1Hours = v; }
    public BigDecimal getPrimary1on1Amount() { return primary1on1Amount; }
    public void setPrimary1on1Amount(BigDecimal v) { this.primary1on1Amount = v; }
    public Long getJunior1on1Count() { return junior1on1Count; }
    public void setJunior1on1Count(Long v) { this.junior1on1Count = v; }
    public BigDecimal getJunior1on1Hours() { return junior1on1Hours; }
    public void setJunior1on1Hours(BigDecimal v) { this.junior1on1Hours = v; }
    public BigDecimal getJunior1on1Amount() { return junior1on1Amount; }
    public void setJunior1on1Amount(BigDecimal v) { this.junior1on1Amount = v; }
    public Long getSenior1on1Count() { return senior1on1Count; }
    public void setSenior1on1Count(Long v) { this.senior1on1Count = v; }
    public BigDecimal getSenior1on1Hours() { return senior1on1Hours; }
    public void setSenior1on1Hours(BigDecimal v) { this.senior1on1Hours = v; }
    public BigDecimal getSenior1on1Amount() { return senior1on1Amount; }
    public void setSenior1on1Amount(BigDecimal v) { this.senior1on1Amount = v; }

    public Long getTotalLessons() { return totalLessons; }
    public void setTotalLessons(Long totalLessons) { this.totalLessons = totalLessons; }
    public BigDecimal getTotalHours() { return totalHours; }
    public void setTotalHours(BigDecimal totalHours) { this.totalHours = totalHours; }
    public BigDecimal getLessonAmount() { return lessonAmount; }
    public void setLessonAmount(BigDecimal lessonAmount) { this.lessonAmount = lessonAmount; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getConfirmStatus() { return confirmStatus; }
    public void setConfirmStatus(String confirmStatus) { this.confirmStatus = confirmStatus; }
    public String getConfirmBy() { return confirmBy; }
    public void setConfirmBy(String confirmBy) { this.confirmBy = confirmBy; }
    public Date getConfirmTime() { return confirmTime; }
    public void setConfirmTime(Date confirmTime) { this.confirmTime = confirmTime; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("recordId", getRecordId())
                .append("teacherName", getTeacherName())
                .append("salaryMonth", getSalaryMonth())
                .append("totalAmount", getTotalAmount())
                .append("confirmStatus", getConfirmStatus())
                .toString();
    }
}
