package com.ruoyi.common.constant;

/**
 * 名远教育业务角色标识。
 */
public interface EduRoleConstants
{
    /** 家长角色，对应小程序家长端。 */
    String PARENT = "parent";

    /** 教师角色，对应小程序教师端。 */
    String TEACHER = "teacher";

    /** 教务角色；只保留核价与课程定价权限，不参与收款。 */
    String EDUCATION = "education";

    /** 管理员角色，沿用若依内置超级管理员 role_key。 */
    String ADMIN = "admin";

    /** 业务管理员；不拥有超级管理员的账号及权限维护权。 */
    String BUSINESS_ADMIN = "business_admin";
}
