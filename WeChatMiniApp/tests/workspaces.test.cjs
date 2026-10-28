const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const root = path.resolve(__dirname, '..')
function harness(name, roles = ['parent'], permissions = ['*:*:*'], custom) {
  const state = { roles, permissions }, store = new Map([['token', 'original-token'], ['roles', roles], ['userInfo', { userId: '41' }]])
  const app = { globalData: { roles, permissions, userInfo: { userId: '41' }, baseUrl: 'http://localhost' } }
  const calls = [], navigations = [], notices = [], cache = new Map()
  const wx = {
    getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, v), removeStorageSync: k => store.delete(k),
    getDeviceInfo: () => ({ platform: 'devtools' }), showToast: o => notices.push(o.title),
    navigateTo: o => navigations.push(o.url), reLaunch: o => navigations.push(o.url), stopPullDownRefresh() {}
  }
  const request = async o => {
    calls.push(structuredClone(o))
    if (custom) { const result = custom(o); if (result !== undefined) return result }
    if (o.url === '/getInfo') return { code: 200, roles: state.roles, permissions: state.permissions, user: { userId: '41', userName: 'test-account' } }
    return { code: 200, data: {} }
  }
  let definition
  function load(file) {
    if (file === path.join(root, 'utils', 'request.js')) return { request }
    if (cache.has(file)) return cache.get(file)
    const module = { exports: {} }
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
      module, Page: value => { definition = value }, getApp: () => app, wx, console,
      require: id => load(path.resolve(path.dirname(file), id + '.js')),
      getCurrentPages: () => [], setInterval: () => 1, clearInterval() {}, setTimeout, clearTimeout
    }, { filename: file })
    cache.set(file, module.exports); return module.exports
  }
  load(path.join(root, 'pages', name, name + '.js'))
  const p = { ...definition, data: structuredClone(definition.data), setData(v) { Object.assign(this.data, v) } }
  return { p, wx, app, state, store, calls, navigations, notices }
}
const click = role => ({ currentTarget: { dataset: { role } } })

test('top-right role menu switches only assigned workspaces and remembers the choice', async () => {
  const h = harness('index', ['admin', 'education', 'teacher'])
  h.p.onLoad(); h.p._visible = true; await h.p.applyLoginRole()
  h.wx.showActionSheet = options => {
    assert.deepEqual(Array.from(options.itemList), ['管理员（当前）', '教务', '教师'])
    options.success({ tapIndex: 1 })
  }
  await h.p.openRoleMenu()
  assert.equal(h.p.data.currentRole, 'education')
  assert.equal(h.store.get('miniappWorkspace:41'), 'education')
  assert.equal(h.store.get('token'), 'original-token')
  assert.ok(h.p.data.quickActions.some(a => a.route === '/pages/admin-tuition/admin-tuition'))
  assert.equal(h.p.data.quickActions.some(a => a.route === '/pages/admin-transactions/admin-transactions'), false)
  await h.p.applyLoginRole(); assert.equal(h.p.data.currentRole, 'education')
  h.p.onUnload()
})
test('role menu is inert for a single role and ignores a choice from an expired session', async () => {
  const single = harness('index', ['education'])
  single.p.onLoad(); await single.p.applyLoginRole()
  single.wx.showActionSheet = () => assert.fail('single role must not open a menu')
  await single.p.openRoleMenu(); single.p.onUnload()
  const h = harness('index', ['admin', 'teacher'])
  h.p.onLoad(); h.p._visible = true; await h.p.applyLoginRole()
  let sheet
  h.wx.showActionSheet = options => { sheet = options }
  const pending = h.p.openRoleMenu()
  h.store.set('token', 'another-session')
  sheet.success({ tapIndex: 1 }); await pending
  assert.equal(h.p.data.currentRole, 'admin')
  assert.equal(h.store.has('miniappWorkspace:41'), false)
  h.p.onUnload()
})
test('role menu handles cancellation and rechecks roles revoked by the server', async () => {
  const h = harness('index', ['admin', 'teacher'])
  h.p.onLoad(); h.p._visible = true; await h.p.applyLoginRole()
  h.wx.showActionSheet = options => options.fail({ errMsg: 'showActionSheet:fail cancel' })
  await h.p.openRoleMenu(); assert.equal(h.p.data.currentRole, 'admin')
  h.wx.showActionSheet = options => { h.state.roles = ['admin']; options.success({ tapIndex: 1 }) }
  await h.p.openRoleMenu(); assert.equal(h.p.data.currentRole, 'admin')
  assert.deepEqual(Array.from(h.p.data.roles, r => r.key), ['admin'])
  h.p.onUnload()
})

test('student attendance badge rejects late responses after switching workspace with the same token', async () => {
  const finishes = []; let delayed = false
  const h = harness('index', ['admin', 'parent'], ['*:*:*'], o => {
    if (o.url === '/miniapp/education/sign/list') return delayed
      ? new Promise(resolve => { finishes.push(resolve) })
      : { data: [{ scheduleId: '1', classDate: '2030-10-15', reviewStatus: '0' }, { scheduleId: '1', classDate: '2030-10-15', reviewStatus: '0' }, { scheduleId: '2', classDate: '2030-10-15', reviewStatus: '2' }] }
  })
  h.p.onLoad(); h.p._visible = true; await h.p.applyLoginRole(); await Promise.resolve()
  assert.equal(h.p.data.quickActions.find(a => a.key === 'studentSignReview').badgeText, '2')
  delayed = true; const pending = h.p.loadSignReviewPending(); assert.equal(finishes.length, 1)
  await h.p.switchRole(click('parent')); finishes.forEach(finish => finish({ data: [{ scheduleId: '9', classDate: '2030-10-15', reviewStatus: '0' }] })); await pending
  assert.equal(h.store.get('token'), 'original-token')
  assert.equal(h.p.data.currentRole, 'parent')
  assert.equal(h.p.data.quickActions.some(a => a.key === 'studentSignReview'), false)
  h.p.onUnload()
})
test('each assigned role sees only its own workspace; education confirms tuition without coupon management', async () => {
  for (const role of ['parent', 'teacher', 'education', 'admin']) {
    const h = harness('index', [role]); h.p.onLoad(); await h.p.applyLoginRole()
    assert.deepEqual(Array.from(h.p.data.roles, r => r.key), [role]); assert.equal(h.p.data.currentRole, role)
    const routes = h.p.data.quickActions.map(a => a.route)
    if (role === 'education') {
      assert.ok(routes.includes('/pages/admin-tuition/admin-tuition'))
      assert.equal(routes.includes('/pages/admin-referral/admin-referral'), false)
      assert.equal(routes.includes('/pages/admin-transactions/admin-transactions'), false)
    }
    if (role === 'teacher') assert.ok(routes.includes('/pages/teacher-students/teacher-students'))
    assert.equal(routes.includes('/pages/admin-tuition-refunds/admin-tuition-refunds'), ['admin', 'education'].includes(role))
    assert.equal(routes.includes('/pages/sign-review/sign-review'), ['admin', 'education'].includes(role))
    assert.equal(h.calls.some(o => o.url === '/miniapp/login'), false)
    h.p.onUnload()
  }
})
test('multi-role switching keeps the token, refuses an unassigned role and clears revoked workspaces', async () => {
  const h = harness('index', ['admin', 'education']); h.p.onLoad(); await h.p.applyLoginRole()
  await h.p.switchRole(click('education')); assert.equal(h.p.data.currentRole, 'education')
  assert.equal(h.store.get('token'), 'original-token'); assert.equal(h.calls.some(o => o.method === 'POST'), false)
  await h.p.switchRole(click('teacher')); assert.equal(h.p.data.currentRole, 'education')
  h.state.roles = ['parent']; await h.p.applyLoginRole()
  assert.equal(h.p.data.currentRole, 'parent'); assert.equal(h.p.data.roles.length, 1)
  assert.equal(h.p.data.quickActions.some(a => a.route.startsWith('/pages/admin-')), false)
  h.p.onUnload()
})
test('staff menu permissions are respected and a missing role never falls back to parent', async () => {
  const h = harness('index', ['education'], []); h.p.onLoad(); await h.p.applyLoginRole()
  // Attendance review is role-authorized, like the existing miniapp check-in review; finance still requires menu permissions.
  assert.deepEqual(Array.from(h.p.data.quickActions, a => a.route), ['/pages/sign-review/sign-review'])
  h.state.roles = []; await h.p.applyLoginRole()
  assert.equal(h.p.data.currentRole, ''); assert.equal(h.p.data.roles.length, 0)
  assert.equal(h.calls.some(o => o.url.startsWith('/miniapp/parent')), false)
  h.p.onUnload()
})
test('account login reuses the existing API, accepts an assigned education account and never stores a password', async () => {
  const h = harness('register', ['education'], ['system:tuition:list'], o => o.url === '/login' ? { token: 'account-token' } : undefined)
  h.p._unloaded = false; h.p.setData({ username: 'office', password: 'test-only-password', code: '1234', uuid: 'captcha-1' })
  await h.p.handleAccountLogin()
  const login = h.calls.find(o => o.url === '/login')
  assert.deepEqual(login.data, { username: 'office', password: 'test-only-password', code: '1234', uuid: 'captcha-1' })
  assert.equal(h.store.get('token'), 'account-token'); assert.equal(h.p.data.password, '')
  assert.equal(JSON.stringify(Array.from(h.store)).includes('test-only-password'), false)
  assert.ok(h.navigations.includes('/pages/index/index'))
})
test('real-device login never probes or displays the simulated identity selector', async () => {
  const h = harness('register'); h.wx.getDeviceInfo = () => ({ platform: 'ios' })
  await h.p.loadLoginConfig('admin', {})
  assert.equal(h.p.data.developmentLoginEnabled, false)
  await h.p.loginWithWechat('mock', 'admin')
  assert.equal(h.calls.length, 0)
})
test('account without an assigned workspace cannot replace the current session', async () => {
  const h = harness('register', [], [], o => o.url === '/login' ? { token: 'unassigned' } : undefined)
  h.p._unloaded = false; h.p.setData({ username: 'unassigned', password: 'test-only-password' })
  await h.p.handleAccountLogin()
  assert.equal(h.store.get('token'), 'original-token'); assert.match(h.p.data.loginError, /角色/)
})
test('unified phone login never submits a chosen role and activates all server-assigned roles', async () => {
  const h = harness('register', ['teacher', 'education'], ['system:tuition:list'], o => {
    if (o.url === '/miniapp/auth/login') return { token: 'phone-token' }
  })
  h.p._unloaded = false; h.p.setData({ phoneLoginEnabled: true, phone: '13900000044', verificationCode: '123456', devRole: 'admin' })
  await h.p.handlePhoneCodeLogin()
  const login = h.calls.find(o => o.url === '/miniapp/auth/login')
  assert.deepEqual(login.data, { phone: '13900000044', code: '123456' })
  assert.equal(h.store.get('token'), 'phone-token')
  assert.deepEqual(Array.from(h.app.globalData.roles), ['teacher', 'education'])
  assert.equal(h.p.data.verificationCode, '')
  assert.equal(JSON.stringify(Array.from(h.store)).includes('123456'), false)
})
test('code cooldown blocks duplicate sends and switching phone clears the prior challenge display', async () => {
  const h = harness('register', ['parent'], [], o => o.url === '/miniapp/auth/code' ? { data: { resendAfter: 60, developmentCode: '678901' } } : undefined)
  h.p._unloaded = false; h.p.setData({ phoneLoginEnabled: true, developmentCodeMode: true, phone: '13900000044' })
  await h.p.sendCode(); await h.p.sendCode()
  assert.equal(h.calls.filter(o => o.url === '/miniapp/auth/code').length, 1)
  assert.equal(h.p.data.developmentCode, '678901')
  h.p.phoneInput({ detail: { value: '13800000010' } })
  assert.equal(h.p.data.developmentCode, ''); assert.equal(h.p.data.verificationCode, ''); assert.equal(h.p.data.cooldown, 0)
})
test('code rejection and an abandoned login never replace an active session', async () => {
  let complete
  const h = harness('register', ['parent'], [], o => o.url === '/miniapp/auth/login' ? new Promise(resolve => { complete = resolve }) : undefined)
  h.p._unloaded = false; h.p.setData({ phoneLoginEnabled: true, phone: '13900000044', verificationCode: '123456' })
  const work = h.p.handlePhoneCodeLogin(); h.store.set('token', 'another-session'); complete({ token: 'late-token' }); await work
  assert.equal(h.store.get('token'), 'another-session')
  assert.equal(h.calls.some(o => o.url === '/getInfo'), false)
})
