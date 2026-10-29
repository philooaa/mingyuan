const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const root = path.resolve(__dirname, '..')
let definition, calls, storage
const app = { globalData: { baseUrl: 'http://127.0.0.1:8080' } }
global.getApp = () => app
global.Page = value => { definition = value }
function env(handler, role = 'parent') {
  calls = []; storage = storage || new Map()
  app.globalData.roles = [role]; app.globalData.userInfo = { userId: '9007199254740993' }; app.globalData.permissions = []
  global.wx = { getStorageSync: key => key === 'token' ? 'jwt' : storage.get(key), setStorageSync: (k, v) => storage.set(k, structuredClone(v)), removeStorageSync: k => storage.delete(k), showToast() {}, showModal: o => o.success({ confirm: true }), reLaunch() {}, navigateTo() {}, stopPullDownRefresh() {}, request(o) { calls.push({ url: o.url, method: o.method, data: structuredClone(o.data) }); handler(o) } }
}
function ok(o, data) { o.success({ statusCode: 200, data: { code: 200, data } }) }
function page(name) {
  const file = path.join(root, 'pages', name, name + '.js')
  assert.ok(fs.existsSync(file), `${name} is implemented`)
  delete require.cache[require.resolve(file)]; require(file)
  const p = { ...definition, data: structuredClone(definition.data), setData(values) { for (const [key, value] of Object.entries(values)) { const parts = key.split('.'); let obj = this.data; while (parts.length > 1) obj = obj[parts.shift()]; obj[parts[0]] = value } } }
  return p
}
const bill = { enrollmentId: '9007199254740995', billingStatus: 'OPEN', financeMode: 'MOCK', mockAllowed: true, originalAmount: '1000.00', discountAmount: '100.00', payableAmount: '900.00', receivedAmount: '0.00', refundedAmount: '0.00', netAmount: '0.00', dueAmount: '900.00', refundableAmount: '0.00', financialVersion: 2, payments: [], refunds: [], receipts: [], coupons: [] }

test('staff selects a coupon using server quote and collects the quoted amount and version', async () => {
  storage = new Map(); const couponId = '9007199254740999'
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.url.endsWith('/quote')) return ok(o, { originalAmount: '1000.00', discountAmount: o.data.userCouponId ? '250.00' : '0.00', payableAmount: o.data.userCouponId ? '750.00' : '1000.00', financialVersion: 7, userCouponId: o.data.userCouponId, coupons: [{ userCouponId: couponId, couponName: '课程券', discountAmount: '250.00' }] })
    ok(o, bill)
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  assert.equal(p.data.couponOptions.length, 2)
  await p.chooseCoupon({ detail: { value: 1 } })
  assert.equal(p.data.quote.payableAmount, '750.00')
  p.onHide(); await p.onShow(); assert.equal(p.data.quote.payableAmount, '750.00'); assert.equal(p.data.couponIndex, 1)
  p.setData({ payerName: '付款人' }); await p.collect()
  const payment = calls.find(c => c.url.endsWith('/payments'))
  assert.equal(payment.data.userCouponId, couponId); assert.equal(payment.data.expectedPayableAmount, '750.00'); assert.equal(payment.data.financialVersion, 7)
})
test('failed coupon quote cannot reuse an old amount to collect payment', async () => {
  storage = new Map(); let rejectQuote = false
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.url.endsWith('/quote') && rejectQuote) return o.success({ statusCode: 200, data: { code: 500, msg: '优惠券已失效' } })
    ok(o, { ...bill, coupons: [{ userCouponId: '9', couponName: '课程券', discountAmount: '100.00' }] })
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  rejectQuote = true; await p.chooseCoupon({ detail: { value: 1 } })
  p.setData({ payerName: '付款人' }); await p.collect()
  assert.equal(p.data.quote, null); assert.match(p.data.quoteError, /失效/); assert.equal(calls.some(c => c.url.endsWith('/payments')), false)
  rejectQuote = false; await p.chooseCoupon({ detail: { value: 0 } }); assert.ok(p.data.quote); assert.equal(p.data.quoteError, '')
})
test('a late coupon quote cannot overwrite a newer quote or a hidden page', async () => {
  storage = new Map(); const quotes = []
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.url.endsWith('/quote')) { quotes.push(o); return }
    ok(o, bill)
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); const loading = p.onShow()
  await new Promise(resolve => setImmediate(resolve))
  const newer = p.refreshQuote(); ok(quotes[1], { ...bill, payableAmount: '800.00' }); await newer
  ok(quotes[0], bill); await loading; assert.equal(p.data.quote.payableAmount, '800.00')
  const hidden = p.refreshQuote(); p.onHide(); ok(quotes[2], bill); await hidden; assert.equal(p.data.quote, null)
})
test('unknown collection retries the frozen coupon and amount without requesting a new quote', async () => {
  storage = new Map(); let quoteCount = 0, posts = 0
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.url.endsWith('/quote')) { quoteCount++; return ok(o, bill) }
    if (o.url.endsWith('/payments')) { posts++; return o.fail({ errMsg: 'request:fail timeout' }) }
    ok(o, bill)
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ payerName: '付款人' }); await p.collect()
  assert.equal(p.data.retryPayment, true); const saved = structuredClone(helper().savedIntent('admin-payment', bill.enrollmentId))
  await p.refreshQuote(); await p.chooseCoupon({ detail: { value: 0 } }); await p.collect()
  assert.equal(quoteCount, 1); assert.equal(posts, 2)
  assert.deepEqual(calls.filter(c => c.url.endsWith('/payments')).map(c => c.data), [saved, saved])
})
test('package collection preserves the purchase coupon and cannot switch coupons', async () => {
  storage = new Map(); const couponId = '9007199254740999'
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    ok(o, { ...bill, billType: 'CREDIT_PACKAGE', userCouponId: couponId, coupons: [] })
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  assert.equal(calls.find(c => c.url.endsWith('/quote')).data.userCouponId, couponId)
  const count = calls.length; await p.chooseCoupon({ detail: { value: 0 } }); assert.equal(calls.length, count); assert.equal(p.data.quote.userCouponId, couponId)
})
test('zero payable coupon quote uses FREE without requiring a payment screenshot', async () => {
  storage = new Map()
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.url.endsWith('/quote')) return ok(o, { originalAmount: '1000.00', discountAmount: '1000.00', payableAmount: '0.00', financialVersion: 3, userCouponId: null, coupons: [] })
    ok(o, { ...bill, financeMode: 'REAL', mockAllowed: false })
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ payerName: '付款人' }); await p.collect()
  assert.equal(calls.find(c => c.url.endsWith('/payments')).data.channel, 'FREE')
})

for (const [index, source, filePath] of [[0, 'album', '/tmp/album.jpg'], [1, 'camera', '/tmp/camera.jpg'], [2, 'chat', '/tmp/proof.pdf']]) {
  test(`payment evidence from ${source} uses the existing authenticated private upload`, async () => {
    env(o => ok(o, {}), 'admin'); app.globalData.permissions = ['system:tuition:collect']
    const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); const uploads = []
    wx.showActionSheet = o => { assert.equal(o.itemList.length, 3); o.success({ tapIndex: index }) }
    wx.chooseMedia = o => {
      assert.deepEqual(o.sourceType, [source]); assert.deepEqual(o.mediaType, ['image']); assert.equal(o.count, 1)
      o.success({ tempFiles: [{ tempFilePath: filePath, size: 2048, fileType: 'image' }] })
    }
    wx.chooseMessageFile = o => {
      assert.equal(source, 'chat'); assert.equal(o.count, 1); assert.ok(o.extension.includes('pdf'))
      o.success({ tempFiles: [{ path: filePath, size: 2048, name: 'proof.pdf' }] })
    }
    wx.uploadFile = o => { uploads.push(o); o.success({ statusCode: 200, data: JSON.stringify({ code: 200, data: { evidenceKey: 'private-selected-proof' } }) }) }
    p.chooseEvidence(); await new Promise(resolve => setImmediate(resolve))
    assert.equal(uploads.length, 1); assert.equal(uploads[0].filePath, filePath)
    assert.equal(uploads[0].url, 'http://127.0.0.1:8080/system/tuition/evidence')
    assert.deepEqual(uploads[0].formData, { businessType: 'PAYMENT', businessId: bill.enrollmentId })
    assert.equal(uploads[0].header.Authorization, 'Bearer jwt')
    assert.equal(p.data.evidenceKey, 'private-selected-proof'); assert.equal(p.data.submitting, false)
  })
}
test('cancelled or failed evidence selection keeps the existing proof and never uploads', async () => {
  for (const error of ['chooseMedia:fail cancel', 'chooseMedia:fail auth deny']) {
    env(o => ok(o, {}), 'admin'); app.globalData.permissions = ['system:tuition:collect']
    const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); p.setData({ evidenceKey: 'previous-proof' })
    const notices = []; wx.showToast = o => notices.push(o.title)
    wx.showActionSheet = o => o.success({ tapIndex: 0 })
    wx.chooseMedia = o => o.fail({ errMsg: error }); wx.chooseMessageFile = () => assert.fail('do not open chat files')
    wx.uploadFile = () => assert.fail('do not upload a cancelled or failed selection')
    p.chooseEvidence(); await new Promise(resolve => setImmediate(resolve))
    assert.equal(p.data.evidenceKey, 'previous-proof'); assert.equal(p.data.submitting, false)
    assert.equal(notices.length, error.endsWith('cancel') ? 0 : 1)
  }
})
test('oversized album evidence still respects the private upload size limit', async () => {
  env(o => ok(o, {}), 'admin'); app.globalData.permissions = ['system:tuition:collect']
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); const notices = []
  wx.showToast = o => notices.push(o.title); wx.showActionSheet = o => o.success({ tapIndex: 0 })
  wx.chooseMessageFile = () => assert.fail('do not open chat files')
  wx.chooseMedia = o => o.success({ tempFiles: [{ tempFilePath: '/tmp/large.jpg', size: 10 * 1024 * 1024 + 1, fileType: 'image' }] })
  wx.uploadFile = () => assert.fail('do not upload oversized evidence')
  p.chooseEvidence(); await new Promise(resolve => setImmediate(resolve))
  assert.ok(notices.some(title => title.includes('10MB'))); assert.equal(p.data.evidenceKey, ''); assert.equal(p.data.submitting, false)
})
test('cancelling the source menu does not select or upload a proof', () => {
  env(o => ok(o, {}), 'admin'); app.globalData.permissions = ['system:tuition:collect']
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId })
  wx.showActionSheet = o => o.fail({ errMsg: 'showActionSheet:fail cancel' })
  wx.chooseMedia = wx.chooseMessageFile = wx.uploadFile = () => assert.fail('cancel must do nothing')
  wx.showToast = () => assert.fail('cancel is not an error')
  p.chooseEvidence(); assert.equal(p.data.submitting, false)
})
test('an account change while choosing a photo cannot upload it under the new account', () => {
  env(o => ok(o, {}), 'admin'); app.globalData.permissions = ['system:tuition:collect']
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); let selection
  wx.showActionSheet = o => o.success({ tapIndex: 0 }); wx.chooseMedia = o => { selection = o }
  wx.uploadFile = () => assert.fail('do not upload across account changes')
  p.chooseEvidence(); const read = wx.getStorageSync
  wx.getStorageSync = k => k === 'token' ? 'another-account-token' : read(k)
  selection.success({ tempFiles: [{ tempFilePath: '/tmp/previous-account.jpg', size: 2048, fileType: 'image' }] })
  assert.equal(p.data.evidenceKey, ''); assert.equal(p.data.submitting, false)
})

test('credit package cancellation uses order API and never exposes course refund route',async()=>{
 storage=new Map();const routes=[];env(o=>ok(o,{...bill,billType:'CREDIT_PACKAGE',canApplyRefund:true,packageSnapshot:{packageCount:2,credits:30}}));wx.navigateTo=o=>routes.push(o.url)
 const p=page('tuition-detail');p.onLoad({id:bill.enrollmentId,accountId:'22'});await p.onShow();assert.equal(p.data.detail.canRefund,false);p.refunds();assert.equal(routes.length,0)
 await p.cancelEnrollment();const cancel=calls.find(c=>c.method==='POST');assert.ok(cancel);assert.ok(cancel.url.endsWith('/lesson-packages/orders/'+bill.enrollmentId+'/cancel'));assert.equal(cancel.data.accountId,'22');assert.equal(calls.some(c=>c.method==='DELETE'),false)
})
function helper() { assert.ok(fs.existsSync(path.join(root, 'utils/tuition.js')), 'tuition helper is implemented'); return require('../utils/tuition') }

test('child billing, receipt and refund navigation preserves scope; coupons stay parent-wide', async () => {
  storage = new Map();const accountId='9007199254740993',routes=[]
  env(o => o.url.endsWith('/'+bill.enrollmentId) ? ok(o,bill) : o.success({statusCode:200,data:{code:200,rows:[],total:0}}))
  wx.navigateTo=o=>routes.push(o.url)
  const list=page('tuition-list');list.onLoad({accountId});await list.onShow()
  list.receipts();list.refunds();list.coupons()
  assert.ok(routes[0].endsWith('?accountId='+accountId));assert.ok(routes[1].endsWith('?accountId='+accountId));assert.equal(routes[2],'/pages/my-coupons/my-coupons')
  const detail=page('tuition-detail');detail.onLoad({id:bill.enrollmentId,accountId});await detail.onShow();detail.refunds();assert.ok(routes.at(-1).endsWith('&accountId='+accountId))
  const receipt=page('tuition-receipt');receipt.onLoad({id:bill.enrollmentId,accountId});await receipt.onShow()
  const refund=page('tuition-refund');refund.onLoad({id:bill.enrollmentId,accountId});await refund.onShow()
  assert.ok(calls.filter(c=>!c.url.endsWith('/getInfo')).every(c=>c.data.accountId===accountId))
})
test('historical unknown money stays unknown and refunds use server due amount', () => {
  env(o => ok(o, {})); const t = helper()
  const history = t.bill({ billingStatus: 'HISTORY_PENDING', originalAmount: null, receivedAmount: null, dueAmount: null })
  assert.equal(history.receivedText, '待核实'); assert.equal(history.canPay, false)
  const refunded = t.bill({ ...bill, receivedAmount: '900.00', refundedAmount: '300.00', netAmount: '600.00', dueAmount: '0.00', payments: [{ paymentId: '9', paymentStatus: 'SUCCESS' }] })
  assert.equal(refunded.dueText, '¥0.00'); assert.equal(refunded.canPay, false)
})
test('teacher cannot read admin bills and missing operation permission prevents writes', async () => {
  env(o => ok(o, {}), 'teacher'); const p = page('admin-tuition'); p.onLoad({}); await p.onShow(); assert.equal(calls.length, 0)
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['system:tuition:list', 'system:tuition:query'] } }) : ok(o, bill), 'admin')
  const a = page('admin-tuition'); a.onLoad({ id: bill.enrollmentId }); await a.onShow(); a.setData({ price: '1.00' }); await a.savePrice(); assert.equal(calls.some(c => c.method === 'PUT'), false)
})
test('staff cannot reopen or submit manual pricing for catalog package purchases', async () => {
  storage = new Map()
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } }) : ok(o, { ...bill, billType: 'CREDIT_PACKAGE', financeMode: 'REAL', mockAllowed: false }), 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  const step = p.data.billingStep; p.editPrice(); assert.equal(p.data.billingStep, step)
  await p.savePrice(); assert.equal(calls.some(c => c.method === 'PUT'), false)
})
test('receipt only opens authenticated successful PDF and blocks a JSON error download', async () => {
  env(o => ok(o, {})); let downloaded, opened = 0, pdf = false
  wx.downloadFile = o => { downloaded = o; o.success({ statusCode: 200, tempFilePath: '/tmp/receipt.pdf' }) }; wx.openDocument = o => { opened++; o.success() }; wx.getFileSystemManager = () => ({ readFile(o) { o.success({ data: Buffer.from(pdf ? '%PDF-1.7\n' : '{"code":403,"msg":"无权限"}') }) } })
  const t = helper(); await assert.rejects(t.downloadReceipt('9007199254740999'), /PDF|无权限/); assert.equal(opened, 0)
  pdf = true; await t.downloadReceipt('9007199254740999'); assert.equal(downloaded.header.Authorization, 'Bearer jwt'); assert.equal(opened, 1)
})
test('parent bill pagination, receipt and coupon lists consume TableDataInfo without forging owner', async () => {
  storage = new Map()
  env(o => o.success({ statusCode: 200, data: { code: 200, rows: o.url.endsWith('/coupons') ? [{ userCouponId: '991', couponName: '课程券', discountAmount: '100.00', minSpendAmount: '800.00', displayStatus: 'EXPIRED' }] : o.url.endsWith('/receipts') ? [{ receiptId: '990', documentType: 'WAIVER', receiptStatus: 'ISSUED', fileStatus: 'FAILED', amount: '0.00' }] : [{ ...bill, enrollmentId: String(o.data.pageNum) }], total: 2 } }))
  const list = page('tuition-list'); list.onLoad(); await list.onShow(); await list.onReachBottom(); assert.deepEqual(list.data.rows.map(r => r.enrollmentId), ['1', '2'])
  const receipt = page('tuition-receipt'); receipt.onLoad({ id: bill.enrollmentId }); await receipt.onShow(); assert.equal(receipt.data.rows[0].canDownload, false); assert.equal(receipt.data.rows[0].title, '费用减免确认单')
  const coupons = page('my-coupons'); coupons.onLoad(); await coupons.onShow(); assert.equal(coupons.data.rows[0].statusText, '已过期')
  assert.equal(calls.some(c => 'parentId' in c.data), false)
})
test('admin authorized price and offline confirmation preserve decimal amount and big IDs', async () => {
  storage = new Map()
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } }) : ok(o, { ...bill, financeMode: 'REAL', mockAllowed: false, payments: [{ paymentId: '9007199254740999', paymentStatus: 'PENDING', channel: 'BANK_TRANSFER', amount: '900.00', evidenceKey: 'private-key', providerTradeNo: 'transfer-1', paidTime: '2026-10-07 12:00:00' }] }), 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ price: '1200.00' }); await p.savePrice()
  const priced = calls.find(c => c.method === 'PUT'); assert.equal(priced.data.originalAmount, '1200.00'); assert.equal(priced.data.financialVersion, 2)
  await p.paymentAction({ currentTarget: { dataset: { id: '9007199254740999', action: 'confirm' } } }); const confirmed = calls.find(c => c.url.endsWith('/confirm')); assert.ok(confirmed.url.includes('9007199254740999')); assert.equal(confirmed.data.evidenceKey, 'private-key')
})
test('successful pricing and offline collection guide staff to the next step without confirming payment automatically', async () => {
  storage = new Map(); let current = { ...bill, financeMode: 'REAL', mockAllowed: false }; const notices = [], sections = []
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.method === 'PUT') current = { ...current, financialVersion: 3 }
    if (o.url.endsWith('/payments')) current = { ...current, payments: [{ paymentId: '91', paymentStatus: 'PENDING', amount: '900.00', channel: 'CASH' }] }
    ok(o, current)
  }, 'admin')
  wx.showToast = o => notices.push(o.title); wx.pageScrollTo = o => sections.push(o.selector)
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); await p.savePrice()
  assert.equal(p.data.billingStep, 2)
  assert.ok(notices.includes('核价成功，请登记实际收款')); assert.ok(sections.includes('#collection-form'))
  p.setData({ payerName: '测试付款人', paidTime: '2026-10-13 20:00:00', evidenceKey: 'private-test-proof' }); await p.collect()
  assert.ok(notices.includes('已登记，请核对并确认收款')); assert.ok(sections.includes('#payment-records'))
  assert.equal(calls.filter(c => c.url.endsWith('/payments')).length, 1)
  assert.equal(calls.some(c => c.url.endsWith('/confirm')), false)
  assert.equal(p.data.detail.payments[0].paymentStatus, 'PENDING')
  assert.equal(p.data.billingStep, 3)
})
test('billing step resumes from server state and failed pricing does not advance', async () => {
  storage = new Map(); let current = { ...bill, billingStatus: 'PENDING_PRICE' }
  env(o => {
    if (o.url.endsWith('/getInfo')) return o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } })
    if (o.method === 'PUT') return o.success({ statusCode: 200, data: { code: 500, msg: '核价失败' } })
    ok(o, current)
  }, 'admin')
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  assert.equal(p.data.billingStep, 1); await p.savePrice(); assert.equal(p.data.billingStep, 1)
  for (const [record, expected] of [
    [bill, 2],
    [{ ...bill, payments: [{ paymentId: '91', paymentStatus: 'PENDING' }] }, 3],
    [{ ...bill, payments: [{ paymentId: '91', paymentStatus: 'UNKNOWN' }] }, 3],
    [{ ...bill, payments: [{ paymentId: '91', paymentStatus: 'SUCCESS' }] }, 4],
    [{ ...bill, billingStatus: 'HISTORY_PENDING' }, 0],
    [{ ...bill, billingStatus: 'CLOSED' }, 0]
  ]) {
    current = record; await p.onShow(); assert.equal(p.data.billingStep, expected)
  }
  current = bill; await p.onShow(); p.editPrice(); assert.equal(p.data.billingStep, 1)
})
test('fee waived bill cannot produce a cash refund application', async () => {
  storage = new Map(); env(o => o.url.endsWith('/refunds') ? o.success({ statusCode: 200, data: { code: 200, rows: [], total: 0 } }) : ok(o, { ...bill, payableAmount: '0.00', refundableAmount: '0.00', payments: [{ paymentId: '9', channel: 'FREE', amount: '0.00', paymentStatus: 'SUCCESS' }] }))
  const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ amount: '1.00', reason: '退课' }); await p.apply(); assert.equal(calls.some(c => c.method === 'POST'), false)
})
test('home hides parent payment entry while retaining coupons, shop and admin finance', async () => {
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: app.globalData.roles, permissions: ['system:tuition:list'] } }) : ok(o, {})); const p = page('index')
  await p.applyLoginRole(); assert.equal(p.data.quickActions.some(a => a.route === '/pages/tuition-list/tuition-list'), false); assert.ok(p.data.quickActions.some(a => a.route === '/pages/my-coupons/my-coupons')); assert.ok(p.data.quickActions.some(a => a.route === '/pages/material/material'))
  app.globalData.roles = ['admin']
  await p.applyLoginRole(); assert.ok(p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition')); p.onUnload()
})
test('enrollment detail always links to financial bill and normalizes cancelled Chinese status', () => {
  env(o => ok(o, {})); let navigated
  wx.navigateTo = o => { navigated = o.url }
  const p = page('enrollment'); const result = p.decorateEnrollments([{ enrollmentId: '9007199254740995', enrollmentStatus: '已取消' }]); assert.equal(result[0].statusText, '已取消')
  p.openTuition({ currentTarget: { dataset: { id: '9007199254740995' } } }); assert.equal(navigated, '/pages/tuition-detail/tuition-detail?id=9007199254740995')
})
test('only an unpaid current bill may cancel enrollment; paid, history and unknown are guarded', async () => {
  storage = new Map(); let current = bill
  env(o => ok(o, current))
  const p = page('tuition-detail'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); await p.cancelEnrollment()
  assert.ok(calls.some(c => c.method === 'DELETE' && c.url.endsWith('/miniapp/parent/enrollment/9007199254740995')))
  for (const blocked of [{ ...bill, payStatus: '已支付', payments: [{ paymentId: '9', paymentStatus: 'SUCCESS' }] }, { ...bill, billingStatus: 'HISTORY_PENDING' }, { ...bill, payments: [{ paymentId: '9', paymentStatus: 'UNKNOWN' }] }]) {
    current = blocked; await p.onShow(); const before = calls.filter(c => c.method === 'DELETE').length; await p.cancelEnrollment(); assert.equal(calls.filter(c => c.method === 'DELETE').length, before)
  }
})
test('a superseded detail refresh cannot replace the newer financial state', async () => {
  storage = new Map(); const reads = []
  env(o => { if (o.url.endsWith('/quote')) ok(o, bill); else reads.push(o) })
  const p = page('tuition-detail'); p.onLoad({ id: bill.enrollmentId }); const first = p.onShow(); const newest = p.load()
  ok(reads[1], { ...bill, receivedAmount: '900.00', payments: [{ paymentId: '9', paymentStatus: 'SUCCESS' }] }); await newest; ok(reads[0], bill); await first
  assert.equal(p.data.detail.receivedText, '¥900.00'); assert.equal(p.data.detail.canPay, false)
})
test('refund return after abandoned modal resets submission without posting an old application', async () => {
  storage = new Map(); let modal
  env(o => o.url.endsWith('/refunds') ? o.success({ statusCode: 200, data: { code: 200, rows: [], total: 0 } }) : ok(o, { ...bill, canApplyRefund: true, payments: [{ paymentId: '9', channel: 'MOCK', paymentStatus: 'SUCCESS' }] })); wx.showModal = o => { modal = o }
  const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ reason: '原因' }); const applying = p.apply(); p.onHide(); await p.onShow(); modal.success({ confirm: true }); await applying
  assert.equal(p.data.submitting, false); assert.equal(calls.some(c => c.method === 'POST'), false)
})
test('admin returning after abandoned price modal resets busy state and rejects old mutation', async () => {
  storage = new Map(); let modal
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: ['admin'], permissions: ['*:*:*'] } }) : ok(o, bill), 'admin'); wx.showModal = o => { modal = o }
  const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ price: '100.00' }); const pricing = p.savePrice(); p.onHide(); await p.onShow(); modal.success({ confirm: true }); await pricing
  assert.equal(p.data.submitting, false); assert.equal(calls.some(c => c.method === 'PUT'), false)
})

test('parent detail has only read calls and preserves old unknown payment intent', async () => {
  storage = new Map(); env(o => ok(o, { ...bill, coupons: [{ userCouponId: '8', couponName: '课程券' }] }))
  helper().intent('payment', bill.enrollmentId, { channel: 'MOCK' })
  const p = page('tuition-detail'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  assert.equal(p.pay, undefined); assert.equal(p.confirmMock, undefined); assert.equal(p.resumePayment, undefined)
  assert.equal(calls.some(c => c.method !== 'GET'), false); assert.equal(p.data.coupons[0].couponName, '课程券')
  assert.ok(helper().savedIntent('payment', bill.enrollmentId))
})
test('parent refund eligibility uses server boolean; amounts only project after SUCCESS; payment label stays payment', () => {
  env(o => ok(o, {})); const t = helper()
  assert.equal(t.bill({ ...bill, refundableAmount: undefined, canApplyRefund: true }).canRefund, true)
  assert.equal(t.bill({ ...bill, refundableAmount: '900.00', canApplyRefund: false }).canRefund, false)
  for (const refundStatus of ['WAIT_EDU', 'WAIT_ADMIN', 'APPROVED']) {
    const r = t.refund({ refundStatus, requestedAmount: '800.00', approvedAmount: '300.00' })
    assert.equal(r.requestedText, ''); assert.equal(r.approvedText, ''); assert.equal(r.canCancel, false)
  }
  assert.equal(t.refund({ refundStatus: 'WAIT_EDU' }).statusText, '等待教务确认金额')
  assert.equal(t.refund({ refundStatus: 'WAIT_ADMIN' }).statusText, '教务已阅，等待管理员退款')
  assert.equal(t.refund({ refundStatus: 'SUCCESS', approvedAmount: '300.00' }).approvedText, '¥300.00')
  assert.equal(t.refund({ refundStatus: 'SUCCESS' }).statusText, '已退款')
  assert.equal(t.bill({ ...bill, payments: [{ paymentStatus: 'SUCCESS' }] }).payments[0].statusText, '已成功')
})
const target = { targetType: 'CLASS_SIGN_IN', targetId: '9007199254740993', label: '2026-10-10 数学 徐老师 已返0次', returnedUnits: 0 }
function refundEnv(post, targets = [target]) {
  env(o => {
    if (o.url.endsWith('/refund-targets')) ok(o, { rows: targets })
    else if (o.method === 'POST') post(o)
    else if (o.url.endsWith('/refunds')) o.success({ statusCode: 200, data: { code: 200, rows: [], total: 0 } })
    else ok(o, { ...bill, canApplyRefund: true, refundableAmount: undefined, payments: [{ paymentId: '9007199254740999', paymentStatus: 'SUCCESS', channel: 'CASH' }] })
  })
}
test('SESSION choice submits exact ID without amount and freezes retries across pages', async () => {
  storage = new Map(); let sent, posts = 0
  refundEnv(o => { posts++; if (!sent) { sent = structuredClone(o.data); o.fail({ errMsg: 'timeout' }) } else { assert.deepEqual(o.data, sent); ok(o, { refundId: '9' }) } })
  const first = page('tuition-refund'); first.onLoad({ id: bill.enrollmentId }); await first.onShow()
  await first.kind({ detail: { value: '2' } }); first.setData({ reason: '请假' }); await first.apply()
  assert.equal(posts, 1); assert.equal(sent.refundKind, 'SESSION'); assert.equal(sent.classSignInId, '9007199254740993')
  assert.equal(Object.hasOwn(sent, 'requestedAmount'), false); assert.equal(Object.hasOwn(sent, 'lessonBookingId'), false)
  const retry = page('tuition-refund'); retry.onLoad({ id: bill.enrollmentId }); await retry.onShow(); retry.setData({ reason: 'changed', kindIndex: 0 }); await retry.apply()
  assert.equal(posts, 2); assert.equal(storage.size, 0); assert.equal(retry.cancel, undefined)
})
test('failed, empty or invented SESSION targets cannot submit', async () => {
  for (const failure of ['failure', 'empty', 'invented']) {
    storage = new Map(); refundEnv(o => ok(o, { refundId: '9' }), failure === 'empty' ? [] : [target])
    const original = wx.request
    if (failure === 'failure') wx.request = o => o.url.endsWith('/refund-targets') ? o.fail({ errMsg: 'offline' }) : original(o)
    const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
    await p.kind({ detail: { value: '2' } }); p.setData({ reason: '请假', ...(failure === 'invented' ? { targetIndex: 9 } : {}) }); await p.apply()
    assert.equal(calls.some(c => c.method === 'POST'), false)
  }
})
test('legacy saved amount intent stays blocked with original key after unknown outcome', async () => {
  storage = new Map(); env(o => ok(o, {})); const saved = helper().intent('refund', bill.enrollmentId, { paymentId: '9', requestedAmount: '100.00', refundKind: 'PARTIAL', reason: 'old' })
  refundEnv(o => ok(o, { refundId: '9' })); const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); await p.apply()
  assert.equal(calls.some(c => c.method === 'POST'), false); assert.deepEqual(helper().savedIntent('refund', bill.enrollmentId), saved); assert.match(p.data.error, /历史.*教务/)
})
test('education needs actual menu permission, pure teacher and revoked role cannot collect', async () => {
  for (const [roles, permissions, expected] of [[['education'], ['system:tuition:query', 'system:tuition:price'], true], [['education'], [], false], [['teacher'], ['*:*:*'], false], [['parent'], ['*:*:*'], false]]) {
    storage = new Map(); env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles, permissions } }) : ok(o, bill), roles[0])
    const p = page('admin-tuition'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ price: '1200.00' }); await p.savePrice()
    assert.equal(calls.some(c => c.method === 'PUT'), expected)
  }
})
test('homepage real education role never becomes devRole or parent requests and still requires permissions', async () => {
  storage = new Map(); let permissions = ['system:tuition:list']
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: ['education'], permissions } }) : ok(o, {}), 'education')
  const p = page('index'); await p.applyLoginRole()
  assert.equal(p.data.currentRole, 'education'); assert.equal(p.data.roleLocked, true); assert.ok(p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition'))
  assert.equal(calls.some(c => c.url.includes('/miniapp/parent')), false)
  assert.equal(p.loginAsDevRole, undefined); assert.equal(calls.some(c => c.data.devRole === 'education'), false)
  permissions = []; await p.applyLoginRole(); assert.equal(p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition'), false)
})

test('unassigned education tab stays hidden and forged switch cannot request another login', async () => {
  for (const [role, permissions] of [['parent', ['*:*:*']], ['teacher', ['*:*:*']], ['admin', []]]) {
    storage = new Map(); env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles: [role], permissions } }) : ok(o, []), role)
    const notices = []; wx.showToast = o => notices.push(o.title)
    const p = page('index'); p.onLoad()
    try {
      await p.applyLoginRole()
      assert.deepEqual(p.data.roles.map(r => r.key), [role])
      await p.switchRole({ currentTarget: { dataset: { role: 'education' } } })
      assert.equal(p.data.currentRole, role)
      assert.equal(p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition'), false)
      assert.ok(notices.some(n => /授权|权限|登录/.test(n)))
      assert.equal(calls.some(c => c.url.endsWith('/miniapp/login')), false)
      assert.equal(wx.getStorageSync('token'), 'jwt')
    } finally { p.onUnload() }
  }
})

test('admin education view preserves identity, survives refresh and checks revoked authority', async () => {
  storage = new Map(); let roles = ['admin', 'education'], permissions = ['*:*:*']
  env(o => o.url.endsWith('/getInfo') ? o.success({ statusCode: 200, data: { code: 200, roles, permissions } }) : ok(o, []), 'admin')
  const p = page('index'); p.onLoad()
  try {
    await p.applyLoginRole(); await p.switchRole({ currentTarget: { dataset: { role: 'education' } } })
    assert.equal(p.data.currentRole, 'education'); assert.equal(p.data.currentRoleName, '教务')
    assert.ok(p.data.roles.some(r => r.key === 'education' && r.className === 'active'))
    assert.ok(p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition'))
    assert.deepEqual(app.globalData.roles, ['admin', 'education']); assert.equal(wx.getStorageSync('token'), 'jwt')
    await p.applyLoginRole(); assert.equal(p.data.currentRole, 'education')
    await p.switchRole({ currentTarget: { dataset: { role: 'admin' } } }); assert.equal(p.data.currentRole, 'admin')
    assert.equal(calls.some(c => c.url.endsWith('/miniapp/login')), false)
    await p.switchRole({ currentTarget: { dataset: { role: 'education' } } })
    roles = ['parent']; await p.applyLoginRole()
    assert.equal(p.data.currentRole, 'parent')
    assert.equal(p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition'), false)
  } finally { p.onUnload() }
})

test('abandoned SESSION query cannot populate a different refund scope', async () => {
  storage = new Map(); refundEnv(o => ok(o, { refundId: '9' })); let pending
  const original = wx.request; wx.request = o => o.url.endsWith('/refund-targets') ? (pending = o) : original(o)
  const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow()
  const changing = p.kind({ detail: { value: '2' } }); await p.kind({ detail: { value: '0' } })
  assert.equal(p.data.targetsLoading, false)
  ok(pending, { rows: [target] }); await changing; assert.equal(p.data.targets.length, 0)
})
test('malformed application success preserves immutable original intent and duplicate click posts once', async () => {
  storage = new Map(); let pending
  refundEnv(o => { pending = o })
  const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ reason: '费用调整' })
  const first = p.apply(); await new Promise(resolve => setImmediate(resolve)); await p.apply()
  assert.equal(calls.filter(c => c.method === 'POST').length, 1)
  ok(pending, {}); await first; assert.equal(p.data.retrying, true)
  const saved = helper().savedIntent('refund', bill.enrollmentId)
  assert.equal(saved.refundKind, 'PARTIAL'); assert.equal(Object.hasOwn(saved, 'requestedAmount'), false)
})

test('whitespace application ID preserves miniapp reason and original retry intent', async () => {
  storage = new Map(); const sent = []
  refundEnv(o => { sent.push(structuredClone(o.data)); ok(o, { refundId: sent.length === 1 ? ' \t\n' : '9' }) })
  const p = page('tuition-refund'); p.onLoad({ id: bill.enrollmentId }); await p.onShow(); p.setData({ reason: '费用调整' })
  await p.apply()
  assert.equal(p.data.retrying, true); assert.equal(p.data.reason, '费用调整'); assert.match(p.data.error, /结果尚未确定/)
  assert.deepEqual(helper().savedIntent('refund', bill.enrollmentId), sent[0])
  p.setData({ reason: 'changed', kindIndex: 1 }); await p.apply()
  assert.equal(sent.length, 2); assert.deepEqual(sent[1], sent[0]); assert.equal(storage.size, 0); assert.equal(p.data.retrying, false)
})

test('legacy completed refund explains history instead of claiming new two-stage progress', () => {
  env(o => ok(o, {})); const t = helper()
  for (const refundWorkflow of ['LEGACY', undefined]) {
    const r = t.refund({ refundStatus: 'SUCCESS', approvedAmount: '100.00', refundWorkflow })
    assert.match(r.progressText || r.workflowText, /历史流程.*管理员/)
  }
  assert.equal(t.refund({ refundStatus: 'SUCCESS', refundWorkflow: 'OFFLINE_TWO_STAGE' }).progressText, '线下退款已登记完成。')
})
