const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')

function load() {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }
  const wx = {
    getStorageSync: (key) => storage[key],
    showToast() {},
    showLoading() {},
    hideLoading() {},
    showModal: (o) => o.success({ confirm: true })
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
        : { request: async () => ({ data: [] }) }
  })
  const page = { ...definition, data: structuredClone(definition.data), _loadedToken: storage.token }
  page.setData = (patch) => Object.assign(page.data, patch)
  return page
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
  termName: '寒假',
  billType: 'COURSE'
}

test('核价后展示原价、优惠与实际应缴', () => {
  const page = load()
  const [row] = page.decorateTimetable([{
    ...base, billingStatus: 'OPEN', payStatus: '未支付',
    billOriginalAmount: '2100.00', billDiscountAmount: '100.00', billPayableAmount: '2000.00'
  }])
  assert.equal(row.payText, '待缴费')
  assert.equal(row.amountView.show, true)
  assert.equal(row.amountView.originalText, '¥2100.00')
  assert.equal(row.amountView.discountText, '-¥100.00')
  assert.equal(row.amountView.hasDiscount, true)
  assert.equal(row.amountView.payableText, '¥2000.00')
  assert.equal(row.amountView.payableLabel, '实际应缴')
})

test('没有优惠时显示「无」', () => {
  const page = load()
  const [row] = page.decorateTimetable([{
    ...base, billingStatus: 'OPEN', payStatus: '未支付',
    billOriginalAmount: '1050.00', billDiscountAmount: '0.00', billPayableAmount: '1050.00'
  }])
  assert.equal(row.amountView.discountText, '无')
  assert.equal(row.amountView.hasDiscount, false)
})

test('已支付也展示金额，便于家长核对', () => {
  const page = load()
  const [row] = page.decorateTimetable([{
    ...base, billingStatus: 'OPEN', payStatus: '已支付',
    billOriginalAmount: '7800.00', billDiscountAmount: '800.00', billPayableAmount: '7000.00'
  }])
  assert.equal(row.payText, '已支付')
  assert.equal(row.amountView.show, true)
  assert.equal(row.amountView.payableText, '¥7000.00')
})

test('未核价不展示金额', () => {
  const page = load()
  const [row] = page.decorateTimetable([{ ...base, billingStatus: 'PENDING_PRICE', payStatus: '未支付' }])
  assert.equal(row.payText, '待核价')
  assert.equal(row.amountView.show, false)
})

test('购物车订单里的课程标注为整单应缴', () => {
  const page = load()
  const [row] = page.decorateTimetable([{
    ...base, billType: 'COURSE_ITEM', billingStatus: 'ORDER_BOUND', payStatus: '未支付',
    orderBillingStatus: 'OPEN', billIsOrder: 1,
    billOriginalAmount: '5670.00', billDiscountAmount: '500.00', billPayableAmount: '5170.00'
  }])
  assert.equal(row.amountView.show, true)
  assert.equal(row.amountView.isOrder, true)
  assert.equal(row.amountView.payableLabel, '整单应缴')
  assert.equal(row.amountView.payableText, '¥5170.00')
})

test('一对一单次课不展示核价金额', () => {
  const page = load()
  const [row] = page.decorateTimetable([{
    ...base, classDate: '2030-10-07', billingStatus: 'CLOSED', payStatus: '0',
    billOriginalAmount: '200.00', billDiscountAmount: '0.00', billPayableAmount: '200.00'
  }])
  assert.equal(row.amountView.show, false)
})

test('金额为空时安全降级为不展示', () => {
  const page = load()
  const [row] = page.decorateTimetable([{ ...base, billingStatus: 'OPEN', payStatus: '未支付' }])
  assert.equal(row.amountView.show, false)
})

test('课表模板渲染原价、优惠与应缴三栏', () => {
  const markup = fs.readFileSync(path.join(__dirname, '../pages/timetable/timetable.wxml'), 'utf8')
  assert.match(markup, /course\.amountView\.show/)
  assert.match(markup, /course\.amountView\.originalText/)
  assert.match(markup, /course\.amountView\.discountText/)
  assert.match(markup, /course\.amountView\.payableText/)
})
