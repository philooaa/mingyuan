package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.EduFinanceExpense;

/**
 * 成本支出登记 服务层
 *
 * @author ruoyi
 * @date 2026-10-07
 */
public interface IEduFinanceExpenseService
{
    public List<EduFinanceExpense> selectExpenseList(EduFinanceExpense query);

    public EduFinanceExpense selectExpenseById(Long expenseId);

    public int insertExpense(EduFinanceExpense expense);

    public int updateExpense(EduFinanceExpense expense);

    public int deleteExpenseByIds(Long[] expenseIds);
}
