package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.EduTeacherSalaryConfig;

/**
 * 教师薪资标准 服务层
 *
 * @author ruoyi
 * @date 2026-09-04
 */
public interface IEduTeacherSalaryConfigService
{
    public List<EduTeacherSalaryConfig> selectConfigList(EduTeacherSalaryConfig config);

    /**
     * 以教师账号为主体的薪资标准列表（未配置教师也返回，附带负责课门数统计）
     */
    public List<EduTeacherSalaryConfig> selectTeacherSalaryList(EduTeacherSalaryConfig config);

    public EduTeacherSalaryConfig selectConfigById(Long configId);

    public int insertConfig(EduTeacherSalaryConfig config);

    public int updateConfig(EduTeacherSalaryConfig config);

    public int deleteConfigByIds(Long[] configIds);
}
