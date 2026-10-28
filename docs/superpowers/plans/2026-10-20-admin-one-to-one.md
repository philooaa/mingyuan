# 管理网页一对一对齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有管理页面内，由管理员选择教师完成与小程序一致的一对一管理，不新增教师页、教务页或业务表。

**Architecture:** 新增管理员入口控制器，发布复用 TeacherOneToOneDraft、ScheduleAvailabilityService 和排课服务，预约变更复用 OneToOneEnrollmentService 的同一事务实现。保留当前登录人作为审计操作者，不切换教师身份。共用一个管理弹窗嵌入现有排课、课表和报名页面。

**Tech Stack:** Java 17 / Spring Boot / MyBatis / MySQL / Vue 2 / Element UI。

**Spec:** 本对话已批准范围：管理网页仅管理员操作；以当前小程序实际行为为准；保留现有收费退费。

## Global Constraints

- 使用当前目录，不重建副本、不覆盖已有未提交修改。
- 不新增角色、菜单页或业务表，不修改家长端预约规则。
- 发布具体日期、机构固定两小时时段及真实空闲教室，学科来自教师配置。
- 接课、拒绝、取消处理、完成消课共享行锁、历史保护、幂等和课包快照；操作人记录实际管理员。
- 已有报名禁止修改/撤回；保留历史，不把取消预约当作现金退费。

### Task 1: 管理员后端入口

**Files:** 新增 `RuoYi-Vue-master/ruoyi-system/src/main/java/com/ruoyi/system/controller/AdminOneToOneController.java`；修改 `RuoYi-Vue-master/ruoyi-system/src/main/java/com/ruoyi/system/service/OneToOneEnrollmentService.java`、`RuoYi-Vue-master/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/EduCourseScheduleServiceImpl.java` 和 `RuoYi-Vue-master/ruoyi-system/src/main/java/com/ruoyi/system/finance/TuitionService.java`；新增 `RuoYi-Vue-master/ruoyi-system/src/test/java/com/ruoyi/system/service/AdminOneToOneMysqlTest.java`。

- [x] 先增加隔离数据库测试：普通教师/家长不能用管理员入口；管理员处理其他教师预约仍记录本人；重复取消只释放一次课次；完成仅消耗快照次数。
- [x] 执行测试，确认缺少管理员方法导致断言失败。
- [x] 增加管理员专用方法并拆出共用事务内部实现，原教师调用继续检查本人归属。
- [x] 增加 `/system/schedule/single` 下的教师资料、可用时段、课程列表、发布/编辑/撤回、预约列表/处理/完成/历史接口，类级仅管理员可用。
- [x] 执行后端针对性测试与打包，不向真实数据库写入测试预约或课次。

### Task 2: 现有页面中的管理窗口

**Files:** 新增 `ruoyi-ui/src/api/system/oneToOne.js`、`ruoyi-ui/src/views/system/schedule/OneToOneManager.vue`、`ruoyi-ui/tests/one-to-one-admin.test.cjs`；修改现有 schedule/index.vue、timetable/index.vue、timetable/TimetableDialog.vue、enrollment/index.vue。

- [x] 先增加真实组件逻辑测试：换教师/日期使旧时段失效；延迟响应不覆盖新选择；提交仅包含选定时段参数；按服务端状态显示预约操作。
- [x] 运行测试确认功能缺失，再实现窗口的开放时段、课程、预约、历史和收费入口。
- [x] 三处现有页面嵌入同一个窗口；一对一记录不再进入不兼容的通用班课表单，班课功能保持原样。
- [x] 执行网页现有测试与正式构建，核对新入口仅管理员可见。

### Task 3: 文档及交付

**Files:** 更新 `docs/一对一整合说明.md`。

- [x] 记录管理员操作位置、与小程序同步的数据流、审计归属及无需数据库迁移。
- [x] 复核差异和测试结果；重新打包并重启原本地后端，不在未经请求时提交、推送或改动数据库。
