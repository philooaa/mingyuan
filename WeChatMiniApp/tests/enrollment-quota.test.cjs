const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')

function harness(request = async () => ({ data: [] })) {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }
  const wx = { getStorageSync: (k) => storage[k], navigateTo() {} }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/enrollment/enrollment.js'), 'utf8'), {
    Page: (p) => { definition = p }, wx, console,
    require: (id) => id.includes('course-term-filter') ? require('../utils/course-term-filter')
      : id.includes('child-context') ? require('./helpers/child-context.cjs')(wx)
      : id.includes('personal-account') ? { requestKey: () => '' }
      : { request }
  })
  const p = { ...definition, data: structuredClone(definition.data), _token: storage.token, _unloaded: false, setData(v) { Object.assign(this.data, v) } }
  p.data.subjects = []; p.data.grades = []
  return p
}

const course = (extra = {}) => ({
  scheduleId: 1, classMode: '1', classDate: null, enrolledCount: 0, enrolled: 0,
  courseClassName: '幼小衔接语文许叶子', subjectName: '语文', gradeName: '一年级', ...extra
})

test('课程列表携带当前孩子身份，已报名孩子被拦截而兄弟姐妹可打开报名', async () => {
  const requests = []
  const p = harness(async options => {
    if (options.url !== '/miniapp/parent/schedules') return { data: [] }
    requests.push(options)
    return { data: [course({ enrolled: options.data.accountId === '11' ? 1 : 0, enrollLimit: 20, enrolledCount: 1 })] }
  })
  p.data.lessonAccounts = [{ accountId: '11', studentName: '甲' }, { accountId: '22', studentName: '乙' }]
  p.data.scopeAccountId = '11'
  await p.loadSchedules()
  assert.equal(requests[0].data.accountId, '11')
  assert.equal(p.data.schedules[0].canEnroll, false)
  assert.equal(p.data.schedules[0].statusText, '已报名')
  p.data.scopeAccountId = '22'
  await p.loadSchedules()
  assert.equal(requests[1].data.accountId, '22')
  assert.equal(p.data.schedules[0].canEnroll, true)
  p.openEnroll({ currentTarget: { dataset: { key: '1:class' } } })
  assert.equal(p.data.showSheet, true)
  assert.equal(p.data.selectedChildId, '22')
  assert.equal(p.data.form.studentName, '乙')
})

test('课程查询随孩子上下文切换，拒绝旧孩子的显式查询并使旧响应失效', () => {
  const store = { roles: ['parent'], userInfo: { userId: 1 }, token: 'parent' }
  const context = require('./helpers/child-context.cjs')({ getStorageSync: k => store[k], setStorageSync: (k, v) => { store[k] = v } })
  context.select({ accountId: '11' })
  const first = context.prepare({ url: '/miniapp/parent/schedules' })
  assert.equal(first.options.data.accountId, '11')
  context.select({ accountId: '22' })
  assert.equal(context.current(first.ticket), false)
  assert.throws(() => context.prepare({ url: '/miniapp/parent/schedules', data: { accountId: '11' } }), /当前孩子已切换/)
  assert.equal(context.prepare({ url: '/miniapp/parent/schedules' }).options.data.accountId, '22')
})

test('未设置报名上限时显示剩余名额充足', () => {
  const p = harness()
  for (const enrollLimit of [null, undefined, 0, -1]) {
    const [row] = p.decorateSchedules([course({ enrollLimit, enrolledCount: 2 })])
    assert.equal(row.countText, '剩余名额：充足')
    assert.equal(row.canEnroll, true)
    assert.equal(row.statusText, '报名')
  }
})

test('设置了报名上限时显示剩余名额并随报名人数递减', () => {
  const p = harness()
  const [row] = p.decorateSchedules([course({ enrollLimit: 20, enrolledCount: 2 })])
  assert.equal(row.countText, '剩余名额：18 人')
  assert.equal(row.canEnroll, true)
})

test('名额已满时剩余为 0 人且不可再报名', () => {
  const p = harness()
  const [row] = p.decorateSchedules([course({ enrollLimit: 2, enrolledCount: 2 })])
  assert.equal(row.countText, '剩余名额：0 人')
  assert.equal(row.canEnroll, false)
  assert.equal(row.statusText, '已满')
  assert.equal(row.statusClass, 'disabled')
})

test('已报名的课程仍标记已报名且不可重复报名', () => {
  const p = harness()
  const [row] = p.decorateSchedules([course({ enrollLimit: 20, enrolledCount: 3, enrolled: 1 })])
  assert.equal(row.countText, '剩余名额：17 人')
  assert.equal(row.statusText, '已报名')
  assert.equal(row.canEnroll, false)
})

test('一对一单次课不受班课名额上限影响，仍按单节课自身状态判断', () => {
  const p = harness()
  const [open] = p.decorateSchedules([course({ classMode: '2', classDate: '2030-10-07', enrollLimit: 1, enrolledCount: 1, canEnroll: true, statusText: '报名' })])
  assert.equal(open.canEnroll, true)
  const [closed] = p.decorateSchedules([course({ classMode: '2', classDate: '2030-10-07', enrollLimit: 1, enrolledCount: 1, canEnroll: false, statusText: '已满' })])
  assert.equal(closed.canEnroll, false)
})

test('报名页不再出现已报名 N 人的文案，后端会下发 enrollLimit', () => {
  const js = fs.readFileSync(path.join(__dirname, '../pages/enrollment/enrollment.js'), 'utf8')
  assert.equal(/已报名 \$\{/.test(js), false)
  assert.match(js, /剩余名额：充足/)
  const xml = fs.readFileSync(path.join(__dirname, '../..', 'RuoYi-Vue-master/ruoyi-system/src/main/resources/mapper/system/MiniAppParentMapper.xml'), 'utf8')
  assert.match(xml, /s\.enroll_limit as enrollLimit/)
})
