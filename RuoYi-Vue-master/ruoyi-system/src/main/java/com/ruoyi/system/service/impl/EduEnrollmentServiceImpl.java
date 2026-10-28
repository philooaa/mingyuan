package com.ruoyi.system.service.impl;

import java.util.List;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.finance.FinanceEnrollmentGuard;
import com.ruoyi.system.finance.TuitionService;
import com.ruoyi.system.shop.ShopRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.mapper.EduEnrollmentMapper;
import com.ruoyi.system.domain.EduEnrollment;
import com.ruoyi.system.service.IEduEnrollmentService;

/**
 * 课程报名Service业务层处理
 *
 * @author ruoyi
 * @date 2026-08-05
 */
@Service
public class EduEnrollmentServiceImpl implements IEduEnrollmentService
{
    @Autowired
    private EduEnrollmentMapper eduEnrollmentMapper;
    @Autowired private com.ruoyi.system.mapper.EduCourseScheduleMapper scheduleMapper;
    @Autowired private TuitionService tuition;
    @Autowired private ShopRepository db;
    @Autowired private com.ruoyi.system.service.LessonCreditService credits;
    @Autowired private com.ruoyi.system.mapper.MiniAppParentMapper parentMapper;

    /**
     * 查询课程报名
     *
     * @param enrollmentId 课程报名主键
     * @return 课程报名
     */
    @Override
    public EduEnrollment selectEduEnrollmentByEnrollmentId(Long enrollmentId)
    {
        return eduEnrollmentMapper.selectEduEnrollmentByEnrollmentId(enrollmentId);
    }

    /**
     * 查询课程报名列表
     *
     * @param eduEnrollment 课程报名
     * @return 课程报名
     */
    @Override
    public List<EduEnrollment> selectEduEnrollmentList(EduEnrollment eduEnrollment)
    {
        return eduEnrollmentMapper.selectEduEnrollmentList(eduEnrollment);
    }

    /**
     * 新增课程报名
     *
     * @param eduEnrollment 课程报名
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public int insertEduEnrollment(EduEnrollment eduEnrollment)
    {
        com.ruoyi.system.service.LessonCreditService.admin();
        var schedule = scheduleMapper.lockSchedule(eduEnrollment.getScheduleId());
        if(schedule == null) throw new ServiceException("课程不存在");
        if (schedule != null && "2".equals(schedule.getClassMode())) throw new ServiceException("一对一须选择具体日期提交，不能作为班课代报名");
        var child=credits.enrollmentChild(eduEnrollment.getParentId(),eduEnrollment.getLessonAccountId(),eduEnrollment.getStudentName(),schedule.getGradeName(),eduEnrollment.getContactPhone(),eduEnrollment.getStudentRequestKey());
        eduEnrollment.setLessonAccountId(Long.valueOf(child.get("accountId").toString()));
        eduEnrollment.setStudentName(child.get("studentName").toString());
        FinanceEnrollmentGuard.prepareNew(eduEnrollment);
        eduEnrollment.setCreateBy(SecurityUtils.getUsername());
        if(parentMapper.countActiveEnrollment(eduEnrollment.getScheduleId(),eduEnrollment.getParentId(),eduEnrollment.getStudentName(),eduEnrollment.getLessonAccountId())>0) throw new ServiceException("该孩子已报名此课程");
        if(parentMapper.increaseScheduleEnrollment(eduEnrollment.getScheduleId())==0) throw new ServiceException("课程不可报名（可能已停招、报名人数已达上限或教室停用）");
        eduEnrollment.setEnrollmentStatus("1"); eduEnrollment.setPayStatus("未支付"); eduEnrollment.setDelFlag("0"); eduEnrollment.setStatus("0");
        eduEnrollment.setCreateTime(DateUtils.getNowDate());
        int rows=eduEnrollmentMapper.insertEduEnrollment(eduEnrollment);
        tuition.initializeBill(eduEnrollment.getEnrollmentId(),SecurityUtils.getUserId());
        parentMapper.insertInitialAttendance("ATT"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,28),eduEnrollment.getScheduleId(),eduEnrollment.getEnrollmentId(),eduEnrollment.getParentId(),eduEnrollment.getStudentName(),SecurityUtils.getUsername());
        return rows;
    }

    /**
     * 修改课程报名
     *
     * @param eduEnrollment 课程报名
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public int updateEduEnrollment(EduEnrollment eduEnrollment)
    {
        if (eduEnrollment.getEnrollmentId() == null)
        {
            throw new ServiceException("报名ID不能为空");
        }
        var identity=db.one("SELECT schedule_id FROM edu_enrollment WHERE enrollment_id=?",eduEnrollment.getEnrollmentId());
        scheduleMapper.lockSchedule(Long.valueOf(identity.get("scheduleId").toString()));
        db.one("SELECT enrollment_id FROM edu_enrollment WHERE enrollment_id=? FOR UPDATE",eduEnrollment.getEnrollmentId());
        EduEnrollment current = eduEnrollmentMapper.selectEduEnrollmentByEnrollmentId(eduEnrollment.getEnrollmentId());
        if (current == null)
        {
            throw new ServiceException("报名记录不存在");
        }
        if ((eduEnrollment.getEnrollmentCode() != null && !Objects.equals(eduEnrollment.getEnrollmentCode(), current.getEnrollmentCode()))
                || (eduEnrollment.getScheduleId() != null && !Objects.equals(eduEnrollment.getScheduleId(), current.getScheduleId()))
                || (eduEnrollment.getParentId() != null && !Objects.equals(eduEnrollment.getParentId(), current.getParentId())))
        {
            throw new ServiceException("报名编码、排课和家长不可通过修改报名信息变更");
        }
        if ((current.getClassDate() != null || current.getLessonAccountId()!=null) && eduEnrollment.getStudentName() != null && !Objects.equals(eduEnrollment.getStudentName(),current.getStudentName())) throw new ServiceException("报名学生已关联孩子，请在孩子信息中修改姓名；原报名姓名保留历史");
        if(eduEnrollment.getLessonAccountId()!=null && !Objects.equals(eduEnrollment.getLessonAccountId(),current.getLessonAccountId())) throw new ServiceException("请在交易管理核对历史报名归属");
        FinanceEnrollmentGuard.checkUpdate(current,eduEnrollment);
        eduEnrollment.setPayStatus(null);
        // 支付状态等局部更新不会提交这些字段；普通编辑也不允许修改报名归属。
        eduEnrollment.setEnrollmentCode(null);
        eduEnrollment.setScheduleId(null);
        eduEnrollment.setParentId(null);
        eduEnrollment.setPayStatus(null);
        // 取消状态及时间只能由取消事务维护，避免旧编辑窗口恢复已取消报名。
        eduEnrollment.setEnrollmentStatus(null);
        eduEnrollment.setDelFlag(null);
        eduEnrollment.setCancelTime(null);
        eduEnrollment.setUpdateTime(DateUtils.getNowDate());
        return eduEnrollmentMapper.updateEduEnrollment(eduEnrollment);
    }

    /**
     * 批量删除课程报名
     *
     * @param enrollmentIds 需要删除的课程报名主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cancelEduEnrollmentByEnrollmentIds(Long[] enrollmentIds)
    {
        if(enrollmentIds==null||enrollmentIds.length==0||Arrays.stream(enrollmentIds).anyMatch(Objects::isNull))throw new ServiceException("请选择报名记录");
        var sorted=Arrays.stream(enrollmentIds).distinct().map(eduEnrollmentMapper::selectEduEnrollmentByEnrollmentId).toList();
        if(sorted.stream().anyMatch(Objects::isNull))throw new ServiceException("部分报名记录不存在");
        int count=0;
        for(var record:sorted.stream().sorted(Comparator.comparing(EduEnrollment::getScheduleId).thenComparing(EduEnrollment::getEnrollmentId)).toList()) {
            if(record.getClassDate()!=null) throw new ServiceException("一对一报名须由教师处理取消申请");
            boolean alreadyCancelled=java.util.Set.of("2","已取消").contains(Objects.toString(record.getEnrollmentStatus(),""));
            tuition.cancelEnrollment(record.getEnrollmentId(),SecurityUtils.getUserId(),true);
            if(!alreadyCancelled)count++;
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteEduEnrollmentByEnrollmentIds(Long[] enrollmentIds)
    {
        return cancelEduEnrollmentByEnrollmentIds(enrollmentIds);
    }

    /**
     * 删除课程报名信息
     *
     * @param enrollmentId 课程报名主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteEduEnrollmentByEnrollmentId(Long enrollmentId)
    {
        return cancelEduEnrollmentByEnrollmentIds(new Long[] { enrollmentId });
    }
}
