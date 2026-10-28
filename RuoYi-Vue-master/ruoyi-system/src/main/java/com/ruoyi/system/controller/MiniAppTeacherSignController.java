package com.ruoyi.system.controller;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.MiniAppSignSubmitBody;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.EduClassSignIn;
import com.ruoyi.system.domain.EduCourseSchedule;
import com.ruoyi.system.domain.EduEnrollment;
import com.ruoyi.system.mapper.EduCourseScheduleMapper;
import com.ruoyi.system.mapper.EduEnrollmentMapper;
import com.ruoyi.system.mapper.EduHomeworkMapper;
import com.ruoyi.system.service.IEduClassSignInService;

/**
 * 小程序 教师上课签到
 */
@RestController
@RequestMapping("/miniapp/teacher/sign")
public class MiniAppTeacherSignController extends BaseController
{
    @Autowired
    private IEduClassSignInService signInService;

    @Autowired
    private EduCourseScheduleMapper scheduleMapper;

    @Autowired
    private EduEnrollmentMapper enrollmentMapper;

    @Autowired
    private EduHomeworkMapper homeworkMapper;
    @Autowired private com.ruoyi.system.shop.ShopRepository singleDb;

    /**
     * 我的排课列表（含最近一次签到汇总）
     */
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        List<EduClassSignIn> list = signInService.selectTeacherSchedules(SecurityUtils.getUserId());
        return success(list);
    }

    /**
     * 某排课的报名学员（预填签到名单）
     */
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @GetMapping("/students")
    public AjaxResult students(@RequestParam("scheduleId") Long scheduleId,@RequestParam(value="classDate",required=false) String classDate)
    {
        assertScheduleAccess(scheduleId);
        if ("2".equals(scheduleMapper.selectEduCourseScheduleByScheduleId(scheduleId).getClassMode())) {
            if (classDate == null) throw new ServiceException("请选择具体课次日期");
            return success(singleDb.rows("SELECT enrollment_id,student_name,contact_phone FROM edu_enrollment WHERE schedule_id=? AND class_date=? AND enrollment_status='1' AND del_flag='0'",scheduleId,classDate));
        }
        return success(signInService.selectEnrolledStudents(scheduleId));
    }

    /**
     * 某课次的签到明细 + 复核信息
     */
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("scheduleId") Long scheduleId,
            @RequestParam("classDate") String classDate)
    {
        assertScheduleAccess(scheduleId);
        return success(signInService.selectDetail(scheduleId, classDate));
    }

    /**
     * 提交签到（覆盖式；复核完成后锁定）
     */
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @PostMapping("/submit")
    public AjaxResult submit(@RequestBody MiniAppSignSubmitBody body)
    {
        if (body == null)
        {
            throw new ServiceException("签到内容不能为空");
        }
        assertScheduleAccess(body.getScheduleId());
        // Preserve free-form trial/transfer students, but never link another class's enrollment.
        if (body.getDetails() != null)
        {
            for (MiniAppSignSubmitBody.Detail detail : body.getDetails())
            {
                if (detail != null && detail.getEnrollmentId() != null)
                {
                    EduEnrollment enrollment = enrollmentMapper.selectEduEnrollmentByEnrollmentId(detail.getEnrollmentId());
                    if (enrollment == null || !body.getScheduleId().equals(enrollment.getScheduleId())
                        || (enrollment.getClassDate() != null && (!enrollment.getClassDate().toString().equals(body.getClassDate()) || !"1".equals(enrollment.getEnrollmentStatus()))))
                    {
                        throw new ServiceException("签到报名记录不属于当前班级");
                    }
                }
            }
        }
        Map<String, Object> result = signInService.submitSignIn(body, SecurityUtils.getUsername());
        AjaxResult ajax = AjaxResult.success();
        ajax.putAll(result);
        return ajax;
    }

    private void assertScheduleAccess(Long scheduleId)
    {
        EduCourseSchedule schedule = scheduleId == null ? null
                : scheduleMapper.selectEduCourseScheduleByScheduleId(scheduleId);
        if (schedule == null || !"0".equals(schedule.getDelFlag()))
        {
            throw new ServiceException("排课不存在或已删除");
        }
        Long userId = SecurityUtils.getUserId();
        boolean admin = SecurityUtils.isAdmin(userId) || SecurityUtils.hasRole("admin");
        if (!admin && !userId.equals(homeworkMapper.selectScheduleTeacher(scheduleId)))
        {
            throw new ServiceException("只能查看或提交自己负责班级的签到");
        }
    }
}
