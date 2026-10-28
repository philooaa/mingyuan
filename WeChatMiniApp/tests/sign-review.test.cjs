const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const root = path.resolve(__dirname, '..')
const sheet = { teacherName: '任课教师', timeSlot: '10:00-12:00', submittedBy: 'teacher-test', submittedTime: '2026-10-19 12:00', submissionVersion: 'sheet-v1', stats: { totalCount: 5, actualCount: 3, attended: 1, recorded: 1, leave: 1, audited: 1, transferred: 1 }, review: { reviewStatus: '0', confirmedCount: 0 }, details: [1, 2, 3, 4, 5].map(n => ({ signInId: String(n), studentName: '学员' + n, signStatus: String(n), sourceType: '1', teacherName: '任课教师', courseClassName: '测试班' })) }
function harness(name, roles = ['education'], custom) {
  let definition, currentSheet = structuredClone(sheet)
  const store = new Map([['token', 'a']]), app = { globalData: { roles, userInfo: { userId: '41' }, permissions: [], baseUrl: 'http://localhost' } }
  const calls = [], notices = [], cache = new Map()
  const wx = { getStorageSync: k => store.get(k), showToast: o => notices.push(o.title), showModal: o => o.success({ confirm: true }), stopPullDownRefresh() {}, reLaunch() {}, navigateTo() {} }
  const request = async o => {
    calls.push(structuredClone(o)); if (custom) { const value = custom(o); if (value !== undefined) return value }
    if (o.url === '/getInfo') return { roles: app.globalData.roles, permissions: [] }
    if (o.url.endsWith('/list')) return { data: [{ scheduleId: '9007199254740999', classDate: '2026-10-19', reviewStatus: '0', teacherName: '任课教师', totalCount: 5, actualCount: 3 }] }
    if (o.method === 'POST') { currentSheet.review = { reviewStatus: '1', review1AdminName: '教务甲', review1Time: '2026-10-19 12:05' }; return { reviewStatus: '1', requiredConfirmations: 1 } }
    return { data: currentSheet }
  }
  function load(file) {
    if (file.endsWith('/utils/request.js')) return { request }
    if (cache.has(file)) return cache.get(file)
    const module = { exports: {} }
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, Page: p => { definition = p }, require: id => load(path.resolve(path.dirname(file), id + '.js')), getApp: () => app, wx, console }, { filename: file })
    cache.set(file, module.exports); return module.exports
  }
  load(path.join(root, 'pages', name, name + '.js'))
  const p = { ...definition, data: structuredClone(definition.data), setData(v) { Object.assign(this.data, v) } }
  return { p, app, calls, notices, store, wx, setSheet: value => { currentSheet = value } }
}
async function open(h) { h.p.onLoad?.({ scheduleId: '9007199254740999', classDate: '2026-10-19' }); await h.p.onShow?.(); await new Promise(r => setImmediate(r)) }
test('education can see pending roll-call sheets through the shared education endpoint', async () => {
  const h = harness('sign-review'); await h.p.onShow()
  assert.ok(h.calls.some(o => o.url === '/miniapp/education/sign/list')); assert.equal(h.p.data.list[0].reviewText, '待教务确认')
})
test('review detail retains teacher, server statistics and large schedule identity', async () => {
  const h = harness('sign-review-detail'); await open(h)
  assert.equal(h.p.data.scheduleId, '9007199254740999'); assert.equal(h.p.data.teacherName, '任课教师')
  assert.equal(h.p.data.stats.actualCount, 3); assert.equal(h.p.data.stats.leave, 1); assert.equal(h.p.data.submissionVersion, 'sheet-v1')
  assert.equal(h.p.data.canReview, true)
})
test('exception sheets show the reason, explicit absence and allow only staff correction', async () => {
  const h = harness('sign-review-detail')
  const d = structuredClone(sheet); d.review = { reviewStatus: '2', reviewReason: '学生到课记录有误' }
  d.details[0].signStatus = '6'; d.details[0].reviewHistory = '[{"action":"REJECT","reason":"学生到课记录有误"}]'
  h.setSheet(d); await open(h)
  assert.equal(h.p.data.rows[0].statusText, '未到课')
  assert.equal(h.p.data.review.reviewReason, '学生到课记录有误')
  assert.equal(h.p.data.canCorrect, true); assert.equal(h.p.data.canReview, false)
})
test('reject requires a reason and sends the inspected version without approving', async () => {
  const h = harness('sign-review-detail', ['education'], o => o.url.endsWith('/reject') ? { code: 200 } : undefined)
  await open(h); await h.p.rejectReview(); assert.equal(h.calls.some(o => o.method === 'POST'), false)
  h.p.setData({ reason: '漏记未到课' }); await h.p.rejectReview()
  const post = h.calls.find(o => o.method === 'POST')
  assert.equal(post.url, '/miniapp/education/sign/reject'); assert.equal(post.data.reason, '漏记未到课'); assert.equal(post.data.submissionVersion, 'sheet-v1')
})
test('different students keep independent draft states and reasons until one batch save', async () => {
  const h = harness('sign-review-detail', ['education'], o => o.url.endsWith('/correct') ? { code: 200 } : undefined)
  const d = structuredClone(sheet); d.review.reviewStatus = '2'; h.setSheet(d); await open(h)
  h.p.chooseCorrection({ currentTarget: { dataset: { id: '1' } }, detail: { value: 2 } })
  h.p.onCorrectionReason({ currentTarget: { dataset: { id: '1' } }, detail: { value: '家长已请假' } })
  h.p.chooseCorrection({ currentTarget: { dataset: { id: '2' } }, detail: { value: 3 } })
  h.p.onCorrectionReason({ currentTarget: { dataset: { id: '2' } }, detail: { value: '核实未到课' } })
  assert.equal(h.p.data.pendingCount, 2)
  assert.equal(h.p.data.rows[0].pendingStatus, '3'); assert.equal(h.p.data.rows[0].correctionReason, '家长已请假')
  assert.equal(h.p.data.rows[1].pendingStatus, '6'); assert.equal(h.p.data.rows[1].correctionReason, '核实未到课')
  await h.p.correctAttendance()
  const posts = h.calls.filter(o => o.method === 'POST'); assert.equal(posts.length, 1)
  assert.deepEqual(posts[0].data.corrections, [{ signInId: '1', signStatus: '3', reason: '家长已请假' }, { signInId: '2', signStatus: '6', reason: '核实未到课' }])
})
test('unsaved drafts block final approval even after an earlier saved correction', async () => {
  const h = harness('sign-review-detail'), d = structuredClone(sheet); d.review.reviewStatus = '2'
  d.details[0].reviewHistory = '[{"action":"CORRECT","beforeStatus":"1","afterStatus":"6"}]'
  h.setSheet(d); await open(h); assert.equal(h.p.data.canReview, true)
  h.p.chooseCorrection({ currentTarget: { dataset: { id: '2' } }, detail: { value: 3 } })
  await h.p.confirmReview(); assert.equal(h.calls.some(o => o.method === 'POST'), false)
  assert.equal(h.p.data.canReview, false)
  h.p.chooseCorrection({ currentTarget: { dataset: { id: '2' } }, detail: { value: 1 } })
  assert.equal(h.p.data.pendingCount, 0); assert.equal(h.p.data.canReview, true)
})
test('missing reasons stop the batch locally and a definite rejection preserves every draft', async () => {
  const h = harness('sign-review-detail', ['education'], o => { if (o.url.endsWith('/correct')) { const error = new Error('出勤版本已变化'); error.uncertain = false; throw error } })
  const d = structuredClone(sheet); d.review.reviewStatus = '2'; h.setSheet(d); await open(h)
  for (const id of ['1', '2']) h.p.chooseCorrection({ currentTarget: { dataset: { id } }, detail: { value: 3 } })
  h.p.onCorrectionReason({ currentTarget: { dataset: { id: '1' } }, detail: { value: '核实未到课' } })
  await h.p.correctAttendance(); assert.equal(h.calls.some(o => o.method === 'POST'), false)
  h.p.onCorrectionReason({ currentTarget: { dataset: { id: '2' } }, detail: { value: '学生未到场' } })
  await h.p.correctAttendance()
  assert.equal(h.p.data.pendingCount, 2); assert.equal(h.p.data.rows[0].correctionReason, '核实未到课'); assert.equal(h.p.data.rows[1].correctionReason, '学生未到场')
  assert.equal(h.p.data.canReview, false); assert.match(h.p.data.error, /版本已变化/)
})
test('staff correction carries stable student identity and reason, then refreshes the sheet', async () => {
  const h = harness('sign-review-detail', ['admin'], o => o.url.endsWith('/correct') ? { code: 200 } : undefined)
  const d = structuredClone(sheet); d.review.reviewStatus = '2'; h.setSheet(d); await open(h)
  h.p.chooseCorrection({ currentTarget: { dataset: { id: '1' } }, detail: { value: 3 } })
  h.p.onCorrectionReason({ currentTarget: { dataset: { id: '1' } }, detail: { value: '已核实未到课' } }); await h.p.correctAttendance()
  const post = h.calls.find(o => o.method === 'POST')
  assert.equal(post.url, '/miniapp/education/sign/correct'); assert.equal(post.data.corrections[0].signInId, '1'); assert.equal(post.data.corrections[0].signStatus, '6')
  assert.equal(post.data.corrections[0].reason, '已核实未到课'); assert.ok(h.calls.filter(o => o.url.endsWith('/detail')).length >= 2)
})
test('a corrected exception can be confirmed while a switched login cannot post corrections', async () => {
  const h = harness('sign-review-detail'); const d = structuredClone(sheet); d.review.reviewStatus = '2'
  d.details[0].reviewHistory = '[{"action":"REJECT"},{"action":"CORRECT","beforeStatus":"1","afterStatus":"6"}]'
  h.setSheet(d); await open(h); assert.equal(h.p.data.canReview, true)
  h.p.chooseCorrection({ currentTarget: { dataset: { id: '1' } }, detail: { value: 3 } }); h.p.onCorrectionReason({ currentTarget: { dataset: { id: '1' } }, detail: { value: '核实' } })
  let modal; h.wx.showModal = o => { modal = o }; const work = h.p.correctAttendance(); await new Promise(r => setImmediate(r))
  h.store.set('token', 'changed'); modal.success({ confirm: true }); await work
  assert.equal(h.calls.some(o => o.method === 'POST'), false)
})
test('one confirmation submits only the inspected sheet version and becomes read-only', async () => {
  const h = harness('sign-review-detail'); await open(h); await h.p.confirmReview(); await h.p.confirmReview()
  const posts = h.calls.filter(o => o.method === 'POST'); assert.equal(posts.length, 1)
  assert.deepEqual(posts[0].data, { scheduleId: '9007199254740999', classDate: '2026-10-19', submissionVersion: 'sheet-v1', images: [] })
  assert.equal(h.p.data.review.reviewStatus, '1'); assert.equal(h.p.data.canReview, false)
})
test('teacher or parent cannot read or confirm education sheets by manually opening the route', async () => {
  for (const role of ['teacher', 'parent']) {
    const h = harness('sign-review-detail', [role]); await open(h); await h.p.confirmReview()
    assert.equal(h.calls.some(o => o.url.includes('/sign/')), false); assert.equal(h.p.data.rows.length, 0)
  }
})
test('leaving or switching login during the confirmation dialog never posts stale approval', async () => {
  for (const change of [h => h.p.onHide(), h => h.store.set('token', 'b')]) {
    const h = harness('sign-review-detail'); await open(h); let modal; h.wx.showModal = o => { modal = o }
    const work = h.p.confirmReview(); await new Promise(r => setImmediate(r)); assert.ok(modal)
    change(h); modal.success({ confirm: true }); await work; assert.equal(h.calls.some(o => o.method === 'POST'), false)
  }
})
test('list date changes reject stale responses and failures offer retry without an empty result', async () => {
  const pending = [], h = harness('sign-review', ['education'], o => o.url.endsWith('/list') ? new Promise(resolve => pending.push(resolve)) : undefined)
  h.p._active = true; h.p._visit = 1; h.p._token = 'a'
  const first = h.p.loadList(); await new Promise(r => setImmediate(r))
  h.p.setData({ filterDate: '2026-10-18' }); const second = h.p.loadList(); await new Promise(r => setImmediate(r))
  pending[1]({ data: [{ scheduleId: '2', classDate: '2026-10-18', reviewStatus: '2' }] }); await second
  pending[0]({ data: [{ scheduleId: '1', classDate: '2026-10-19', reviewStatus: '0' }] }); await first
  assert.equal(h.p.data.list[0].scheduleId, '2'); assert.match(h.p.data.list[0].reviewText, /审核异常/)
  const failed = harness('sign-review', ['education'], o => { if (o.url.endsWith('/list')) throw Error('连接失败') })
  await failed.p.onShow(); assert.equal(failed.p.data.error, '连接失败'); assert.equal(failed.p.data.showEmpty, false)
})
test('teacher exception sheets keep the exact occurrence and stay locked for staff correction', async () => {
  const h = harness('sign-in-edit', ['teacher']), d = structuredClone(sheet)
  d.review = { reviewStatus: '2', reviewReason: '出勤有误', reviewVersion: 7 }; h.setSheet(d)
  h.p.onLoad({ scheduleId: '9007199254740999', classDate: '2026-10-18' }); await new Promise(r => setImmediate(r))
  assert.equal(h.p.data.locked, true); assert.equal(h.p.data.scheduleId, '9007199254740999'); assert.equal(h.p.data.classDate, '2026-10-18')
  const source = fs.readFileSync(path.join(root, 'pages/sign-in-edit/sign-in-edit.js'), 'utf8')
  assert.match(source, /reviewVersion: this.data.review\?\.reviewVersion \|\| 0/)
})
test('parent attendance keeps attendance facts separate from the staff review status', () => {
  const { p } = harness('attendance', ['parent'])
  const row = p.decorateDetails([{ attendanceStatus: '1', reviewStatus: '2' }])[0]
  assert.equal(row.statusText, '已上课'); assert.equal(row.reviewText, '审核异常，教务核查中')
})
