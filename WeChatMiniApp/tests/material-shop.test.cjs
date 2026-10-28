const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const root = path.resolve(__dirname, '..')
let pageDefinition
const app = { globalData: { baseUrl: 'http://127.0.0.1:8080' } }
global.getApp = () => app
global.Page = definition => { pageDefinition = definition }
function environment(extra = {}) {
  global.wx = { getStorageSync: key => key === 'token' ? 'test-token' : key === 'roles' ? ['teacher'] : '', setStorageSync() {}, removeStorageSync() {}, showToast() {}, ...extra }
}
function page(name) {
  const file = path.join(root, 'pages', name, name + '.js')
  delete require.cache[require.resolve(file)]
  require(file)
  const instance = { ...pageDefinition, data: structuredClone(pageDefinition.data) }
  instance.setData = updates => {
    for (const [key, value] of Object.entries(updates)) {
      const parts = key.split('.'); let target = instance.data
      while (parts.length > 1) { const part = parts.shift(); target = target[part] }
      target[parts[0]] = value
    }
  }
  return instance
}
const { request } = require('../utils/request')
test('public upload addresses use the current HTTPS API prefix without duplication', () => {
  const previous = app.globalData.baseUrl
  app.globalData.baseUrl = 'https://test-admin.mingyuantostars.online/prod-api'
  try {
    const s = require('../utils/material-shop')
    const want = 'https://test-admin.mingyuantostars.online/prod-api/profile/upload/a.jpg'
    for (const value of ['/profile/upload/a.jpg', 'profile/upload/a.jpg',
      'http://test-admin.mingyuantostars.online/profile/upload/a.jpg',
      'https://test-admin.mingyuantostars.online/profile/upload/a.jpg',
      '/prod-api/profile/upload/a.jpg',
      'http://test-admin.mingyuantostars.online/prod-api/profile/upload/a.jpg']) assert.equal(s.imageUrl(value), want)
    assert.equal(s.imageUrl('https://cdn.example.com/profile/a.jpg'), 'https://cdn.example.com/profile/a.jpg')
    assert.equal(s.imageUrl('wxfile://tmp/test.jpg'), 'wxfile://tmp/test.jpg')
    assert.equal(s.imageUrl(''), '')
  } finally { app.globalData.baseUrl = previous }
})
test('new material images persist the canonical file path rather than a broken proxy URL', async () => {
  const previous = app.globalData.baseUrl
  app.globalData.baseUrl = 'https://test-admin.mingyuantostars.online/prod-api'
  try {
    environment({ chooseMedia: o => o.success({ tempFiles: [{ tempFilePath: '/tmp/image.jpg', size: 50 }] }),
      uploadFile: o => o.success({ statusCode: 200, data: JSON.stringify({ code: 200, fileName: '/profile/upload/a.jpg', url: 'http://test-admin.mingyuantostars.online/profile/upload/a.jpg' }) }) })
    const p = page('material-upload'); p._role = 'admin'
    p.chooseImages({ currentTarget: { dataset: { kind: 'cover' } } }); await new Promise(resolve => setImmediate(resolve))
    assert.equal(p.data.form.coverUrl, 'https://test-admin.mingyuantostars.online/prod-api/profile/upload/a.jpg')
  } finally { app.globalData.baseUrl = previous }
})
test('HTTP 200 carrying application 403 rejects and preserves backend message', async () => {
  environment({ request: options => options.success({ statusCode: 200, data: { code: 403, msg: '无权限' } }) })
  await assert.rejects(request({ url: '/system/material' }), /无权限/)
})
test('teacher PDF uses authenticated protected upload and retains private metadata', async () => {
  let uploaded, saved
  environment({
    uploadFile(options) { uploaded = options; options.success({ statusCode: 200, data: JSON.stringify({ code: 200, data: { filePath: 'private/asset.pdf', fileName: '试卷.pdf', fileSize: 123 } }) }) },
    request(options) { if (options.method === 'POST') saved = options.data; options.success({ statusCode: 200, data: { code: 200, data: { rows: [], total: 0 } } }) }
  })
  const p = page('material-upload')
  p._role = 'teacher'
  p.setData({ role: 'teacher', form: { title: '试卷', price: '20.00', intro: '练习', deliveryType: 'DIGITAL', images: [] }, subjectIndex: 0, gradeIndex: 0, subjects: [{ dictValue: 'math' }], grades: [{ dictValue: 'g1' }], file: { path: '/tmp/a.pdf', name: '试卷.pdf', size: 123 } })
  await p.submit()
  assert.equal(uploaded.url, 'http://127.0.0.1:8080/miniapp/teacher/material/file')
  assert.equal(uploaded.header.Authorization, 'Bearer test-token')
  assert.equal(saved.filePath, 'private/asset.pdf')
  assert.equal(saved.fileName, '试卷.pdf')
  assert.equal(saved.shelfStatus, '2')
})
function shop() {
  assert.ok(fs.existsSync(path.join(root, 'utils/material-shop.js')), 'shop helper is implemented')
  return require('../utils/material-shop')
}
test('integer cents calculate 20 × 2 + 5 exactly and reject invalid precision', () => {
  const s = shop()
  assert.equal(s.calculateTotal('20.00', 2, '5.00'), '45.00')
  assert.equal(s.calculateTotal('0.10', 3, '0.20'), '0.50')
  assert.throws(() => s.calculateTotal('1.001', 1, '0.00'))
})
test('quantity rejects zero, fractions, stock exhaustion and purchase limit', () => {
  const s = shop()
  for (const qty of [0, -1, 1.5, 4]) assert.throws(() => s.normalizeQuantity(qty, 3, 99))
  assert.throws(() => s.normalizeQuantity(3, 10, 2))
  assert.equal(s.normalizeQuantity(2, 3, 2), 2)
})
test('product images normalize paths and bought booleans without precision loss on ids', () => {
  const s = shop()
  const product = s.product({ materialId: '9007199254740993', price: '20.00', coverUrl: '/profile/a.png', bought: true, deliveryType: 'DIGITAL', images: [{ imageType: 'DETAIL', imageUrl: '/profile/b.png' }] })
  assert.equal(product.materialId, '9007199254740993')
  assert.equal(product.coverUrl, 'http://127.0.0.1:8080/profile/a.png')
  assert.equal(product.detailImages[0].imageUrl, 'http://127.0.0.1:8080/profile/b.png')
  assert.equal(product.bought, true)
  assert.equal(s.product({ bought: '0' }).bought, false)
})
test('private upload rejects 10MB overflow and application 403 without exposing success', async () => {
  let count = 0
  environment({ uploadFile(options) { count++; options.success({ statusCode: 200, data: '{"code":403,"msg":"禁止上传"}' }) } })
  const s = shop()
  await assert.rejects(s.upload('/system/material/file', { path: '/tmp/a.pdf', size: 10485761 }), /10MB/)
  assert.equal(count, 0)
  await assert.rejects(s.upload('/system/material/file', { path: '/tmp/a.pdf', size: 123 }), /禁止上传/)
})
test('checkout replays unchanged key and payload after ambiguous network failure', async () => {
  assert.ok(fs.existsSync(path.join(root, 'pages/material-checkout/material-checkout.js')), 'checkout page is implemented')
  const bodies = [], stored = new Map()
  environment({
    getStorageSync: key => key === 'token' ? 'test-token' : key === 'userInfo' ? { userId: '1' } : stored.get(key),
    setStorageSync: (key, value) => stored.set(key, structuredClone(value)), removeStorageSync: key => stored.delete(key),
    redirectTo() {}, request(options) {
      bodies.push(structuredClone(options.data))
      if (bodies.length === 1) options.fail({ errMsg: 'socket closed' })
      else options.success({ statusCode: 200, data: { code: 200, data: { orderId: '9007199254740993', payableAmount: '45.00', orderStatus: 'WAIT_PAY' } } })
    }
  })
  const p = page('material-checkout'); p.materialId = '9'
  p.setData({ detail: { materialId: '9', price: '20.00', deliveryType: 'PHYSICAL', availableStock: 3, purchaseLimit: 2 }, quantity: 2, address: { addressId: '1', receiverName: '家长', receiverPhone: '13800138000' }, buyerRemark: '原备注' })
  await p.submit()
  assert.equal(p.data.submitting, false)
  assert.ok(p.data.pending)
  p.setData({ quantity: 1, buyerRemark: '已改备注' })
  await p.submit()
  assert.equal(bodies.length, 2)
  assert.ok(bodies[0].clientRequestId)
  assert.deepEqual(bodies[1], bodies[0])
  assert.equal(p.data.order.payableAmount, '45.00')
})
test('catalogue onLoad/onShow starts one list load and late old response cannot overwrite latest filters', async () => {
  const queued = []
  environment({ request(options) {
    if (options.url.endsWith('/getInfo')) options.success({ statusCode: 200, data: { code: 200, roles: ['parent'], permissions: [] } })
    else if (options.url.endsWith('/dict')) options.success({ statusCode: 200, data: { code: 200, data: { subjects: [], grades: [] } } })
    else queued.push(options)
  } })
  const p = page('material'); p.onLoad({}); const show = p.onShow(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(queued.length, 1)
  p.setData({ keyword: '新' }); const newer = p.loadList()
  queued[1].success({ statusCode: 200, data: { code: 200, data: { rows: [{ materialId: '2', title: '新' }], total: 1 } } })
  await newer
  queued[0].success({ statusCode: 200, data: { code: 200, data: { rows: [{ materialId: '1', title: '旧' }], total: 1 } } })
  await show
  assert.equal(p.data.list[0].title, '新')
})
test('cancelled simulated payment never confirms payment', async () => {
  const urls = []
  environment({ showModal: options => options.success({ confirm: false }), request(options) { urls.push(options.url); options.success({ statusCode: 200, data: { code: 200, data: { mode: 'MOCK', orderId: '9' } } }) } })
  const result = await shop().pay('9')
  assert.equal(result.state, 'cancelled')
  assert.deepEqual(urls, ['http://127.0.0.1:8080/miniapp/parent/material/orders/9/payment'])
})
test('payment success comes from refreshed server order, not modal confirmation', async () => {
  environment({ showModal: options => options.success({ confirm: true }), request(options) {
    const data = options.url.endsWith('/payment') ? { mode: 'MOCK' } : { orderId: '9', orderStatus: 'WAIT_PAY', payStatus: '0' }
    options.success({ statusCode: 200, data: { code: 200, data } })
  } })
  const result = await shop().pay('9')
  assert.equal(result.state, 'pending')
})
test('customer role display cannot authorize admin product submission', async () => {
  let count = 0
  environment({ request(options) { count++; options.success({ statusCode: 200, data: { code: 200 } }) } })
  const p = page('material-upload'); p._role = 'parent'
  p.setData({ role: 'admin', form: { title: '注入', deliveryType: 'PHYSICAL', price: '20.00', shippingFee: '0.00', images: [] }, subjectIndex: 0, gradeIndex: 0, subjects: [{ dictValue: 'math' }], grades: [{ dictValue: 'g1' }] })
  await p.submit()
  assert.equal(count, 0)
})
test('admin catalogue resolves authenticated role and uses permission checked admin API', async () => {
  const urls = []
  environment({ request(options) {
    urls.push(options.url)
    const body = options.url.endsWith('/getInfo') ? { code: 200, roles: ['admin'], permissions: ['*:*:*'] } : options.url.endsWith('/dict') ? { code: 200, data: { subjects: [], grades: [] } } : { code: 200, rows: [{ materialId: '12', title: '管理预览', price: '20.00' }], total: 1 }
    options.success({ statusCode: 200, data: body })
  } })
  const p = page('material'); p.onLoad({}); await p.onShow()
  assert.ok(urls.some(url => url.endsWith('/system/material/list')))
  assert.ok(!urls.some(url => url.includes('/parent/material')))
  assert.equal(p.data.list[0].title, '管理预览')
  assert.equal(p.data.role, 'admin')
})
test('teacher catalogue directs draft management without issuing customer API requests', async () => {
  const urls = []
  environment({ request(options) { urls.push(options.url); options.success({ statusCode: 200, data: { code: 200, roles: ['teacher'] } }) } })
  const p = page('material'); p.onLoad({}); await p.onShow()
  assert.equal(p.data.role, 'teacher')
  assert.ok(!urls.some(url => url.includes('/parent/material') || url.includes('/system/material')))
})
test('closed order tab includes cancelled and expired orders using supported server filters', async () => {
  environment({ request(options) {
    const cancelled = options.data.orderStatus === 'CANCELLED'
    options.success({ statusCode: 200, data: { code: 200, data: { rows: [{ orderId: cancelled ? '1' : '2', orderStatus: options.data.orderStatus, payStatus: '0', createTime: cancelled ? '2026-10-04 10:00' : '2026-10-04 11:00' }], total: 1 } } })
  } })
  const p = page('material-orders'); p.onLoad({}); p.setData({ tabIndex: 5 }); await p.onShow()
  assert.deepEqual(p.data.orders.map(item => item.orderStatus), ['CLOSED', 'CANCELLED'])
  assert.equal(p.data.total, 2)
})
test('retry binding event resets order pagination instead of appending an obsolete page', async () => {
  let requestedPage
  environment({ request(options) { requestedPage = options.data.pageNum; options.success({ statusCode: 200, data: { code: 200, data: { rows: [], total: 0 } } }) } })
  const p = page('material-orders'); p.onLoad({}); p.setData({ pageNum: 4, orders: [{ orderId: 'old' }] }); await p.loadOrders({ type: 'tap' })
  assert.equal(requestedPage, 1)
  assert.equal(p.data.orders.length, 0)
})
test('order payment response from a hidden visit cannot overwrite the next visit', async () => {
  let completePayment
  environment({ showModal: options => options.success({ confirm: true }), request(options) {
    if (options.url.endsWith('/payment')) options.success({ statusCode: 200, data: { code: 200, data: { mode: 'MOCK' } } })
    else if (options.url.endsWith('/mock-pay')) completePayment = options
    else options.success({ statusCode: 200, data: { code: 200, data: { orderId: '1', orderStatus: 'CLOSED', payStatus: '0', items: [] } } })
  } })
  const p = page('material-order-detail'); p.onLoad({ id: '1' }); p.setData({ detail: { canPay: true } }); const payment = p.pay()
  await new Promise(resolve => setImmediate(resolve)); p.onHide(); await p.onShow()
  assert.equal(p.data.busy, false)
  completePayment.success({ statusCode: 200, data: { code: 200, data: {} } }); await payment
  assert.equal(p.data.detail.orderStatus, 'CLOSED')
  assert.equal(p.data.paymentText, '')
})
test('address save sends region names and codes then picker emits the saved address', async () => {
  let body, picked
  const address = { addressId: '9007199254740993', receiverName: '家长', receiverPhone: '13800138000', provinceName: '江苏省', cityName: '南京市', districtName: '鼓楼区', detailAddress: '1号', isDefault: '1' }
  environment({ navigateBack() {}, request(options) { if (options.method === 'POST') body = options.data; options.success({ statusCode: 200, data: { code: 200, data: options.method === 'POST' ? address : [address] } }) } })
  const p = page('material-address'); p.onLoad({ pick: '1' }); p.getOpenerEventChannel = () => ({ emit: (name, value) => { picked = value } })
  p.setData({ saveToBook: true })
  p.setData({ form: { ...address, addressId: undefined } }); p.regionChange({ detail: { value: ['江苏省', '南京市', '鼓楼区'], code: ['320000', '320100', '320106'] } }); await p.save()
  assert.equal(body.districtCode, '320106'); assert.equal(body.districtName, '鼓楼区')
  p.select({ currentTarget: { dataset: { id: '9007199254740993' } } }); assert.equal(picked.addressId, '9007199254740993')
})
test('download sends bearer token to protected order route and never opens denied file', () => {
  let opened = false, downloaded
  environment({ showLoading() {}, hideLoading() {}, openDocument() { opened = true }, downloadFile(options) { downloaded = options; options.success({ statusCode: 403, tempFilePath: '/tmp/error' }) } })
  shop().download('9007199254740993')
  assert.equal(downloaded.url, 'http://127.0.0.1:8080/miniapp/parent/material/download/9007199254740993')
  assert.equal(downloaded.header.Authorization, 'Bearer test-token'); assert.equal(opened, false)
})
test('admin digital publishing uses protected PDF metadata and exact product fields', async () => {
  let saved, uploaded
  environment({ uploadFile(options) { uploaded = options.url; options.success({ statusCode: 200, data: JSON.stringify({ code: 200, data: { filePath: 'private/new.pdf', fileName: '新.pdf', fileSize: 10 } }) }) }, request(options) { if (options.method === 'POST') saved = options.data; options.success({ statusCode: 200, data: { code: 200, rows: [], total: 0 } }) } })
  const p = page('material-upload'); p._role = 'admin'
  p.setData({ form: { title: '新教材', price: '20.00', shippingFee: '0.00', deliveryType: 'DIGITAL', images: [{ imageType: 'GALLERY', imageUrl: '/profile/a.jpg', sortOrder: 0 }], materialType: 'handout' }, file: { path: '/tmp/new.pdf', name: '新.pdf', size: 10 } })
  await p.submit({ currentTarget: { dataset: { publish: '1' } } })
  assert.equal(uploaded, 'http://127.0.0.1:8080/system/material/file'); assert.equal(saved.shelfStatus, '1'); assert.equal(saved.materialType, 'handout'); assert.equal(saved.price, '20.00'); assert.equal(saved.filePath, 'private/new.pdf'); assert.equal(saved.images[0].imageType, 'GALLERY')
})
test('HTTP200 JSON download denial is surfaced without opening it as a PDF', async () => {
  let opened = false, toast
  const body = '{"code":500,"msg":"历史文件不存在"}'
  environment({ showLoading() {}, hideLoading() {}, showToast: options => { toast = options.title }, openDocument() { opened = true }, downloadFile(options) { options.success({ statusCode: 200, tempFilePath: '/tmp/denied' }) }, getFileSystemManager: () => ({ readFile(options) { options.success({ data: options.encoding ? body : Uint8Array.from(Buffer.from(body)).buffer }) } }) })
  await shop().download('10')
  assert.equal(opened, false)
  assert.equal(toast, '历史文件不存在')
})
test('valid downloaded PDF signature opens authenticated document', async () => {
  let opened
  environment({ showLoading() {}, hideLoading() {}, downloadFile(options) { options.success({ statusCode: 200, tempFilePath: '/tmp/valid.pdf' }) }, getFileSystemManager: () => ({ readFile(options) { options.success({ data: Uint8Array.from(Buffer.from('%PDF-1.7\n')).buffer }) } }), openDocument: options => { opened = options } })
  await shop().download('10')
  assert.equal(opened.filePath, '/tmp/valid.pdf'); assert.equal(opened.fileType, 'pdf')
})
test('checkout restores ambiguous submission from storage on a later page visit', async () => {
  const record = { materialId: '9', quantity: 2, expectedPrice: '20.00', addressId: '1', buyerRemark: '原备注', clientRequestId: 'same-key' }
  let sent
  environment({ getStorageSync: key => key === 'token' ? 'test-token' : key === 'userInfo' ? { userId: '1' } : key === 'material-checkout:1:9' ? record : '', redirectTo() {}, request(options) { sent = options.data; options.success({ statusCode: 200, data: { code: 200, data: { orderId: '10', payableAmount: '45.00', orderStatus: 'WAIT_PAY' } } }) } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); await p.submit()
  assert.deepEqual(sent, record); assert.equal(p.data.order.orderId, '10')
})
test('changed price modal cancellation prevents silently resubmitting at updated price', async () => {
  let creates = 0
  environment({ showModal: options => options.success({ confirm: false }), request(options) {
    if (options.method === 'POST') { creates++; options.success({ statusCode: 200, data: { code: 500, msg: '商品价格已变化，请重新确认' } }) }
    else options.success({ statusCode: 200, data: { code: 200, data: { materialId: '9', price: '25.00', deliveryType: 'DIGITAL' } } })
  } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { materialId: '9', price: '20.00', deliveryType: 'DIGITAL' } }); await p.submit(); await p.submit()
  assert.equal(creates, 1); assert.equal(p.data.detail.price, '25.00'); assert.equal(p.data.pending, false)
})
test('mock payment failure does not mark unpaid order as successful', async () => {
  environment({ showModal: options => options.success({ confirm: true }), request(options) {
    if (options.url.endsWith('/mock-pay')) options.success({ statusCode: 200, data: { code: 500, msg: '订单已取消' } })
    else options.success({ statusCode: 200, data: { code: 200, data: options.url.endsWith('/payment') ? { mode: 'MOCK' } : { orderId: '10', orderStatus: 'CANCELLED', payStatus: '0' } } })
  } })
  await assert.rejects(shop().pay('10'), /订单已取消/)
})
test('admin stock adjustment refuses to discard unsaved product changes', async () => {
  let calls = 0
  environment({ request(options) { calls++; options.success({ statusCode: 200, data: { code: 200, data: { materialId: '9', title: '旧名', version: 2 } } }) } })
  const p = page('material-upload'); p._role = 'admin'; p.setForm({ materialId: '9', title: '旧名', deliveryType: 'PHYSICAL', stockQuantity: 3, images: [] })
  p.input({ currentTarget: { dataset: { field: 'title' } }, detail: { value: '未保存的新名' } }); p.setData({ stockDelta: '1', stockRemark: '补货' }); await p.adjustStock()
  assert.equal(calls, 0); assert.equal(p.data.form.title, '未保存的新名')
})
test('checkout response after leaving page persists resolution without redirecting another page', async () => {
  let finish, redirected = false
  environment({ redirectTo() { redirected = true }, request(options) { finish = options } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { materialId: '9', price: '20.00', deliveryType: 'DIGITAL' } })
  const submission = p.submit(); p.onHide()
  finish.success({ statusCode: 200, data: { code: 200, data: { orderId: '10', orderStatus: 'WAIT_PAY', payableAmount: '20.00' } } }); await submission
  assert.equal(redirected, false)
  assert.equal(p.data.order, null)
  assert.equal(p._submission.materialId, '9')
})
test('payment preparation returning after page hides never opens confirmation modal', async () => {
  let prepared, modalOpened = false
  environment({ showModal(options) { modalOpened = true; options.success({ confirm: false }) }, request(options) { prepared = options } })
  const p = page('material-order-detail'); p.onLoad({ id: '10' }); p.setData({ detail: { canPay: true } }); const paying = p.pay(); p.onHide()
  prepared.success({ statusCode: 200, data: { code: 200, data: { mode: 'MOCK' } } }); await paying
  assert.equal(modalOpened, false)
})
test('order timeline translates events while preserving purchased snapshots and download rights', () => {
  const result = shop().order({ orderId: '10', deliveryType: 'DIGITAL', orderStatus: 'COMPLETED', payStatus: '1', aftersaleStatus: 'NONE', items: [{ materialTitle: '购买时的名称', unitPrice: '20.00' }], logs: [{ eventType: 'PAY_SUCCESS', toOrderStatus: 'COMPLETED', createTime: '2026-10-04' }] })
  assert.equal(result.logs[0].eventText, '模拟支付成功'); assert.equal(result.logs[0].statusText, '已完成')
  assert.equal(result.items[0].materialTitle, '购买时的名称'); assert.equal(result.canDownload, true)
  assert.equal(shop().order({ deliveryType: 'DIGITAL', payStatus: '2', aftersaleStatus: 'REFUNDED' }).canDownload, false)
})
for (const [name, response] of [
  ['HTTP502', { statusCode: 502, data: { code: 500, msg: 'gateway failed' } }],
  ['HTTP503', { statusCode: 503, data: '<html>unavailable</html>' }],
  ['missing envelope', { statusCode: 200, data: {} }],
  ['malformed envelope', { statusCode: 200, data: 'invalid json' }],
  ['missing order payload', { statusCode: 200, data: { code: 200 } }],
  ['malformed order payload', { statusCode: 200, data: { code: 200, data: { orderId: 'invalid', payableAmount: '20.00' } } }]
]) test(`uncertain ${name} preserves persisted checkout key and exact retry payload`, async () => {
  const stored = new Map(), bodies = []
  environment({ setStorageSync: (key, value) => stored.set(key, structuredClone(value)), removeStorageSync: key => stored.delete(key), getStorageSync: key => key === 'token' ? 'test-token' : key === 'userInfo' ? { userId: '1' } : stored.get(key), redirectTo() {}, request(options) { bodies.push(structuredClone(options.data)); options.success(bodies.length === 1 ? response : { statusCode: 200, data: { code: 200, data: { orderId: '10', orderStatus: 'WAIT_PAY', payableAmount: '20.00' } } }) } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { price: '20.00', deliveryType: 'DIGITAL' } }); await p.submit()
  assert.deepEqual(stored.get('material-checkout:1:9'), bodies[0]); assert.equal(p.data.pending, true)
  p.setData({ buyerRemark: 'changed' }); await p.submit(); assert.deepEqual(bodies[1], bodies[0])
})
test('valid backend application rejection is definitive and allows corrected checkout submission', async () => {
  const bodies = []
  environment({ redirectTo() {}, request(options) { bodies.push(options.data); options.success({ statusCode: 200, data: { code: 403, msg: '拒绝提交' } }) } })
  await assert.rejects(request({ url: '/system/material' }), error => error.applicationRejected === true)
  bodies.length = 0
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { price: '20.00', deliveryType: 'DIGITAL' } }); await p.submit()
  assert.equal(p.data.pending, false); assert.equal(p._submission, null)
})
for (const outcome of ['success', 'error', 'price-error']) test(`checkout hide-show ignores stale ${outcome} UI and preserves key for current visit reconciliation`, async () => {
  let finish, redirects = 0, modals = 0
  environment({ redirectTo() { redirects++ }, showModal(options) { modals++; options.success({ confirm: true }) }, request(options) { if (options.method === 'POST') finish = options; else options.success({ statusCode: 200, data: { code: 200, data: { materialId: '9', deliveryType: 'DIGITAL', price: '25.00' } } }) } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { price: '20.00', deliveryType: 'DIGITAL' } }); const submitting = p.submit(); const key = finish.data.clientRequestId
  p.onHide(); await p.onShow(); p.setData({ error: 'current visit', buyerRemark: 'current note' }); const current = structuredClone(p.data)
  finish.success({ statusCode: 200, data: outcome === 'success' ? { code: 200, data: { orderId: '10', orderStatus: 'WAIT_PAY', payableAmount: '20.00' } } : { code: 500, msg: outcome === 'price-error' ? '商品价格已变化' : '提交被拒绝' } }); await submitting
  assert.deepEqual(p.data, current); assert.equal(redirects, 0); assert.equal(modals, 0); assert.equal(p._submission.clientRequestId, key)
})
test('checkout price reload from an old visit cannot open a confirmation in a new visit', async () => {
  let reload, modalCount = 0, reads = 0
  environment({ showModal(options) { modalCount++; options.success({ confirm: true }) }, request(options) { if (options.method === 'POST') options.success({ statusCode: 200, data: { code: 500, msg: '商品价格已变化' } }); else if (++reads === 1) reload = options; else options.success({ statusCode: 200, data: { code: 200, data: { materialId: '9', price: '25.00', deliveryType: 'DIGITAL' } } }) } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { price: '20.00', deliveryType: 'DIGITAL' } }); const submitting = p.submit(); await new Promise(resolve => setImmediate(resolve)); p.onHide(); await p.onShow()
  const current = structuredClone(p.data); reload.success({ statusCode: 200, data: { code: 200, data: { materialId: '9', price: '30.00', deliveryType: 'DIGITAL' } } }); await submitting
  assert.equal(modalCount, 0); assert.deepEqual(p.data, current)
})
test('checkout confirmation from an old visit cannot send a new order or alter current state', async () => {
  let modal, writes = 0
  environment({ showModal(options) { modal = options }, request(options) { if (options.method === 'POST') { writes++; options.success({ statusCode: 200, data: { code: 500, msg: '拒绝提交' } }) } else options.success({ statusCode: 200, data: { code: 200, data: { materialId: '9', price: '25.00', deliveryType: 'DIGITAL' } } }) } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.setData({ detail: { price: '25.00', deliveryType: 'DIGITAL' } }); p._needsPriceConfirmation = true; const submitting = p.submit(); p.onHide(); await p.onShow(); const current = structuredClone(p.data)
  modal.success({ confirm: true }); await submitting; assert.equal(writes, 0); assert.deepEqual(p.data, current); assert.equal(p._needsPriceConfirmation, true)
})
for (const action of ['cancel', 'receive']) test(`leaving during order ${action} confirmation prevents mutation after hide-show`, async () => {
  let modal, mutations = 0
  environment({ showModal(options) { modal = options }, request(options) { if (options.method === 'POST') mutations++; options.success({ statusCode: 200, data: { code: 200, data: { orderId: '10', orderStatus: 'WAIT_PAY', payStatus: '0' } } }) } })
  const p = page('material-order-detail'); p.onLoad({ id: '10' }); const acting = p.action({ currentTarget: { dataset: { action } } }); p.onHide(); await p.onShow(); const current = structuredClone(p.data)
  modal.success({ confirm: true }); await acting; assert.equal(mutations, 0); assert.deepEqual(p.data, current)
})
test('leaving during address-delete confirmation prevents stale DELETE and UI changes', async () => {
  let modal, mutations = 0
  environment({ showModal(options) { modal = options }, request(options) { if (options.method === 'DELETE') mutations++; options.success({ statusCode: 200, data: { code: 200, data: [] } }) } })
  const p = page('material-address'); p.onLoad({}); const deleting = p.operate({ currentTarget: { dataset: { action: 'delete', id: '1' } } }); p.onHide(); await p.onShow(); const current = structuredClone(p.data)
  modal.success({ confirm: true }); await deleting; assert.equal(mutations, 0); assert.deepEqual(p.data, current)
})
for (const role of ['admin', 'teacher']) test(`incomplete ${role} DIGITAL draft saves without PDF and preserves teacher draft-only enforcement`, async () => {
  let saved
  environment({ request(options) { if (options.method === 'POST') saved = options.data; options.success({ statusCode: 200, data: { code: 200, rows: [], total: 0, data: { rows: [], total: 0 } } }) } })
  const p = page('material-upload'); p._role = role; p.openForm(); p.setData({ 'form.title': '不完整草稿', 'form.deliveryType': 'DIGITAL' }); await p.submit({ currentTarget: { dataset: { publish: role === 'teacher' ? '1' : '0' } } })
  assert.ok(saved); assert.equal(saved.shelfStatus, '2'); assert.equal(saved.deliveryType, 'DIGITAL'); assert.equal(saved.filePath, '')
})
test('admin DIGITAL publication without PDF fails before product mutation', async () => {
  let writes = 0, message
  environment({ showToast(options) { message = options.title }, request() { writes++ } })
  const p = page('material-upload'); p._role = 'admin'; p.openForm(); p.setData({ 'form.title': '待上架', 'form.deliveryType': 'DIGITAL' }); await p.submit({ currentTarget: { dataset: { publish: '1' } } })
  assert.equal(writes, 0); assert.match(message, /PDF/)
})
for (const pick of [false, true]) test(`address save completion from a previous visit cannot close a new editor (${pick ? 'optional checkout save' : 'address book'})`, async () => {
  let saved
  environment({ request(options) { if (options.method === 'POST') saved = options; else options.success({ statusCode: 200, data: { code: 200, data: [] } }) } })
  const p = page('material-address'); p.onLoad({ pick: pick ? '1' : '0' }); p.setData({ saveToBook: true, form: { receiverName: '旧姓名', receiverPhone: '13800138000', provinceName: '江苏省', cityName: '南京市', districtName: '鼓楼区', detailAddress: '1号' } }); const saving = p.save()
  p.onHide(); await p.onShow(); p.edit({ currentTarget: { dataset: {} } }); p.input({ currentTarget: { dataset: { field: 'receiverName' } }, detail: { value: '新姓名' } }); const current = structuredClone(p.data)
  saved.success({ statusCode: 200, data: { code: 200, data: { addressId: '1' } } }); await saving; assert.deepEqual(p.data, current)
})

const inlineAddress = { receiverName: '张三', receiverPhone: '13800138000', provinceCode: '320000', cityCode: '320100', districtCode: '320106', provinceName: '江苏省', cityName: '南京市', districtName: '鼓楼区', detailAddress: '一号楼101', postalCode: '', isDefault: '0' }
test('inactive one-off address editor cannot emit a selection or navigate', async () => {
  let selected = 0, navigated = 0
  environment({ navigateBack() { navigated++ } })
  const p = page('material-address'); p.onLoad({ pick: '1' }); p.setData({ form: { ...inlineAddress } }); p.getOpenerEventChannel = () => ({ emit() { selected++ } }); p.onHide()
  const before = structuredClone(p.data); await p.save()
  assert.equal(selected, 0); assert.equal(navigated, 0); assert.deepEqual(p.data, before)
})
for (const whitespace of [false, true]) test(`first physical checkout uses a normalized one-off address without creating an address-book record (${whitespace ? 'trimmed input' : 'canonical input'})`, async () => {
  let navigation, submitted, saves = 0, backs = 0
  environment({ navigateTo(options) { navigation = options }, navigateBack() { backs++ }, redirectTo() {}, request(options) {
    if (options.method === 'POST') {
      if (options.url.endsWith('/addresses')) saves++
      else submitted = structuredClone(options.data)
      options.success({ statusCode: 200, data: { code: 200, data: { orderId: '10', payableAmount: '25.00', orderStatus: 'WAIT_PAY' } } })
    } else options.success({ statusCode: 200, data: { code: 200, data: options.url.endsWith('/addresses') ? [] : { materialId: '9', deliveryType: 'PHYSICAL', price: '20.00', shippingFee: '5.00', availableStock: 3, purchaseLimit: 2 } } })
  } })
  const checkout = page('material-checkout'); checkout.onLoad({ id: '9' }); await checkout.onShow()
  assert.equal(checkout.data.address, null)
  checkout.chooseAddress(); checkout.onHide()
  const editor = page('material-address'); editor.onLoad({ pick: '1' }); await editor.onShow()
  editor.getOpenerEventChannel = () => ({ emit(name, address) { assert.equal(name, 'selected'); navigation.events.selected(address) } })
  editor.edit({ currentTarget: { dataset: {} } })
  editor.setData({ form: whitespace ? { ...inlineAddress, receiverName: ' 张三 ', receiverPhone: ' 13800138000 ', detailAddress: ' 一号楼101 ' } : { ...inlineAddress } })
  await editor.save(); await checkout.onShow(); await checkout.submit()
  assert.equal(saves, 0); assert.equal(backs, 1)
  assert.deepEqual(submitted.address, inlineAddress); assert.equal('addressId' in submitted, false)
  assert.equal(checkout.data.order.orderId, '10')
})
test('one-off ambiguous checkout restores the normalized address and immutable payload on later visits', async () => {
  const stored = new Map(), bodies = []; let navigation
  environment({ navigateTo(options) { navigation = options }, redirectTo() {}, getStorageSync: key => key === 'token' ? 'test-token' : key === 'userInfo' ? { userId: '1' } : stored.get(key), setStorageSync: (key, value) => stored.set(key, structuredClone(value)), removeStorageSync: key => stored.delete(key), request(options) {
    if (options.method === 'POST') { bodies.push(structuredClone(options.data)); if (bodies.length === 1) options.fail({ errMsg: 'socket closed' }); else options.success({ statusCode: 200, data: { code: 200, data: { orderId: '10', payableAmount: '25.00', orderStatus: 'WAIT_PAY' } } }) }
    else options.success({ statusCode: 200, data: { code: 200, data: options.url.endsWith('/addresses') ? [{ addressId: '2', isDefault: 1 }] : { materialId: '9', deliveryType: 'PHYSICAL', price: '20.00', shippingFee: '5.00', availableStock: 3, purchaseLimit: 2 } } })
  } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); await p.onShow(); p.chooseAddress()
  navigation.events.selected({ ...inlineAddress }); await p.submit()
  assert.deepEqual(bodies[0].address, inlineAddress); assert.deepEqual(stored.get('material-checkout:1:9'), bodies[0])
  navigation.events.selected({ ...inlineAddress, detailAddress: '迟到的新地址' })
  assert.equal(p.data.address.detailAddress, '一号楼101')
  p.onUnload()
  const retry = page('material-checkout'); retry.onLoad({ id: '9' }); await retry.onShow()
  assert.deepEqual(retry.data.address, inlineAddress)
  retry.setData({ 'address.detailAddress': 'changed display address' })
  retry.setData({ address: { addressId: '2' }, buyerRemark: 'changed', quantity: 2 }); await retry.submit()
  assert.deepEqual(bodies[1], bodies[0]); assert.equal(stored.size, 0)
})
test('address selection after checkout unload cannot change the abandoned page', () => {
  let navigation
  environment({ navigateTo(options) { navigation = options } })
  const p = page('material-checkout'); p.onLoad({ id: '9' }); p.chooseAddress(); p.onUnload(); const before = structuredClone(p.data)
  navigation.events.selected({ ...inlineAddress }); assert.deepEqual(p.data, before)
})
test('numeric default-address flags drive badges, disabled action and editor switch consistently', async () => {
  environment({ request(options) { options.success({ statusCode: 200, data: { code: 200, data: [{ addressId: '1', isDefault: 1 }, { addressId: '2', isDefault: 0 }] } }) } })
  const p = page('material-address'); p.onLoad({}); await p.onShow()
  assert.deepEqual(p.data.addresses.map(a => a.isDefault), ['1', '0'])
  p.edit({ currentTarget: { dataset: { id: '1' } } }); assert.equal(p.data.form.isDefault, '1')
  p.edit({ currentTarget: { dataset: { id: '2' } } }); assert.equal(p.data.form.isDefault, '0')
})
for (const retry of ['search', 'onPullDownRefresh']) test(`catalogue ${retry} recovers from failed initial role lookup and then refreshes only the list`, async () => {
  let auth = 0, lists = 0, stopped = 0
  environment({ stopPullDownRefresh() { stopped++ }, request(options) {
    if (options.url.endsWith('/getInfo')) { if (++auth === 1) { options.fail({ errMsg: 'network failure' }); return }; options.success({ statusCode: 200, data: { code: 200, roles: ['parent'] } }) }
    else if (options.url.endsWith('/dict')) options.success({ statusCode: 200, data: { code: 200, data: { subjects: [], grades: [] } } })
    else { lists++; options.success({ statusCode: 200, data: { code: 200, data: { rows: [{ materialId: '9', title: '重试商品' }], total: 1 } } }) }
  } })
  const p = page('material'); p.onLoad({}); await p.onShow(); assert.match(p.data.error, /network failure/)
  await p[retry](); assert.equal(auth, 2); assert.equal(lists, 1); assert.equal(p.data.error, ''); assert.equal(p.data.list[0].title, '重试商品')
  await p[retry](); assert.equal(auth, 2); assert.equal(lists, 2); assert.equal(stopped, retry === 'onPullDownRefresh' ? 2 : 0)
})
for (const role of ['admin', 'teacher']) test(`active ${role} product removal uses the role and id confirmed before mutable state changes`, async () => {
  let modal, removed
  environment({ showModal(options) { modal = options }, request(options) { if (options.method === 'DELETE') removed = options.url; options.success({ statusCode: 200, data: { code: 200, rows: [], total: 0, data: { rows: [], total: 0 } } }) } })
  const p = page('material-upload'); p.onLoad(); p._role = role
  const event = { currentTarget: { dataset: { id: '9' } } }, removing = p.remove(event)
  p._role = role === 'admin' ? 'teacher' : 'admin'; event.currentTarget.dataset.id = '99'
  modal.success({ confirm: true }); await removing
  assert.equal(removed, `http://127.0.0.1:8080/${role === 'admin' ? 'system/material' : 'miniapp/teacher/material'}/9`)
  assert.equal(p.data.submitting, false)
})
for (const lifecycle of ['hide', 'unload', 'hide-show']) test(`product removal confirmation after ${lifecycle} sends no DELETE and changes no current UI`, async () => {
  let modal, mutations = 0
  environment({ showModal(options) { modal = options }, request(options) { if (options.method === 'DELETE') mutations++; options.success({ statusCode: 200, data: options.url.endsWith('/getInfo') ? { code: 200, roles: ['admin'] } : { code: 200, rows: [], total: 0, data: { rows: [], total: 0 } } }) } })
  const p = page('material-upload'); p.onLoad(); p._role = 'admin'; const removing = p.remove({ currentTarget: { dataset: { id: '9' } } })
  if (lifecycle === 'unload') p.onUnload(); else p.onHide()
  if (lifecycle === 'hide-show') await p.onShow()
  const before = structuredClone(p.data); modal.success({ confirm: true }); await removing
  assert.equal(mutations, 0); assert.deepEqual(p.data, before)
})
for (const outcome of ['success', 'failure']) test(`late product removal ${outcome} after hide-show cannot reload, toast or change current UI`, async () => {
  let finish, modal, reads = 0, toasts = 0
  environment({ showToast() { toasts++ }, showModal(options) { modal = options }, request(options) { if (options.method === 'DELETE') finish = options; else { reads++; options.success({ statusCode: 200, data: options.url.endsWith('/getInfo') ? { code: 200, roles: ['admin'] } : { code: 200, rows: [], total: 0, data: { rows: [], total: 0 } } }) } } })
  const p = page('material-upload'); p.onLoad(); p._role = 'admin'; const removing = p.remove({ currentTarget: { dataset: { id: '9' } } }); modal.success({ confirm: true }); await new Promise(resolve => setImmediate(resolve))
  p.onHide(); await p.onShow(); const before = structuredClone(p.data), currentReads = reads
  if (outcome === 'success') finish.success({ statusCode: 200, data: { code: 200 } }); else finish.fail({ errMsg: 'late failure' })
  await removing; assert.deepEqual(p.data, before); assert.equal(reads, currentReads); assert.equal(toasts, 0)
})
test('product media pickers keep successful results when the native picker temporarily hides the page', async () => {
  let imagePicker, filePicker
  environment({ chooseMedia(options) { imagePicker = options }, chooseMessageFile(options) { filePicker = options }, uploadFile(options) { options.success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/new.png' }) }) } })
  const p = page('material-upload'); p.onLoad(); p._role = 'admin'; p.openForm()
  p.chooseImages({ currentTarget: { dataset: { kind: 'cover' } } }); p.onHide()
  await imagePicker.success({ tempFiles: [{ tempFilePath: '/tmp/new.png', size: 10 }] }); assert.equal(p.data.form.coverUrl, 'http://127.0.0.1:8080/profile/new.png')
  p.chooseFile(); p.onHide(); filePicker.success({ tempFiles: [{ name: 'test.pdf', path: '/tmp/test.pdf', size: 10 }] }); assert.equal(p.data.file.name, 'test.pdf')
})
