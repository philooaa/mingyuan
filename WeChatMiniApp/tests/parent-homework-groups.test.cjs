const test = require('node:test'), assert = require('node:assert/strict')
const vm = require('node:vm'), fs = require('node:fs'), path = require('node:path')
function setup() {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/homework/homework.js'), 'utf8'), {
    Page: p => { definition = p }, wx: { getStorageSync: () => 'parent-token' },
    require: name => name.includes('child-context') ? { page: p => p } : { request: async () => ({ data: [] }) }
  })
  const page = { ...definition, data: structuredClone(definition.data) }
  page.setData = data => Object.assign(page.data, data)
  return page
}
test('parent homework groups by stable class ID and distinguishes classes sharing a name', () => {
  const p = setup()
  p.data.list = [
    { rowKey: '1_10', classId: '10', className: '英语班', category: 'todo' },
    { rowKey: '2_10', classId: '10', className: '英语班', category: 'done' },
    { rowKey: '1_20', classId: '20', className: '英语班', category: 'todo' }
  ]
  p.applyFilter()
  assert.equal(p.data.groups.length, 2)
  assert.deepEqual(Array.from(p.data.groups, g => g.items.length), [2, 1])
  assert.match(p.data.groups[0].displayName, /10/)
  assert.match(p.data.groups[1].displayName, /20/)
  p.switchStatus({ currentTarget: { dataset: { key: 'done' } } })
  assert.equal(p.data.groups.length, 1)
  assert.equal(p.data.groups[0].items[0].rowKey, '2_10')
  p.switchClass({ currentTarget: { dataset: { key: '20' } } })
  assert.equal(p.data.groups.length, 0)
})
test('unmatched class homework remains visible in a separate group', () => {
  const p = setup()
  p.data.list = [{ rowKey: '3_', classId: '', className: '未命名班级', category: 'todo' }]
  p.applyFilter()
  assert.equal(p.data.groups.length, 1)
  assert.equal(p.data.groups[0].items[0].rowKey, '3_')
})
