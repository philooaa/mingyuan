const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const root=path.join(__dirname,'..')
function harness(name,request,role='parent',options={}) {
  const storage={token:'session',roles:[role],userInfo:{phonenumber:'13900000000'}},notices=[],pages=options.pages || []
  const wx={getStorageSync:k=>storage[k],showToast:o=>notices.push(o),showModal:o=>o.success({confirm:true,content:'核对'}),stopPullDownRefresh(){},...options.wx}
  const module={exports:{}}
  vm.runInNewContext(fs.readFileSync(path.join(root,'utils/personal-account.js'),'utf8'),{module,require:()=>({request}),wx,getCurrentPages:()=>pages,console})
  const childModule={exports:{}}
  vm.runInNewContext(fs.readFileSync(path.join(root,'utils/child-account.js'),'utf8'),{module:childModule,require:id=>id.endsWith('access')?require('./helpers/access.cjs')(wx):id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):id.endsWith('personal-account')?module.exports:{request},wx,console})
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(root,'pages',name,name+'.js'),'utf8'),{Page:p=>definition=p,require:id=>id.endsWith('access')?require('./helpers/access.cjs')(wx):id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):id.endsWith('/child-account')?childModule.exports:id.endsWith('/personal-account')?module.exports:id.endsWith('/dev-session')?{}:id.endsWith('/teacher-course-state')?require('../utils/teacher-course-state'):{request},wx,getCurrentPages:()=>pages,console,setInterval,clearInterval})
  const p={...definition,data:structuredClone(definition.data),_alive:true,_unloaded:false,_visible:true,_token:'session'}
  p.setData=updates=>{for(const [key,value] of Object.entries(updates)){const parts=key.split('.');let target=p.data;while(parts.length>1)target=target[parts.shift()];target[parts[0]]=value}}
  return {p,storage,notices}
}
const child={accountId:'17',studentName:'孩子甲',gradeName:'高一',availableCredits:3}
const snapshot={parent:{parentName:'家长甲',parentPhone:'13900000000'},children:[child],pendingCount:2,unlinkedCount:0}
const click=(id,extra={})=>({currentTarget:{dataset:{id,...extra}}})
const plain=value=>JSON.parse(JSON.stringify(value))
test('parent child creation retains one request key through uncertain response then refreshes shared children',async()=>{
  let attempts=0;const posts=[],other={};const {p}=harness('parent-children',o=>{if(o.method==='POST'){posts.push(o);return ++attempts===1?Promise.reject(new Error('timeout')):Promise.resolve({data:17})}return Promise.resolve({data:snapshot})},'parent',{pages:[other]})
  p.data.grades=['高一'];await p.openCreate();p.data.form.studentName='孩子甲';await p.save();assert.equal(p.data.showForm,true);await p.save();assert.equal(posts.length,2);assert.equal(posts[0].url,'/miniapp/parent/children');assert.equal(posts[0].data.requestKey,posts[1].data.requestKey);assert.equal(p.data.showForm,false);assert.equal(other._scheduleDirty,true);assert.equal(p.data.accounts[0].accountId,'17')
})
test('parent page cannot submit admin credit or payment actions even through forged events',async()=>{
  const calls=[];const {p}=harness('parent-account',o=>{calls.push(o);return Promise.resolve({data:snapshot})})
  p.data.accounts=[child];p.openAdjust(click('17',{action:'GRANT'}));assert.equal(p.data.showForm,false);assert.equal(p.markPayment,undefined);assert.equal(calls.length,0)
})
test('identity change discards late personal account responses and clears child details on show',async()=>{
  let respond;const {p,storage}=harness('parent-account',()=>new Promise(r=>respond=r));p.data.tab='children';const running=p.load();storage.token='other';respond({data:snapshot});await running;assert.equal(p.data.detail,null);p.onShow();assert.deepEqual(plain(p.data.accounts),[]);assert.match(p.data.error,/登录身份已变化/)
})
test('deletion confirmation cannot proceed after changing parent identity',async()=>{
  let modal;const calls=[];const {p,storage}=harness('parent-children',o=>{calls.push(o);return Promise.resolve({})},'parent',{wx:{showModal:o=>modal=o}});p.data.accounts=[child];const pending=p.removeChild(click('17'));storage.token='other';modal.success({confirm:true});await pending;assert.equal(calls.length,0)
})
test('admin grants retain request key after uncertain response',async()=>{
  const calls=[];let attempt=0;const {p}=harness('admin-transactions',o=>{if(o.method==='POST'){calls.push(o);return ++attempt===1?Promise.reject(new Error('timeout')):Promise.resolve({})}return Promise.resolve({data:{rows:[],total:0,pendingCount:0}})},'admin');p.data.accounts=[child];p.openAdjust(click('17',{action:'GRANT'}));p.data.form.quantity='15';p.data.form.reason='线下登记';await p.save();assert.equal(p.data.showForm,true);await p.save();assert.equal(calls[0].data.requestKey,calls[1].data.requestKey)
})
test('cash enrollment in both class and single lessons sends stable child identity without holding credits in UI',async()=>{
  for(const single of [false,true]){const posts=[];const {p}=harness('enrollment',o=>{posts.push(o);return Promise.resolve({})});p.loadPage=async()=>{};p.data.lessonAccounts=[child];p.data.selectedChildId='17';p.data.newChild=false;p.data.form={studentName:child.studentName,contactPhone:''};p.data.selectedSchedule={scheduleId:12,classDate:single?'2030-10-07':null};await p.submitEnroll();assert.equal(posts.length,1);assert.equal(posts[0].data.lessonAccountId,'17');assert.equal(posts[0].data.settlementMethod,'C');assert.equal(posts[0].data.studentRequestKey,null)}
})
test('new child enrollment uses stable retry identity and remains open after an uncertain response',async()=>{
  const calls=[];const {p}=harness('enrollment',o=>{calls.push(o);return Promise.reject(new Error('timeout'))});p.data.schedules=[{scheduleId:1,viewKey:'1:class',canEnroll:true}];p.openEnroll({currentTarget:{dataset:{key:'1:class'}}});p.data.form.studentName='新孩子';await p.submitEnroll();await p.submitEnroll();assert.equal(calls.length,2);assert.equal(calls[0].data.lessonAccountId,null);assert.ok(calls[0].data.studentRequestKey);assert.equal(calls[0].data.studentRequestKey,calls[1].data.studentRequestKey);assert.equal(p.data.showSheet,true)
})
test('failed child lookup blocks cash enrollment instead of silently creating a duplicate',async()=>{
  const calls=[];const {p}=harness('enrollment',o=>{calls.push(o);return Promise.resolve({})});p.data.selectedSchedule={scheduleId:12};p.data.form={studentName:'孩子甲',contactPhone:''};p.data.accountsError='查询失败';await p.submitEnroll();assert.equal(calls.length,0)
})
test('editing child refreshes name by stable identity; removed child cannot switch to another child silently',async()=>{
  let children=[{...child,studentName:'新姓名'}];const {p}=harness('enrollment',()=>Promise.resolve({data:children}));p.data.selectedChildId='17';p.data.newChild=false;await p.loadLessonAccounts();assert.equal(p.data.form.studentName,'新姓名');assert.equal(p.data.selectedChildId,'17');children=[{...child,accountId:'18'}];await p.loadLessonAccounts();assert.equal(p.data.childMissing,true);assert.equal(p.data.selectedChildId,'17')
})
test('package mode cannot enroll a new or zero credit child',async()=>{
  const calls=[];const {p}=harness('enrollment',o=>{calls.push(o);return Promise.resolve({})});p.data.selectedSchedule={scheduleId:12,classDate:'2030-10-07'};p.data.settlementMethod='P';p.data.form={studentName:'新孩子',contactPhone:''};await p.submitEnroll();assert.equal(calls.length,0);p.data.lessonAccounts=[{...child,availableCredits:0}];p.data.selectedChildId='17';p.data.newChild=false;await p.submitEnroll();assert.equal(calls.length,0)
})
test('homepage account badge rejects a response from a previous role and uses the parent-scoped endpoint',async()=>{
  let finish;const calls=[];const {p,storage}=harness('index',o=>{calls.push(o);return new Promise(r=>finish=r)});p.data.quickActions=[{key:'personalAccount'}];const pending=p.loadAccountPending();assert.equal(calls[0].url,'/miniapp/parent/account/pending-count');storage.roles=['teacher'];p.data.currentRole='teacher';finish({data:8});await pending;assert.equal(p.data.quickActions[0].badgeText,undefined)
})
test('parent pending badge hides zero and updates only the personal account action',async()=>{
  let count=4;const {p}=harness('index',()=>Promise.resolve({data:count}));p.data.quickActions=[{key:'personalAccount'},{key:'teacherCourses',badgeText:'2'}];await p.loadAccountPending();assert.equal(p.data.quickActions[0].badgeText,'4');assert.equal(p.data.quickActions[1].badgeText,'2');count=0;await p.loadAccountPending();assert.equal(p.data.quickActions[0].badgeText,'')
})
