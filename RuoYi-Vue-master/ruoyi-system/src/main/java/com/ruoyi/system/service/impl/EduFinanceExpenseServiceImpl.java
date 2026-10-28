package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduFinanceExpense;
import com.ruoyi.system.mapper.EduFinanceExpenseMapper;
import com.ruoyi.system.service.IEduFinanceExpenseService;

/**
 * 成本支出登记 服务层实现
 *
 * @author ruoyi
 * @date 2026-10-07
 */
@Service
public class EduFinanceExpenseServiceImpl implements IEduFinanceExpenseService
{
    @Autowired
    private EduFinanceExpenseMapper expenseMapper;

    @Override
    public List<EduFinanceExpense> selectExpenseList(EduFinanceExpense query)
    {
        return expenseMapper.selectExpenseList(query);
    }

    @Override
    public EduFinanceExpense selectExpenseById(Long expenseId)
    {
        return expenseMapper.selectExpenseById(expenseId);
    }

    @Override
    public int insertExpense(EduFinanceExpense expense)
    {
        expense.setCreateBy(SecurityUtils.getUsername());
        expense.setRegisterBy(SecurityUtils.getLoginUser().getUser().getNickName());
        return expenseMapper.insertExpense(expense);
    }

    @Override
    public int updateExpense(EduFinanceExpense expense)
    {
        expense.setUpdateBy(SecurityUtils.getUsername());
        return expenseMapper.updateExpense(expense);
    }

    @Override
    public int deleteExpenseByIds(Long[] expenseIds)
    {
        return expenseMapper.deleteExpenseByIds(expenseIds);
    }
}
