# 自定义排课规则

生产发布前先阅读 [数据库兼容与发布步骤](custom-schedule-rules-deployment.md)。必须先完成增量迁移并升级网页、小程序，再开启 `CUSTOM_SCHEDULE_RULES_ENABLED`；生产默认关闭自定义规则写入，本地 `local` 配置开启。

## 使用

1. 网页端进入「教务工具 → 排课规则」，新增规则，填写名称，并在日期选择器中选中所有上课日期。可以跨月份选择，重复点击取消日期，也可以移除日期标签。
2. 在普通排课管理或可视化课表中新增／编辑班课，将上课模式改为「自定义日期规则」，选择规则。
3. 核对日期清单、自动生成的开课／结课日期和实际计划课次数，继续设置教师、时段、校区／教室等信息。计费课次数独立保留，首次选择规则时仅在未填写时提供日期数作为建议。
4. 保存后课程使用一份日期快照。规则以后改名、修改日期或停用，不会联动修改该课程。编辑课程时如要使用规则的新版本，需主动重新选择或点击「应用最新规则」。
5. 已有考勤、锁定讲次或调课记录时，不允许用更换日期规则覆盖历史，请使用原有调课管理。普通已报名课程仍保留确认修改流程。

自定义日期适用于班课，一对一继续按原有单日预约方式管理。课表导入继续使用原来的递推规则，覆盖导入会跳过已有自定义课程，清理旧格子时也会保留自定义课程。

小程序可读取和展示自定义日期，首页、家长课表、教师课表、课次、报名与考勤继续使用原有入口。小程序不能维护或更换规则；编辑已有自定义课程时日期模式和日期保持只读，后端同时保护旧客户端提交。

## 数据与部署

- 增量迁移：`database/migrations/20261123_schedule_rules.sql`，可重复执行。创建规则模板和模板日期表，给课程添加可空的来源与日期快照字段，并按菜单名称／组件和角色标识增加菜单授权。没有历史课程回填或金额重算。
- 接口：`GET /system/schedule-rules`、`GET /system/schedule-rules/{id}`、`POST /system/schedule-rules`、`PUT /system/schedule-rules/{id}`。查询支持 `enabledOnly=true`。启用／停用在编辑规则中完成，不提供物理删除。
- 保存课程使用 `applyScheduleRule=true`、`scheduleRuleId`、`scheduleRuleVersion` 显式应用规则；切回递推模式使用 `clearScheduleRule=true`。普通编辑不会重新应用模板。日期由后端读取模板生成，不采用客户端上传的日期快照。
- 规则更新和应用均校验版本。模板修改和课程快照存储在各自事务中；失败会回滚。已有订单、收据、支付、退款和计费覆盖值不追溯改写。
- 先备份目标库，再执行增量迁移，然后更新后端、网页与小程序。**先发布支持自定义日期的小程序，再让教务创建自定义课程**，避免旧版小程序无法显示该模式。业务启用前可以先完成代码发布与规则维护，暂不创建自定义排课。
- 已产生自定义课程后，回滚时必须保留对该模式的读取能力；不能直接回滚到不识别该模式的旧程序。
- 本次数据库验证仅使用随机创建后删除的临时测试库，没有对本地既有业务库或线上数据库执行迁移。

## 验证

后端回归覆盖日期展开、规则快照保存、普通编辑不重新应用、规则停用／空日期／版本冲突、与旧课程的实际日期冲突、计费课次数独立、考勤保护和真实 MyBatis SQL。MySQL 测试在随机临时库重复执行迁移，验证菜单不重复、规则创建／修改／停用、过期版本拒绝以及原课程所有原字段不变。

小程序测试覆盖首页、家长课表、自定义日期及调课、停课和日期范围外调课，并覆盖编辑自定义课程时保持日期规则。浏览器测试用本机 Edge 和真实 Vue／Element UI 组件验证规则维护、选择、自动起止日期、独立计费值和修改规则不改变课程预览；该浏览器测试使用内存接口，数据库持久化由独立 MySQL 测试验证。

可重复执行：

```powershell
# 项目根目录：后端隔离构建，不覆盖运行中服务的 JAR
.\scripts\build_backend_local.ps1 -JavaHome 'C:\Program Files\Java\jdk-25.0.3' -TestFilter 'ScheduleDateRuleTest,ScheduleCrudTest,CourseLessonCountTest,TimetableTeacherMatchingTest,AttendanceSalaryApprovalTest'

# MySQL 验证：通过环境变量 MYSQL_PWD 提供本机凭据，增加 JVM 参数 -Dschedule.mysql=true
# 运行测试类 ScheduleRuleMysqlTest；仅创建和删除 codex_schedule_rule_test_* 随机临时库

# 小程序回归
$tests = (Get-ChildItem WeChatMiniApp\tests -Filter '*.cjs' -File).FullName
node --test @tests

# 网页组件交互（需安装 Playwright 与本机 Edge；可设置 PLAYWRIGHT_MODULE）
node scripts/tests/schedule_rule_ui.cjs

# 网页生产构建，在 RuoYi-Vue-master/ruoyi-ui 中执行
npm run build:prod
```

没有执行线上部署、数据库覆盖、提交或推送。
