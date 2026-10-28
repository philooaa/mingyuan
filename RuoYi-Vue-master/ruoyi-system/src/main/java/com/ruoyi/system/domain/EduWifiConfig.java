package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 校区WiFi白名单对象 edu_wifi_config
 *
 * @author ruoyi
 * @date 2026-10-10
 */
public class EduWifiConfig extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** WiFi配置ID */
    private Long wifiId;

    /** 校区名称 */
    private String campusName;

    /** WiFi名称 */
    private String ssid;

    /** WiFi路由器MAC（可选，优先匹配） */
    private String bssid;

    /** 状态（0正常 1停用） */
    private String status;

    public void setWifiId(Long wifiId)
    {
        this.wifiId = wifiId;
    }

    public Long getWifiId()
    {
        return wifiId;
    }

    public void setCampusName(String campusName)
    {
        this.campusName = campusName;
    }

    public String getCampusName()
    {
        return campusName;
    }

    public void setSsid(String ssid)
    {
        this.ssid = ssid;
    }

    public String getSsid()
    {
        return ssid;
    }

    public void setBssid(String bssid)
    {
        this.bssid = bssid;
    }

    public String getBssid()
    {
        return bssid;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("wifiId", getWifiId())
            .append("campusName", getCampusName())
            .append("ssid", getSsid())
            .append("bssid", getBssid())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
