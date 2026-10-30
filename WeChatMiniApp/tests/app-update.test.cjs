const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function setup(supported = true) {
  let app, ready, failed, applied = 0
  const modals = [], toasts = [], removed = []
  const storage = new Map(Object.entries({ backendBaseUrl: 'https://test-admin.mingyuantostars.online/prod-api', token: 'existing-login', userInfo: { userId: 7 }, roles: ['parent'], draft: 'unsaved-draft' }))
  const manager = { onUpdateReady(fn) { ready = fn }, onUpdateFailed(fn) { failed = fn }, applyUpdate() { applied++ } }
  const wx = { getDeviceInfo: () => ({ platform: 'ios' }), getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }), getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => { removed.push(key); storage.delete(key) }, clearStorageSync() { throw new Error('Update must not clear cache') }, showModal: options => modals.push(options), showToast: options => toasts.push(options) }
  if (supported) wx.getUpdateManager = () => manager
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8'), { App: value => { app = value }, wx })
  app.onLaunch(); app.onShow()
  return { app, storage, removed, modals, toasts, ready: () => ready(), failed: () => failed(), applied: () => applied }
}

test('ready update prompts once and restarts only after consent, retaining login and drafts', () => {
  const s = setup()
  assert.equal(s.modals.length, 0)
  s.ready(); s.ready(); s.app.onShow()
  assert.equal(s.modals.length, 1); assert.equal(s.applied(), 0)
  assert.equal(s.modals[0].confirmText, '重新进入')
  s.modals[0].success({ confirm: true }); s.modals[0].success({ confirm: true }); s.app.onShow()
  assert.equal(s.applied(), 1)
  assert.equal(s.storage.get('token'), 'existing-login'); assert.equal(s.storage.get('draft'), 'unsaved-draft')
  assert.deepEqual(s.removed, [])
})

test('postponed update is offered on next foreground entry without clearing login', () => {
  const s = setup(); s.ready(); s.modals[0].success({ confirm: false })
  assert.equal(s.applied(), 0); assert.equal(s.modals.length, 1)
  s.app.onHide(); s.app.onShow()
  assert.equal(s.modals.length, 2); assert.equal(s.storage.get('token'), 'existing-login')
})

test('background readiness waits for foreground and failed dialog can be offered again', () => {
  const s = setup(); s.app.onHide(); s.ready()
  assert.equal(s.modals.length, 0)
  s.app.onShow(); assert.equal(s.modals.length, 1)
  s.modals[0].fail(); s.app.onHide(); s.app.onShow()
  assert.equal(s.modals.length, 2); assert.equal(s.applied(), 0)
})

test('failed download never applies an update or deletes existing storage', () => {
  const s = setup(); s.failed()
  assert.equal(s.applied(), 0); assert.equal(s.modals.length, 0)
  assert.equal(s.toasts.length, 1); assert.deepEqual(s.removed, [])
})

test('clients without update manager still start normally', () => {
  const s = setup(false)
  assert.equal(s.storage.get('token'), 'existing-login'); assert.equal(s.modals.length, 0)
})
