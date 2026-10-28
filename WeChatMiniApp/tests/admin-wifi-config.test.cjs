const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const root = path.resolve(__dirname, '..')
let definition, calls, storage, toasts
const app = { globalData: { baseUrl: 'http://127.0.0.1:8080' } }
global.getApp = () => app
global.Page = value => { definition = value }

function env(handler, wifi = {}) {
  calls = []; toasts = []; storage = new Map()
  global.wx = {
    getStorageSync: key => key === 'token' ? 'jwt' : storage.get(key),
    setStorageSync: (k, v) => storage.set(k, structuredClone(v)),
    removeStorageSync: k => storage.delete(k),
    showToast: o => toasts.push(o.title),
    showLoading() {}, hideLoading() {}, stopPullDownRefresh() {},
    getSystemInfoSync: () => ({ platform: wifi.platform || 'android' }),
    startWifi: o => (wifi.startWifi ? wifi.startWifi(o) : o.success({})),
    getConnectedWifi: o => (wifi.getConnectedWifi
      ? wifi.getConnectedWifi(o)
      : o.success({ wifi: { SSID: 'MingYuan-5G', BSSID: 'aa:bb:cc:dd:ee:ff' } })),
    request(o) { calls.push({ url: o.url, method: o.method, data: structuredClone(o.data) }); handler(o) }
  }
}
function ok(o, data) { o.success({ statusCode: 200, data: { code: 200, data } }) }
function page() {
  const file = path.join(root, 'pages/admin-wifi-config/admin-wifi-config.js')
  assert.ok(fs.existsSync(file), 'admin-wifi-config is implemented')
  delete require.cache[require.resolve(file)]; require(file)
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(values) {
      for (const [key, value] of Object.entries(values)) {
        const parts = key.split('.'); let obj = this.data
        while (parts.length > 1) obj = obj[parts.shift()]
        obj[parts[0]] = value
      }
    }
  }
}
function type(p, field, value) {
  p.onField({ currentTarget: { dataset: { field } }, detail: { value } })
}

test('opening the add sheet auto-fills the connected wifi name and bssid', async () => {
  env(o => ok(o, []))
  const p = page()
  await p.openAdd()
  assert.equal(p.data.editForm.ssid, 'MingYuan-5G')
  assert.equal(p.data.editForm.bssid, 'aa:bb:cc:dd:ee:ff')
  assert.equal(p.data.connectedSsid, 'MingYuan-5G')
  assert.equal(p.data.wifiError, '')
  assert.equal(p.data.wifiReading, false)
})

test('quoted android SSID is normalized before filling', async () => {
  env(o => ok(o, []), { getConnectedWifi: o => o.success({ wifi: { SSID: '"MingYuan-5G"', BSSID: 'aa:bb:cc:dd:ee:ff' } }) })
  const p = page()
  await p.openAdd()
  assert.equal(p.data.editForm.ssid, 'MingYuan-5G')
})

test('typed wifi name reaches the save payload', async () => {
  env(o => ok(o, []), { startWifi: o => o.fail({ errMsg: 'startWifi:fail' }), platform: 'devtools' })
  const p = page()
  await p.openAdd()
  assert.equal(p.data.editForm.ssid, '')
  assert.match(p.data.wifiError, /开发者工具/)
  type(p, 'ssid', 'MingYuan-5G')
  type(p, 'bssid', '  aa:bb  ')
  type(p, 'remark', ' 前台 ')
  assert.equal(p.data.editForm.ssid, 'MingYuan-5G')
  await p.save()
  const post = calls.find(c => c.method === 'POST')
  assert.ok(post.url.endsWith('/system/wificonfig'), `unexpected save url: ${post.url}`)
  assert.equal(post.data.ssid, 'MingYuan-5G')
  assert.equal(post.data.bssid, 'aa:bb')
  assert.equal(post.data.remark, '前台')
  assert.equal(post.data.campusName, '万达')
  assert.equal(post.data.status, '0')
})

test('saving without a wifi name is rejected locally and never posts', async () => {
  env(o => ok(o, []), { startWifi: o => o.fail({ errMsg: 'startWifi:fail' }), platform: 'devtools' })
  const p = page()
  await p.openAdd()
  await p.save()
  assert.equal(calls.some(c => c.method === 'POST' || c.method === 'PUT'), false)
  assert.ok(toasts.some(t => /请填写WiFi名称/.test(t)))
})

test('editing keeps the stored name and never overwrites it with the connected wifi', async () => {
  env(o => ok(o, [{
    wifiId: 7, campusName: '万达', ssid: 'Old-Net', bssid: 'aa:bb:cc:dd:ee:ff', status: '0', remark: ''
  }]))
  const p = page()
  await p.load()
  p.openEdit({ currentTarget: { dataset: { id: 7 } } })
  assert.equal(p.data.editForm.ssid, 'Old-Net')
  type(p, 'ssid', 'New-Net')
  await p.save()
  const put = calls.find(c => c.method === 'PUT')
  assert.ok(put.url.endsWith('/system/wificonfig'), `unexpected save url: ${put.url}`)
  assert.equal(put.data.wifiId, 7)
  assert.equal(put.data.ssid, 'New-Net')
})

test('explicit read overwrites the name with the connected wifi and confirms', async () => {
  env(o => ok(o, []))
  const p = page()
  p.setData({ editVisible: true, editForm: { wifiId: null, ssid: '旧名字', bssid: '', campusIndex: 0, remark: '' } })
  await p.readWifi()
  assert.equal(p.data.editForm.ssid, 'MingYuan-5G')
  assert.equal(p.data.editForm.bssid, 'aa:bb:cc:dd:ee:ff')
  assert.ok(toasts.some(t => /已填入当前WiFi/.test(t)))
})

test('failed wifi read keeps manual input usable and explains why', async () => {
  env(o => ok(o, []), { getConnectedWifi: o => o.fail({ errMsg: 'getConnectedWifi:fail:12006 gps not turned on' }) })
  const p = page()
  await p.openAdd()
  assert.equal(p.data.editForm.ssid, '')
  assert.match(p.data.wifiError, /定位/)
  await p.readWifi()
  assert.ok(toasts.some(t => /定位|手动输入/.test(t)))
})

test('developer tools without wifi support still allows a manual save', async () => {
  env(o => ok(o, []), { startWifi: o => o.fail({ errMsg: 'startWifi:fail' }), platform: 'devtools' })
  const p = page()
  await p.openAdd()
  type(p, 'ssid', '手动输入的WiFi')
  await p.save()
  assert.equal(calls.some(c => c.method === 'POST' && c.data.ssid === '手动输入的WiFi'), true)
})

test('missing wifi apis degrade to a clear hint instead of throwing', async () => {
  env(o => ok(o, []))
  delete wx.startWifi
  const p = page()
  await p.openAdd()
  assert.equal(p.data.wifiReading, false)
  assert.match(p.data.wifiError, /不支持读取WiFi/)
})
