const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const root = path.resolve(__dirname, '..')
const record = { refundId: '9007199254740999', enrollmentId: '9007199254740998', refundNo: 'TF-test', studentName: '测试学生', refundWorkflow: 'OFFLINE_TWO_STAGE', refundStatus: 'WAIT_EDU', refundKind: 'PARTIAL', paymentAmount: '1000.00', availableForThisRefund: '800.00', version: 3, reason: '费用调整' }
function setup(roles = ['education'], permissions = ['*:*:*'], custom) {
  let definition, current = structuredClone(record)
  const store = new Map([['token', 'token-a']]), app = { globalData: { roles, permissions, userInfo: { userId: '41' } } }
  const calls = [], routes = [], notices = [], cache = new Map()
  const wx = { getStorageSync: k => store.get(k), showToast: o => notices.push(o.title), reLaunch: o => routes.push(o.url), navigateTo: o => routes.push(o.url), stopPullDownRefresh() {}, showModal: o => o.success({ confirm: true }) }
  const request = async o => {
    calls.push(structuredClone(o))
    if (custom) { const result = custom(o); if (result !== undefined) return result }
    if (o.url === '/getInfo') return { roles: app.globalData.roles, permissions: app.globalData.permissions }
    if (o.method === 'POST') { current = { ...current, version: current.version + 1, refundStatus: o.url.endsWith('/review') ? o.data.approved ? 'WAIT_ADMIN' : 'REJECTED' : 'SUCCESS', ...(o.data.approved ? { approvedAmount: o.data.approvedAmount } : {}) }; return { data: current } }
    return o.url.endsWith('/list') ? { rows: [current], total: 1 } : { data: current }
  }
  function load(file) {
    if (file.endsWith('/utils/request.js')) return { request }
    if (cache.has(file)) return cache.get(file)
    const module = { exports: {} }
    assert.ok(fs.existsSync(file), 'staff refund handling page is implemented')
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, Page: p => { definition = p }, getApp: () => app, wx, console, require: id => load(path.resolve(path.dirname(file), id + '.js')) }, { filename: file })
    cache.set(file, module.exports); return module.exports
  }
  load(path.join(root, 'pages/admin-tuition-refunds/admin-tuition-refunds.js'))
  const p = { ...definition, data: structuredClone(definition.data), setData(v) { Object.assign(this.data, v) } }
  p.onLoad({ id: record.refundId })
  return { p, app, calls, wx, store, routes, notices, setRecord: value => { current = { ...current, ...value } } }
}
const action = value => ({ currentTarget: { dataset: { action: value } } })
test('education confirms amount through the existing review API without completing the refund', async () => {
  const h = setup(); await h.p.onShow()
  assert.equal(h.p.data.allowed.review, true); assert.equal(h.p.data.allowed.markRefunded, false)
  h.p.setData({ approvedAmount: '120.5', reviewRemark: '按请假情况核定' }); await h.p.review(action('approve'))
  const post = h.calls.find(o => o.method === 'POST')
  assert.equal(post.url, '/system/tuition/refunds/' + record.refundId + '/review')
  assert.deepEqual(post.data, { approved: true, approvedAmount: '120.50', reviewRemark: '按请假情况核定', version: 3 })
  assert.equal(h.p.data.detail.refundStatus, 'WAIT_ADMIN')
  assert.equal(h.calls.some(o => o.url.endsWith('/mark-refunded')), false)
})
test('administrator must complete two separate steps and final registration never submits a different amount', async () => {
  const h = setup(['admin']); await h.p.onShow(); await h.p.markRefunded()
  assert.equal(h.calls.filter(o => o.method === 'POST').length, 0)
  h.p.setData({ approvedAmount: '150' }); await h.p.review(action('approve')); await h.p.markRefunded()
  const post = h.calls.filter(o => o.method === 'POST')
  assert.equal(post.length, 2); assert.equal(post[1].url.endsWith('/mark-refunded'), true)
  assert.deepEqual(post[1].data, { version: 4 }); assert.equal(h.p.data.detail.refundStatus, 'SUCCESS')
})
test('teacher, parent and staff without menu authority cannot read or mutate refunds', async () => {
  for (const roles of [['teacher'], ['parent'], ['education']]) {
    const h = setup(roles, []); await h.p.onShow(); h.p.setData({ approvedAmount: '10' }); await h.p.review(action('approve')); await h.p.markRefunded()
    assert.equal(h.calls.some(o => o.url.startsWith('/system/')), false)
  }
  const h = setup(['education'], ['system:tuitionRefund:query']); h.setRecord({ refundStatus: 'WAIT_ADMIN', approvedAmount: '100.00' }); await h.p.onShow(); await h.p.markRefunded()
  assert.equal(h.calls.some(o => o.method === 'POST'), false)
})
test('review rejects zero, excessive precision, excessive amounts and rejection without a reason', async () => {
  const h = setup(); await h.p.onShow()
  for (const approvedAmount of ['0', '1.001', '800.01']) { h.p.setData({ approvedAmount }); await h.p.review(action('approve')) }
  await h.p.review(action('reject')); assert.equal(h.calls.some(o => o.method === 'POST'), false)
  h.p.setData({ reviewRemark: '不符合退费条件' }); await h.p.review(action('reject'))
  assert.equal(h.p.data.detail.refundStatus, 'REJECTED')
})
test('leaving or changing login during confirmation prevents a stale refund mutation', async () => {
  for (const leave of [h => h.p.onHide(), h => h.store.set('token', 'token-b')]) {
    const h = setup(); await h.p.onShow(); h.p.setData({ approvedAmount: '100' })
    let modal; h.wx.showModal = o => { modal = o }
    const pending = h.p.review(action('approve')); await new Promise(resolve => setImmediate(resolve)); assert.ok(modal)
    leave(h); modal.success({ confirm: true }); await pending
    assert.equal(h.calls.some(o => o.method === 'POST'), false)
  }
})
test('legacy records remain read-only and a revoked review permission cannot write', async () => {
  const h = setup(); h.setRecord({ refundWorkflow: 'LEGACY', refundStatus: 'PENDING_REVIEW' }); await h.p.onShow(); h.p.setData({ approvedAmount: '100' }); await h.p.review(action('approve')); assert.equal(h.calls.some(o => o.method === 'POST'), false)
  h.setRecord({ refundWorkflow: 'OFFLINE_TWO_STAGE', refundStatus: 'WAIT_EDU' }); await h.p.load(); h.app.globalData.permissions = []; await h.p.review(action('approve')); assert.equal(h.calls.some(o => o.method === 'POST'), false)
})
test('administrator with review permission but no payment-registration permission may reject the second stage', async () => {
  const h = setup(['admin'], ['system:tuitionRefund:query', 'system:tuitionRefund:review'])
  h.setRecord({ refundStatus: 'WAIT_ADMIN', approvedAmount: '100.00' }); await h.p.onShow()
  assert.equal(h.p.data.allowed.markRefunded, false); assert.equal(h.p.data.allowed.adminReject, true)
  h.p.setData({ reviewRemark: '需要重新核对退费依据' }); await h.p.review(action('reject'))
  assert.deepEqual(h.calls.find(o => o.method === 'POST').data, { approved: false, approvedAmount: null, reviewRemark: '需要重新核对退费依据', version: 3 })
})
test('refreshing permissions before submit stops an authority revoked since opening the page', async () => {
  let revoked = false
  const h = setup(['education'], ['*:*:*'], o => o.url === '/getInfo' && revoked ? { roles: ['education'], permissions: ['system:tuitionRefund:query'] } : undefined)
  await h.p.onShow(); revoked = true; h.p.setData({ approvedAmount: '100' }); await h.p.review(action('approve'))
  assert.equal(h.calls.some(o => o.method === 'POST'), false); assert.equal(h.p.data.allowed.review, false)
})
test('refund list uses existing filters and keeps large IDs intact for detail navigation', async () => {
  const h = setup(); h.p.onLoad({ enrollmentId: record.enrollmentId }); await h.p.onShow()
  await h.p.filter({ detail: { value: 1 } })
  const list = h.calls.filter(o => o.url.endsWith('/list')).at(-1)
  assert.equal(list.data.refundStatus, 'WAIT_EDU'); assert.equal(list.data.enrollmentId, record.enrollmentId)
  h.p.openDetail({ currentTarget: { dataset: { id: record.refundId } } })
  assert.equal(h.routes.at(-1), '/pages/admin-tuition-refunds/admin-tuition-refunds?id=' + record.refundId)
})
