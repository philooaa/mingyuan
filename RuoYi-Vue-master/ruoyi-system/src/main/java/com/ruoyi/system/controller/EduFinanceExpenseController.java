package com.ruoyi.system.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.system.domain.EduFinanceExpense;
import com.ruoyi.system.service.IEduFinanceExpenseService;

/**
 * 成本支出登记Controller
 *
 * @author ruoyi
 * @date 2026-10-07
 */
@RestController
@RequestMapping("/system/expense")
public class EduFinanceExpenseController extends BaseController
{
    @Autowired
    private IEduFinanceExpenseService expenseService;

    @PreAuthorize("@ss.hasPermi('system:expense:list')")
    @GetMapping("/list")
    public TableDataInfo list(EduFinanceExpense query)
    {
        startPage();
        List<EduFinanceExpense> list = expenseService.selectExpenseList(query);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('system:expense:export')")
    @Log(title = "成本支出登记", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, EduFinanceExpense query)
    {
        List<EduFinanceExpense> list = expenseService.selectExpenseList(query);
        ExcelUtil<EduFinanceExpense> util = new ExcelUtil<EduFinanceExpense>(EduFinanceExpense.class);
        util.exportExcel(response, list, "成本支出数据");
    }

    @PreAuthorize("@ss.hasPermi('system:expense:query')")
    @GetMapping(value = "/{expenseId}")
    public AjaxResult getInfo(@PathVariable("expenseId") Long expenseId)
    {
        return success(expenseService.selectExpenseById(expenseId));
    }

    @PreAuthorize("@ss.hasPermi('system:expense:add')")
    @Log(title = "成本支出登记", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody EduFinanceExpense expense)
    {
        return toAjax(expenseService.insertExpense(expense));
    }

    @PreAuthorize("@ss.hasPermi('system:expense:edit')")
    @Log(title = "成本支出登记", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EduFinanceExpense expense)
    {
        return toAjax(expenseService.updateExpense(expense));
    }

    @PreAuthorize("@ss.hasPermi('system:expense:remove')")
    @Log(title = "成本支出登记", businessType = BusinessType.DELETE)
    @DeleteMapping("/{expenseIds}")
    public AjaxResult remove(@PathVariable Long[] expenseIds)
    {
        return toAjax(expenseService.deleteExpenseByIds(expenseIds));
    }
}
