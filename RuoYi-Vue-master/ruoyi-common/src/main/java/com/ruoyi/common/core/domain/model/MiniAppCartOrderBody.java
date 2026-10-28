package com.ruoyi.common.core.domain.model;

import java.util.List;

/**
 * 小程序课程报名购物车下单请求：一个购物车生成一个订单。
 */
public class MiniAppCartOrderBody
{
    /** 购物车里的课程（排课）ID，一个订单最多 10 门 */
    private List<Long> scheduleIds;
    /** 报名的孩子账户 ID；为空时按 studentName 新建孩子 */
    private Long lessonAccountId;
    /** 当前查看的孩子账户 ID，用于校验家长只能替自己的孩子下单 */
    private Long accountId;
    private String studentName;
    private String studentPhone;
    private String contactPhone;
    /** 新建孩子时的幂等键 */
    private String studentRequestKey;
    /** 下单幂等键，重复提交返回同一订单 */
    private String idempotencyKey;

    public List<Long> getScheduleIds() { return scheduleIds; }
    public void setScheduleIds(List<Long> value) { scheduleIds = value; }
    public Long getLessonAccountId() { return lessonAccountId; }
    public void setLessonAccountId(Long value) { lessonAccountId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getStudentName() { return studentName; }
    public void setStudentName(String value) { studentName = value; }
    public String getStudentPhone() { return studentPhone; }
    public void setStudentPhone(String value) { studentPhone = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value; }
    public String getStudentRequestKey() { return studentRequestKey; }
    public void setStudentRequestKey(String value) { studentRequestKey = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
}
