const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const root=path.join(__dirname,'..'),plain=x=>JSON.parse(JSON.stringify(x))
function harness(name,request,role='parent',extra={}) {
  const storage={token:'session',roles:[role]},notices=[]
  const wx={getStorageSync:k=>storage[k],showToast:o=>notices.push(o),showModal:o=>o.success({confirm:true,content:'拒绝原因'}),stopPullDownRefresh(){},...extra}
  const module={exports:{}}
  vm.runInNewContext(fs.readFileSync(path.join(root,'utils/class-leave.js'),'utf8'),{module,require:id=>id.endsWith('access')?require('./helpers/access.cjs')(wx):id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):{request},wx,console})
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(root,'pages',name,name+'.js'),'utf8'),{Page:p=>definition=p,require:id=>id.endsWith('access')?require('./helpers/access.cjs')(wx):id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):id.endsWith('class-leave')?module.exports:id.endsWith('teacher-course-state')?require('../utils/teacher-course-state'):{request},wx,console,setTimeout:fn=>fn(),setInterval,clearInterval})
  const p={...definition,data:structuredClone(definition.data),_alive:true,_visible:true,_token:'session',_loadedToken:'session'}
  p.setData=u=>Object.assign(p.data,u)
  return {p,storage,notices}
}
const click=(id,action)=>({currentTarget:{dataset:{id,action}}}),pick=value=>({detail:{value}})
const enrollment={enrollmentId:'11',childKey:'S:1',childName:'孩子甲',courseClassName:'班课甲',teacherName:'教师全名'}
const row={leaveId:'1',version:3,state:'PENDING',studentName:'孩子甲',courseName:'班课甲',timeText:'2030-10-12 08:00–10:00'}

test('numeric API identifiers open the matching application history panel',async()=>{
  const {p}=harness('teacher-leave',o=>Promise.resolve({data:o.url.endsWith('/1')?{history:JSON.stringify([{action:'SUBMIT',version:1,reason:'原因',time:'2030-10-11T10:00:00'}])}:{rows:[{...row,leaveId:1}],total:1,pendingCount:1}}),'teacher')
  await p.load();await p.showHistory(click(1));assert.equal(p.data.historyId,p.data.rows[0].leaveId);assert.equal(p.data.history[0].label,'提交申请')
})
test('class leave selects stable child, class and explicit actual occurrence; does not send parent or teacher identity',async()=>{
  const calls=[];const {p}=harness('parent-leave',o=>{calls.push(o);return Promise.resolve({data:o.url.endsWith('/options')?[enrollment]:o.url.endsWith('/lessons')?[{classDate:'2030-10-12',startTime:'08:00',endTime:'10:00',version:2}]:{rows:[],total:0}})})
  await p.load();p.openForm();p.chooseChild(pick(0));await p.chooseCourse(pick(0));p.chooseLesson(pick(0));p.inputReason(pick('身体不适'));await p.submit()
  const post=calls.find(o=>o.method==='POST');assert.equal(post.url,'/miniapp/parent/leave');assert.deepEqual(plain(post.data),{enrollmentId:'11',classDate:'2030-10-12',reason:'身体不适',version:2});assert.equal(p.data.showForm,false)
})
test('uncertain submit keeps form and original selection available for safe retry',async()=>{
  const {p}=harness('parent-leave',()=>Promise.reject(new Error('timeout')));p.data.showForm=true;p.data.courses=[enrollment];p.data.courseIndex=0;p.data.lessons=[{classDate:'2030-10-12',version:null}];p.data.lessonIndex=0;p.data.reason='原因';await p.submit();assert.equal(p.data.showForm,true);assert.equal(p.data.reason,'原因');assert.equal(p.data.submitting,false)
})
test('changing child discards previous class/date and late occurrence response',async()=>{
  let finish;const {p}=harness('parent-leave',()=>new Promise(r=>finish=r));p.data.options=[enrollment,{...enrollment,enrollmentId:'12',childKey:'S:2'}];p.data.children=[{key:'S:1'},{key:'S:2'}];p.chooseChild(pick(0));const work=p.chooseCourse(pick(0));p.chooseChild(pick(1));finish({data:[{classDate:'2030-10-12',startTime:'08:00',endTime:'10:00'}]});await work;assert.equal(p.data.courseIndex,-1);assert.equal(p.data.lessons.length,0)
})
test('identity switch drops late lists and clears private content',async()=>{
  let finish;const {p,storage}=harness('teacher-leave',()=>new Promise(r=>finish=r),'teacher');const work=p.load();storage.token='other';finish({data:{rows:[row],total:1}});await work;assert.equal(p.data.rows.length,0);p.onShow();assert.match(p.data.error,/登录身份已变化/)
})
test('withdrawal confirmation cannot execute after identity changes',async()=>{
  let modal;const calls=[];const {p,storage}=harness('parent-leave',o=>{calls.push(o);return Promise.resolve({})},'parent',{showModal:o=>modal=o});p.data.rows=[row];const work=p.act(click('1','WITHDRAW'));storage.token='other';modal.success({confirm:true});await work;assert.equal(calls.length,0)
})
test('teacher review sends version and reason; parent cannot forge teacher action',async()=>{
  const calls=[];const {p}=harness('teacher-leave',o=>{calls.push(o);return Promise.resolve({data:{rows:[],total:0}})},'teacher');p.data.rows=[row];await p.act(click('1','REJECT'));const post=calls.find(c=>c.method==='POST');assert.equal(post.url,'/miniapp/teacher/leave/1/review');assert.equal(post.data.version,3);assert.equal(post.data.reason,'拒绝原因')
  const parent=harness('parent-leave',()=>{throw Error('must not request')});parent.p.data.rows=[row];await parent.p.act(click('1','APPROVE'))
})
test('review is guarded against double clicks while modal is open',async()=>{
  let modal;let count=0;const {p}=harness('teacher-leave',()=>Promise.resolve({data:{rows:[]}}),'teacher',{showModal:o=>{modal=o;count++}});p.data.rows=[row];const work=p.act(click('1','APPROVE'));await p.act(click('1','APPROVE'));assert.equal(count,1);modal.success({confirm:false});await work;assert.equal(p.data.submitting,false)
})
test('missing occurrence or reason cannot submit',async()=>{
  let count=0;const {p}=harness('parent-leave',()=>{count++;return Promise.resolve({})});p.data.courses=[enrollment];p.data.courseIndex=0;p.data.reason='原因';await p.submit();assert.equal(count,0)
})
test('approved leave prefills new and existing unreviewed sign rows using enrollment identity',async()=>{
  for(const existing of [false,true]) {
    const {p}=harness('sign-in-edit',o=>Promise.resolve({data:o.url.endsWith('/detail')?{review:{reviewStatus:'0'},approvedLeaveEnrollmentIds:['11'],details:existing?[{enrollmentId:11,studentName:'孩子甲',sourceType:'1',signStatus:'1'}]:[]}:[{enrollment_id:'11',student_name:'孩子甲'}]}),'teacher')
    p.data.scheduleId=1;await p.loadDetail();assert.equal(p.data.rows[0].signStatus,'3');assert.equal(p.data.locked,false)
    p.setStatus({currentTarget:{dataset:{rowKey:p.data.rows[0].rowKey,key:'1'}}});assert.equal(p.data.rows[0].signStatus,'1','manual roll-call status remains available')
  }
})
test('first administrator review locks original attendance and suppresses leave overlay',async()=>{
  const {p}=harness('sign-in-edit',()=>Promise.resolve({data:{review:{reviewStatus:'0',review1AdminId:'1'},approvedLeaveEnrollmentIds:['11'],details:[{enrollmentId:'11',studentName:'孩子甲',sourceType:'1',signStatus:'1'}]}}),'teacher');p.data.scheduleId=1;await p.loadDetail();assert.equal(p.data.rows[0].signStatus,'1');assert.equal(p.data.locked,true);assert.equal(p.canEdit(),false)
})
test('teacher homepage leave badge is scoped, hides zero and caps at 99+',async()=>{
  let count=101;const {p}=harness('index',()=>Promise.resolve({data:{pendingCount:count}}),'teacher');p.data.currentRole='teacher';p.data.quickActions=[{key:'teacherLeave'},{key:'teacherCourses',badgeText:'7'}];await p.loadLeavePending();assert.equal(p.data.quickActions[0].badgeText,'99+');assert.equal(p.data.quickActions[1].badgeText,'7');count=0;await p.loadLeavePending();assert.equal(p.data.quickActions[0].badgeText,'')
})
test('teacher homepage ignores old teacher pending count after switching session',async()=>{
  let finish;const {p,storage}=harness('index',()=>new Promise(r=>finish=r),'teacher');p.data.currentRole='teacher';p.data.quickActions=[{key:'teacherLeave'}];const work=p.loadLeavePending();storage.token='new-teacher';finish({data:{pendingCount:8}});await work;assert.equal(p.data.quickActions[0].badgeText,undefined)
})
