package com.ruoyi.system.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.service.IEduCourseScheduleService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 课程排课Controller
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
@RestController
@RequestMapping("/system/schedule")
public class EduCourseScheduleController extends BaseController
{
    @Autowired
    private IEduCourseScheduleService eduCourseScheduleService;

    /**
     * 查询课程排课列表
     */
    @PreAuthorize("@ss.hasPermi('system:schedule:list')")
    @GetMapping("/list")
    public TableDataInfo list(EduCourseSchedule eduCourseSchedule)
    {
        startPage();
        List<EduCourseSchedule> list = eduCourseScheduleService.selectEduCourseScheduleList(eduCourseSchedule);
        return getDataTable(list);
    }

    /**
     * 导出课程排课列表
     */
    @PreAuthorize("@ss.hasPermi('system:schedule:export')")
    @Log(title = "课程排课", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, EduCourseSchedule eduCourseSchedule)
    {
        List<EduCourseSchedule> list = eduCourseScheduleService.selectEduCourseScheduleList(eduCourseSchedule);
        ExcelUtil<EduCourseSchedule> util = new ExcelUtil<EduCourseSchedule>(EduCourseSchedule.class);
        util.exportExcel(response, list, "课程排课数据");
    }

    /**
     * 获取课程排课详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:schedule:query')")
    @GetMapping(value = "/{scheduleId}")
    public AjaxResult getInfo(@PathVariable("scheduleId") Long scheduleId)
    {
        return success(eduCourseScheduleService.selectEduCourseScheduleByScheduleId(scheduleId));
    }

    /**
     * 新增课程排课
     */
    @PreAuthorize("@ss.hasPermi('system:schedule:add')")
    @Log(title = "课程排课", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody EduCourseSchedule eduCourseSchedule)
    {
        return toAjax(eduCourseScheduleService.insertEduCourseSchedule(eduCourseSchedule));
    }

    /**
     * 修改课程排课
     */
    @PreAuthorize("@ss.hasPermi('system:schedule:edit')")
    @Log(title = "课程排课", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EduCourseSchedule eduCourseSchedule, @RequestParam(defaultValue="false") boolean force)
    {
        return toAjax(eduCourseScheduleService.updateEduCourseSchedule(eduCourseSchedule, force));
    }

    /**
     * 删除课程排课
     */
    @PreAuthorize("@ss.hasPermi('system:schedule:remove')")
    @Log(title = "课程排课", businessType = BusinessType.DELETE)
	@DeleteMapping("/{scheduleIds}")
    public AjaxResult remove(@PathVariable Long[] scheduleIds, @RequestParam(defaultValue="false") boolean force)
    {
        return toAjax(force ? eduCourseScheduleService.removeConfirmedSchedules(scheduleIds) : eduCourseScheduleService.deleteEduCourseScheduleByScheduleIds(scheduleIds));
    }
}
