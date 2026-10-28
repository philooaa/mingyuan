package com.ruoyi.system.controller;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.EduWifiConfig;
import com.ruoyi.system.mapper.EduWifiConfigMapper;

/**
 * 校区WiFi白名单配置（管理端）
 */
@RestController
@RequestMapping("/system/wificonfig")
public class EduWifiConfigController extends BaseController
{
    @Autowired
    private EduWifiConfigMapper wifiConfigMapper;

    /**
     * WiFi白名单列表（不分页）
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')")
    @GetMapping("/list")
    public AjaxResult list(EduWifiConfig query)
    {
        List<EduWifiConfig> list = wifiConfigMapper.selectList(query);
        return success(list);
    }

    /**
     * 新增WiFi配置
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')")
    @PostMapping
    public AjaxResult add(@RequestBody EduWifiConfig config)
    {
        validate(config);
        config.setWifiId(null);
        config.setCreateBy(SecurityUtils.getUsername());
        config.setCreateTime(new java.util.Date());
        if (StringUtils.isEmpty(config.getStatus()))
        {
            config.setStatus("0");
        }
        assertSsidNotDuplicated(config.getCampusName(), config.getSsid(), null);
        return toAjax(wifiConfigMapper.insert(config));
    }

    /**
     * 修改WiFi配置
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')")
    @PutMapping
    public AjaxResult edit(@RequestBody EduWifiConfig config)
    {
        if (config.getWifiId() == null)
        {
            throw new ServiceException("WiFi配置ID不能为空");
        }
        validate(config);
        config.setUpdateBy(SecurityUtils.getUsername());
        config.setUpdateTime(new java.util.Date());
        assertSsidNotDuplicated(config.getCampusName(), config.getSsid(), config.getWifiId());
        return toAjax(wifiConfigMapper.update(config));
    }

    /**
     * 删除WiFi配置
     */
    @PreAuthorize("@ss.hasAnyRoles('admin,administrator')")
    @DeleteMapping("/{wifiId}")
    public AjaxResult remove(@PathVariable Long wifiId)
    {
        return toAjax(wifiConfigMapper.deleteById(wifiId));
    }

    private void validate(EduWifiConfig config)
    {
        if (config == null || StringUtils.isEmpty(config.getCampusName()))
        {
            throw new ServiceException("校区名称不能为空");
        }
        if (StringUtils.isEmpty(config.getSsid()))
        {
            throw new ServiceException("WiFi名称不能为空");
        }
    }

    private void assertSsidNotDuplicated(String campusName, String ssid, Long excludeId)
    {
        EduWifiConfig query = new EduWifiConfig();
        query.setCampusName(campusName);
        for (EduWifiConfig exist : wifiConfigMapper.selectList(query))
        {
            if (exist.getSsid().equals(ssid) && !exist.getWifiId().equals(excludeId))
            {
                throw new ServiceException("该校区已存在同名WiFi：" + ssid);
            }
        }
    }
}
