const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')

// confirm=false 时模拟家长在弹窗里选「否」
function load({ confirm = true } = {}) {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }
  const calls = [], modals = [], toasts = []
  const wx = {
    getStorageSync: (key) => storage[key],
    showToast: (o) => toasts.push(o),
    showLoading() {},
    hideLoading() {},
    showModal: (o) => { modals.push(o); o.success({ confirm }) }
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/timetable/timetable.js'), 'utf8'), {
    Page: (p) => (definition = p),
    wx,
    getCurrentPages: () => [],
    require: (id) =>
      id.endsWith('course-payment-progress')
        ? require('../utils/course-payment-progress')
        : id.endsWith('child-context')
        ? require('./helpers/child-context.cjs')(wx)
        : { request: async (options) => { calls.push(options); return { data: [] } } }
  })
  const page = { ...definition, data: structuredClone(definition.data), _loadedToken: storage.token }
  page.setData = (patch) => Object.assign(page.data, patch)
  page.data.loadSucceeded = true
  page.loadTimetable = async () => {}
  return { page, calls, modals, toasts }
}

const base = {
  enrollmentId: 1376,
  scheduleId: 3,
  studentName: '学生甲',
  courseClassName: '幼小衔接语文',
  enrollmentStatus: '1',
  classDate: null,
  startDate: '2027-01-04',
  endDate: '2027-02-28',
  classPattern: 'DAILY',
  periodName: '寒假',
  termName: '寒假',
  billType: 'COURSE_ITEM',
  billingStatus: 'ORDER_BOUND',
  payStatus: '未支付',
  orderEnrollmentId: 1375,
  orderBillingStatus: 'PENDING_PRICE'
}
const event = { currentTarget: { dataset: { enrollmentId: 1376 } } }

test('购物车订单多门课程：标记整单取消，取消入口仍在待核价保留', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, orderItemCount: 3 }])
  assert.equal(row.payText, '待核价')
  assert.equal(row.canCancel, true)
  assert.equal(row.cartOrderItemCount, 3)
  assert.equal(row.cartOrderMulti, true)
})

test('购物车订单只有一门课程：不提示整单取消', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, orderItemCount: 1 }])
  assert.equal(row.cartOrderItemCount, 1)
  assert.equal(row.cartOrderMulti, false)
})

test('普通报名没有订单明细数：按单门课程处理', () => {
  const { page } = load()
  const [row] = page.decorateTimetable([{ ...base, billType: 'COURSE', billingStatus: 'PENDING_PRICE', orderItemCount: null }])
  assert.equal(row.cartOrderItemCount, 0)
  assert.equal(row.cartOrderMulti, false)
})

test('多门课程时弹窗说明其他课程会同步取消，选「是」才发请求', async () => {
  const { page, calls, modals } = load({ confirm: true })
  page.data.timetable = page.decorateTimetable([{ ...base, orderItemCount: 3 }])
  await page.handleCancel(event)
  assert.equal(modals.length, 1)
  assert.equal(modals[0].title, '整单课程将一起取消')
  assert.match(modals[0].content, /其他 2 门课程也会同步取消/)
  assert.match(modals[0].content, /是否确定取消课程/)
  assert.equal(modals[0].confirmText, '是')
  assert.equal(modals[0].cancelText, '否')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'DELETE')
  assert.match(calls[0].url, /\/miniapp\/parent\/enrollment\/1376$/)
})

test('多门课程时选「否」不发请求', async () => {
  const { page, calls, modals } = load({ confirm: false })
  page.data.timetable = page.decorateTimetable([{ ...base, orderItemCount: 3 }])
  await page.handleCancel(event)
  assert.equal(modals.length, 1)
  assert.equal(calls.length, 0)
  assert.equal(page.data.cancelingId, '')
})

test('只有一门课程时保持原确认文案', async () => {
  const { page, calls, modals } = load({ confirm: true })
  page.data.timetable = page.decorateTimetable([{ ...base, orderItemCount: 1 }])
  await page.handleCancel(event)
  assert.equal(modals[0].title, '确认取消报名？')
  assert.equal(modals[0].confirmText, '确认取消')
  assert.equal(calls.length, 1)
})

test('一对一单次课不走整单取消提示', async () => {
  const { page, modals } = load({ confirm: true })
  page.data.timetable = page.decorateTimetable([{ ...base, classDate: '2030-10-07', orderItemCount: 3 }])
  await page.handleCancel(event)
  assert.equal(modals[0].title, '确认申请取消本次课？')
  assert.equal(modals[0].confirmText, '提交申请')
})
