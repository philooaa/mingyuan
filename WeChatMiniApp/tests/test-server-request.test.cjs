const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')

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
