const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')

test('开发者工具连接本地，手机保留服务器地址，切换环境清除旧会话', () => {
  for (const platform of ['devtools', 'ios', 'android']) {
    let app
    const store = new Map([['token', 'server-token'], ['roles', ['admin']]])
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8'), {
      App(value) { app = value }, wx: {
        getDeviceInfo: () => ({ platform }), getStorageSync: key => store.get(key),
        setStorageSync: (key, value) => store.set(key, value), removeStorageSync: key => store.delete(key)
      }
    })
    app.onLaunch()
    assert.equal(app.globalData.baseUrl, platform === 'devtools' ? 'http://127.0.0.1:8080' : 'https://test-admin.mingyuantostars.online/prod-api')
    assert.equal(app.globalData.token, platform === 'devtools' ? '' : 'server-token')
    store.set('token', 'same-backend-token'); app.onLaunch()
    assert.equal(app.globalData.token, 'same-backend-token')
  }
})

test('体验版请求走远程HTTPS测试后端，而不是体验者手机的本地地址', async () => {
  let app
  const root = path.resolve(__dirname, '..')
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), { App(value) { app = value }, wx: {} })
  const requestFile = path.join(root, 'utils/request.js')
  const requests = []
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(requestFile, 'utf8'), {
    module, getApp: () => app,
    require: () => ({ prepare: options => ({ options }) }),
    wx: { getStorageSync: () => '', request(options) { requests.push(options); options.success({ statusCode: 200, data: { code: 200, data: {} } }) } }
  })
  await module.exports.request({ url: '/miniapp/auth/config' })
  assert.equal(requests[0].url, 'https://test-admin.mingyuantostars.online/prod-api/miniapp/auth/config')
})

test('体验版和正式版忽略历史调试后端地址，继续连接服务器', () => {
  for (const envVersion of ['trial', 'release']) {
    let app
    const store = new Map([['backendOverride', 'http://192.168.1.8:8080']])
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8'), {
      App(value) { app = value }, wx: {
        getDeviceInfo: () => ({ platform: 'ios' }), getAccountInfoSync: () => ({ miniProgram: { envVersion } }),
        getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, value), removeStorageSync: key => store.delete(key)
      }
    })
    app.onLaunch()
    assert.equal(app.globalData.baseUrl, 'https://test-admin.mingyuantostars.online/prod-api')
  }
})
