const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')

function harness() {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }
  const wx = { getStorageSync: (k) => storage[k], navigateTo() {} }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/enrollment/enrollment.js'), 'utf8'), {
    Page: (p) => { definition = p }, wx, console,
    require: (id) => id.includes('course-term-filter') ? require('../utils/course-term-filter')
      : id.includes('child-context') ? require('./helpers/child-context.cjs')(wx)
      : id.includes('personal-account') ? { requestKey: () => '' }
      : { request: async () => ({ data: [] }) }
  })
  const p = { ...definition, data: structuredClone(definition.data), _token: storage.token, _unloaded: false, setData(v) { Object.assign(this.data, v) } }
  p.data.subjects = []; p.data.grades = []
  return p
}

const course = (extra = {}) => ({
  scheduleId: 1, classMode: '1', classDate: null, enrolledCount: 0, enrolled: 0,
  courseClassName: '幼小衔接语文许叶子', subjectName: '语文', gradeName: '一年级', ...extra
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
