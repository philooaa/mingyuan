const { test, before } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')
let finance = {}

test('credit packages cannot be repriced and collection always retains the parent coupon',async()=>{
 assert.equal(finance.canPrice({billType:'CREDIT_PACKAGE',billingStatus:'OPEN',payments:[]}),false)
 const ids=[];const p=component('tuition',{quoteTuition:async(id,coupon)=>{ids.push(coupon);return{data:{payableAmount:'7000.00',coupons:[],userCouponId:'88'}}}})
 p.detail={enrollmentId:'9',billType:'CREDIT_PACKAGE',packageSnapshot:{userCouponId:'88'}};await p.openOperation('collect');assert.equal(p.form.userCouponId,'88');p.form.userCouponId='99';await p.refreshQuote();assert.deepEqual(ids,['88','88'])
 const routes=[];p.$router={push:v=>routes.push(v)};p.goRefund({paymentId:'1'});assert.equal(routes.length,0)
})
before(async () => {
  const file = path.join(root, 'src/utils/tuitionFinance.js')
  if (fs.existsSync(file))
    finance = await import(
      'data:text/javascript;base64,' + fs.readFileSync(file).toString('base64')
    )
})
test('unknown historical money stays unknown and cents never silently round', () => {
  assert.equal(typeof finance.money, 'function')
  assert.equal(finance.money(null), '待核验')
  assert.equal(finance.money('0'), '0.00')
  assert.equal(finance.money('99999999.99'), '99999999.99')
  assert.equal(finance.money('199999999.98'), '199999999.98')
  assert.equal(finance.money('1.001'), '金额异常')
  assert.equal(finance.amount('12.3'), '12.30')
  assert.throws(() => finance.amount('1.001'), /两位/)
  assert.throws(() => finance.amount('-1'), /金额/)
  assert.throws(() => finance.amount('0', true), /大于零/)
})
test('unknown or pending payment reserves the bill and blocks new collection and repricing', () => {
  assert.equal(typeof finance.canCollect, 'function')
  const bill = { billingStatus: 'OPEN', financeMode: 'REAL', payments: [] }
  assert.equal(finance.canCollect(bill), true)
  for (const status of ['UNKNOWN', 'PENDING', 'SUCCESS', 'UNRECOGNIZED']) {
    assert.equal(
      finance.canCollect({ ...bill, payments: [{ paymentStatus: status }] }),
      false
    )
    assert.equal(
      finance.canPrice({ ...bill, payments: [{ paymentStatus: status }] }),
      false
    )
  }
  assert.equal(
    finance.canCollect({ ...bill, billingStatus: 'HISTORY_PENDING' }),
    false
  )
  assert.equal(
    finance.canPrice({ ...bill, billingStatus: 'PENDING_PRICE' }),
    true
  )
  assert.equal(finance.canClosePayment({ paymentStatus: 'UNKNOWN' }), false)
})
test('new two-stage review helper excludes historical states', () => {
  assert.equal(finance.canReview({ refundStatus: 'WAIT_EDU' }, '2'), true)
  for (const refundStatus of [
    'PENDING_REVIEW',
    'APPROVED',
    'PROCESSING',
    'UNKNOWN',
    'SUCCESS',
  ])
    assert.equal(finance.canReview({ refundStatus }, '2'), false)
})
test('evidence upload accepts only small PDF/image files and validated private keys', () => {
  assert.equal(typeof finance.validateEvidenceFile, 'function')
  assert.equal(
    finance.validateEvidenceFile({ name: '凭证.pdf', size: 1024 }),
    true
  )
  assert.throws(
    () => finance.validateEvidenceFile({ name: 'x.html', size: 100 }),
    /PDF/
  )
  assert.throws(
    () =>
      finance.validateEvidenceFile({
        name: 'x.png',
        size: 10 * 1024 * 1024 + 1,
      }),
    /10/
  )
  assert.equal(
    finance.evidenceKey({ code: 200, data: { evidenceKey: 'private-token' } }),
    'private-token'
  )
  assert.throws(
    () => finance.evidenceKey({ code: 500, msg: '上传失败' }),
    /上传失败/
  )
})
test('refund evidence is mandatory for success and unknown outcome cannot mean success', () => {
  assert.equal(typeof finance.refundConfirmation, 'function')
  assert.throws(
    () =>
      finance.refundConfirmation({
        outcome: 'SUCCESS',
        completedTime: '2026-10-07 10:00:00',
      }),
    /凭证/
  )
  assert.deepEqual(
    finance.refundConfirmation({
      outcome: 'UNKNOWN',
      reason: '银行结果待核对',
      approvedAmount: '99',
    }),
    { outcome: 'UNKNOWN', reason: '银行结果待核对' }
  )
  assert.throws(
    () => finance.refundConfirmation({ outcome: 'FAILED', reason: '' }),
    /原因/
  )
  assert.deepEqual(
    finance.refundConfirmation({
      outcome: 'SUCCESS',
      evidenceKey: 'key',
      completedTime: '2026-10-07 10:00:00',
      providerRefundNo: 'B001',
    }),
    {
      outcome: 'SUCCESS',
      evidenceKey: 'key',
      completedTime: '2026-10-07 10:00:00',
      providerRefundNo: 'B001',
    }
  )
  assert.deepEqual(
    finance.refundConfirmation(
      {
        outcome: 'SUCCESS',
        evidenceKey: 'cash-proof',
        completedTime: '2026-10-07 10:00:00',
      },
      'CASH'
    ),
    {
      outcome: 'SUCCESS',
      evidenceKey: 'cash-proof',
      completedTime: '2026-10-07 10:00:00',
      providerRefundNo: null,
    }
  )
})
test('batch grant deduplicates exact long IDs, limits 100 and preserves a retry key', () => {
  assert.equal(typeof finance.grantBody, 'function')
  assert.deepEqual(
    finance.grantBody({
      parentIds: ['9007199254740993', '9007199254740993', '9007199254740994'],
      reason: '活动',
      idempotencyKey: 'same-key',
    }),
    {
      parentIds: ['9007199254740993', '9007199254740994'],
      reason: '活动',
      idempotencyKey: 'same-key',
    }
  )
  assert.throws(
    () =>
      finance.grantBody({
        parentIds: [],
        reason: '活动',
        idempotencyKey: 'same-key',
      }),
    /家长/
  )
  assert.throws(
    () =>
      finance.grantBody({
        parentIds: Array.from({ length: 101 }, (_, i) => String(i + 1)),
        reason: '活动',
        idempotencyKey: 'same-key',
      }),
    /100/
  )
})
test('authenticated PDF download rejects JSON failures instead of saving an error file', async () => {
  assert.equal(typeof finance.checkedBlob, 'function')
  const pdf = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
  assert.equal(await finance.checkedBlob(pdf), pdf)
  await assert.rejects(
    finance.checkedBlob(
      new Blob(['{"code":403,"msg":"没有下载权限"}'], {
        type: 'application/json',
      })
    ),
    /没有下载权限/
  )
})
function component(name, dependencies = {}) {
  const filename = path.join(root, 'src/views/system', name, 'index.vue')
  assert.ok(fs.existsSync(filename), name + ' component missing')
  const script = stripImports(
    fs.readFileSync(filename, 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
  ).replace('export default', 'component =')
  const context = vm.createContext({
    component: null,
    ReferralRewards:{},
    OneToOneManager:{},
    ...finance,
    ...dependencies,
  })
  vm.runInContext(script, context)
  const definition = context.component
  const instance = {
    ...definition.data(),
    $modal: {
      msgSuccess() {},
      msgError() {},
      confirm: () => Promise.resolve(),
    },
    $store: { state: { user: { id: '2' } } },
    $route: { query: {} },
    ...dependencies,
  }
  for (const [name, method] of Object.entries(definition.methods))
    instance[name] = method.bind(instance)
  return instance
}
function stripImports(source) {
  const tree = require('@babel/parser').parse(source, { sourceType: 'module' })
  for (const node of tree.program.body
    .filter((n) => n.type === 'ImportDeclaration')
    .reverse()) {
    source = source.slice(0, node.start) + source.slice(node.end)
  }
  return source
}
test('collection submits backend quote unchanged and reuses idempotency after network failure', async () => {
  const sent = []
  const instance = component('tuition', {
    createPayment: async (id, body) => {
      sent.push({ id, body: JSON.parse(JSON.stringify(body)) })
      throw new Error('网络中断')
    },
  })
  instance.detail = {
    enrollmentId: '9007199254740993',
    billingStatus: 'OPEN',
    payments: [],
  }
  instance.quote = {
    payableAmount: '900.00',
    financialVersion: 3,
    userCouponId: '9007199254740994',
  }
  instance.operation = 'collect'
  instance.form = {
    payerName: '家长',
    channel: 'CASH',
    paidTime: '2026-10-07 10:00:00',
    evidenceKey: 'key',
    idempotencyKey: 'same-key',
    expectedPayableAmount: '1.00',
  }
  await instance.submitOperation()
  await instance.submitOperation()
  assert.equal(sent.length, 2)
  assert.equal(sent[0].id, '9007199254740993')
  assert.equal(sent[0].body.expectedPayableAmount, '900.00')
  assert.equal(sent[0].body.idempotencyKey, sent[1].body.idempotencyKey)
  assert.equal(instance.saving, false)
  assert.match(instance.formError, /网络中断/)
})
test('private detail clears stale data after denied request', async () => {
  const instance = component('tuition', {
    getTuition: () => Promise.reject(new Error('没有查询权限')),
  })
  instance.detail = { enrollmentId: '1', contactPhone: 'private' }
  await instance.handleDetail({ enrollmentId: '2' })
  assert.equal(instance.detail, null)
  assert.equal(instance.detailLoading, false)
  assert.match(instance.detailError, /没有查询权限/)
})
test('tuition summary uses server totals for selected mode, never sums the current page', async () => {
  let mode
  const instance = component('tuition', {
    listTuition: async () => ({
      rows: [{ enrollmentId: '1', receivedAmount: '100.00' }],
      total: 20,
    }),
    getTuitionSummary: async (query) => {
      mode = query.financeMode
      return {
        data: {
          receivedAmount: '999.00',
          refundedAmount: '200.00',
          netAmount: '799.00',
          dueAmount: '50.00',
          todayReceivedAmount: '100.00',
          todayRefundedAmount: '0.00',
          historyPendingCount: 4,
        },
      }
    },
  })
  instance.queryParams.financeMode = 'MOCK'
  await instance.getList()
  assert.equal(mode, 'MOCK')
  assert.equal(instance.summary.receivedAmount, '999.00')
  assert.equal(instance.summary.netAmount, '799.00')
  assert.equal(instance.rows.length, 1)
  assert.equal(instance.summary.historyPendingCount, 4)
})
test('a denied summary clears old totals while preserving an independently authorized list', async () => {
  const instance = component('tuition', {
    listTuition: async () => ({ rows: [{ enrollmentId: '1' }], total: 1 }),
    getTuitionSummary: async () => {
      throw new Error('没有汇总权限')
    },
  })
  instance.summary = { receivedAmount: 'private-total' }
  await instance.getList()
  assert.equal(instance.summary, null)
  assert.match(instance.summaryError, /汇总权限/)
  assert.equal(instance.rows.length, 1)
})
test('receipt replacement requires a reason and keeps failed operation visible', async () => {
  let writes = 0
  const instance = component('tuitionReceipt', {
    replaceReceipt: () => {
      writes++
      return Promise.reject(new Error('请刷新后重试'))
    },
  })
  instance.form = { receiptId: '9007199254740993', reason: '' }
  await instance.submitReplace()
  assert.equal(writes, 0)
  instance.form.reason = '抬头更正'
  instance.replaceOpen = true
  await instance.submitReplace()
  assert.equal(writes, 1)
  assert.equal(instance.replaceOpen, true)
  assert.equal(instance.saving, false)
})
test('coupon grants preserve selected parents and same batch key after failed request', async () => {
  const sent = []
  const instance = component('coupon', {
    grantCoupon: async (id, body) => {
      sent.push({ id, body: JSON.parse(JSON.stringify(body)) })
      throw new Error('断网')
    },
  })
  instance.grantTemplate = { templateId: '9007199254740993' }
  instance.grantForm = {
    parentIds: ['9007199254740994'],
    reason: '补偿',
    idempotencyKey: 'same-batch',
  }
  instance.grantOpen = true
  await instance.submitGrant()
  await instance.submitGrant()
  assert.equal(sent.length, 2)
  assert.equal(sent[0].body.parentIds[0], '9007199254740994')
  assert.equal(sent[0].body.idempotencyKey, sent[1].body.idempotencyKey)
  assert.equal(instance.grantOpen, true)
  assert.equal(instance.saving, false)
})
test('coupon all-results selection freezes 101 recipients and resumes only the unconfirmed batch', async () => {
  const sent = [],
    queries = [],
    confirmations = []
  let fail = true
  const instance = component('coupon', {
    listParents: async (query) => {
      queries.push({ ...query })
      return {
        rows: Array.from({ length: query.pageNum === 1 ? 100 : 1 }, (_, i) => ({
          parentId: String((query.pageNum - 1) * 100 + i + 1),
        })),
        total: 101,
      }
    },
    grantCoupon: async (id, body) => {
      sent.push({ id, body: JSON.parse(JSON.stringify(body)) })
      if (body.parentIds.length === 1 && fail) {
        fail = false
        throw new Error('第二批断网')
      }
      return { rows: body.parentIds, total: body.parentIds.length }
    },
    $modal: {
      confirm: async (message) => confirmations.push(message),
      msgSuccess() {},
    },
  })
  instance.getList = async () => {}
  instance.grantTemplate = { templateId: '9007199254740993' }
  instance.parentQuery.gradeName = '五年级'
  instance.parentQuery.subjectName = '英语'
  instance.grantForm.reason = '筛选活动'
  await instance.selectAllParents()
  assert.equal(instance.grantForm.parentIds.length, 101)
  assert.equal(queries.length, 2)
  assert.equal(queries[1].gradeName, '五年级')
  await instance.submitGrant()
  assert.equal(sent[0].body.parentIds.length, 100)
  assert.equal(sent[1].body.parentIds.length, 1)
  assert.equal(sent[0].body.subjectName, '英语')
  assert.match(instance.grantFormError, /100.*1/)
  assert.match(confirmations[0], /101/)
  instance.grantForm.parentIds = ['999']
  instance.grantForm.reason = '新说明'
  instance.parentQuery.gradeName = '六年级'
  instance.grantTemplate = { templateId: '999' }
  await instance.submitGrant()
  assert.equal(sent.length, 3)
  assert.deepEqual(sent[2], sent[1])
})
test('coupon filter changes and denied candidate queries clear reserved selections and stale all-results lists', async () => {
  let clears = 0
  const instance = component('coupon', {
    listParents: async () => {
      throw new Error('没有查询权限')
    },
    $refs: {
      parentsTable: {
        clearSelection() {
          clears++
        },
      },
    },
  })
  instance.grantForm.parentIds = ['old']
  instance.parents = [{ userId: 'old' }]
  instance.parentQuery.gradeName = '五年级'
  await instance.searchParents()
  assert.equal(instance.grantForm.parentIds.length, 0)
  assert.equal(instance.parents.length, 0)
  assert.ok(clears > 0)
  instance.grantForm.parentIds = ['old-again']
  await instance.selectAllParents()
  assert.equal(instance.grantForm.parentIds.length, 0)
  assert.match(instance.grantFormError, /权限/)
})
test('coupon all-results fetch blocks edits and discards a stale response after filter changes', async () => {
  let release
  const instance = component('coupon', {
    listParents: () =>
      new Promise((resolve) => {
        release = resolve
      }),
  })
  instance.grantTemplate = { templateId: '1' }
  const pending = instance.selectAllParents()
  instance.selectParents([{ parentId: '2' }])
  await instance.openGrant({ templateId: '3' })
  assert.equal(instance.grantTemplate.templateId, '1')
  assert.equal(instance.grantForm.parentIds.length, 0)
  instance.parentQuery.subjectName = '英语'
  release({ rows: [{ parentId: '4' }], total: 1 })
  await pending
  assert.equal(instance.grantForm.parentIds.length, 0)
})
test('coupon failed grant stays frozen until explicit history-check cancellation', async () => {
  const instance = component('coupon', {
    grantCoupon: async () => {
      throw new Error('结果未知')
    },
  })
  instance.grantTemplate = { templateId: '1' }
  instance.grantForm = {
    parentIds: ['2'],
    reason: '活动',
    idempotencyKey: 'original',
  }
  await instance.submitGrant()
  instance.selectParents([{ parentId: '3' }])
  await instance.openGrant({ templateId: '4' })
  assert.equal(instance.grantTemplate.templateId, '1')
  assert.deepEqual(Array.from(instance.grantForm.parentIds), ['2'])
  let warning
  instance.$modal.confirm = async (message) => {
    warning = message
  }
  await instance.cancelGrantBatch()
  assert.match(warning, /历史发放/)
  assert.equal(instance.grantBatch, null)
  assert.equal(instance.grantForm.parentIds.length, 0)
})
test('coupon failed batch can reopen from its rendered page entry and retry with the original key', async () => {
  const sent = []
  const instance = component('coupon', {
    grantCoupon: async (id, body) => {
      sent.push({ id, body: JSON.parse(JSON.stringify(body)) })
      if (sent.length === 1) throw new Error('结果未知')
      return { total: 1 }
    },
  })
  instance.getList = async () => {}
  instance.grantTemplate = { templateId: '1', templateStatus: 'ACTIVE' }
  instance.grantForm = {
    parentIds: ['2'],
    reason: '活动',
    idempotencyKey: 'original',
  }
  instance.grantOpen = true
  await instance.submitGrant()
  instance.grantOpen = false
  // Recovery must remain available even if the original template is now paused or exhausted.
  instance.grantTemplate.templateStatus = 'PAUSED'
  instance.grantTemplate.issuedQuantity =
    instance.grantTemplate.totalQuantity = 1
  const source = fs.readFileSync(
    path.join(root, 'src/views/system/coupon/index.vue'),
    'utf8'
  )
  const compiled = require('vue-template-compiler').compile(
    source.match(/<template>([\s\S]*?)<\/template>\s*<script>/)[1]
  )
  instance.dict = { type: { edu_grade: [] } }
  const view = new (require('vue'))({
    data: () => instance,
    render: new Function(compiled.render),
    staticRenderFns: compiled.staticRenderFns.map((code) => new Function(code)),
  })
  function recoveryButton(node) {
    if (
      node.tag === 'el-button' &&
      (node.children || []).some((child) => /继续原批次/.test(child.text || ''))
    )
      return node
    for (const child of node.children || []) {
      const button = recoveryButton(child)
      if (button) return button
    }
  }
  const button = recoveryButton(view._render())
  assert.ok(button, 'closed failed batch must have a visible recovery entry')
  assert.equal(button.data.attrs.disabled, false)
  instance.saving = true
  assert.equal(recoveryButton(view._render()).data.attrs.disabled, true)
  instance.saving = false
  await button.data.on.click()
  assert.equal(instance.grantOpen, true)
  assert.equal(instance.grantTemplate.templateId, '1')
  assert.deepEqual(Array.from(instance.grantForm.parentIds), ['2'])
  await instance.submitGrant()
  assert.deepEqual(sent[1], sent[0])
  view.$destroy()
})
test('old enrollment editor strips finance fields while financial link preserves long ID', async () => {
  let body, route
  const instance = component('enrollment', {
    updateEnrollment: async (data) => {
      body = data
    },
    $router: {
      push: (value) => {
        route = value
      },
    },
  })
  instance.$refs = { form: { validate: (callback) => callback(true) } }
  instance.form = {
    enrollmentId: '9007199254740993',
    studentName: '学生',
    payStatus: '已支付',
    billingStatus: 'OPEN',
    originalAmount: '1.00',
    financialVersion: 1,
  }
  instance.getList = () => {}
  instance.submitForm()
  await Promise.resolve()
  assert.equal('payStatus' in body, false)
  assert.equal('originalAmount' in body, false)
  instance.handleFinance({ enrollmentId: '9007199254740993' })
  assert.equal(route.query.enrollmentId, '9007199254740993')
})
test('zero backend quote submits FREE without inventing cash collection', async () => {
  let saved
  const instance = component('tuition', {
    createPayment: async (_, data) => {
      saved = data
    },
  })
  instance.detail = { enrollmentId: '1', billingStatus: 'OPEN', payments: [] }
  instance.quote = {
    originalAmount: '100.00',
    discountAmount: '100.00',
    payableAmount: '0.00',
    financialVersion: 1,
    userCouponId: '2',
  }
  instance.operation = 'collect'
  instance.form = {
    payerName: '家长',
    channel: 'CASH',
    idempotencyKey: 'free-key',
  }
  instance.getList = instance.handleDetail = async () => {}
  await instance.submitOperation()
  assert.equal(saved.channel, 'FREE')
  assert.equal(saved.expectedPayableAmount, '0.00')
  assert.equal(saved.evidenceKey, null)
})
test('closing an UNKNOWN payment is blocked before any write', async () => {
  let writes = 0
  const instance = component('tuition', {
    closePayment: async () => {
      writes++
    },
  })
  instance.operation = 'close'
  instance.detail = { enrollmentId: '1' }
  instance.payment = { paymentId: '2', paymentStatus: 'UNKNOWN' }
  instance.form = { reason: '重新登记' }
  await instance.submitOperation()
  assert.equal(writes, 0)
  assert.match(instance.formError, /核对/)
})
test('local allowed MOCK bill sends only MOCK channel without real evidence', async () => {
  let saved
  const instance = component('tuition', {
    createPayment: async (_, data) => {
      saved = data
    },
  })
  instance.detail = {
    enrollmentId: '1',
    billingStatus: 'OPEN',
    financeMode: 'MOCK',
    mockAllowed: true,
    payments: [],
  }
  instance.quote = { payableAmount: '900.00', financialVersion: 3 }
  instance.operation = 'collect'
  instance.form = {
    payerName: '演示家长',
    channel: 'CASH',
    idempotencyKey: 'mock-key',
  }
  instance.getList = instance.handleDetail = async () => {}
  await instance.submitOperation()
  assert.ok(saved, 'mock collection should not require real evidence')
  assert.equal(saved.channel, 'MOCK')
  assert.equal(saved.evidenceKey, null)
})
test('pricing a real bill into MOCK requires explicit local pricing capability', async () => {
  let writes = 0
  const instance = component('tuition', {
    priceTuition: async () => {
      writes++
    },
  })
  instance.detail = {
    enrollmentId: '1',
    billingStatus: 'PENDING_PRICE',
    financeMode: 'REAL',
    financialVersion: 1,
    payments: [],
  }
  instance.operation = 'price'
  instance.form = { originalAmount: '100.00', financeMode: 'MOCK' }
  instance.getList = instance.handleDetail = async () => {}
  await instance.submitOperation()
  assert.equal(writes, 0)
  instance.detail.mockPricingAllowed = true
  await instance.submitOperation()
  assert.equal(writes, 1)
})
test('pending real confirmation uses already bound evidence without requiring another upload', async () => {
  let saved
  const instance = component('tuition', {
    confirmPayment: async (_, data) => {
      saved = data
    },
  })
  instance.detail = { enrollmentId: '1', financeMode: 'REAL' }
  instance.payment = {
    paymentId: '2',
    channel: 'CASH',
    paymentStatus: 'PENDING',
    evidenceKey: 'bound-file',
    paidTime: '2026-10-07 10:00:00',
  }
  instance.operation = 'confirm'
  instance.form = {
    paidTime: '2026-10-07 10:00:00',
    evidenceKey: '',
    providerTradeNo: '',
  }
  instance.getList = instance.handleDetail = async () => {}
  await instance.submitOperation()
  assert.ok(saved, 'bound evidence should remain usable')
  assert.equal(saved.evidenceKey, null)
})
test('coupon parent query normalizes authorized parent names and search fields', async () => {
  let query
  const instance = component('coupon', {
    listParents: async (params) => {
      query = params
      return {
        rows: [
          {
            parentId: '9007199254740993',
            parentName: '家长',
            userName: 'parent',
          },
        ],
        total: 1,
      }
    },
  })
  instance.parentQuery.search = '11111111111'
  await instance.getParents()
  assert.equal(query.search, '')
  assert.equal(query.q, '')
  assert.equal(query.parentPhone, '11111111111')
  assert.equal(instance.parents[0].userId, '9007199254740993')
  assert.equal(instance.parents[0].nickName, '家长')
})
test('issued coupon template ignores rule edits and sends only allowed status', async () => {
  let saved
  const instance = component('coupon', {
    editTemplate: async (id, body) => {
      saved = { id, body }
    },
  })
  instance.form = {
    templateId: '9007199254740993',
    issuedQuantity: 1,
    discountAmount: '1.00',
    templateStatus: 'PAUSED',
  }
  instance.getList = async () => {}
  await instance.submitTemplate()
  assert.equal(saved.id, '9007199254740993')
  assert.deepEqual(JSON.parse(JSON.stringify(saved.body)), {
    templateStatus: 'PAUSED',
  })
})
function api(name, request) {
  const calls = []
  const source = fs
    .readFileSync(path.join(root, 'src/api/system', name + '.js'), 'utf8')
    .replace(/^import .*$/gm, '')
    .replace(/export function /g, 'function ')
  const context = vm.createContext({
    FormData,
    request:
      request ||
      (async (config) => {
        calls.push(config)
        return { code: 200 }
      }),
  })
  vm.runInContext(source, context)
  return { calls, context }
}
test('finance API binds evidence to exact business long ID using multipart and authenticated request', async () => {
  const { calls, context } = api('tuition')
  await context.uploadEvidence(
    new Blob(['evidence'], { type: 'application/pdf' }),
    'HISTORY',
    '9007199254740993'
  )
  const config = calls[0]
  assert.equal(config.url, '/system/tuition/evidence')
  assert.equal(config.data.get('businessId'), '9007199254740993')
  assert.equal(config.data.get('businessType'), 'HISTORY')
  assert.equal(config.headers.isToken, undefined)
  assert.equal(config.headers['Content-Type'], 'multipart/form-data')
  await context.paymentEvidence('9007199254740993')
  assert.equal(
    calls[1].url,
    '/system/tuition/payments/9007199254740993/evidence'
  )
  assert.equal(calls[1].responseType, 'blob')
})
test('receipt and refund use dedicated routes and blob download retains authentication', async () => {
  const receipt = api('tuitionReceipt')
  await receipt.context.receiptFile('9007199254740993')
  await receipt.context.replaceReceipt('9007199254740993', '抬头更正')
  assert.equal(receipt.calls[0].responseType, 'blob')
  assert.equal(
    receipt.calls[0].url,
    '/system/tuition/receipts/9007199254740993/file'
  )
  assert.equal(
    receipt.calls[0].headers && receipt.calls[0].headers.isToken,
    undefined
  )
  assert.deepEqual(JSON.parse(JSON.stringify(receipt.calls[1].data)), {
    reason: '抬头更正',
  })
  const refund = api('tuitionRefund')
  await refund.context.confirmRefund('9007199254740993', {
    outcome: 'UNKNOWN',
    reason: '待核对',
  })
  assert.equal(
    refund.calls[0].url,
    '/system/tuition/refunds/9007199254740993/confirm'
  )
  assert.deepEqual(JSON.parse(JSON.stringify(refund.calls[0].data)), {
    outcome: 'UNKNOWN',
    reason: '待核对',
  })
})
test('receipt download actually passes Bearer through the existing Axios interceptor', async () => {
  const axios = require('axios')
  const source = stripImports(
    fs.readFileSync(path.join(root, 'src/utils/request.js'), 'utf8')
  )
    .replace('export let isRelogin', 'let isRelogin')
    .replace('export function download', 'function download')
    .replace('export default service', 'serviceResult = service')
  const context = vm.createContext({
    serviceResult: null,
    axios,
    process: { env: { VUE_APP_BASE_API: '/api' } },
    getToken: () => 'finance-test-token',
    tansParams: () => '',
    cache: { session: { getJSON() {}, setJSON() {} } },
    Notification: { error() {} },
    Message() {},
    MessageBox: {},
    errorCode: {},
    store: {},
    console,
  })
  vm.runInContext(source, context)
  let authorization, target
  context.serviceResult.defaults.adapter = async (config) => {
    authorization = config.headers.Authorization
    target = config.url
    return {
      status: 200,
      statusText: 'OK',
      config,
      headers: {},
      request: { responseType: config.responseType },
      data: new Blob(['%PDF-1.7'], { type: 'application/pdf' }),
    }
  }
  const receipt = api('tuitionReceipt', context.serviceResult)
  const blob = await receipt.context.receiptFile('9007199254740993')
  assert.equal(authorization, 'Bearer finance-test-token')
  assert.equal(target, '/system/tuition/receipts/9007199254740993/file')
  assert.equal(blob.type, 'application/pdf')
})
test('every financial template and JavaScript compiles using the installed Vue2 toolchain', () => {
  const compiler = require('vue-template-compiler')
  const parser = require('@babel/parser')
  for (const name of [
    'tuition',
    'tuitionReceipt',
    'tuitionRefund',
    'coupon',
    'enrollment',
  ]) {
    const sfc = compiler.parseComponent(
      fs.readFileSync(
        path.join(root, 'src/views/system', name, 'index.vue'),
        'utf8'
      )
    )
    const result = compiler.compile(sfc.template.content)
    assert.deepEqual(result.errors, [], name + ' template errors')
    parser.parse(sfc.script.content, {
      sourceType: 'module',
      plugins: ['objectRestSpread'],
    })
  }
})

function renderedRefund(instance) {
  const source = fs.readFileSync(
    path.join(root, 'src/views/system/tuitionRefund/index.vue'),
    'utf8'
  )
  const compiled = require('vue-template-compiler').compile(
    source.match(/<template>([\s\S]*?)<\/template>\s*<script>/)[1]
  )
  const view = new (require('vue'))({
    data: () => instance,
    render: new Function(compiled.render),
    staticRenderFns: compiled.staticRenderFns.map((code) => new Function(code)),
  })
  const nodes = []
  function walk(node) {
    if (!node) return
    nodes.push(node)
    ;(node.children || []).forEach(walk)
  }
  walk(view._render())
  const text = nodes
    .map((n) => n.text || (n.data && n.data.attrs && n.data.attrs.title) || '')
    .join('')
  const buttonsText = nodes
    .filter((n) => n.tag === 'el-button')
    .map((n) => (n.children || []).map((c) => c.text || '').join(''))
    .join('')
  view.$destroy()
  return { text, nodes, buttonsText }
}
function staffRefund(
  dependencies = {},
  roles = ['admin'],
  permissions = ['*:*:*']
) {
  return component('tuitionRefund', {
    $store: { state: { user: { id: '2', roles, permissions } } },
    ...dependencies,
  })
}
test('rendered two-stage actions show education then administrator completion and preserve legacy history', async () => {
  const instance = staffRefund()
  instance.detail = {
    refundId: '9',
    refundStatus: 'WAIT_EDU',
    version: 0,
    calculationSnapshot: '{"private":true}',
  }
  let view = renderedRefund(instance)
  assert.match(view.text, /教务确认金额/)
  assert.doesNotMatch(
    view.text,
    /已退款登记|计算快照|private|撤回申请|退款凭证/
  )
  instance.detail.refundStatus = 'WAIT_ADMIN'
  view = renderedRefund(instance)
  assert.match(view.text, /已退款登记/)
  assert.match(view.text, /驳回/)
  instance.detail.refundStatus = 'PROCESSING'
  view = renderedRefund(instance)
  assert.match(view.text, /历史流程.*管理员/)
  assert.doesNotMatch(view.text, /执行退费|核对到账结果|已退款登记/)
})
test('education amount review and same administrator completion use exact ID and version in separate steps', async () => {
  const sent = []
  const instance = staffRefund({
    reviewRefund: async (id, data) => sent.push({ id, data }),
    markRefunded: async (id, data) => sent.push({ id, data }),
  })
  instance.detail = {
    refundId: '9007199254740993',
    refundStatus: 'WAIT_EDU',
    refundableAmount: '900.00',
    version: 0,
  }
  instance.getList = instance.handleDetail = async () => {}
  const reviewButton = renderedRefund(instance).nodes.find(
    (node) =>
      node.tag === 'el-button' &&
      (node.children || []).some((child) =>
        /教务确认金额/.test(child.text || '')
      )
  )
  assert.ok(reviewButton)
  assert.equal(reviewButton.data.attrs.disabled, false)
  await reviewButton.data.on.click()
  assert.equal(instance.operation, 'review')
  assert.equal(instance.form.version, 0)
  instance.form = {
    approved: true,
    approvedAmount: '300.00',
    reviewRemark: '实际授课后核定',
    version: 0,
  }
  await instance.submitOperation()
  assert.equal(sent.length, 1)
  assert.deepEqual(JSON.parse(JSON.stringify(sent[0])), {
    id: '9007199254740993',
    data: {
      approved: true,
      approvedAmount: '300.00',
      reviewRemark: '实际授课后核定',
      version: 0,
    },
  })
  instance.openOperation('markRefunded')
  await instance.submitOperation()
  assert.equal(sent.length, 1, 'WAIT_EDU must not complete')
  instance.detail = {
    ...instance.detail,
    refundStatus: 'WAIT_ADMIN',
    approvedAmount: '300.00',
    version: 1,
  }
  instance.openOperation('markRefunded')
  instance.form.completedTime = '2026-10-11 10:00:00'
  let confirmation
  instance.$modal.confirm = async (message) => {
    confirmation = message
  }
  await instance.submitOperation()
  assert.match(confirmation, /已在线下实际退还/)
  assert.deepEqual(JSON.parse(JSON.stringify(sent[1])), {
    id: '9007199254740993',
    data: { version: 1, completedTime: '2026-10-11 10:00:00' },
  })
})
test('WAIT_ADMIN rejection preserves education facts and stale version refreshes the record', async () => {
  let body,
    refreshed = 0
  const instance = staffRefund({
    reviewRefund: async (_, data) => {
      body = data
      throw Error('版本已变化，请刷新')
    },
  })
  instance.detail = {
    refundId: '9',
    refundStatus: 'WAIT_ADMIN',
    approvedAmount: '100.00',
    reviewRemark: '教务核定',
    version: 1,
  }
  instance.handleDetail = async () => {
    refreshed++
  }
  instance.openOperation('reject')
  instance.form.reviewRemark = '尚未退还，需核对'
  await instance.submitOperation()
  assert.deepEqual(JSON.parse(JSON.stringify(body)), {
    approved: false,
    approvedAmount: null,
    reviewRemark: '尚未退还，需核对',
    version: 1,
  })
  assert.equal(refreshed, 1)
  assert.match(instance.formError, /版本/)
})
test('staff application has no amount and freezes SESSION target and key after uncertain outcome', async () => {
  const sent = []
  const instance = staffRefund({
    applyRefund: async (data) => {
      sent.push(JSON.parse(JSON.stringify(data)))
      throw Error('网络中断')
    },
  })
  instance.bill = { enrollmentId: '1', refundableAmount: '900.00' }
  instance.successfulPayments = [{ paymentId: '2' }]
  instance.targets = [
    {
      targetType: 'CLASS_SIGN_IN',
      targetId: '9007199254740993',
      label: '2026-10-10',
    },
  ]
  instance.operation = 'apply'
  instance.form = {
    enrollmentId: '1',
    paymentId: '2',
    refundKind: 'SESSION',
    targetId: '9007199254740993',
    reason: '请假',
    idempotencyKey: 'original',
  }
  await instance.submitOperation()
  instance.form.reason = 'changed'
  instance.form.targetId = 'changed'
  await instance.submitOperation()
  assert.equal(sent.length, 2)
  assert.deepEqual(sent[1], sent[0])
  assert.equal(sent[0].classSignInId, '9007199254740993')
  assert.equal(Object.hasOwn(sent[0], 'requestedAmount'), false)
  await instance.openApply()
  assert.equal(instance.form.idempotencyKey, 'original')
})
for (const [name, refundId] of [
  ['empty', ''],
  ['whitespace', ' \t\n'],
  ['missing', undefined],
  ['numeric', 9],
  ['null', null],
]) {
  test(`malformed ${name} application ID preserves web dialog and original retry intent`, async () => {
    const sent = []
    const instance = staffRefund({
      applyRefund: async (data) => {
        sent.push(JSON.parse(JSON.stringify(data)))
        return { data: { refundId: sent.length === 1 ? refundId : '9' } }
      },
    })
    instance.bill = { enrollmentId: '1' }
    instance.successfulPayments = [{ paymentId: '2' }]
    instance.operation = 'apply'
    instance.operationOpen = true
    instance.form = {
      enrollmentId: '1',
      paymentId: '2',
      refundKind: 'PARTIAL',
      reason: '费用调整',
      idempotencyKey: 'original',
    }
    const originalForm = { ...instance.form }
    instance.getList = async () => {}
    await instance.submitOperation()
    assert.equal(instance.operationOpen, true)
    assert.deepEqual(instance.form, originalForm)
    assert.deepEqual(JSON.parse(JSON.stringify(instance.applyForm)), originalForm)
    assert.deepEqual(JSON.parse(JSON.stringify(instance.applyIntent)), {
      paymentId: '2',
      refundKind: 'PARTIAL',
      reason: '费用调整',
      idempotencyKey: 'original',
    })
    assert.match(instance.formError, /结果尚未确定/)
    assert.match(renderedRefund(instance).text, /结果尚未确定/)
    instance.form.reason = 'changed'
    instance.form.idempotencyKey = 'replacement'
    await instance.submitOperation()
    assert.equal(sent.length, 2)
    assert.deepEqual(sent[1], sent[0])
    assert.equal(instance.applyIntent, null)
    assert.equal(instance.operationOpen, false)
  })
}

test('role and menu permission both guard staff writes and rendered actions', async () => {
  let writes = 0
  for (const [roles, permissions] of [
    [['teacher'], ['*:*:*']],
    [['education'], []],
  ]) {
    const instance = staffRefund(
      {
        reviewRefund: async () => {
          writes++
        },
      },
      roles,
      permissions
    )
    instance.detail = { refundId: '9', refundStatus: 'WAIT_EDU', version: 0 }
    assert.doesNotMatch(renderedRefund(instance).buttonsText, /教务确认金额/)
    instance.openOperation('review')
    instance.form = {
      approved: true,
      approvedAmount: '1.00',
      reviewRemark: '核定',
      version: 0,
    }
    await instance.submitOperation()
    assert.doesNotMatch(renderedRefund(instance).buttonsText, /教务确认金额/)
  }
  assert.equal(writes, 0)
})
test('new refund API forwards version and target lookup with exact string IDs', async () => {
  const tuition = api('tuition')
  await tuition.context.refundTargets('9007199254740993')
  assert.equal(
    tuition.calls[0].url,
    '/system/tuition/9007199254740993/refund-targets'
  )
  const refund = api('tuitionRefund')
  await refund.context.markRefunded('9007199254740993', { version: 1 })
  assert.equal(
    refund.calls[0].url,
    '/system/tuition/refunds/9007199254740993/mark-refunded'
  )
  assert.equal(refund.calls[0].data.version, 1)
})

test('a superseded SESSION query cannot leave staff form busy or restore old targets', async () => {
  let release
  const instance = staffRefund({
    refundTargets: () =>
      new Promise((resolve) => {
        release = resolve
      }),
  })
  instance.bill = { enrollmentId: '1' }
  instance.form = { enrollmentId: '1', refundKind: 'SESSION', targetId: '' }
  const pending = instance.loadTargets()
  instance.form.refundKind = 'PARTIAL'
  instance.changeKind()
  assert.equal(instance.targetsLoading, false)
  release({
    data: {
      rows: [{ targetType: 'CLASS_SIGN_IN', targetId: '9', label: 'old' }],
    },
  })
  await pending
  assert.equal(instance.targets.length, 0)
})
test('failed SESSION lookup prevents staff submission and duplicate clicks issue only one request', async () => {
  let writes = 0,
    release
  const instance = staffRefund({
    refundTargets: async () => {
      throw Error('断网')
    },
    applyRefund: () => {
      writes++
      return new Promise((resolve) => {
        release = resolve
      })
    },
  })
  instance.bill = { enrollmentId: '1' }
  instance.successfulPayments = [{ paymentId: '2' }]
  instance.form = {
    enrollmentId: '1',
    paymentId: '2',
    refundKind: 'SESSION',
    targetId: '9',
    reason: '请假',
    idempotencyKey: 'key',
  }
  instance.operation = 'apply'
  await instance.loadTargets()
  await instance.submitOperation()
  assert.equal(writes, 0)
  instance.form.refundKind = 'PARTIAL'
  instance.getList = async () => {}
  const first = instance.submitOperation()
  await instance.submitOperation()
  assert.equal(writes, 1)
  release({ data: { refundId: '9' } })
  await first
  assert.equal(instance.applyIntent, null)
})

test('legacy SUCCESS preserves historical reviewer and amount semantics with or without workflow tag', () => {
  const instance = staffRefund()
  for (const refundWorkflow of ['LEGACY', undefined]) {
    instance.detail = {
      refundId: '9',
      refundStatus: 'SUCCESS',
      refundWorkflow,
      approvedAmount: '100.00',
      reviewerId: '2',
      executorId: '2',
      completedTime: '2026-10-10 10:00:00',
    }
    const view = renderedRefund(instance)
    const labels = view.nodes
      .filter((node) => node.tag === 'el-descriptions-item')
      .map((node) => node.data.attrs.label)
    assert.ok(labels.includes('旧流程审核人'))
    assert.ok(labels.includes('旧流程批准金额'))
    assert.ok(labels.includes('旧流程执行人'))
    assert.equal(labels.includes('教务已阅'), false)
    assert.match(view.text, /历史流程.*管理员/)
    assert.doesNotMatch(view.buttonsText, /确认金额|已退款登记|驳回/)
  }
  instance.detail.refundWorkflow = 'OFFLINE_TWO_STAGE'
  const labels = renderedRefund(instance)
    .nodes.filter((node) => node.tag === 'el-descriptions-item')
    .map((node) => node.data.attrs.label)
  assert.ok(labels.includes('教务已阅'))
  assert.ok(labels.includes('教务核定金额'))
})
test('coupon template form sends single or multiple subject rules and full selection as unrestricted',async()=>{
  const saved=[];const instance=component('coupon',{addTemplate:async body=>saved.push(body)});instance.getList=async()=>{};
  instance.form={couponName:'数学券',discountAmount:'50',minSpendAmount:'100',validFrom:'2020-01-01 00:00:00',validUntil:'2099-01-01 00:00:00',totalQuantity:10,templateStatus:'ACTIVE',subjectNames:['数学'],courseTypes:['ONE_TO_ONE']};instance.allSubjects=false;instance.couponKind='THRESHOLD';await instance.submitTemplate();assert.deepEqual(Array.from(saved[0].subjectNames),['数学']);assert.equal(saved[0].minSpendAmount,'100.00');assert.equal(saved[0].courseScope,'ONE_TO_ONE');assert.equal(saved[0].scopeScheduleId,null);
  instance.form.subjectNames=['数学','化学'];await instance.submitTemplate();assert.deepEqual(Array.from(saved[1].subjectNames),['数学','化学']);instance.allSubjects=true;instance.couponKind='NONE';await instance.submitTemplate();assert.deepEqual(Array.from(saved[2].subjectNames),[]);assert.equal(saved[2].minSpendAmount,'0.00');
});
test('coupon pending badge ignores response after account change or page hide',async()=>{
  let token='admin',finish;const instance=component('coupon',{getToken:()=>token,referralCounts:()=>new Promise(r=>finish=r)});let work=instance.loadRewardCounts();token='parent';finish({data:{pending:9,anomalies:2}});await work;assert.equal(instance.rewardCounts.pending,0);work=instance.loadRewardCounts();instance._couponVisible=false;finish({data:{pending:9,anomalies:2}});await work;assert.equal(instance.rewardCounts.pending,0);
});
