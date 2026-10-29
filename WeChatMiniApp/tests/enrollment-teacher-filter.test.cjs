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
    require: id => id.includes('course-term-filter') ? require('../utils/course-term-filter') : id.includes('child-context') ? require('./helpers/child-context.cjs')(wx) : id.includes('personal-account') ? { requestKey: () => '' } : { request }
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
  assert.deepEqual(Array.from(p.data.schedules, c => c.teacherId).sort(), ['11', '12'])
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
  p.clearTeachers(); assert.equal(p.data.selectedTeacherKeys.length, 0); assert.equal(p.data.courseGroups.length, 1)
  assert.equal(p.data.allVisibleTeachersSelected, false)
})
test('changing dimensions removes unavailable teachers, and stale sessions cannot change filters', async () => {
  const { p, storage } = harness(); await p.loadSchedules(); p.chooseTeachers(change(['teacher:11', 'teacher:12']))
  p.onSubjectChange(change(2))
  assert.equal(p.data.selectedTeacherKeys.length, 0); assert.deepEqual(Array.from(p.data.teacherOptions, t => t.key), ['teacher:13'])
  storage.token = 'different-parent'; p.selectAllTeachers(); p.chooseTeachers(change(['teacher:13']))
  assert.equal(p.data.selectedTeacherKeys.length, 0)
})


test('a fixed grade groups by subject in dictionary order instead of teacher names', async () => {
  const { p } = harness(); await p.loadSchedules(); p.onGradeChange(change(1))
  assert.deepEqual(Array.from(p.data.courseGroups, g => g.label), ['数学', '英语'])
  assert.equal(p.data.sortHint, '按学科排列')
  assert.deepEqual(Array.from(p.data.courseGroups[0].courses, c => c.teacherId), ['11', '12'])
})
test('a fixed subject groups by grade and both filters still narrow to one grade', async () => {
  const { p } = harness(); await p.loadSchedules(); p.onSubjectChange(change(1))
  assert.deepEqual(Array.from(p.data.courseGroups, g => g.label), ['五年级', '高一'])
  assert.equal(p.data.sortHint, '按年级排列')
  p.onGradeChange(change(1))
  assert.deepEqual(Array.from(p.data.courseGroups, g => g.label), ['五年级'])
  assert.deepEqual(Array.from(p.data.schedules, c => c.scheduleId), ['1', '2'])
})
test('unfiltered courses are ordered by grade then subject and class enrollment uses the same grouping', async () => {
  const { p } = harness(); await p.loadSchedules()
  assert.deepEqual(Array.from(p.data.courseGroups, g => g.label), ['五年级', '高一'])
  assert.deepEqual(Array.from(p.data.courseGroups[0].courses, c => c.subjectName), ['数学', '数学', '英语'])
  p.onCourseTypeChange(change(0)); p.onGradeChange(change(1))
  assert.deepEqual(Array.from(p.data.courseGroups, g => g.label), ['数学'])
  assert.deepEqual(Array.from(p.data.schedules, c => c.scheduleId), ['4'])
})
test('subject grouping preserves one-to-one course dates and expansion', async () => {
  const { p } = harness(o => ({ data: o.url.endsWith('/single/lessons') ? [{ ...lesson(1, teachers[0]), lessons: [{ classDate: '2030-10-09', canEnroll: true }, { classDate: '2030-10-07', canEnroll: true }] }] : [] }))
  await p.loadSchedules(); p.onGradeChange(change(1))
  const course = p.data.courseGroups[0].courses[0]
  assert.equal(p.data.courseGroups[0].label, '数学'); assert.equal(course.lessonCount, 2)
  assert.deepEqual(Array.from(course.lessons, c => c.classDate), ['2030-10-07', '2030-10-09'])
  p.toggleCourseDates({ currentTarget: { dataset: { id: '1' } } })
  assert.equal(p.data.courseGroups[0].courses[0].expanded, true)
})

test('cart changes refresh grouped course cards without losing grade and subject sorting', async () => {
  const { p } = harness(); p.data.courseTypeIndex = 0
  await p.loadSchedules(); p.onGradeChange(change(1))
  p.setData({ cart: [{ scheduleId: '4', amount: '900.00' }] }); p.refreshCartSummary()
  assert.equal(p.data.courseGroups[0].label, '数学')
  assert.equal(p.data.courseGroups[0].courses[0].inCart, true)
  assert.equal(p.data.cartTotal, '900.00')
  p.setData({ cart: [] }); p.refreshCartSummary()
  assert.equal(p.data.courseGroups[0].courses[0].inCart, false)
  assert.equal(p.data.cartCount, 0)
})


test('parent enrollment dictionary includes preschool first and can filter its courses',async()=>{
 const preschool=lesson(91,teachers[0],'语文','幼小衔接','1'),primary=lesson(92,teachers[1],'数学','一年级','1')
 const {p}=harness(async o=>o.url.endsWith('/dict')?{data:{grades:[{dictLabel:'幼小衔接',dictValue:'g0'},{dictLabel:'一年级',dictValue:'g1'}],subjects:[{dictLabel:'语文'},{dictLabel:'数学'}]}}:{data:[primary,preschool]})
 p.data.courseTypeIndex=0;await p.loadDict();await p.loadSchedules()
 assert.deepEqual(Array.from(p.data.gradeOptions),['全部年级','幼小衔接','一年级'])
 assert.deepEqual(Array.from(p.data.courseGroups,g=>g.label),['幼小衔接','一年级'])
 p.onGradeChange({detail:{value:1}})
 assert.equal(p.data.schedules.length,1);assert.equal(p.data.schedules[0].scheduleId,'91')
})

test('parent term filter combines with teachers and dimensions, reset restores all periods for both course types',async()=>{
 const periods=[['春季','周六'],['秋季','周日'],['寒假','一期'],['寒假','二期'],['暑假','一期'],['暑假','二期']]
 const rows=periods.map(([termName,periodName],i)=>({...lesson(i+20,teachers[i%2]),termName,periodName}))
 rows.push({...rows[2],scheduleId:'different-grade',gradeName:'高一'})
 const {p}=harness(()=>Promise.resolve({data:rows}));await p.loadSchedules()
 p.onGradeChange(change(1));p.onSubjectChange(change(1))
 for(let index=1;index<=6;index++){p.onTermChange(change(index));assert.equal(p.data.schedules.length,1);assert.equal(p.data.schedules[0].scheduleId,String(index+19))}
 p.onTermChange(change(3));p.chooseTeachers(change(['teacher:11']));assert.equal(p.data.selectedTeachers[0].key,'teacher:11')
 p.onTermChange(change(4));assert.equal(p.data.selectedTeacherKeys.length,0);assert.equal(p.data.teacherOptions.length,1)
 p.resetFilters();assert.equal(p.data.termIndex,0);assert.equal(p.data.schedules.length,7)
 const actualTerm=p.data.termIndex;p.onTermChange(change(7));assert.equal(p.data.termIndex,actualTerm)
 p._allSchedules=rows.map(c=>({...c,classMode:'1'}));p.data.courseTypeIndex=0;p.onTermChange(change(5));assert.equal(p.data.schedules.length,1);assert.equal(p.data.schedules[0].termName,'暑假')
})
