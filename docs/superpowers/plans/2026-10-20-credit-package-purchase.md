# 一对一课包购买 Implementation Plan

> **For agentic workers:** Execute the approved scope in the current checkout; preserve existing dirty changes. Use focused tests and a single scoped review, per the user's preference to reduce testing/review overhead.

**Goal:** 家长为选定孩子购买15次整数课包，按本单包数折扣叠加后台通用优惠券，复用线下收费确认并一次性增加孩子账户余额。

**Architecture:** 扩展现有 edu_enrollment 财务账单，增加 CREDIT_PACKAGE 类型；课程类型仍为 COURSE。课包账单不绑定排课，因此 schedule_id 允许 NULL，但类型检查保证课程报名仍需真实排课。复用 edu_tuition_payment/receipt、edu_user_coupon 和现有账户/额度流水，不恢复已停用的旧课包表。价格快照、请求幂等、优惠券占用和收款入账在后端维护。

**Tech Stack:** Java17、Spring、MySQL8、Vue2、微信原生小程序。

**Spec:** 用户2026-10-20确认的对话方案与价格海报：每包15次；小学400、初一二500、初三600、高一二700、高三800元/基础次；1包95折、2包92折、3包及以上9折；优惠券在折后抵扣；线下收款确认后入账；资深2、精英1额度规则不变。

## Global Constraints

- 当前文件夹继续开发，不复制、不切换工作区，不提交/推送未请求的代码。
- 课次属于每位孩子；年级从现有孩子档案读取，客户端不能改价或代选别人的孩子。
- 本次购买、账户余额和交易记录；课包余额退费尚无逐单消耗分摊，不能放入班课退款自动处理，必须明确拒绝，保留价格快照待后续对接。
- 通用跨学科课包只允许无指定课程、无学科限制的优惠券，避免把指定科目的券用于通用余额。
- 优惠券提交订单即占用，订单取消释放；线下确认才核销并增加额度。

### Task 1: 价格、订单、收费及迁移（主代理）

- [x] 先写 CreditPackageMysqlTest 价格、归属、重复请求、优惠券锁、收款一次入账的失败检查。
- [x] 新增 finance/CreditPackageService.java 和 controller/CreditPackageController.java；增量迁移 database/migrations/20261020_credit_package_purchase.sql。新增 bill_type、package_snapshot、package_request_key、package_credited_time，扩展而非重建原表。
- [x] TuitionService、CouponService、LessonFinance 接入类型保护、固定报价、所选券及确认入账；原班课流程保持原样。
- [x] 个人账户费用列表包含课包订单，课程列表不把购买当作课程。普通课程报名 CRUD 不操作课包订单。

### Task 2: 小程序与管理网页（独立前端任务）

只编辑前端文件及对应前端测试，不编辑Java、数据库或本文；不提交、不启动/停止服务、不用业务记录测试写入、不派生子代理。

- [x] 新增 `WeChatMiniApp/pages/lesson-packages/lesson-packages.{js,json,wxml,wxss}`，沿用蓝白UI和现有 child-context/request/session 防护。入口为选定孩子详情“课包与费用”中的“购买一对一课包”，通过accountId跳转。
- [x] GET `/miniapp/parent/lesson-packages/catalog` → data `{prices:[{label,unitPrice}],creditsPerPackage:15,discounts:[{label,rate}],notice}`；GET `/miniapp/parent/account/children/{accountId}` 读取现有孩子信息。展示按当前年级计价、购买包数1/2/3/自定义整数1至100、每包15额度、固定2小时规则及资深扣2。
- [x] POST `/miniapp/parent/lesson-packages/quote` body `{accountId,packageCount,userCouponId:null或ID}` → data `{accountId,studentName,gradeName,packageCount,credits,unitPrice,grossAmount,packageDiscount,subtotal,couponDiscount,payableAmount,coupons:[已有券字段],quoteHash}`；报价仅后端计算。改数量/券立即使旧报价不可提交，查询失败禁用购买，延迟响应不覆盖新选择。
- [x] POST `/miniapp/parent/lesson-packages/orders` body `{accountId,packageCount,userCouponId,quoteHash,expectedPayableAmount,idempotencyKey}` → data 复用TuitionService.bill、包含enrollmentId。重复点击只发一次，网络未知保留请求键/原购买参数，允许幂等重试，不创建新的可能重复订单。成功跳转现有tuition-detail，带id和accountId。
- [x] POST `/miniapp/parent/lesson-packages/orders/{id}/cancel` body `{accountId}` 取消未收款订单。现有tuition-detail根据 `detail.billType==='CREDIT_PACKAGE'` 使用此接口；收费仍只联系教务线下，不增加微信支付。
- [x] 后端账单data含 `billType:'CREDIT_PACKAGE'`、`packageSnapshot`（解析对象，包含报价字段）、`packageCreditedTime`；detail显示原价、包数、基础次数、课包优惠、优惠券优惠、最终应付以及待确认/已入账提示。课包暂不开放原班课退款申请，保留原课程功能。
- [x] shared child-account 模板加购买按钮；utils/child-account load fees 时 `includePackages:true`，courses 时不传，由后端默认排除新账单类型。费用记录 package 名称由后端courseClassName返回。余额已有 remaining/reserved/consumed，沿用现有展示。
- [x] Web `tuition/index.vue` 识别课包详情，展示计价快照和入账状态，固定所选优惠券（collect报价沿用父母订单所选券）。`utils/tuitionFinance.canPrice` 课包返回false；后端同样拒绝篡改课包价格。原收费登记、凭证、确认和收据复用，无新教师/教务页。
- [x] 新增针对性前端测试，运行对应测试与现有网页/小程序测试。不做真实订单或收费操作。报告修改文件、测试命令/结果及疑虑。

### Task 3: 合并验证与交付（主代理）

- [x] 隔离库验证迁移重复执行、原课程财务不变、收款和余额在同一事务；前端构建/WXML编译。
- [x] 备份本地库后执行兼容增量，确认旧记录与余额不变，重新打包启动；真实库仅只读核验。
- [x] 更新一对一整合说明，明确字段含义、操作路径和课包退款限制，不上传本机数据库或凭证。
