const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const root = path.join(__dirname, '../pages/admin-classrooms')
function setup() {
  let definition
  const room = { classroomId: 101, classroomName: '原教室', classroomCode: 'ROOM101', campusName: '万达' }, calls = []
  const wx = { showLoading() {}, hideLoading() {}, showToast() {} }
  vm.runInNewContext(fs.readFileSync(path.join(root, 'admin-classrooms.js'), 'utf8'), {
    Page: p => { definition = p }, wx,
    require: () => ({ request: async o => {
      calls.push(structuredClone(o))
      if (o.method === 'PUT') Object.assign(room, o.data)
      return { rows: o.url === '/system/classroom/list' ? [{ ...room }] : [] }
    } })
  })
  const p = { ...definition, data: structuredClone(definition.data), setData(values) {
    for (const [key, value] of Object.entries(values)) {
      const keys = key.split('.'); let target = this.data
      for (const part of keys.slice(0, -1)) target = target[part]
      target[keys.at(-1)] = value
    }
  } }
  // Dispatch through the actual WXML binding, so a missing bindinput fails the regression.
  function input(field, value) {
    const markup = fs.readFileSync(path.join(root, 'admin-classrooms.wxml'), 'utf8')
    const tag = markup.match(new RegExp('<input\\b[^>]*data-field="' + field + '"[^>]*>'))
    assert.ok(tag, 'input exists: ' + field)
    const handler = tag[0].match(/bindinput="([^"]+)"/)
    assert.ok(handler, 'input must update the form: ' + field)
    p[handler[1]]({ currentTarget: { dataset: { field } }, detail: { value } })
  }
  return { p, calls, input }
}
test('editing a classroom submits typed name and code, then refreshes the displayed name', async () => {
  const { p, calls, input } = setup(); await p.load()
  p.openEdit({ currentTarget: { dataset: { id: '101' } } })
  assert.equal(p.data.editVisible, true)
  input('classroomName', ' 万达新教室 '); input('classroomCode', ' NEW101 ')
  await p.save(); await new Promise(resolve => setImmediate(resolve))
  const saved = calls.find(c => c.method === 'PUT')
  assert.equal(saved.data.classroomId, 101)
  assert.equal(saved.data.classroomName, '万达新教室'); assert.equal(saved.data.classroomCode, 'NEW101')
  assert.equal(p.data.groups[0].rooms[0].classroomName, '万达新教室')
  assert.equal(p.data.editVisible, false)
})
test('new classroom inputs use the same binding and submit through creation', async () => {
  const { p, calls, input } = setup(); p.openAdd()
  input('classroomName', '新增教室'); input('classroomCode', 'NEW102')
  await p.save()
  const saved = calls.find(c => c.method === 'POST')
  assert.equal(saved.data.classroomName, '新增教室'); assert.equal(saved.data.classroomCode, 'NEW102')
  assert.equal(saved.data.classroomId, undefined)
})
