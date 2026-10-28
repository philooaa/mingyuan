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
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.EduClassroom;
import com.ruoyi.system.service.IEduClassroomService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 教室信息Controller
 * 
 * @author ruoyi
 * @date 2026-08-01
 */
@RestController
@RequestMapping("/system/classroom")
public class EduClassroomController extends BaseController
{
    @Autowired
    private IEduClassroomService eduClassroomService;

    /**
     * 查询教室信息列表
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:list')")
    @GetMapping("/list")
    public TableDataInfo list(EduClassroom eduClassroom)
    {
        startPage();
        List<EduClassroom> list = eduClassroomService.selectEduClassroomList(eduClassroom);
        return getDataTable(list);
    }

    /**
     * 校区分类统计（教室管理页顶部的「全部 / 万达校区 / 百家湖校区」分类）
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:list')")
    @GetMapping("/campusStats")
    public AjaxResult campusStats()
    {
        return success(eduClassroomService.selectCampusStats());
    }

    /**
     * 导出教室信息列表
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:export')")
    @Log(title = "教室信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, EduClassroom eduClassroom)
    {
        List<EduClassroom> list = eduClassroomService.selectEduClassroomList(eduClassroom);
        ExcelUtil<EduClassroom> util = new ExcelUtil<EduClassroom>(EduClassroom.class);
        util.exportExcel(response, list, "教室信息数据");
    }

    /**
     * 获取教室信息详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:query')")
    @GetMapping(value = "/{classroomId}")
    public AjaxResult getInfo(@PathVariable("classroomId") Long classroomId)
    {
        return success(eduClassroomService.selectEduClassroomByClassroomId(classroomId));
    }

    /**
     * 新增教室信息
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:add')")
    @Log(title = "教室信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody EduClassroom eduClassroom)
    {
        return toAjax(eduClassroomService.insertEduClassroom(eduClassroom));
    }

    /**
     * 修改教室信息
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:edit')")
    @Log(title = "教室信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EduClassroom eduClassroom)
    {
        return toAjax(eduClassroomService.updateEduClassroom(eduClassroom));
    }

    /**
     * 删除教室信息
     */
    @PreAuthorize("@ss.hasPermi('system:classroom:remove')")
    @Log(title = "教室信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{classroomIds}")
    public AjaxResult remove(@PathVariable Long[] classroomIds)
    {
        return toAjax(eduClassroomService.deleteEduClassroomByClassroomIds(classroomIds));
    }
}
