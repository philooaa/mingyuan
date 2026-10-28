package com.ruoyi.system.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.system.mapper.MiniAppParentMapper;
import com.ruoyi.system.service.IMiniAppParentService;
import com.ruoyi.system.finance.TuitionService;
import com.ruoyi.system.shop.ShopRepository;

/**
 * MiniApp parent service implementation.
 */
@Service
public class MiniAppParentServiceImpl implements IMiniAppParentService
{
    @Autowired private com.ruoyi.system.service.OneToOneEnrollmentService singleService;
    @Autowired private com.ruoyi.system.service.LessonCreditService credits;
    @Autowired private com.ruoyi.system.mapper.EduCourseScheduleMapper schedules;
    @Autowired
    private MiniAppParentMapper miniAppParentMapper;
    @Autowired private TuitionService tuition;
    @Autowired private ShopRepository db;

    @Override
    public List<Map<String, Object>> selectAvailableSchedules(String gradeName, String subjectName, String termName)
    {
        return miniAppParentMapper.selectAvailableSchedules(SecurityUtils.getUserId(), gradeName, subjectName, termName);
    }

    @Override
    public List<Map<String, Object>> selectMyEnrollments()
    {
        return miniAppParentMapper.selectParentEnrollments(SecurityUtils.getUserId());
    }

    @Override
    public List<Map<String, Object>> selectMyTimetable()
    {
        List<Map<String, Object>> list = miniAppParentMapper.selectParentTimetable(SecurityUtils.getUserId());
        if (list != null)
        {
            for (Map<String, Object> item : list)
            {
                Object scheduleId = item.get("scheduleId");
                if (scheduleId != null)
                {
                    item.put("adjustments", miniAppParentMapper.selectScheduleAdjustments(scheduleId));
                }
            }
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> selectMyAttendanceSummary()
    {
        return miniAppParentMapper.selectParentAttendanceSummary(SecurityUtils.getUserId());
    }

    @Override
    public List<Map<String, Object>> selectMyAttendanceDetails(String courseClassName)
    {
        return miniAppParentMapper.selectParentAttendanceDetails(SecurityUtils.getUserId(), courseClassName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public int enroll(MiniAppEnrollmentBody body)
    {
        if (body != null && body.getScheduleId() != null && singleService.isSingle(body.getScheduleId())) return singleService.enroll(body);
        if (body == null || body.getScheduleId() == null)
        {
            throw new ServiceException("Please select a schedule");
        }
        String studentName = StringUtils.trim(body.getStudentName());
        if (StringUtils.isBlank(studentName))
        {
            throw new ServiceException("Please enter student name");
        }
        if (studentName.length() > 30)
        {
            throw new ServiceException("Student name is too long");
        }

        Long parentId = SecurityUtils.getUserId();
        if ("P".equals(body.getSettlementMethod())) throw new ServiceException("班课暂不支持课包结算");
        if (miniAppParentMapper.countScheduleAvailable(body.getScheduleId()) == 0)
        {
            throw new ServiceException("This schedule is not available");
        }
        // 先锁课程，再锁孩子；同一孩子跨课程报名和资料管理保持原子归属。
        var schedule=schedules.lockSchedule(body.getScheduleId());
        if(schedule == null) throw new ServiceException("课程不存在");
        String contactPhone = StringUtils.defaultString(body.getContactPhone(), SecurityUtils.getLoginUser().getUser().getPhonenumber());
        var child=credits.enrollmentChild(parentId,body.getLessonAccountId(),studentName,schedule.getGradeName(),contactPhone,body.getStudentRequestKey());
        studentName=child.get("studentName").toString();
        if (miniAppParentMapper.countActiveEnrollment(body.getScheduleId(), parentId, studentName,Long.valueOf(child.get("accountId").toString())) > 0)
        {
            throw new ServiceException("This student has already enrolled in this schedule");
        }
        if (miniAppParentMapper.increaseScheduleEnrollment(body.getScheduleId()) == 0)
        {
            throw new ServiceException("This schedule is full");
        }

        String enrollmentCode = "ENR" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + IdUtils.fastSimpleUUID().substring(0, 6).toUpperCase();
        int rows = miniAppParentMapper.insertParentEnrollment(enrollmentCode, body.getScheduleId(), parentId, studentName,
                StringUtils.defaultString(body.getStudentPhone()), contactPhone, SecurityUtils.getUsername(),Long.valueOf(child.get("accountId").toString()));
        Long enrollmentId = miniAppParentMapper.selectEnrollmentIdByCode(enrollmentCode);
        if (rows != 1 || enrollmentId == null) throw new ServiceException("报名失败，请重试");
        if (enrollmentId != null)
        {
            tuition.initializeBill(enrollmentId, parentId);
            String attendanceCode = "ATT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + IdUtils.fastSimpleUUID().substring(0, 6).toUpperCase();
            miniAppParentMapper.insertInitialAttendance(attendanceCode, body.getScheduleId(), enrollmentId, parentId,
                    studentName, SecurityUtils.getUsername());
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelEnrollment(Long enrollmentId)
    {
        if (enrollmentId == null) throw new ServiceException("请选择要取消的课程");
        Map<String, Object> owner = miniAppParentMapper.selectEnrollmentOwnerInfo(enrollmentId);
        if (owner == null) throw new ServiceException("报名记录不存在");
        if (!SecurityUtils.getUserId().equals(Long.valueOf(String.valueOf(owner.get("parentId")))))
            throw new ServiceException("无权取消他人的报名");
        if (singleService.isSingle(Long.valueOf(owner.get("scheduleId").toString()))) throw new ServiceException("一对一报名请提交取消申请，由老师处理");
        boolean alreadyCancelled = java.util.Set.of("2", "已取消").contains(String.valueOf(owner.get("enrollmentStatus")));
        tuition.cancelEnrollment(enrollmentId, SecurityUtils.getUserId(), false);
        return alreadyCancelled ? 0 : 1;
    }
}
