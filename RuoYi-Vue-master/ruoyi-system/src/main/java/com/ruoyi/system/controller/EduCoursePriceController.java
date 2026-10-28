package com.ruoyi.system.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.finance.FinanceRules;
import com.ruoyi.system.service.CoursePriceService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 课程定价：按年级规则定价，按年级 + 学科批量改价。
 *
 * <p>单价规则由 {@code edu_course_price_rule} 维护；课程总价 = 单价 × 本期课次数量。
 */
@RestController
@RequestMapping("/system/coursePrice")
public class EduCoursePriceController extends BaseController {
    private final CoursePriceService prices;

    public EduCoursePriceController(CoursePriceService prices) {
        this.prices = prices;
    }

    @PreAuthorize("@ss.hasPermi('system:coursePrice:query')")
    @GetMapping("/rules")
    public AjaxResult rules() {
        return success(prices.rules());
    }

    @PreAuthorize("@ss.hasPermi('system:coursePrice:query')")
    @GetMapping("/dims")
    public AjaxResult dims() {
        return success(prices.dims());
    }

    @PreAuthorize("@ss.hasPermi('system:coursePrice:query')")
    @GetMapping("/courses")
    public TableDataInfo courses(
            @RequestParam(required = false) String gradeName,
            @RequestParam(required = false) String subjectName,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> page = prices.courses(gradeName, subjectName, pageNum, pageSize);
        var result =
                new TableDataInfo(
                        (List<?>) page.get("rows"), ((Number) page.get("total")).longValue());
        result.setCode(200);
        result.setMsg("查询成功");
        return result;
    }

    @PreAuthorize("@ss.hasPermi('system:coursePrice:edit')")
    @Log(title = "课程批量定价", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping("/batch")
    public AjaxResult batch(@RequestBody Map<String, Object> body) {
        return success(prices.batch(body, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('system:coursePrice:edit')")
    @Log(title = "课程定价规则", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping("/rule")
    public AjaxResult updateRule(@RequestBody Map<String, Object> body) {
        return success(prices.updateRule(body, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('system:coursePrice:edit')")
    @Log(title = "套用课程定价规则", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping("/rule/apply")
    public AjaxResult applyRule(@RequestBody Map<String, Object> body) {
        return success(prices.applyRule(body, SecurityUtils.getUserId()));
    }

    /** 定价规则快照，供财务核价页面展示「按规则应为」。 */
    @PreAuthorize("@ss.hasPermi('system:coursePrice:query')")
    @GetMapping("/grade/{gradeName}")
    public AjaxResult gradePrice(@PathVariable String gradeName) {
        return success(FinanceRules.values("gradeName", gradeName, "unitPrice", prices.rulePrice(gradeName).toPlainString()));
    }
}
