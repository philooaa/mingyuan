const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const teachers = [{ teacherId: '11', teacherName: '甲老师' }, { teacherId: '12', teacherName: '乙老师' }, { teacherId: '13', teacherName: '丙老师' }]
const lesson = (id, teacher, subject = '数学', grade = '五年级', classMode = '2') => ({ scheduleId: String(id), ...teacher, subjectName: subject, gradeName: grade, classMode, classDate: classMode === '2' ? '2030-10-07' : null, canEnroll: true })
const courses = [lesson(1, teachers[0]), lesson(2, teachers[1]), lesson(3, teachers[2], '英语'), lesson(4, { teacherId: '14', teacherName: '班课老师' }, '数学', '五年级', '1'), lesson(5, teachers[2], '数学', '高一')]
function harness(respond) {
  let definition
  const storage = { token: 'parent-session', roles: ['parent'] }, calls = []
  const routes=[],wx = { getStorageSync: k => storage[k], navigateTo:o=>routes.push(o.url) }
  const request = async o => { calls.push(o); if(respond)return respond(o);return { data: o.url.endsWith('/single/lessons') ? courses.filter(c => c.classMode === '2').map(c => ({ ...c, lessons: [{ classDate: c.classDate, canEnroll: true }] })) : courses.filter(c => c.classMode === '1') } }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/enrollment/enrollment.js'), 'utf8'), {
    Page: p => { definition = p }, wx, console,
    require: id => id.includes('child-context') ? require('./helpers/child-context.cjs')(wx) : id.includes('personal-account') ? { requestKey: () => '' } : { request }
  })
  const p = { ...definition, data: structuredClone(definition.data), _token: storage.token, _unloaded: false, setData(v) { Object.assign(this.data, v) } }
  p.data.courseTypeIndex = 1; p.data.subjects = [{ dictLabel: '数学' }, { dictLabel: '英语' }]; p.data.grades = [{ dictLabel: '五年级' }, { dictLabel: '高一' }]
  return { p, storage, calls, routes }
}
test('booking balance and package shortcut use only the selected child and refresh on return',async()=>{
 let available=12
 const {p,routes}=harness(async()=>({data:[{accountId:'11',studentName:'甲',availableCredits:80},{accountId:'22',studentName:'乙',availableCredits:available,remainingCredits:15,reservedCredits:3,consumedCredits:5}]}))
 p.setData({scopeAccountId:'22',selectedChildId:'22'});await p.loadLessonAccounts()
 assert.equal(p.data.creditAccount.studentName,'乙');assert.equal(p.data.creditAccount.availableCredits,12)
 p.purchasePackage();assert.equal(routes.at(-1),'/pages/lesson-packages/lesson-packages?accountId=22')
 available=27;await p.onShow();assert.equal(p.data.creditAccount.availableCredits,27)
 p.setData({scopeAccountId:'999'});await p.loadLessonAccounts();assert.equal(p.data.creditAccount,null)
 p.purchasePackage();assert.equal(routes.length,1)
})
test('balance query failure clears stale balance and prevents purchasing against stale child data',async()=>{
 let fail=false;const {p,routes}=harness(async()=>{if(fail)throw Error('offline');return {data:[{accountId:'22',availableCredits:12}]}})
 p.setData({scopeAccountId:'22',selectedChildId:'22'});await p.loadLessonAccounts();fail=true;await p.loadLessonAccounts()
 assert.equal(p.data.creditAccount,null);p.purchasePackage();assert.equal(routes.length,0)
})
const change = values => ({ detail: { value: values } })
test('one-to-one teacher candidates follow course type, grade and subject before teacher selection', async () => {
  const { p } = harness(); await p.loadSchedules()
  assert.equal(p.data.teacherOptions.some(t => t.name === '班课老师'), false)
  p.onSubjectChange(change(1)); p.onGradeChange(change(1))
  assert.deepEqual(Array.from(p.data.teacherOptions, t => t.key).sort(), ['teacher:11', 'teacher:12'])
})
test('native checkbox changes select multiple teachers and show the union of their courses', async () => {
  const { p, calls } = harness(); await p.loadSchedules()
  p.chooseTeachers(change(['teacher:11', 'teacher:12']))
  assert.deepEqual(Array.from(p.data.selectedTeacherKeys).sort(), ['teacher:11', 'teacher:12'])
  assert.deepEqual(Array.from(p.data.teacherGroups, g => g.key).sort(), ['teacher:11', 'teacher:12'])
  assert.equal(calls.length, 2, 'teacher selection must not send booking requests')
})
test('search-based multi-select preserves hidden selected teachers and select-all adds only visible candidates', async () => {
  const { p } = harness(); await p.loadSchedules()
  p.chooseTeachers(change(['teacher:11']))
  p.searchTeacher(change('乙'))
  p.selectAllTeachers()
  assert.deepEqual(Array.from(p.data.selectedTeacherKeys).sort(), ['teacher:11', 'teacher:12'])
  assert.equal(p.data.allVisibleTeachersSelected, true)
  p.chooseTeachers(change([]))
  assert.deepEqual(Array.from(p.data.selectedTeacherKeys), ['teacher:11'])
  p.searchTeacher(change('')); assert.equal(p.data.teacherOptions.find(t => t.key === 'teacher:11').selected, true)
})
test('explicit select-all checks every eligible teacher while clear restores an unrestricted teacher filter', async () => {
  const { p } = harness(); await p.loadSchedules(); p.onSubjectChange(change(1)); p.onGradeChange(change(1))
  p.selectAllTeachers(); p.selectAllTeachers()
  assert.deepEqual(Array.from(p.data.selectedTeacherKeys).sort(), ['teacher:11', 'teacher:12'])
  assert.equal(p.data.teacherOptions.every(t => t.selected), true); assert.equal(p.data.allVisibleTeachersSelected, true)
  p.clearTeachers(); assert.equal(p.data.selectedTeacherKeys.length, 0); assert.equal(p.data.teacherGroups.length, 2)
  assert.equal(p.data.allVisibleTeachersSelected, false)
})
test('changing dimensions removes unavailable teachers, and stale sessions cannot change filters', async () => {
  const { p, storage } = harness(); await p.loadSchedules(); p.chooseTeachers(change(['teacher:11', 'teacher:12']))
  p.onSubjectChange(change(2))
  assert.equal(p.data.selectedTeacherKeys.length, 0); assert.deepEqual(Array.from(p.data.teacherOptions, t => t.key), ['teacher:13'])
  storage.token = 'different-parent'; p.selectAllTeachers(); p.chooseTeachers(change(['teacher:13']))
  assert.equal(p.data.selectedTeacherKeys.length, 0)
})
