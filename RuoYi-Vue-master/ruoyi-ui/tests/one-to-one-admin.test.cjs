const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue'), compiler = require('vue-template-compiler')
Vue.config.silent = true
const filename = path.resolve(__dirname, '../src/views/system/schedule/OneToOneManager.vue')
const cell = { date: '2028-10-23', timeSlot: '8:00-10:00', startTime: '08:00', endTime: '10:00', canPublish: true, classrooms: [{ classroomId: '4', classroomName: '一教室', campusName: '校区' }] }
function page(api = {}) {
  assert.ok(fs.existsSync(filename), '缺少现有页面共用的管理员一对一窗口')
  const sfc = compiler.parseComponent(fs.readFileSync(filename, 'utf8'))
  const context = { definition: null, getToken: () => 'token', ...api }
  vm.runInNewContext(sfc.script.content.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?[ \t]*$/gm, '').replace('export default', 'definition ='), context)
  const def = context.definition, render = compiler.compileToFunctions(sfc.template.content)
  assert.equal(compiler.compile(sfc.template.content).errors.length, 0)
  const p = new Vue({ ...def, components: {}, propsData: { dims: { teachers: [], grades: [] } }, render: render.render, staticRenderFns: render.staticRenderFns })
  p.$message = { warning() {}, success() {}, error() {} }; p.$confirm = async () => {}; p.$prompt = async () => ({ value: '已与家长沟通' })
  p.visible = true; p.teacherId = 10; p.date = '2028-10-23'; p.cells = [cell]; p.profile = { teacherSubject: '数学', teacherLevel: 'senior' }
  return p
}
test('only server-allowed actions are offered; string zero is not true', () => {
  const p = page()
  assert.deepEqual(Array.from(p.actions({ enrollmentStatus: '0', canProcess: 1 })), ['CONFIRM', 'REJECT'])
  assert.deepEqual(Array.from(p.actions({ enrollmentStatus: '1', cancelRequestStatus: '1', canProcess: '1' })), ['APPROVE_CANCEL', 'REJECT_CANCEL'])
  assert.deepEqual(Array.from(p.actions({ enrollmentStatus: '0', canProcess: '0', canComplete: '0' })), [])
  assert.deepEqual(Array.from(p.actions({ enrollmentStatus: '1', canComplete: 1 })), ['COMPLETE'])
})
test('publish uses the selected real slot and classroom, not arbitrary client teacher properties', async () => {
  let sent
  const p = page({ publishSingle: async body => { sent = body } })
  p.refresh = async () => {}; p.pickCell(cell); p.form.gradeName = '五年级'; p.form.courseClassName = '数学提升'; p.form.classroomId = '4'
  await p.save()
  assert.deepEqual(JSON.parse(JSON.stringify(sent)), { teacherId: 10, classroomId: '4', timeSlot: '8:00-10:00', startDate: '2028-10-23', gradeName: '五年级', courseClassName: '数学提升' })
})
test('unavailable slot or room cannot be submitted even if old draft fields remain', async () => {
  let writes = 0
  const p = page({ publishSingle: async () => { writes++ } }); p.refresh = async () => {}
  p.pickCell(cell); Object.assign(p.form, { gradeName: '五年级', courseClassName: '数学', classroomId: '999' })
  await p.save(); assert.equal(writes, 0)
  p.form.classroomId = '4'; p.cells = [{ ...cell, canPublish: false }]
  await p.save(); assert.equal(writes, 0)
})
test('teacher change clears the previous edit and selectable slot before loading', async () => {
  const p = page(); p.refresh = async () => {}
  p.editing = { scheduleId: 7 }; p.pickCell(cell); p.changeTeacher()
  assert.equal(p.editing, null); assert.equal(p.selectedCell, null); assert.equal(p.form.classroomId, null)
})
test('late teacher availability cannot overwrite the newer teacher selection', async () => {
  let resolveOld
  const p = page({ singleCourses: async () => ({ data: [] }), singleBookings: async () => ({ data: [] }), singleProfile: async teacher => ({ data: { teacherSubject: teacher === 10 ? '数学' : '英语' } }), singleAvailability: teacher => teacher === 10 ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve({ data: { cells: [{ ...cell, date: '2028-10-24' }] } }) })
  const old = p.refresh(); p.teacherId = 11; await p.refresh(); resolveOld({ data: { cells: [cell] } }); await old
  assert.equal(p.profile.teacherSubject, '英语'); assert.equal(p.cells[0].date, '2028-10-24'); assert.equal(p.loading, false)
})
test('decisions keep actual enrollment and reason and completion uses a separate endpoint', async () => {
  const calls = [], p = page({ decideSingle: async (id, body) => calls.push([id, body]), completeSingle: async id => calls.push([id, 'complete']) })
  p.refresh = async () => {}; const row = { enrollmentId: '8', enrollmentStatus: '0', canProcess: 1 }; p.bookings = [row]
  await p.act(row, 'REJECT')
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0])), ['8', { action: 'REJECT', reason: '已与家长沟通' }])
  const done = { enrollmentId: '9', enrollmentStatus: '1', canComplete: 1 }; p.bookings = [done]; await p.act(done, 'COMPLETE')
  assert.deepEqual(calls[1], ['9', 'complete'])
})
test('an account switch while confirming cannot send a decision as the next account', async () => {
  let token = 'first', resolvePrompt, writes = 0
  const p = page({ getToken: () => token, decideSingle: async () => { writes++ } })
  p.refresh = async () => {}; p.$prompt = () => new Promise(resolve => { resolvePrompt = resolve })
  const pending = p.act({ enrollmentId: '8', enrollmentStatus: '0', canProcess: 1 }, 'REJECT')
  token = 'second'; resolvePrompt({ value: '确认取消' }); await pending
  assert.equal(writes, 0); assert.equal(p.visible, false)
})
