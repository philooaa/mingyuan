const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
const root=path.resolve(__dirname,'..')
test('quarter and payment apply to the same course; unregistered profiles are included',async()=>{
  let def
  const rows=[
    {studentId:1,parentId:1,studentName:'甲',enrollmentStatus:'1',courseYear:2026,termName:'秋季',billingStatus:'OPEN',billingPayStatus:'1'},
    {studentId:1,parentId:1,studentName:'甲',enrollmentStatus:'1',courseYear:2026,termName:'秋季',billingStatus:'OPEN',billingPayStatus:'0'},
    {studentId:2,parentId:2,studentName:'乙',enrollmentStatus:'1',courseYear:2026,termName:'春季',billingStatus:'OPEN',billingPayStatus:'1'},
    {studentId:2,parentId:2,studentName:'乙',enrollmentStatus:'1',courseYear:2026,termName:'秋季',billingStatus:'PENDING_PRICE',billingPayStatus:'0'}]
  const request=async o=>o.url==='/system/enrollment/list'?{rows,total:rows.length}:o.url.endsWith('student-profiles')?{data:[{studentId:1,parentId:1,studentName:'甲'},{studentId:2,parentId:2,studentName:'乙'},{studentId:3,parentId:3,studentName:'丙'}]}:o.url.endsWith('filter-options')?{data:[{courseYear:2026,termName:'秋季'},{courseYear:2026,termName:'春季'}]}:{data:[]}
  vm.runInNewContext(fs.readFileSync(path.join(root,'pages/admin-students/admin-students.js'),'utf8'),{Page:p=>def=p,require:id=>id.includes('enrollment-payment-status')?require('../utils/enrollment-payment-status'):{request},wx:{showToast(){}}})
  const p={...def,data:structuredClone(def.data),setData(v){Object.assign(this.data,v)}};await p.load()
  const select=(quarter,status)=>{p.data.quarterIndex=p.data.quarterOptions.findIndex(o=>o.value===quarter);p.data.filterStatus=status;p.applyFilter(true);return Array.from(p.data.list,x=>x.studentName)}
  assert.deepEqual(select('2026|秋季','已支付'),['甲'])
  assert.deepEqual(select('2026|秋季','未支付'),['甲'])
  assert.deepEqual(select('2026|秋季','待核价'),['乙'])
  assert.deepEqual(select('2026|秋季','未报名'),['丙'])
  assert.deepEqual(select('2026|春季','未报名').sort(),['甲','丙'].sort())
  assert.deepEqual(select('','未报名'),['丙'])
})
test('account tools are reached through student archive and remain administrator-only',()=>{
  let def, selected='admin', allowed=true;const urls=[]
  const access={selected:()=>selected,has:()=>allowed,can:()=>allowed}
  vm.runInNewContext(fs.readFileSync(path.join(root,'pages/admin-students/admin-students.js'),'utf8'),{Page:p=>def=p,require:id=>id.includes('enrollment-payment-status')?require('../utils/enrollment-payment-status'):id.endsWith('/access')?access:{request(){}},wx:{navigateTo:o=>urls.push(o.url),showToast(){}}})
  const p={...def,data:structuredClone(def.data),setData(v){Object.assign(this.data,v)}}
  p.openAccounts();p.data.detail={parentId:'42'};p.openStudentAccount()
  assert.deepEqual(urls,['/pages/admin-transactions/admin-transactions','/pages/admin-transactions/admin-transactions?parentId=42'])
  selected='education';p.openAccounts();p.openStudentAccount();assert.equal(urls.length,2)
  selected='admin';allowed=false;p.openAccounts();assert.equal(urls.length,2)
})
