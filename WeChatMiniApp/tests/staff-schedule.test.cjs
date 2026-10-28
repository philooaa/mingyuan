const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function setup(roles=['education'],send) {
  let definition
  const storage={token:'staff-session',roles},requests=[]
  const request=async options=>{
    requests.push(options)
    if(send){const result=send(options);if(result!==undefined)return result}
    if(options.url==='/getInfo')return {roles:storage.roles}
    if(options.url.endsWith('/options'))return {data:{classrooms:{'万达':[]},teachers:[{userId:7,realName:'老师甲'}],grades:[{label:'初一',value:'初一'}]}}
    if(options.url.endsWith('/list'))return {rows:[],total:0}
    return {data:1}
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/admin-schedule/admin-schedule.js'),'utf8'),{Page:p=>definition=p,wx:{getStorageSync:key=>storage[key],showToast(){},stopPullDownRefresh(){}},require:()=>({request})})
  const p={...definition,data:structuredClone(definition.data)}
  p.setData=change=>{for(const [key,value]of Object.entries(change)){const keys=key.split('.');let obj=p.data;while(keys.length>1)obj=obj[keys.shift()];obj[keys[0]]=value}}
  p.onLoad();p._visible=true
  return {p,storage,requests}
}
test('only education or administrator accounts can load the planning page',async()=>{
  for(const roles of [['education'],['admin'],['business_admin','teacher']]) {
    const {p,requests}=setup(roles);await p.load();assert.equal(p.data.ready,true);assert.ok(requests.some(r=>r.url.endsWith('/list')))
  }
  const {p,requests}=setup(['teacher']);await p.load();assert.equal(requests.length,0);assert.equal(p.data.ready,false)
})
test('publishing uses the selected campus and teacher, without assigning a classroom',async()=>{
  const {p,requests}=setup();await p.load();p.add()
  Object.assign(p.data.form,{campusName:'万达',teacherId:'7',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',startTime:'08:00:00',endTime:'10:00:00',gradeName:'初一',subjectName:'数学'})
  await p.save()
  const body=requests.find(r=>r.method==='POST').data
  assert.equal(body.campusName,'万达');assert.equal(body.teacherId,7);assert.equal(body.recruitStatus,'0')
  assert.equal(Object.hasOwn(body,'classroomId'),false);assert.equal(p.data.formOpen,false)
})
test('uncertain saves retain the course code instead of generating another new course identity',async()=>{
  const {p,requests}=setup(['admin'],o=>o.method==='POST'?Promise.reject(new Error('timeout')):undefined)
  await p.load();p.add()
  Object.assign(p.data.form,{campusName:'万达',teacherId:'7',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',gradeName:'初一',subjectName:'数学'})
  await p.save();await p.save()
  const writes=requests.filter(r=>r.method==='POST');assert.equal(writes.length,2);assert.equal(writes[0].data.scheduleCode,writes[1].data.scheduleCode)
  assert.match(p.data.formError,/刷新列表核对/)
})
test('changed tokens and revoked roles cannot publish through an old form',async()=>{
  for(const kind of ['token','role']) {
    const {p,storage,requests}=setup();await p.load();p.add()
    if(kind==='token')storage.token='another-session';else storage.roles=['teacher']
    await p.save();assert.equal(requests.some(r=>r.method==='POST'),false)
  }
})
