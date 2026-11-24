const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
function harness(request,role='teacher',now){
 let definition;const storage={token:'staff'},calls=[],notices=[]
 const wx={getStorageSync:k=>storage[k],showToast:o=>notices.push(o.title),showModal:o=>o.success({confirm:true})}
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/attendance-leave-report/attendance-leave-report.js'),'utf8'),{Page:p=>definition=p,wx,getApp:()=>({globalData:{baseUrl:'https://example.test'}}),Uint8Array,Date:now?class extends Date{constructor(...args){super(...(args.length?args:[now]))}static now(){return new Date(now).getTime()}}:Date,require:id=>id.endsWith('/access')?{selected:()=>role}:id.endsWith('/sign-review')?{authorize:async()=>true}:id.endsWith('/attendance-filters')?require('../utils/attendance-filters'):{request:async o=>{calls.push(o);return o.url.endsWith('/todos')?{data:{courses:[]}}:request(o)}}})
 const p={...definition,data:structuredClone(definition.data),setData(v){Object.assign(this.data,v)}}
 return {p,storage,calls,notices,wx,setRole:value=>{role=value}}
}
test('miniapp staff report supports optional filters and paging',async()=>{
 const {p,calls}=harness(async o=>({data:o.url.endsWith('/courses')?[]:{rows:[{rowKey:'S:1:1',studentName:'同名孩子',lessons:[]}],total:21,pendingCount:2}}),'education')
 await p.onShow();assert.equal(p.data.rows.length,1);assert.equal(p.data.more,true)
 p.data.query.teacherName='王老师';await p.search();p.data.query.teacherName='李老师';await p.more()
 const requests=calls.filter(o=>o.url==='/miniapp/attendance/leave-report');assert.equal(requests.at(-1).data.teacherName,'王老师');assert.equal(requests.at(-1).data.pageNum,2)
})
test('miniapp denies parent and ignores leave data after leaving or changing login',async()=>{
 const blocked=harness(async()=>{throw Error('must not request')},'parent');await blocked.p.onShow();assert.match(blocked.p.data.error,/仅教师、教务或管理员/);assert.equal(blocked.calls.length,0)
 let finish;const {p}=harness(async o=>o.url.endsWith('/courses')?{data:[]} : new Promise(resolve=>finish=resolve))
 const work=p.onShow();await new Promise(resolve=>setImmediate(resolve));p.onHide();finish({data:{rows:[{studentName:'旧数据'}],total:1}});await work;assert.equal(p.data.rows.length,0)
})

const event=(signInId,extra={})=>({currentTarget:{dataset:{signInId,...extra}}})
const deferred=()=>{let resolve,reject;const promise=new Promise((r,j)=>{resolve=r;reject=j});return {promise,resolve,reject}}
const sourceLesson={signInId:101,lessonNo:2,canArrange:true,canCancel:true,makeup:{planId:501},state:'failed'}
const report={rows:[{rowKey:'S:1:1',lessons:[sourceLesson]}],total:1,scheduledCount:1,canManageMakeup:true,readOnly:false}
test('makeup choices use stable IDs and submit the selected lesson with remark',async()=>{
 const {p,calls}=harness(async o=>({data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{courses:[{scheduleId:90,courseName:'同名班'},{scheduleId:91,courseName:'同名班'}],lessons:o.data.scheduleId===91?[{lessonId:909,lessonNo:3,label:'第三讲'}]:[],history:[{planId:501,operatorName:'教务'}]}:report}))
 await p.onShow();assert.equal(p.data.rows[0].lessons[0].stateText,'补课未完成')
 await p.openMakeup(event(101));await p.chooseMakeupCourse({detail:{value:'1'}})
 p.chooseMakeupLesson({detail:{value:'0'}});p.makeupRemarkInput({detail:{value:' 调课备注 '}})
 await p.submitMakeup();const post=calls.find(o=>o.method==='POST');assert.equal(post.url,'/miniapp/attendance/leave-report/makeup');assert.equal(post.data.signInId,101);assert.equal(post.data.targetLessonId,909);assert.equal(post.data.mode,'CLASS');assert.equal(post.data.remark,'调课备注');assert.equal(p.data.makeupOpen,false)
 p.chooseState({detail:{value:'1'}});assert.equal(p.data.query.state,'scheduled')
})
test('makeup options ignore older class choices and page lifecycle changes',async()=>{
 const first=deferred(),second=deferred();const {p}=harness(async o=>o.url.endsWith('/options')?(o.data.scheduleId===90?first.promise:o.data.scheduleId===91?second.promise:{data:{courses:[{scheduleId:90},{scheduleId:91}],lessons:[]}}):{data:o.url.endsWith('/courses')?[]:report})
 await p.onShow();await p.openMakeup(event(101));const a=p.chooseMakeupCourse({detail:{value:'0'}}),b=p.chooseMakeupCourse({detail:{value:'1'}})
 second.resolve({data:{lessons:[{lessonId:909,label:'new'}]}});await b;first.resolve({data:{lessons:[{lessonId:808,label:'stale'}]}});await a;assert.equal(p.data.makeupLessons[0].lessonId,909);assert.equal(p.data.makeupLessonIndex,-1)
 p.onHide();assert.equal(p.data.makeupOpen,false)
})
test('cancel requires reason and prevents duplicate writes',async()=>{
 const write=deferred();const {p,calls,notices}=harness(async o=>o.method==='POST'?write.promise:{data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));await p.submitMakeup();assert.equal(calls.some(o=>o.method==='POST'),false);assert.ok(notices.includes('请填写取消原因'))
 p.makeupReasonInput({detail:{value:'时间冲突'}});const pending=p.submitMakeup();await new Promise(resolve=>setImmediate(resolve));await p.submitMakeup();assert.equal(calls.filter(o=>o.method==='POST').length,1)
 const post=calls.find(o=>o.method==='POST');assert.equal(post.data.planId,501);assert.equal(post.data.reason,'时间冲突');write.resolve({data:{}});await pending
})
test('typing a new search invalidates the old target and an in-flight options response',async()=>{
 const pending=deferred();const {p,calls}=harness(async o=>o.url.endsWith('/options')?(o.data.scheduleId?pending.promise:{data:{courses:[{scheduleId:90,courseName:'原班'}]}}):{data:o.url.endsWith('/courses')?[]:report})
 await p.onShow();await p.openMakeup(event(101));const work=p.chooseMakeupCourse({detail:{value:'0'}})
 p.makeupKeywordInput({detail:{value:'新班'}});pending.resolve({data:{lessons:[{lessonId:900,label:'过期讲次'}]}});await work
 assert.equal(p.data.makeupLessons.length,0);assert.equal(p.data.makeupCourseIndex,-1);assert.equal(p.data.makeupLessonIndex,-1)
 await p.submitMakeup();assert.equal(calls.some(o=>o.method==='POST'),false)
})
test('login changes suppress options and writes from the previous session',async()=>{
 const pending=deferred();const {p,storage,calls}=harness(async o=>o.url.endsWith('/options')?pending.promise:{data:o.url.endsWith('/courses')?[]:report})
 await p.onShow();const work=p.openMakeup(event(101));storage.token='different-user';pending.resolve({data:{courses:[{scheduleId:90}],history:[{planId:1}]}});await work
 assert.equal(p.data.makeupCourses.length,0);assert.equal(p.data.makeupHistory.length,0);await p.submitMakeup();assert.equal(calls.some(o=>o.method==='POST'),false)
})
test('failed writes remain visible for correction and do not reload the report',async()=>{
 const {p,calls}=harness(async o=>{if(o.method==='POST')throw Error('目标已提交，请选择其他讲次');return {data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report}})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'取消'}});await p.submitMakeup()
 assert.equal(p.data.makeupOpen,true);assert.match(p.data.makeupError,/目标已提交/);assert.equal(p.data.makeupWriting,false);assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,1)
})
test('closing the sheet during a successful write still refreshes the active report',async()=>{
 const write=deferred();const {p,calls}=harness(async o=>o.method==='POST'?write.promise:{data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'时间冲突'}});const work=p.submitMakeup();await new Promise(resolve=>setImmediate(resolve));p.closeMakeup();write.resolve({data:{}});await work
 assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,2)
})
test('uncertain writes require a report refresh before another attempt, even after closing',async()=>{
 const {p,calls}=harness(async o=>{if(o.method==='POST')throw Object.assign(Error('网络超时'),{uncertain:true});return {data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report}})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'取消'}});await p.submitMakeup();assert.equal(p.data.makeupUncertain,true)
 await p.submitMakeup();assert.equal(calls.filter(o=>o.method==='POST').length,1)
 p.closeMakeup();await p.openMakeup(event(101,{mode:'cancel'}));assert.equal(p.data.makeupOpen,false);assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,2)
 await p.openMakeup(event(101,{mode:'cancel'}));assert.equal(p.data.makeupOpen,true)
})

test('same-owner hide and return before POST success reloads after the commit',async()=>{
 const write=deferred();let committed=false
 const {p,calls}=harness(async o=>o.method==='POST'?write.promise:{data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:committed?{rows:[{rowKey:'S:1:1',lessons:[{...sourceLesson,canCancel:false,makeup:null}]}],total:1}:report})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'取消'}});const work=p.submitMakeup();await new Promise(resolve=>setImmediate(resolve))
 p.onHide();await p.onShow();assert.equal(p.data.rows[0].lessons[0].canCancel,true)
 committed=true;write.resolve({data:{}});await work
 assert.equal(p.data.rows[0].lessons[0].canCancel,false);assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,3)
})
test('same-owner late POST rejection blocks stale-row retry after hide and return',async()=>{
 const write=deferred();const {p,calls}=harness(async o=>o.method==='POST'?write.promise:{data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'取消'}});const work=p.submitMakeup();await new Promise(resolve=>setImmediate(resolve))
 p.onHide();await p.onShow();write.reject(Object.assign(Error('网络超时'),{uncertain:true}));await work
 await p.openMakeup(event(101,{mode:'cancel'}));assert.equal(p.data.makeupOpen,false);assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,3);assert.equal(calls.filter(o=>o.method==='POST').length,1)
})
test('a pre-settlement report response cannot clear late uncertain-write protection',async()=>{
 const write=deferred(),oldRead=deferred();let reports=0
 const {p,calls}=harness(async o=>o.method==='POST'?write.promise:o.url==='/miniapp/attendance/leave-report'?(++reports===2?oldRead.promise:{data:report}):{data:o.url.endsWith('/courses')?[]:{history:[]}})
 await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'取消'}});const work=p.submitMakeup();await new Promise(resolve=>setImmediate(resolve))
 p.onHide();const showing=p.onShow();await new Promise(resolve=>setImmediate(resolve));write.reject(Error('连接中断'));await work;oldRead.resolve({data:report});await showing
 await p.openMakeup(event(101,{mode:'cancel'}));assert.equal(p.data.makeupOpen,false);assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,3)
})
test('late POST settlement does not refresh or block a different owner',async()=>{
 for(const rejected of [false,true]){
  const write=deferred();const {p,storage,calls,notices}=harness(async o=>o.method==='POST'?write.promise:{data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report})
  await p.onShow();await p.openMakeup(event(101,{mode:'cancel'}));p.makeupReasonInput({detail:{value:'取消'}});const work=p.submitMakeup();await new Promise(resolve=>setImmediate(resolve))
  p.onHide();storage.token='new-owner';await p.onShow();if(rejected)write.reject(Error('连接中断'));else write.resolve({data:{}});await work
  assert.equal(calls.filter(o=>o.url==='/miniapp/attendance/leave-report').length,2);assert.equal(notices.includes('已取消补课安排'),false)
  await p.openMakeup(event(101,{mode:'cancel'}));assert.equal(p.data.makeupOpen,true)
 }
})

test('staff can read history but cannot arrange or cancel even with stale flags',async()=>{
 for(const role of ['education','admin']){
 const {p,calls}=harness(async o=>({data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[{planId:501}]}:report}),role)
 await p.onShow();assert.equal(p.data.readOnly,true)
 await p.openMakeup(event(101));assert.equal(p.data.makeupOpen,false)
 await p.openMakeup(event(101,{mode:'history'}));assert.equal(p.data.makeupHistory.length,1)
 p.data.makeupMode='cancel';p.data.makeupReason='取消';await p.submitMakeup();assert.ok(!calls.some(o=>o.method==='POST'))
 }
})
test('teacher scope and report permissions govern writes',async()=>{
 const {p,calls}=harness(async o=>({data:o.url.endsWith('/options')?{history:[]}:report}))
 await p.onShow();assert.equal(p.data.readOnly,false);assert.ok(!p.data.fields.some(f=>f.key==='teacherName'))
 assert.equal(calls.find(o=>o.url==='/miniapp/attendance/leave-report').data.scope,'teacher')
 await p.openMakeup(event(101));assert.equal(p.data.makeupOpen,true)
 const denied=harness(async()=>({data:{...report,canManageMakeup:false,readOnly:true}}));await denied.p.onShow();await denied.p.openMakeup(event(101));assert.equal(denied.p.data.makeupOpen,false)
})
test('custom mode validates fields and submits a single custom payload while busy',async()=>{
 const write=deferred();const {p,calls,notices}=harness(async o=>o.method==='POST'?write.promise:{data:o.url.endsWith('/options')?{courses:[]}:report})
 await p.onShow();await p.openMakeup(event(101));p.chooseMakeupType({currentTarget:{dataset:{type:'CUSTOM'}}})
 await p.submitMakeup();assert.ok(notices.at(-1).includes('日期'))
 Object.assign(p.data,{makeupDate:'2026-12-01',makeupStartTime:'10:00',makeupEndTime:'09:00',makeupLocation:'教室'})
 await p.submitMakeup();assert.ok(notices.at(-1).includes('结束时间'))
 p.data.makeupEndTime='11:00';p.data.makeupLocation=' ';await p.submitMakeup();assert.ok(notices.at(-1).includes('地点'))
 p.data.makeupLocation='x'.repeat(201);await p.submitMakeup();assert.ok(notices.at(-1).includes('200'))
 p.data.makeupLocation=' 一号教室 ';p.data.makeupRemark='x'.repeat(501);await p.submitMakeup();assert.ok(notices.at(-1).includes('500'))
 p.data.makeupRemark=' 备注 ';const pending=p.submitMakeup();await new Promise(r=>setImmediate(r));await p.submitMakeup();p.chooseMakeupType({currentTarget:{dataset:{type:'CLASS'}}});assert.equal(p.data.makeupType,'CUSTOM')
 const posts=calls.filter(o=>o.method==='POST');assert.equal(posts.length,1);assert.deepEqual(JSON.parse(JSON.stringify(posts[0].data)),{signInId:101,mode:'CUSTOM',classDate:'2026-12-01',startTime:'10:00',endTime:'11:00',location:'一号教室',remark:'备注'})
 write.resolve({data:{}});await pending
})
test('mode switches clear stale targets and custom fields and invalidate old options',async()=>{
 const stale=deferred();const {p,calls}=harness(async o=>o.url.endsWith('/options')?(o.data.scheduleId?stale.promise:{data:{courses:[{scheduleId:90}]}}):{data:report})
 await p.onShow();await p.openMakeup(event(101));const work=p.chooseMakeupCourse({detail:{value:'0'}})
 p.chooseMakeupType({currentTarget:{dataset:{type:'CUSTOM'}}});stale.resolve({data:{lessons:[{lessonId:999}]}});await work;assert.equal(p.data.makeupLessons.length,0);assert.equal(p.data.makeupLoading,false)
 p.makeupCustomInput({currentTarget:{dataset:{field:'makeupLocation'}},detail:{value:'教室'}});assert.equal(p.data.makeupLocation,'教室')
 p.chooseMakeupType({currentTarget:{dataset:{type:'CLASS'}}});assert.equal(p.data.makeupLocation,'');assert.equal(p.data.makeupCourseIndex,-1);assert.equal(p.data.makeupLessonIndex,-1)
 await p.submitMakeup();assert.ok(!calls.some(o=>o.method==='POST'))
})

test('custom start accepts later today and rejects current or past start',async()=>{
 const {p,calls,notices}=harness(async o=>({data:o.url.endsWith('/options')?{}:report}),'teacher','2026-11-23T10:00:00+08:00')
 await p.onShow();await p.openMakeup(event(101));p.chooseMakeupType({currentTarget:{dataset:{type:'CUSTOM'}}})
 Object.assign(p.data,{makeupDate:'2026-11-23',makeupStartTime:'10:00',makeupEndTime:'12:00',makeupLocation:'教室'})
 await p.submitMakeup();assert.ok(notices.at(-1).includes('当前时间'));assert.ok(!calls.some(o=>o.method==='POST'))
 p.data.makeupDate='2026-11-22';await p.submitMakeup();assert.ok(!calls.some(o=>o.method==='POST'))
 p.data.makeupDate='2026-11-23';p.data.makeupStartTime='11:00';await p.submitMakeup();assert.equal(calls.filter(o=>o.method==='POST').length,1)
})

test('mixed-role accounts expose actions only in the selected teacher workspace',async()=>{
 const {p,setRole,calls}=harness(async o=>({data:o.url.endsWith('/courses')?[]:o.url.endsWith('/options')?{history:[]}:report}))
 await p.onShow();await p.openMakeup(event(101));assert.equal(p.data.makeupOpen,true)
 setRole('education');assert.equal(p.current(),false);await p.submitMakeup();assert.ok(!calls.some(o=>o.method==='POST'))
 await p.onShow();assert.equal(p.data.readOnly,true);await p.openMakeup(event(101));assert.equal(p.data.makeupOpen,false)
 await p.openMakeup(event(101,{mode:'history'}));assert.equal(p.data.makeupOpen,true)
 setRole('teacher');await p.onShow();assert.equal(p.data.readOnly,false)
})
test('missing server report permission leaves teacher actions read-only',async()=>{
 const {p}=harness(async()=>({data:{rows:report.rows,total:1}}));await p.onShow();await p.openMakeup(event(101));assert.equal(p.data.readOnly,true);assert.equal(p.data.makeupOpen,false)
})

test('custom China time uses China today and rejects past times independently of device timezone',async()=>{
 const {p,calls,notices}=harness(async o=>({data:o.url.endsWith('/options')?{}:report}),'teacher','2026-11-23T00:30:00+08:00')
 await p.onShow();await p.openMakeup(event(101));assert.equal(p.data.makeupToday,'2026-11-23')
 p.chooseMakeupType({currentTarget:{dataset:{type:'CUSTOM'}}})
 Object.assign(p.data,{makeupDate:'2026-11-23',makeupStartTime:'00:29',makeupEndTime:'01:00',makeupLocation:'教室'})
 await p.submitMakeup();assert.ok(notices.at(-1).includes('当前时间'));assert.ok(!calls.some(o=>o.method==='POST'))
 p.data.makeupStartTime='00:31';await p.submitMakeup();assert.equal(calls.filter(o=>o.method==='POST').length,1)
 const markup=fs.readFileSync(path.join(__dirname,'../pages/attendance-leave-report/attendance-leave-report.wxml'),'utf8')
 assert.match(markup,/mode="date"[^>]*start="{{makeupToday}}"/)
})

test('confirmation rechecks refreshed write permission and plan identity before sending',async()=>{
 const h=harness(async o=>({data:o.url.endsWith('/options')?{}:report}));await h.p.onShow();await h.p.openMakeup(event(101,{mode:'cancel'}));h.p.makeupReasonInput({detail:{value:'改期'}})
 let confirmation;h.wx.showModal=o=>confirmation=o;const work=h.p.submitMakeup();await new Promise(r=>setImmediate(r));h.p.setData({readOnly:true,rows:[{rowKey:'own',lessons:[{...sourceLesson,canCancel:false}]}]});confirmation.success({confirm:true});await work;assert.equal(h.calls.some(o=>o.method==='POST'),false)
 h.p.setData({readOnly:false,rows:[{rowKey:'own',lessons:[sourceLesson]}]});await h.p.openMakeup(event(101,{mode:'cancel'}));h.p.makeupReasonInput({detail:{value:'改期'}});const second=h.p.submitMakeup();await new Promise(r=>setImmediate(r));h.p.setData({rows:[{rowKey:'own',lessons:[{...sourceLesson,makeup:{planId:999}}]}]});confirmation.success({confirm:true});await second;assert.equal(h.calls.some(o=>o.method==='POST'),false)
})
