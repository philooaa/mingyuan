const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function setup(roles=['education'],send,confirm=true) {
  let definition
  const storage={token:'staff-session',roles},requests=[]
  const request=async options=>{
    requests.push(options)
    if(send){const result=send(options);if(result!==undefined)return result}
    if(options.url==='/getInfo')return {roles:storage.roles}
    if(options.url.endsWith('/options'))return {data:{classrooms:{'万达':[]},teachers:[{userId:7,realName:'老师甲'}],grades:[{label:'初一',value:'初一'}],classTypes:[{label:'卓越班',value:'卓越班'}]}}
    if(options.url.endsWith('/list'))return {rows:[],total:0}
    return {data:1}
  }
  const wxApi={getStorageSync:key=>storage[key],showToast(){},showModal:async()=>({confirm}),stopPullDownRefresh(){}}
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/admin-schedule/admin-schedule.js'),'utf8'),{Page:p=>definition=p,wx:wxApi,require:()=>({request})})
  const p={...definition,data:structuredClone(definition.data)}
  p.setData=change=>{for(const [key,value]of Object.entries(change)){const keys=key.split('.');let obj=p.data;while(keys.length>1)obj=obj[keys.shift()];obj[keys[0]]=value}}
  p.onLoad();p._visible=true
  return {p,storage,requests,wx:wxApi}
}
test('only education or administrator accounts can load the planning page',async()=>{
  for(const roles of [['education'],['admin'],['business_admin','teacher']]) {
    const {p,requests}=setup(roles);await p.load();assert.equal(p.data.ready,true);assert.ok(requests.some(r=>r.url.endsWith('/list')))
  }
  const {p,requests}=setup(['teacher']);await p.load();assert.equal(requests.length,0);assert.equal(p.data.ready,false)
})
test('publishing uses the selected campus and teacher, without assigning a classroom',async()=>{
  const {p,requests}=setup();await p.load();p.add()
  Object.assign(p.data.form,{campusName:'万达',teacherId:'7',unitPrice:'100',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',startTime:'08:00:00',endTime:'10:00:00',gradeName:'初一',subjectName:'数学'})
  await p.save()
  const body=requests.find(r=>r.method==='POST').data
  assert.equal(body.campusName,'万达');assert.equal(body.teacherId,7);assert.equal(body.recruitStatus,'0')
  assert.equal(Object.hasOwn(body,'classroomId'),false);assert.equal(p.data.formOpen,false)
})
test('staff select a class type from enabled dictionary options',async()=>{
  const {p,requests}=setup();await p.load();p.add()
  Object.assign(p.data.form,{campusName:'万达',teacherId:'7',unitPrice:'100',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',gradeName:'初一',subjectName:'数学'})
  p.choose({currentTarget:{dataset:{key:'classType'}},detail:{value:0}})
  await p.save()
  assert.equal(requests.find(r=>r.method==='POST').data.classType,'卓越班')
})
test('course name follows selected dimensions while retaining manual edits',async()=>{
  const {p}=setup();await p.load();p.add()
  Object.assign(p.data.form,{gradeName:'高一',subjectName:'数学',classType:'尖子班'})
  p.chooseTeacher({detail:{value:0}});assert.equal(p.data.form.courseClassName,'高一数学尖子班老师甲')
  p.data.form.subjectName='英语';p.autoCourseName();assert.equal(p.data.form.courseClassName,'高一英语尖子班老师甲')
  p.data.form.courseClassName='手动课程名';p.data.form.subjectName='物理';p.autoCourseName();assert.equal(p.data.form.courseClassName,'手动课程名')
})
test('recognition fills empty fields and keeps existing fields when replacement is declined',async()=>{
  const result={data:{gradeName:'初一',subjectName:'数学',classType:'卓越班',teacherId:'7',teacherName:'老师甲',warnings:[]}}
  const {p}=setup(['education'],o=>o.url.endsWith('/recognize-name')?result:undefined,false)
  await p.load();p.add();p.data.form.courseClassName='初一数学卓越班老师甲';p.data.form.gradeName='初二'
  await p.recognizeName()
  assert.equal(p.data.form.gradeName,'初二');assert.equal(p.data.form.subjectName,'数学')
  assert.equal(p.data.form.classType,'卓越班');assert.equal(p.data.form.teacherId,'7');assert.equal(p.data.form.teacherLabel,'老师甲')
})
test('an outdated recognition response cannot overwrite a newer course name',async()=>{
  let finish
  const {p}=setup(['education'],o=>o.url.endsWith('/recognize-name')?new Promise(resolve=>{finish=resolve}):undefined)
  await p.load();p.add();p.data.form.courseClassName='初一数学卓越班老师甲'
  const pending=p.recognizeName();p.data.form.courseClassName='初二英语'
  finish({data:{gradeName:'初一',subjectName:'数学',teacherId:'7',warnings:[]}});await pending
  assert.equal(p.data.form.gradeName,'');assert.equal(p.data.form.teacherId,'')
})
test('new courses require a positive unit price with at most two decimals',async()=>{
  const {p,requests}=setup();await p.load();p.add()
  Object.assign(p.data.form,{campusName:'万达',teacherId:'7',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',gradeName:'初一',subjectName:'数学'})
  for(const price of ['', '0', '-1', '1.234']) { p.data.form.unitPrice=price; await p.save(); assert.equal(requests.filter(r=>r.method==='POST').length,0) }
  p.data.form.unitPrice='0.5'; await p.save(); assert.equal(requests.filter(r=>r.method==='POST').length,1)
})
test('unit price supports presets and manual input',async()=>{
  const {p}=setup();await p.load();p.add()
  for (const [i,value] of ['220','250','280'].entries()) { p.choosePrice({detail:{value:i}});assert.equal(p.data.form.unitPrice,value) }
  p.choosePrice({detail:{value:3}});assert.equal(p.data.form.unitPrice,'');assert.equal(p.data.priceChoice,'手动填写')
  p.input({currentTarget:{dataset:{key:'unitPrice'}},detail:{value:'168.50'}});assert.equal(p.data.form.unitPrice,'168.50')
})
test('uncertain saves retain the course code instead of generating another new course identity',async()=>{
  const {p,requests}=setup(['admin'],o=>o.method==='POST'?Promise.reject(new Error('timeout')):undefined)
  await p.load();p.add()
  Object.assign(p.data.form,{campusName:'万达',teacherId:'7',unitPrice:'100',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',gradeName:'初一',subjectName:'数学'})
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

test('editing and deleting require confirmation; cancelling never sends a mutation',async()=>{
  for(const confirm of [false,true]) {
    const {p,requests}=setup(['education'],undefined,confirm);await p.load();p.add()
    Object.assign(p.data.form,{scheduleId:10,campusName:'万达',teacherId:'7',unitPrice:'100',courseClassName:'初一数学',termName:'秋季',periodName:'周六',timeSlot:'8:00-10:00',gradeName:'初一',subjectName:'数学'})
    await p.save()
    assert.equal(requests.some(r=>r.method==='PUT'),confirm)
    if(confirm)assert.equal(requests.find(r=>r.method==='PUT').url,'/miniapp/staff/schedule?force=true')
    await p.remove({currentTarget:{dataset:{id:10}}})
    assert.equal(requests.some(r=>r.method==='DELETE'),confirm)
    if(confirm)assert.equal(requests.find(r=>r.method==='DELETE').url,'/miniapp/staff/schedule/10?force=true')
  }
})
test('teacher cannot force delete a course',async()=>{
  const {p,requests}=setup(['teacher']);await p.load()
  await p.remove({currentTarget:{dataset:{id:10}}});assert.equal(requests.length,0)
})

test('class-only 90 minute slots fill times and are cleared when switching to one-to-one',async()=>{
  const {p}=setup(['education'],o=>o.url.endsWith('/options')?{data:{classrooms:{'万达':[]},timeSlots:[{value:'8:00-10:00'},{value:'8:30-10:00'}],singleTimeSlots:[{value:'8:00-10:00'}]}}:undefined)
  await p.load();p.add()
  p.choose({currentTarget:{dataset:{key:'timeSlot'}},detail:{value:1}})
  assert.equal(p.data.form.timeSlot,'8:30-10:00');assert.equal(p.data.form.startTime,'08:30:00');assert.equal(p.data.form.endTime,'10:00:00')
  p.choose({currentTarget:{dataset:{key:'classMode'}},detail:{value:1}})
  assert.equal(p.data.form.timeSlot,'');assert.equal(p.data.form.startTime,'')
  assert.deepEqual(Array.from(p.data.fields.find(f=>f.key==='timeSlot').options,s=>s.value),['8:00-10:00'])
  p.choose({currentTarget:{dataset:{key:'classMode'}},detail:{value:0}})
  assert.equal(p.data.fields.find(f=>f.key==='timeSlot').options.length,2)
})

const weekdays=['周一','周二','周三','周四','周五','周六','周日']
const periodSetup=()=>setup(['education'],o=>o.url.endsWith('/options')?{data:{periods:['一期','二期','三期',...weekdays].map(value=>({value,label:value}))}}:undefined)
const choosePattern=(p,value)=>p.choose({currentTarget:{dataset:{key:'classPattern'}},detail:{value}})
test('period field follows the class pattern and clears incompatible selections',async()=>{
  const {p}=periodSetup();await p.load();p.add()
  const field=()=>p.data.fields.find(f=>f.key==='periodName')
  assert.equal(field().label,'上课星期');assert.deepEqual(Array.from(field().options,r=>r.value),weekdays)
  p.data.form.periodName='周三';choosePattern(p,2)
  assert.equal(p.data.form.periodName,'');assert.equal(field().label,'期次')
  assert.deepEqual(Array.from(field().options,r=>r.value),['一期','二期','三期'])
  p.data.form.periodName='二期';choosePattern(p,6)
  assert.equal(p.data.form.periodName,'二期');choosePattern(p,0)
  assert.equal(p.data.form.periodName,'')
})
test('editing a daily course opens with period options without erasing its saved period',async()=>{
  const {p}=setup(['education'],o=>o.url.endsWith('/options')?{data:{periods:['一期','周一'].map(value=>({value,label:value}))}}:o.url.endsWith('/12')?{data:{scheduleId:12,classMode:'1',classPattern:'DAILY_5_1',periodName:'一期'}}:undefined)
  await p.load();await p.edit({currentTarget:{dataset:{id:'12'}}})
  assert.equal(p.data.form.periodName,'一期');assert.equal(p.data.fields.find(f=>f.key==='periodName').label,'期次')
})
test('switching to one-to-one derives its weekday from the date instead of keeping a period',async()=>{
  const {p}=periodSetup();await p.load();p.add();choosePattern(p,1)
  Object.assign(p.data.form,{periodName:'二期',startDate:'2026-11-11',endDate:'2026-12-30'})
  p.choose({currentTarget:{dataset:{key:'classMode'}},detail:{value:1}})
  assert.equal(p.data.form.classPattern,'WEEKLY');assert.equal(p.data.form.periodName,'周三')
  assert.equal(p.data.form.endDate,'2026-11-11');assert.equal(p.data.patternIndex,0)
})
test('weekly course cannot save an incompatible period',async()=>{
  const {p,requests}=periodSetup();await p.load();p.add()
  Object.assign(p.data.form,{periodName:'一期',unitPrice:'100'})
  await p.save();assert.equal(p.data.formError,'请选择上课星期')
  assert.ok(!requests.some(r=>r.method==='POST'))
})

test('staff billing count mismatch requires a reason and final confirmation',async()=>{
  const {p,requests,wx}=setup(['education'],o=>o.url==='/system/coursePrice/lesson-count/12' && !o.method?{data:{systemLessonCount:14,lessonCount:14,lessonCountVersion:'fresh'}}:undefined)
  await p.load();p.add();Object.assign(p.data.form,{scheduleId:12,classMode:'1'})
  wx.showActionSheet=async()=>({tapIndex:0})
  const replies=[{confirm:true,content:'12'},{confirm:true,content:'少计费两讲'},{confirm:true}]
  wx.showModal=async()=>replies.shift()
  await p.editBillingCount()
  const sent=requests.find(r=>r.method==='PUT');assert.equal(sent.data.lessonCount,12);assert.equal(sent.data.confirmMismatch,true);assert.equal(sent.data.version,'fresh');assert.equal(p.data.saving,false)
})
test('canceling billing count confirmation never sends a mutation',async()=>{
  const {p,requests,wx}=setup(['education'],o=>o.url==='/system/coursePrice/lesson-count/12' && !o.method?{data:{systemLessonCount:14,lessonCount:12,lessonCountVersion:'fresh'}}:undefined)
  await p.load();p.add();Object.assign(p.data.form,{scheduleId:12,classMode:'1'})
  wx.showActionSheet=async()=>({tapIndex:1});wx.showModal=async()=>({confirm:false})
  await p.editBillingCount();assert.ok(!requests.some(r=>r.method==='PUT'));assert.equal(p.data.saving,false)
})
