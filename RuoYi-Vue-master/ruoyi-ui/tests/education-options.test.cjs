const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue'), compiler = require('vue-template-compiler')
Vue.config.silent = true
const root = path.resolve(__dirname, '../src/views/system')
const subjects = ['语文', '数学', '英语', '政治', '历史', '地理', '物理', '化学', '生物']
function render(name, extra = {}) {
  const sfc = compiler.parseComponent(fs.readFileSync(path.join(root, name, 'index.vue'), 'utf8'))
  const script = sfc.script.content.replace(/^import\s+['"][^'"]+['"];?\s*$/gm, '').replace(/^import\s+[\s\S]*?\sfrom\s+['"][^'"]+['"];?[ \t]*$/gm, '').replace('export default', 'definition =')
  const context = { definition: null, passwordRule: {}, Treeselect: {}, TreePanel: {}, ExcelImportDialog: {}, UserViewDrawer: {}, ReferralRewards: {}, OneToOneManager: {}, money: v => v, label: v => v, statusType: () => '', clearInterval() {}, getToken: () => 'test-token', process: { env: {} } }
  vm.runInNewContext(script, context)
  const definition = context.definition
  const dictionary = { type: { edu_subject: subjects.map((label, i) => ({ label, value: 'subject-' + i })), edu_grade: [{ label: '五年级', value: 'g5' }], sys_normal_disable: [], sys_user_sex: [] } }
  const compiled = compiler.compileToFunctions(sfc.template.content)
  const instance = new Vue({ ...definition, created: undefined, mounted: undefined, mixins: [], components: {}, data() { return { ...definition.data.call(this), dict: dictionary, ...extra } }, render: compiled.render, staticRenderFns: compiled.staticRenderFns, methods: { ...definition.methods, parseTime: value => value } })
  const tree = instance._render(), nodes = []
  function visit(node) { if (!node) return; nodes.push(node); (node.children || []).forEach(visit) }
  visit(tree); instance.$destroy(); nodes.instance = instance; return nodes
}
test('teacher authorization renders real subject labels and submits subject names rather than dictionary codes', () => {
  const nodes = render('user', { form: { userId: 7, roleIds: [101] }, roleOptions: [{ roleId: 101, roleKey: 'teacher' }], teacherProfile: {} })
  const select = nodes.find(n => n.data?.model?.expression === 'teacherProfile.teacherSubject')
  assert.ok(select)
  assert.deepEqual(select.children.filter(n => n.tag === 'el-option').map(n => [n.data.attrs.label, n.data.attrs.value]), subjects.map(s => [s, s]))
})
test('user editor hides unused posts and remarks without clearing historical values or role controls', () => {
  const nodes = render('user', { form: { userId: 7, roleIds: [101], postIds: [3], remark: '旧账号生成说明' }, roleOptions: [{ roleId: 101, roleKey: 'teacher' }], teacherProfile: {} })
  assert.equal(nodes.some(n => ['form.postIds', 'form.remark'].includes(n.data?.model?.expression)), false)
  assert.ok(nodes.some(n => n.data?.model?.expression === 'form.roleIds'))
  assert.ok(nodes.some(n => n.data?.model?.expression === 'teacherProfile.teacherSubject'))
  assert.deepEqual(Array.from(nodes.instance.form.postIds), [3]); assert.equal(nodes.instance.form.remark, '旧账号生成说明')
})
test('user editor permits editing login name and nickname while preserving multiple roles', () => {
  const nodes = render('user', { form: { userId: 7, userName: 'teacher', nickName: '老师', roleIds: [101, 102] }, roleOptions: [] })
  assert.ok(nodes.some(n => n.data?.model?.expression === 'form.userName'))
  assert.ok(nodes.some(n => n.data?.model?.expression === 'form.nickName'))
  assert.equal(nodes.some(n => n.data?.model?.expression === 'form.deptId'), false)
  const roles = nodes.find(n => n.data?.model?.expression === 'form.roleIds')
  assert.equal(roles.data.attrs.multiple, '')
  assert.deepEqual(Array.from(nodes.instance.form.roleIds), [101, 102])
})
test('user sidebar filters by role and resets the filter without using departments', () => {
  const { instance } = render('user')
  let queries = 0
  instance.handleQuery = () => { queries++ }
  instance.handleNodeClick({ id: 101 })
  assert.equal(instance.queryParams.roleId, 101)
  assert.equal(instance.queryParams.deptId, undefined)
  instance.handleNodeClick({ id: 'all' })
  assert.equal(instance.queryParams.roleId, undefined)
  assert.equal(queries, 2)
})
test('lesson account grade selection renders labels and preserves the existing grade-name API contract', () => {
  const nodes = render('transaction', { formKind: 'create', formOpen: true })
  const select = nodes.find(n => n.data?.model?.expression === 'form.gradeName')
  assert.ok(select)
  assert.deepEqual(select.children.filter(n => n.tag === 'el-option').map(n => [n.data.attrs.label, n.data.attrs.value]), [['五年级', '五年级']])
})
test('schedule editing and filtering use selectors for grades and subjects instead of free input', () => {
  const nodes = render('schedule')
  for (const binding of ['form.gradeName', 'form.subjectName', 'queryParams.gradeName', 'queryParams.subjectName']) {
    const node = nodes.find(n => n.data?.model?.expression === binding)
    assert.ok(node, binding); assert.equal(node.tag, 'el-select', binding)
  }
})
test('coupon recipient filters use the existing grade and subject options', () => {
  const nodes = render('coupon', { subjectOptions: subjects })
  for (const binding of ['parentQuery.gradeName', 'parentQuery.subjectName']) {
    const node = nodes.find(n => n.data?.model?.expression === binding)
    assert.ok(node, binding); assert.equal(node.tag, 'el-select', binding)
  }
})
test('choosing a timetable slot updates actual start and end times used for schedule conflicts', () => {
  const { instance } = render('schedule')
  instance.form = { startTime: '08:00:00', endTime: '10:00:00' }
  instance.onTimeSlotChange('14:00-16:00')
  assert.equal(instance.form.startTime, '14:00:00'); assert.equal(instance.form.endTime, '16:00:00')
  instance.onTimeSlotChange('8:00-10:00')
  assert.equal(instance.form.startTime, '08:00:00'); assert.equal(instance.form.endTime, '10:00:00')
})
