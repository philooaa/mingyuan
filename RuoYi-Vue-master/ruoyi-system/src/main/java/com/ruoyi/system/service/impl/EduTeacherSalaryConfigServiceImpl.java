package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduTeacherSalaryConfig;
import com.ruoyi.system.mapper.EduTeacherSalaryConfigMapper;
import com.ruoyi.system.service.IEduTeacherSalaryConfigService;

/**
 * 教师薪资标准 服务实现
 *
 * @author ruoyi
 * @date 2026-09-04
 */
@Service
public class EduTeacherSalaryConfigServiceImpl implements IEduTeacherSalaryConfigService
{
    @Autowired
    private EduTeacherSalaryConfigMapper configMapper;

    @Override
    public List<EduTeacherSalaryConfig> selectConfigList(EduTeacherSalaryConfig config)
    {
        return configMapper.selectConfigList(config);
    }

    @Override
    public List<EduTeacherSalaryConfig> selectTeacherSalaryList(EduTeacherSalaryConfig config)
    {
        return configMapper.selectTeacherSalaryList(config);
    }

    @Override
    public EduTeacherSalaryConfig selectConfigById(Long configId)
    {
        return configMapper.selectConfigById(configId);
    }

    @Override
    public int insertConfig(EduTeacherSalaryConfig config)
    {
        EduTeacherSalaryConfig exist = configMapper.selectConfigByTeacherId(config.getTeacherId());
        if (exist != null)
        {
            throw new ServiceException("该教师已存在薪资标准，请直接修改");
        }
        if (config.getStatus() == null || config.getStatus().isEmpty())
        {
            config.setStatus("0");
        }
        // teacher_id 上有唯一键：该教师此前删除过标准时复用旧行复活，避免重复插入撞唯一键
        EduTeacherSalaryConfig deleted = configMapper.selectDeletedByTeacherId(config.getTeacherId());
        if (deleted != null)
        {
            config.setConfigId(deleted.getConfigId());
            config.setDelFlag("0");
            config.setUpdateBy(SecurityUtils.getUsername());
            config.setUpdateTime(DateUtils.getNowDate());
            return configMapper.updateConfig(config);
        }
        config.setCreateTime(DateUtils.getNowDate());
        config.setCreateBy(SecurityUtils.getUsername());
        return configMapper.insertConfig(config);
    }

    @Override
    public int updateConfig(EduTeacherSalaryConfig config)
    {
        config.setUpdateTime(DateUtils.getNowDate());
        config.setUpdateBy(SecurityUtils.getUsername());
        return configMapper.updateConfig(config);
    }

    @Override
    public int deleteConfigByIds(Long[] configIds)
    {
        return configMapper.deleteConfigByIds(configIds);
    }
}
