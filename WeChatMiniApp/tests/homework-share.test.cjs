const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const root = path.join(__dirname, '..')
function env({ token = 'parent-token', roles = ['parent'], children = [{ accountId: 12, studentName: '孩子甲' }], request } = {}) {
  const storage = { token, roles, userInfo: { userId: 8 } }, app = { globalData: { baseUrl: 'test', userInfo: { userId: 8 }, roles } }
  const routes = [], calls = [], cache = {}
  const wx = { getStorageSync: k => storage[k], setStorageSync: (k, v) => storage[k] = v, removeStorageSync: k => delete storage[k],
    redirectTo: o => routes.push(o.url), navigateTo: o => routes.push(o.url), reLaunch: o => routes.push(o.url), showToast() {} }
  function load(rel) {
    if (cache[rel]) return cache[rel]
    const module = { exports: {} }
    vm.runInNewContext(fs.readFileSync(path.join(root, rel), 'utf8'), { module, wx, getApp: () => app })
    return cache[rel] = module.exports
  }
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/homework-share/homework-share.js'), 'utf8'), {
    Page: p => definition = p, wx, getApp: () => app,
    require: name => name.endsWith('/request') ? { request: async o => { calls.push(o); return request ? request(o) : o.url === '/getInfo' ? { user: { userId: 8 }, roles } : { data: { children } } } } : load('utils/' + name.split('/').pop() + '.js')
  })
  const page = { ...definition, data: structuredClone(definition.data), _visible: true }
  page.setData = v => Object.assign(page.data, v)
  return { page, storage, routes, calls, app, wx, load }
}
test('share card includes no teacher, parent or child identity and uses a packaged cover', () => {
  const card = require('../utils/homework-share').card()
  assert.equal(card.path, '/pages/homework-share/homework-share')
  assert.ok(fs.existsSync(path.join(root, card.imageUrl)))
  const config = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  assert.ok(config.pages.includes(card.path.slice(1)))
})
test('logged-out recipient goes to login with a fixed homework return destination', async () => {
  const h = env({ token: '' }); await h.page.load()
  assert.deepEqual(h.routes, ['/pages/register/register?next=homework'])
  assert.equal(h.calls.length, 0)
})
test('single-child parent resolves own child even when the stored child is stale', async () => {
  const h = env(); h.storage['parentChild:test:8'] = { accountId: '999' }; await h.page.load()
  assert.deepEqual(h.routes, ['/pages/homework/homework?accountId=12'])
  assert.equal(h.storage['parentChild:test:8'].accountId, '12')
})
test('multi-child parent chooses only a child returned for their own account', async () => {
  const h = env({ children: [{ accountId: 12 }, { accountId: 13 }] }); await h.page.load()
  assert.equal(h.routes.length, 0)
  h.page.chooseChild({ currentTarget: { dataset: { id: 999 } } }); assert.equal(h.routes.length, 0)
  h.page.chooseChild({ currentTarget: { dataset: { id: 13 } } })
  assert.deepEqual(h.routes, ['/pages/homework/homework?accountId=13'])
})
test('staff without parent role cannot enter parent homework', async () => {
  const h = env({ roles: ['teacher'] }); await h.page.load()
  assert.equal(h.page.data.wrongRole, true); assert.equal(h.calls.length, 1); assert.equal(h.routes.length, 0)
})
test('dual-role recipient switches to their own parent workspace', async () => {
  const h = env({ roles: ['teacher', 'parent'] }); h.storage['miniappWorkspace:8'] = 'teacher'; await h.page.load()
  assert.equal(h.storage['miniappWorkspace:8'], 'parent')
  assert.deepEqual(h.routes, ['/pages/homework/homework?accountId=12'])
})
test('recipient without child records gets an add-child entry', async () => {
  const h = env({ children: [] }); await h.page.load(); assert.equal(h.page.data.noChildren, true)
  h.page.addChild(); assert.deepEqual(h.routes, ['/pages/parent-account/parent-account?create=1'])
})
test('an account change while the recipient is loading cannot select the old account child', async () => {
  let resolve
  const h = env({ request: () => new Promise(r => resolve = r) }); const waiting = h.page.load()
  h.storage.token = 'another-parent'; resolve({ user: { userId: 8 }, roles: ['parent'] }); await waiting
  assert.equal(h.routes.length, 0); assert.equal(h.calls.length, 1)
})
test('leaving a share entry before loading completes cannot redirect the next page', async () => {
  let resolve
  const h = env({ request: () => new Promise(r => resolve = r) }); const waiting = h.page.load()
  h.page.onHide(); resolve({ user: { userId: 8 }, roles: ['parent'] }); await waiting
  assert.equal(h.routes.length, 0)
})
test('normal login still opens home, and shared-link login returns to the homework entry', async () => {
  const h = env(); let page
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/register/register.js'), 'utf8'), {
    Page: p => page = p, wx: h.wx, getApp: () => h.app,
    require: name => name.endsWith('/access') ? { developmentBuild: () => false } : {}
  })
  page.setData = v => Object.assign(page.data, v)
  await page.onLoad({ next: 'https://other.invalid' }); assert.equal(h.routes.pop(), '/pages/index/index')
  await page.onLoad({ next: 'homework' }); assert.equal(h.routes.pop(), '/pages/homework-share/homework-share')
  assert.equal(page.data.autoPreview, false)
})
test('expired session retains homework destination but refuses arbitrary login redirect URLs', async () => {
  for (const [redirect, expected] of [['/pages/register/register?next=homework', '/pages/register/register?next=homework'], ['https://other.invalid', '/pages/register/register']]) {
    const h = env(), module = { exports: {} }
    h.wx.request = o => o.success({ statusCode: 401, data: { code: 401, msg: 'expired' } })
    vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/request.js'), 'utf8'), {
      module, wx: h.wx, getApp: () => h.app, setTimeout: fn => fn(),
      require: () => ({ prepare: o => ({ options: o, ticket: null }) })
    })
    await assert.rejects(module.exports.request({ url: '/getInfo', loginRedirect: redirect }))
    assert.deepEqual(h.routes, [expected]); assert.equal(h.storage.token, undefined)
  }
})
