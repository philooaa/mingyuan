# 教师主导补课（本地）

用户确认：老师安排后立即生效，课后教师点名→教务初审→管理员终审。只有终审实到才抵扣原请假。原记录与财务不改。

## 界面与权限
- 教师“学生请假”从只读改为只能管理本人原授课讲次的补课，可插班或自定义日期、起止时间、地点；备注选填。
- 教务/管理员仅查看补课及参与既有两级出勤审核，不再安排或取消补课。混合角色账号仅在教师工作台展示操作；后端写入必须验证活跃教师角色及原请假讲次教师身份，不能仅凭JWT或前端标识。
- 老安排保留；原任课老师能维护尚可取消的旧安排。
- 自定义补课不生成报名、订单、付款或课包扣次。通过受限的出勤专用排课记录复用教师点名及两级审核；不上架招生，不受全学期调课覆盖。
- 检查学生和自定义授课教师的时间冲突；同一欠讲不能重复安排，重试幂等。已提交/审核/完成安排不得随意取消；补课失败可重排并留历史。

## 接口约定
GET /miniapp/attendance/leave-report 原接口：教师自己范围，canManageMakeup=true,readOnly=false；教务/管理员canManageMakeup=false,readOnly=true。每讲canArrange/canCancel只对任课老师开放。
GET /miniapp/attendance/leave-report/makeup/options：教师限本人原讲次；教务/管理员可看历史；保留source,courses,lessons,history，canManageMakeup可附加。普通可选班不含出勤专用自定义补课。
POST /miniapp/attendance/leave-report/makeup：
  插班 {signInId,mode:'CLASS',targetLessonId,remark}
  自定义 {signInId,mode:'CUSTOM',classDate:'YYYY-MM-DD',startTime:'HH:mm',endTime:'HH:mm',location,remark}
POST /miniapp/attendance/leave-report/makeup/cancel {planId,reason}：仅原任课教师。
历史及计划新增arrangeType='CLASS'/'CUSTOM'，其它字段兼容。

## 分工与验证
1. 后端：权限、插班/自定义安排、数据库增量迁移、出勤专用记录及课程CRUD保护、报告操作权限、孤立MySQL回归测试。已有只读内部roster helper需改为包内可信调用，公开staff接口保持权限。
2. 小程序：教师操作与两种表单；教务/管理员只读历史，查询、切换账号和待提交请求保护延续。
3. 网页：教师组件启用安排、两种表单，职员界面只读历史。
4. 综合：客户端全量测试、官方WXML/WXSS编译、后端孤立库测试/打包、审查。仅本地新增迁移表列，备份后应用；启动新后端，验证接口，不发布不提交GitHub。

隔离：当前目录有上轮已授权修改，保留原分支和全部未提交内容，不新建空分支或重置文件。各任务仅编辑自己的文件，避免共享文件冲突。
