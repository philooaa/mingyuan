package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 排课调课记录对象 edu_schedule_adjustment
 *
 * @author ruoyi
 * @date 2026-08-26
 */
public class EduScheduleAdjustment extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 调课ID */
    @Excel(name = "调课ID")
    private Long adjustmentId;

    /** 关联排课ID；0 表示「整期次」日期级记录（一条记录作用于该学期期次当天全部课程） */
    private Long scheduleId;

    /** 学期（日期级记录用） */
    @Excel(name = "学期")
    private String termName;

    /** 期次/上课日（日期级记录用） */
    @Excel(name = "期次")
    private String periodName;

    /** 原上课日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "原上课日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date originalDate;

    /** 调整后日期（NULL=停课） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "调整后日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date adjustedDate;

    /** 涉及课程数（日期级记录用） */
    @Excel(name = "涉及课程数")
    private Integer courseCount;

    /** 调课原因（国庆节/元旦等） */
    @Excel(name = "调课原因")
    private String reason;

    public void setAdjustmentId(Long adjustmentId)
    {
        this.adjustmentId = adjustmentId;
    }

    public Long getAdjustmentId()
    {
        return adjustmentId;
    }

    public void setScheduleId(Long scheduleId)
    {
        this.scheduleId = scheduleId;
    }

    public Long getScheduleId()
    {
        return scheduleId;
    }

    public void setTermName(String termName)
    {
        this.termName = termName;
    }

    public String getTermName()
    {
        return termName;
    }

    public void setPeriodName(String periodName)
    {
        this.periodName = periodName;
    }

    public String getPeriodName()
    {
        return periodName;
    }

    public void setCourseCount(Integer courseCount)
    {
        this.courseCount = courseCount;
    }

    public Integer getCourseCount()
    {
        return courseCount;
    }

    public void setOriginalDate(Date originalDate)
    {
        this.originalDate = originalDate;
    }

    public Date getOriginalDate()
    {
        return originalDate;
    }

    public void setAdjustedDate(Date adjustedDate)
    {
        this.adjustedDate = adjustedDate;
    }

    public Date getAdjustedDate()
    {
        return adjustedDate;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public String getReason()
    {
        return reason;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("adjustmentId", getAdjustmentId())
            .append("scheduleId", getScheduleId())
            .append("termName", getTermName())
            .append("periodName", getPeriodName())
            .append("originalDate", getOriginalDate())
            .append("adjustedDate", getAdjustedDate())
            .append("courseCount", getCourseCount())
            .append("reason", getReason())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
