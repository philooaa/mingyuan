package com.ruoyi.system.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 成本支出登记对象 edu_finance_expense
 *
 * @author ruoyi
 * @date 2026-10-07
 */
public class EduFinanceExpense extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 支出ID */
    private Long expenseId;

    /** 支出类型（字典 edu_expense_type） */
    @Excel(name = "支出类型", dictType = "edu_expense_type")
    private String expenseType;

    /** 摘要 */
    @Excel(name = "摘要")
    private String title;

    /** 金额（元） */
    @Excel(name = "金额(元)")
    private BigDecimal amount;

    /** 明细 */
    @Excel(name = "明细")
    private String detail;

    /** 支出日期 */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    @Excel(name = "支出日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date expenseDate;

    /** 登记人 */
    @Excel(name = "登记人")
    private String registerBy;

    /** 状态（0正常 1停用） */
    private String status;

    /** 删除标志（0存在 2删除） */
    private String delFlag;

    public Long getExpenseId() { return expenseId; }
    public void setExpenseId(Long expenseId) { this.expenseId = expenseId; }
    public String getExpenseType() { return expenseType; }
    public void setExpenseType(String expenseType) { this.expenseType = expenseType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Date getExpenseDate() { return expenseDate; }
    public void setExpenseDate(Date expenseDate) { this.expenseDate = expenseDate; }
    public String getRegisterBy() { return registerBy; }
    public void setRegisterBy(String registerBy) { this.registerBy = registerBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
}
