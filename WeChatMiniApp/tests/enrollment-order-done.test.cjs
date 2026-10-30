const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const root = path.resolve(__dirname, '..')

function harness() {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }
  const calls = [], modals = [], toasts = [], copied = [], previews = []
  const wx = {
    getStorageSync: (key) => storage[key],
    setStorageSync() {},
    showToast: (o) => toasts.push(o),
    showModal: (o) => { modals.push(o); o.success && o.success({ confirm: true }) },
    setClipboardData: (o) => { copied.push(o.data); o.success && o.success() },
    previewImage: (o) => previews.push(o),
    navigateTo() {}
  }
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/enrollment/enrollment.js'), 'utf8'), {
    Page: (p) => { definition = p }, wx, console,
    require: (id) => id.includes('course-term-filter') ? require('../utils/course-term-filter')
      : id.includes('child-context') ? require('./helpers/child-context.cjs')(wx)
      : id.includes('personal-account') ? { requestKey: () => 'key-1' }
      : { request: async (o) => { calls.push(o); return { data: { enrollmentId: '1' } } } }
  })
  const p = { ...definition, data: structuredClone(definition.data), _token: storage.token, _unloaded: false }
  p.setData = (patch) => Object.assign(p.data, patch)
  p.loadPage = async () => {}
  p.refreshCartSummary = () => {}
  p.data.cart = [{ scheduleId: 1, courseClassName: '三年级英语拓展班', amount: '2100.00' }]
  p.data.newChild = true
  p.data.form = { studentName: '小盛测试1', contactPhone: '13800000000', studentRequestKey: 'key-1' }
  return { p, storage, calls, modals, toasts, copied, previews }
}

test('下单成功后弹出扫码缴费说明，不再使用系统 showModal', async () => {
  const { p, calls, modals } = harness()
  await p.submitCart()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/miniapp/parent/cart/order')
  assert.equal(modals.length, 0)
  assert.equal(p.data.orderDoneOpen, true)
  assert.equal(p.data.showSheet, false)
  assert.equal(p.data.cart.length, 0)
})

test('弹窗文案、二维码与课程服务老师微信号按需求呈现', () => {
  const { p } = harness()
  assert.equal(p.data.advisorWechat, 'myedu666888')
  assert.equal(p.data.advisorQrImage, '/images/course-advisor-wechat.png')
  assert.ok(fs.existsSync(path.join(root, p.data.advisorQrImage)), '二维码图片必须打包在小程序内')

  const markup = fs.readFileSync(path.join(root, 'pages/enrollment/enrollment.wxml'), 'utf8')
  assert.match(markup, /<view class="order-done-title">订单已提交<\/view>/)
  assert.match(markup, /扫码联系课程服务老师交费或48小时内课程服务老师联系您缴费。/)
  assert.match(markup, /src="\{\{advisorQrImage\}\}"/)
  assert.match(markup, /课程服务老师微信号：\{\{advisorWechat\}\}/)
  assert.match(markup, /wx:if="\{\{orderDoneOpen\}\}"/)
})

test('弹窗可以关闭、复制微信号、预览二维码', () => {
  const { p, copied, previews, toasts } = harness()
  p.setData({ orderDoneOpen: true })
  p.copyAdvisorWechat()
  assert.deepEqual(copied, ['myedu666888'])
  assert.match(toasts.at(-1).title, /已复制/)
  p.previewAdvisorQr()
  assert.equal(previews[0].current, '/images/course-advisor-wechat.png')
  assert.deepEqual(Array.from(previews[0].urls), ['/images/course-advisor-wechat.png'])
  p.closeOrderDone()
  assert.equal(p.data.orderDoneOpen, false)
  // 弹窗关闭后不再响应复制与预览
  p.copyAdvisorWechat(); p.previewAdvisorQr()
  assert.equal(copied.length, 1)
  assert.equal(previews.length, 1)
})

test('下单失败不弹窗，只提示错误', async () => {
  let definition
  const modals = [], toasts = []
  const wx = {
    getStorageSync: (k) => ({ token: 'parent-session', roles: ['parent'] })[k],
    setStorageSync() {},
    showToast: (o) => toasts.push(o),
    showModal: (o) => modals.push(o)
  }
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/enrollment/enrollment.js'), 'utf8'), {
    Page: (v) => { definition = v }, wx, console,
    require: (id) => id.includes('course-term-filter') ? require('../utils/course-term-filter')
      : id.includes('child-context') ? require('./helpers/child-context.cjs')(wx)
      : id.includes('personal-account') ? { requestKey: () => 'key-1' }
      : { request: async () => { throw new Error('课程已满') } }
  })
  const q = { ...definition, data: structuredClone(definition.data), _token: 'parent-session', _unloaded: false }
  q.setData = (patch) => Object.assign(q.data, patch)
  q.loadPage = async () => {}
  q.refreshCartSummary = () => {}
  q.data.cart = [{ scheduleId: 1 }]
  q.data.newChild = true
  q.data.form = { studentName: '甲', contactPhone: '', studentRequestKey: 'key-1' }
  await q.submitCart()
  assert.equal(q.data.orderDoneOpen, false)
  assert.equal(modals.length, 0)
  assert.match(toasts.at(-1).title, /课程已满/)
})
