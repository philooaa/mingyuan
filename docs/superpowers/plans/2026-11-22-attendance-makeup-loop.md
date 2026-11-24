# 请假补课闭环实施计划

用户已批准先完成补课闭环。仅本地实现和验证；不导出、不部署、不自动退费。

- 从已终审请假明细安排补课，原请假记录保留；教务/管理员可安排和取消，教师/家长不可操作。
- 按稳定档案及原请假 signInId 关联未来未提交课次，插入现有 TRANSFER 临时名单；查重、上课时间冲突校验，原讲次允许已锁定。
- 新增补课安排历史表，保留取消/重排记录；一次原请假只能有一条有效安排。学生、原记录、目标讲次加锁，唯一约束兜底。
- 目标终审到课后自动完成；待审核不完成，补课请假/录播等未到课仍未完成；补课产生的请假不得再次成为独立欠课记录。允许终审未到课后重排，保留原签到。
- 网页与小程序均在具体请假讲次提供安排、取消/重新安排及历史记录，复用既有系统样式。

## 接口约定
GET /miniapp/attendance/leave-report/makeup/options?signInId=&scheduleId=&keyword=
返回 {source:{studentName,courseName,lessonNo,classDate},courses:[{scheduleId,courseName,teacherName,year,termName,timeSlot}],lessons:[{lessonId,lessonNo,classDate,startTime,endTime,label}],history:[{planId,courseName,lessonNo,classDate,cancelled,remark,cancelReason,createTime,operatorName}]}。
POST /miniapp/attendance/leave-report/makeup {signInId,targetLessonId,remark}。
POST /miniapp/attendance/leave-report/makeup/cancel {planId,reason}。

报表 lesson.state 新增 scheduled（待补课）、failed（补课未完成），保留 pending/completed/conflict。lesson.makeup 添加 planId，canArrange/canCancel 用于操作入口。pendingCount 仍代表未完成总数，scheduledCount 是其中已安排待补课数。

任务：独立实现后端安排与测试、网页操作、小程序操作；集成报表与教师名单；执行隔离数据库、前端测试、构建和本地实测；独立审查。
