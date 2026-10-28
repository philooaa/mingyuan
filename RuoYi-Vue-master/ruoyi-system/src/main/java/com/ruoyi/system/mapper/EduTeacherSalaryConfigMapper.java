package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.EduTeacherSalaryConfig;

/**
 * 教师薪资标准 数据层
 *
 * @author ruoyi
 * @date 2026-09-04
 */
public interface EduTeacherSalaryConfigMapper
{
    public List<EduTeacherSalaryConfig> selectConfigList(EduTeacherSalaryConfig config);

    /**
     * 以教师账号为主体的薪资标准列表（未配置的教师也会返回，附带负责课门数统计）
     */
    public List<EduTeacherSalaryConfig> selectTeacherSalaryList(EduTeacherSalaryConfig config);

    public EduTeacherSalaryConfig selectConfigById(Long configId);

    public EduTeacherSalaryConfig selectConfigByTeacherId(@Param("teacherId") Long teacherId);

    /** 查询该教师已软删除的薪资标准（teacher_id 有唯一键，重新配置需复用旧行） */
    public EduTeacherSalaryConfig selectDeletedByTeacherId(@Param("teacherId") Long teacherId);

    public int insertConfig(EduTeacherSalaryConfig config);

    public int updateConfig(EduTeacherSalaryConfig config);

    public int deleteConfigByIds(Long[] configIds);
}
