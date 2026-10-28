const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
function load(login) {
  let page
  const calls = [], storage = {}, app = { globalData: {} }, notices = []
  const wx = { login, getStorageSync: key => storage[key], setStorageSync: (key, value) => storage[key] = value,
    removeStorageSync: key => delete storage[key], showToast: o => notices.push(o.title), reLaunch: o => calls.push(o) }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/register/register.js'), 'utf8'), {
    Page: value => page = value, wx, getApp: () => app,
    require: id => id.endsWith('request') ? { request: async o => { calls.push(o); return o.url === '/miniapp/login' ? { token: 'new-token' } : { user: { userId: 10 }, roles: ['teacher', 'parent'], permissions: [] } } } : id.endsWith('access') ? { options: roles => roles } : {}
  })
  page.data = structuredClone(page.data); page.setData = values => Object.assign(page.data, values)
  return { page, calls, storage, notices }
}
test('微信登录失败不发送mock凭据、不替换当前会话', async () => {
  const h = load(o => o.fail({ errMsg: 'login:fail' }))
  await h.page.loginWithWechat('real-phone-code')
  assert.equal(h.calls.length, 0)
  assert.equal(h.storage.token, undefined)
  assert.equal(h.page.data.submitting, false)
})
test('空微信登录code不发送后端请求', async () => {
  const h = load(o => o.success({ code: '' }))
  await h.page.loginWithWechat('real-phone-code')
  assert.equal(h.calls.length, 0)
})
test('微信授权登录不传客户端角色，核实身份后再激活会话', async () => {
  const h = load(o => o.success({ code: 'real-wx-code' }))
  await h.page.loginWithWechat('real-phone-code')
  assert.equal(h.calls[0].url, '/miniapp/login')
  assert.equal(h.calls[0].data.loginCode, 'real-wx-code')
  assert.equal(h.calls[0].data.phoneCode, 'real-phone-code')
  assert.equal(h.calls[0].data.devRole, undefined)
  assert.equal(h.calls[1].header.Authorization, 'Bearer new-token')
  assert.equal(h.storage.token, 'new-token')
  assert.deepEqual(JSON.parse(JSON.stringify(h.storage.roles)), ['teacher', 'parent'])
})
