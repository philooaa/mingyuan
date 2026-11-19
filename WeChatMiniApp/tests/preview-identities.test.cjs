const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
function load() {
  let page
  const selectedRoles = []
  const teachers = Array.from({length: 20}, (_, i) => ({userId: String(i), name: i === 19 ? '王文晶' : `教师${i}`}))
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/register/register.js'), 'utf8'), {
    Page: value => page = value, wx: {showToast() {}, getStorageSync() {}, reLaunch() {}},
    require: id => id.endsWith('request') ? {request: async o => o.url === '/miniapp/login' ? {token:'test-token'} : o.url === '/getInfo' ? {user:{userId:19}, roles:['teacher','business_admin']} : {data: teachers}} : id.endsWith('access') ? {select: role => selectedRoles.push(role)} : {}
  })
  page.data = structuredClone(page.data)
  page.setData = values => Object.assign(page.data, values)
  page.selectedRoles = selectedRoles
  return page
}
test('完整教师列表可搜索并选择第六人之后的教师', async () => {
  const page = load()
  await page.previewIdentity('teacher')
  assert.equal(page.data.previewIdentities.length, 20)
  page.searchPreviewIdentity({detail:{value:'王文晶'}})
  assert.equal(page.data.filteredPreviewIdentities.length, 1)
  let selected
  page.handleLocalPreview = (role, id) => selected = {role, id}
  page.selectPreviewIdentity({currentTarget:{dataset:{id:'19'}}})
  assert.deepEqual(selected, {role:'teacher', id:'19'})
  assert.equal(page.data.previewIdentityVisible, false)
})

test('选择多身份教师后进入教师工作台', async () => {
  const page = load()
  page.data.localPreviewEnabled = true
  page.isLocalPreviewEnvironment = () => true
  page.activateAccount = () => {}
  await page.handleLocalPreview('teacher', '19')
  assert.deepEqual(page.selectedRoles, ['teacher'])
  assert.equal(page.data.loginError, '')
})
