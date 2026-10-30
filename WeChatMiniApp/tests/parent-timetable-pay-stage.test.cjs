const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')

function load() {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }
  const calls = [], modals = [], toasts = []
  const wx = {
    getStorageSync: (key) => storage[key],
    showToast: (o) => toasts.push(o),
    showLoading() {},
    hideLoading() {},
    showModal: (o) => { modals.push(o); o.success({ confirm: true }) }
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/timetable/timetable.js'), 'utf8'), {
    Page: (p) => (definition = p),
    wx,
    getCurrentPages: () => [],
    require: (id) =>
      id.endsWith('child-context')
        ? require('./helpers/child-context.cjs')(wx)
        : { request: async (options) => { calls.push(options); return { data: [] } } }
  })
  const page = { ...definition, data: structuredClone(definition.data), _loadedToken: storage.token }
  page.setData = (patch) => Object.assign(page.data, patch)
  page.data.loadSucceeded = true
  return { page, wx, storage, calls, modals, toasts }
}

const base = {
  enrollmentId: 7,
  scheduleId: 3,
  studentName: '学生甲',
  courseClassName: '四年级数学',
  enrollmentStatus: '1',
  classDate: null,
  startDate: '2027-01-04',
  endDate: '2027-02-28',
  classPattern: 'DAILY',
  periodName: '寒假',
  termName: '寒假'
}
const event = { currentTarget: { dataset: { enrollmentId: 7 } } }

test('待核价：价格未确认时展示待核价并保留取消入口', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, billType: 'COURSE', billingStatus: 'PENDING_PRICE', payStatus: '未支付' }])
  assert.equal(row.payStage, 'pendingPrice')
  assert.equal(row.payText, '待核价')
  assert.equal(row.canCancel, true)
})

test('待缴费：已核价未收款时展示待缴费且不再提供取消入口', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, billType: 'COURSE', billingStatus: 'OPEN', payStatus: '未支付' }])
  assert.equal(row.payStage, 'pendingPay')
  assert.equal(row.payText, '待缴费')
  assert.equal(row.canCancel, false)
})

test('已支付：展示已支付且不再提供取消入口', () => {
  const { page } = load()
  for (const payStatus of ['已支付', '1']) {
    const [row] = page.decorateTimetable([{ ...base, billType: 'COURSE', billingStatus: 'OPEN', payStatus }])
    assert.equal(row.payStage, 'paid')
    assert.equal(row.payText, '已支付')
    assert.equal(row.canCancel, false)
  }
})

test('购物车订单内的课程按所属订单的核价状态判定', () => {
  const { page } = load()
  const item = { ...base, billType: 'COURSE_ITEM', billingStatus: 'ORDER_BOUND', payStatus: '未支付' }
  const [pendingPrice] = page.decorateTimetable([{ ...item, orderBillingStatus: 'PENDING_PRICE' }])
  assert.equal(pendingPrice.payText, '待核价')
  assert.equal(pendingPrice.canCancel, true)
  const [pendingPay] = page.decorateTimetable([{ ...item, orderBillingStatus: 'OPEN' }])
  assert.equal(pendingPay.payText, '待缴费')
  assert.equal(pendingPay.canCancel, false)
  const [paid] = page.decorateTimetable([{ ...item, orderBillingStatus: 'OPEN', orderPayStatus: '已支付' }])
  assert.equal(paid.payText, '已支付')
  assert.equal(paid.canCancel, false)
})

test('历史报名待核验按待核价处理，可取消', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, billType: 'COURSE', billingStatus: 'HISTORY_PENDING' }])
  assert.equal(row.payText, '待核价')
  assert.equal(row.canCancel, true)
})

test('一对一单次课保持原有支付标签与取消申请', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, classDate: '2030-10-07', billingStatus: 'CLOSED', payStatus: '0' }])
  assert.equal(row.payText, '未支付')
  assert.equal(row.canCancel, true)
})

test('待缴费与已支付的报名点击取消不会发请求，只提示联系教务', async () => {
  for (const [row, hint] of [
    [{ ...base, billingStatus: 'OPEN', payStatus: '未支付' }, /已核价/],
    [{ ...base, billingStatus: 'OPEN', payStatus: '已支付' }, /已支付/]
  ]) {
    const { page, calls, modals, toasts } = load()
    page.data.timetable = page.decorateTimetable([row])
    await page.handleCancel(event)
    assert.equal(calls.length, 0)
    assert.equal(modals.length, 0)
    assert.match(toasts.at(-1).title, hint)
  }
})

test('待核价的报名仍然可以正常取消', async () => {
  const { page, calls, modals } = load()
  page.data.timetable = page.decorateTimetable([{ ...base, billingStatus: 'PENDING_PRICE', payStatus: '未支付' }])
  page.loadTimetable = async () => {}
  await page.handleCancel(event)
  assert.equal(modals.length, 1)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'DELETE')
})

test('页面模板按 canCancel 决定是否渲染取消按钮', () => {
  const markup = fs.readFileSync(path.join(__dirname, '../pages/timetable/timetable.wxml'), 'utf8')
  assert.match(markup, /class="cancel-btn" wx:elif="\{\{course\.canCancel\}\}"/)
  assert.match(markup, /class="pay-tag \{\{course\.payStage\}\}"/)
})
