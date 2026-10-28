const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

function page(name, roles) {
  const file = path.join(__dirname, '../pages', name, `${name}.js`)
  const localRequire = createRequire(file), storage = { token: 'teacher-session', roles }, requests = [], redirects = []
  let definition
  const request = async options => {
    requests.push(options)
    return { data: options.url.endsWith('/profile') ? { teacherSubject: '数学', teacherLevel: 'elite' }
      : options.url.endsWith('/availability') ? { cells: [] }
        : options.url.endsWith('/dict') ? { grades: [{ dictLabel: '高一' }] }
          : options.url.endsWith('/dimensions') ? {}
            : options.url.endsWith('/edit') ? { scheduleId: '9', classMode: '1', teacherId: '17' } : [] }
  }
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: value => { definition = value },
    require: id => id.endsWith('/request') ? { request } : localRequire(id),
    wx: { getStorageSync: key => storage[key], getSystemInfoSync: () => ({ windowWidth: 375 }), stopPullDownRefresh() {}, redirectTo: o => redirects.push(o.url) },
    setInterval: () => 0, clearInterval() {}, console
  })
  const p = { ...definition, data: structuredClone(definition.data) }
  p.setData = updates => Object.assign(p.data, updates)
  return { p, storage, requests, redirects }
}

for (const name of ['teacher-single-transactions']) {
  test(`${name}: an administrator who is also a teacher loads the teacher-scoped page`, async () => {
    const { p, requests, storage } = page(name, ['admin', 'business_admin', 'teacher', 'parent'])
    p.onLoad(name === 'teacher-schedule-edit' ? { id: '9' } : {}); await new Promise(resolve => setImmediate(resolve))
    assert.equal(p.current(), true)
    assert.ok(requests.some(r => r.url.startsWith('/miniapp/teacher/course/')))
    assert.ok(requests.every(r => !r.data || !('teacherId' in r.data)), 'do not choose or impersonate another teacher')
    if (name === 'teacher-schedule-edit') { assert.equal(p.data.error, ''); assert.equal(p.data.form.scheduleId, '9') }
    else assert.equal(p.data.loadSucceeded, true)
    storage.roles = ['admin', 'parent']; assert.equal(p.current(), false)
    storage.roles = ['admin', 'teacher']; storage.token = 'different-session'; assert.equal(p.current(), false)
    p.onUnload()
  })
  test(`${name}: administrator status alone cannot enter or request teacher data`, async () => {
    const { p, requests } = page(name, ['admin', 'business_admin'])
    p.onLoad({ id: '9' }); await new Promise(resolve => setImmediate(resolve))
    assert.equal(p.current(), false); assert.equal(requests.length, 0)
    p.onUnload()
  })
}

for(const name of ['teacher-schedule-create','teacher-schedule-edit']) {
  test(`${name}: retired teacher publisher performs no requests and redirects staff`,()=>{
    const teacher=page(name,['teacher']);teacher.p.onLoad({id:'9'});assert.equal(teacher.redirects.length,0);assert.equal(teacher.requests.length,0)
    const staff=page(name,['education','teacher']);staff.p.onLoad({id:'9'});assert.deepEqual(staff.redirects,['/pages/admin-schedule/admin-schedule?id=9']);assert.equal(staff.requests.length,0)
  })
}
