const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const pageDir = path.join(__dirname, '../pages/admin-teachers')
function setup(configId, send) {
  let definition
  const requests = []
  const wx = { showLoading() {}, hideLoading() {}, showToast() {} }
  vm.runInNewContext(fs.readFileSync(path.join(pageDir, 'admin-teachers.js'), 'utf8'), {
    Page: value => { definition = value }, wx,
    require: () => ({ request: async value => { requests.push(value); return send ? send(value) : { data: {} } } })
  })
  const p = { ...definition, data: structuredClone(definition.data), load: async () => {} }
  p.data.editVisible = true
  p.data.editForm = { configId, teacherId: 7, teacherName: '测试教师', baseSalary: '', ratePrimaryClass: '', rateJhClass: '', ratePrimary1on1: '', rateJunior1on1: '', rateSenior1on1: '', classHoursPerSession: '', remark: '' }
  p.setData = changes => {
    for (const [key, value] of Object.entries(changes)) {
      const parts = key.split('.'); let object = p.data
      for (const part of parts.slice(0, -1)) object = object[part]
      object[parts[parts.length - 1]] = value
    }
  }
  function type(field, value) {
    const markup = fs.readFileSync(path.join(pageDir, 'admin-teachers.wxml'), 'utf8')
    const input = [...markup.matchAll(/<input\b[^>]*>/g)].find(m => m[0].includes(`data-field="${field}"`))
    assert.ok(input, `input missing: ${field}`)
    assert.match(input[0], /bindinput="onEditInput"/)
    p.onEditInput({ currentTarget: { dataset: { field } }, detail: { value } })
  }
  return { p, requests, type }
}
for (const configId of [null, 12]) {
  test(`salary ${configId ? 'edit' : 'create'} submits hourly rates and fractional hours from input events`, async () => {
    const { p, requests, type } = setup(configId)
    for (const field of ['baseSalary','ratePrimaryClass','rateJhClass','rateSpecialClass','ratePrimary1on1','rateJunior1on1','rateSenior1on1']) type(field, '100.50')
    type('classHoursPerSession', '1.5'); type('remark', '按小时计薪')
    await p.saveEdit()
    assert.equal(requests.length, 1)
    assert.equal(requests[0].method, configId ? 'PUT' : 'POST')
    assert.equal(requests[0].data.classHoursPerSession, 1.5)
    assert.equal(requests[0].data.ratePrimaryClass, 100.5)
    assert.equal(requests[0].data.rateSpecialClass, 100.5)
    assert.equal(requests[0].data.rateSenior1on1, 100.5)
    assert.equal(requests[0].data.remark, '按小时计薪')
    assert.equal(p.data.editVisible, false)
  })
}


test('class attendance edits and reset are separate from salary and keep the selected month', async () => {
  const {p,requests}=setup(12,async o=>({data:{salaryMonth:'2026-11',automaticCount:8,count:o.data.count==null?8:o.data.count,manual:o.data.count!=null}}))
  p.data.canAdjustAttendance=true;p.data.attendanceMonth='2026-11';p.data.attendanceInfo={count:8};p.data.attendanceInput='12'
  await p.saveAttendance();assert.equal(requests[0].data.count,12);assert.match(requests[0].url,/salaryMonth=2026-11/)
  await p.resetAttendance();assert.equal(requests[1].data.count,null);assert.equal(p.data.attendanceInput,'8')
  assert.equal(p.data.editForm.ratePrimaryClass,'');assert.equal(requests.length,2)
  p.data.attendanceInput='1.5';await p.saveAttendance();assert.equal(requests.length,2)
})
