package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.EduWifiConfig;

/**
 * 校区WiFi白名单 数据层
 *
 * @author ruoyi
 * @date 2026-10-10
 */
public interface EduWifiConfigMapper
{
    /**
     * WiFi白名单列表（可按校区、状态过滤）
     */
    public List<EduWifiConfig> selectList(EduWifiConfig query);

    /**
     * 按ID查询
     */
    public EduWifiConfig selectById(Long wifiId);

    /**
     * 新增
     */
    public int insert(EduWifiConfig config);

    /**
     * 修改
     */
    public int update(EduWifiConfig config);

    /**
     * 删除
     */
    public int deleteById(Long wifiId);
}
