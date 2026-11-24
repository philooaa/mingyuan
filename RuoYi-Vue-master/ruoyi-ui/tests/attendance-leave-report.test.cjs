const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
const teacherReport=()=>({canManageMakeup:true,readOnly:false,rows:[{lessons:[{signInId:1,canArrange:true,canCancel:true,makeup:{planId:1}},{signInId:91,canArrange:true,canCancel:true,makeup:{planId:37}}]}]})
function harness(request, extra={}){
 const storage={token:'staff'},calls=[],module={exports:{}}
 const source=fs.readFileSync(path.join(__dirname,'../src/views/system/signReview/LeaveReport.vue'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
 vm.runInNewContext(source.replace(/^import .*$/gm,'').replace('export default','module.exports ='),{module,attendanceLeaveReport:async p=>{calls.push(p);return request(p)},getToken:()=>storage.token,...extra})
 const def=module.exports,p={...def.data(),...def.methods,teacherOnly:true,_alive:true,_owner:'staff'}
 p.report=teacherReport()
 return {p,storage,calls,def}
}
test('web optional filters and paging use the last searched scope, not unsubmitted edits',async()=>{
 const {p,calls}=harness(async()=>({data:{total:1,rows:[{studentKey:'S:1'}],leaveCount:2,pendingCount:2}}))
 p.query.teacherName='王老师';await p.search();assert.equal(calls[0].teacherName,'王老师');assert.equal(calls[0].gradeName,'')
 p.query.teacherName='李老师'
 p.pageNum=2;await p.load();assert.equal(calls[1].pageNum,2);assert.equal(calls[1].teacherName,'王老师')
})
test('web stale requests and switched sessions cannot expose previous leave rows',async()=>{
 const pending=[],{p,storage}=harness(()=>new Promise(resolve=>pending.push(resolve)))
 const first=p.search(),second=p.search();pending[1]({data:{total:1,rows:[{studentName:'新'}]}});await second
 pending[0]({data:{total:1,rows:[{studentName:'旧'}]}});await first;assert.equal(p.report.rows[0].studentName,'新')
 const third=p.search();storage.token='other';pending[2]({data:{total:1,rows:[{studentName:'不应显示'}]}});await third;assert.equal(p.report.rows,undefined)
})

test('makeup options keep only the current class and dialog scope',async()=>{
 const pending=[],{p}=harness(async()=>({data:teacherReport()}),{attendanceMakeupOptions:q=>new Promise(resolve=>pending.push({q,resolve}))})
 const opening=p.openMakeup({studentName:'学员'},{signInId:91,canArrange:true});pending[0].resolve({data:{source:{studentName:'学员'},courses:[{scheduleId:1}],history:[]}});await opening
 p.makeup.scheduleId=1;const first=p.loadMakeupOptions('旧');p.makeup.scheduleId=2;const second=p.loadMakeupOptions('新')
 assert.equal(pending[2].q.signInId,91);assert.equal(pending[2].q.scheduleId,2)
 pending[2].resolve({data:{lessons:[{lessonId:22}]}});await second;pending[1].resolve({data:{lessons:[{lessonId:11}]}});await first
 assert.equal(p.makeup.lessons[0].lessonId,22)
 const closing=p.loadMakeupOptions('');p.closeMakeup();pending[3].resolve({data:{lessons:[{lessonId:99}]}});await closing;assert.equal(p.makeup.visible,false);assert.equal(p.makeup.lessons.length,0)
})
test('makeup writes stable identifiers once, requires cancel reason and reloads applied report',async()=>{
 const writes=[],pending=[],{p,calls}=harness(async()=>({data:teacherReport()}),{attendanceMakeupOptions:async()=>({data:{courses:[],lessons:[],history:[]}}),arrangeAttendanceMakeup:body=>{writes.push(body);return new Promise(resolve=>pending.push(resolve))},cancelAttendanceMakeup:async body=>{writes.push(body);return {code:200}}})
 p.$message={success(){},warning(){},error(){}};p.query.teacherName='原查询';await p.search();p.query.teacherName='未查询'
 await p.openMakeup({studentName:'学员'},{signInId:91,canArrange:true});p.makeup.targetLessonId=22;p.makeup.lessons=[{lessonId:22}];p.makeup.remark='备注'
 const saving=p.submitMakeup();await p.submitMakeup();assert.equal(writes.length,1);assert.equal(JSON.stringify(writes[0]),JSON.stringify({signInId:91,mode:'CLASS',targetLessonId:22,remark:'备注'}));pending[0]({code:200});await saving;assert.equal(calls.at(-1).teacherName,'原查询')
 p.openCancel({signInId:91,canCancel:true,makeup:{planId:37}});await p.submitCancel();assert.equal(writes.length,1);p.cancel.reason=' 调整时间 ';await p.submitCancel();assert.equal(JSON.stringify(writes[1]),JSON.stringify({planId:37,reason:'调整时间'}))
})
test('uncertain writes block retry until refreshed and switched sessions discard options',async()=>{
 const deferred=[],{p,storage}=harness(async()=>({data:teacherReport()}),{attendanceMakeupOptions:()=>new Promise(resolve=>deferred.push(resolve)),arrangeAttendanceMakeup:async()=>{throw new Error('Network Error')}})
 p.$message={success(){},warning(){},error(){}};const first=p.openMakeup({}, {signInId:1,canArrange:true});storage.token='other';deferred[0]({data:{source:{studentName:'旧'},lessons:[{lessonId:2}]}});await first;assert.equal(p.makeup.source.studentName,undefined)
 storage.token='staff';const next=p.openMakeup({}, {signInId:1,canArrange:true});deferred[1]({data:{}});await next;p.makeup.targetLessonId=2;p.makeup.lessons=[{lessonId:2}];await p.submitMakeup();assert.equal(p.makeup.uncertain,true)
})
test('closing while a write is pending still blocks uncertain repeats when reopened',async()=>{
 let rejectWrite;const {p}=harness(async()=>({data:teacherReport()}),{attendanceMakeupOptions:async()=>({data:teacherReport()}),arrangeAttendanceMakeup:()=>new Promise((resolve,reject)=>{rejectWrite=reject})})
 p.$message={success(){},warning(){},error(){}};await p.openMakeup({}, {signInId:1,canArrange:true});p.makeup.targetLessonId=2;p.makeup.lessons=[{lessonId:2}];const saving=p.submitMakeup();p.closeMakeup();rejectWrite(new Error('Network Error'));await saving;await p.openMakeup({}, {signInId:1,canArrange:true});assert.equal(p.makeup.uncertain,true)
})

test('makeup API wrappers preserve read scope and write bodies',async()=>{
 const calls=[],source=fs.readFileSync(path.join(__dirname,'../src/api/system/signReview.js'),'utf8'),context={request:async p=>{calls.push(p);return {code:200}}}
 vm.createContext(context);vm.runInContext(source.replace(/^import .*$/gm,'').replace(/export function/g,'function'),context)
 await context.attendanceMakeupOptions({signInId:91,scheduleId:12,keyword:'课'});await context.arrangeAttendanceMakeup({signInId:91,targetLessonId:22,remark:'备注'});await context.cancelAttendanceMakeup({planId:37,reason:'调整'})
 assert.deepEqual(JSON.parse(JSON.stringify(calls)),[{url:'/miniapp/attendance/leave-report/makeup/options',method:'get',params:{signInId:91,scheduleId:12,keyword:'课'}},{url:'/miniapp/attendance/leave-report/makeup',method:'post',data:{signInId:91,targetLessonId:22,remark:'备注'}},{url:'/miniapp/attendance/leave-report/makeup/cancel',method:'post',data:{planId:37,reason:'调整'}}])
})

test('scheduled and completed leaves expose read-only history without allowing a write',async()=>{
 let writes=0;const {p}=harness(async()=>({data:{canManageMakeup:false,readOnly:true}}),{attendanceMakeupOptions:async()=>({data:{history:[{planId:31}]}}),arrangeAttendanceMakeup:async()=>{writes++}})
 p.teacherOnly=false;p.report={canManageMakeup:false,readOnly:true}
 for(const state of ['scheduled','completed']){await p.openMakeup({}, {signInId:91,state,canArrange:false},true);assert.equal(p.makeup.visible,true);assert.equal(p.makeup.readOnly,true);assert.equal(p.makeup.history[0].planId,31);p.makeup.targetLessonId=2;p.makeup.lessons=[{lessonId:2}];await p.submitMakeup()}
 assert.equal(writes,0)
})

test('teacher leave view forces own scope and obeys denied server permission',async()=>{
 let writes=0;const {p,calls}=harness(async()=>({data:{readOnly:true,rows:[]}}),{arrangeAttendanceMakeup:async()=>{writes++}})
 p.teacherOnly=true;p.query.scope='staff';await p.search();assert.equal(calls[0].scope,'teacher')
 p.openMakeup({}, {signInId:1,canArrange:true});assert.equal(p.makeup.visible,false)
 p.openCancel({canCancel:true,makeup:{planId:2}});assert.equal(p.cancel.visible,false)
 p.makeup.visible=true;p.makeup.targetLessonId=3;p.makeup.lessons=[{lessonId:3}];await p.submitMakeup();assert.equal(writes,0)
})

test('staff keeps history access even when a mixed-role server flag permits management',async()=>{
 let writes=0;const {p}=harness(async()=>({data:{}}),{attendanceMakeupOptions:async()=>({data:{history:[{planId:1}]}}),arrangeAttendanceMakeup:async()=>{writes++}})
 p.teacherOnly=false;p.report=teacherReport()
 await p.openMakeup({}, {signInId:1,canArrange:true});assert.equal(p.makeup.visible,false)
 p.openCancel({canCancel:true,makeup:{planId:1}});assert.equal(p.cancel.visible,false)
 p.report={canManageMakeup:false,readOnly:true}
 await p.openMakeup({}, {signInId:1,state:'completed'},true);assert.equal(p.makeup.history.length,1)
 p.makeup.readOnly=false;p.makeup.targetLessonId=2;p.makeup.lessons=[{lessonId:2}];await p.submitMakeup();assert.equal(writes,0)
})
test('custom makeup validates future date, times and lengths and sends only custom fields',async()=>{
 const writes=[],{p}=harness(async()=>({data:teacherReport()}),{attendanceMakeupOptions:async()=>({data:{}}),arrangeAttendanceMakeup:async body=>{writes.push(body);return {code:200}}})
 p.$message={success(){}};await p.openMakeup({}, {signInId:1,canArrange:true});p.makeup.mode='CUSTOM'
 const valid={classDate:'2099-12-30',startTime:'09:00',endTime:'10:00',location:' 教室A ',remark:' 备注 '}
 for(const invalid of [{classDate:'2020-01-01'},{classDate:'2099-02-30'},{endTime:'09:00'},{startTime:'25:00'},{location:' '},{location:'a'.repeat(201)},{remark:'a'.repeat(501)}]){Object.assign(p.makeup,valid,invalid);await p.submitMakeup();assert.equal(writes.length,0)}
 Object.assign(p.makeup,valid,{targetLessonId:9});await p.submitMakeup()
 assert.deepEqual(JSON.parse(JSON.stringify(writes)),[{signInId:1,mode:'CUSTOM',classDate:valid.classDate,startTime:'09:00',endTime:'10:00',location:'教室A',remark:'备注'}])
})
test('switching makeup modes discards stale class replies and hidden fields',async()=>{
 let resolve;const {p}=harness(async()=>({data:{}}),{attendanceMakeupOptions:()=>new Promise(r=>{resolve=r})})
 const opening=p.openMakeup({}, {signInId:1,canArrange:true});resolve({data:{}});await opening
 p.makeup.scheduleId=3;const pending=p.loadMakeupOptions();p.makeup.mode='CUSTOM';p.modeChanged()
 assert.equal(p.makeup.scheduleId,null);assert.equal(p.makeup.targetLessonId,null);assert.equal(p.makeup.loading,false)
 resolve({data:{lessons:[{lessonId:7}]}});await pending;assert.equal(p.makeup.lessons.length,0)
 Object.assign(p.makeup,{classDate:'2099-01-01',startTime:'09:00',endTime:'10:00',location:'教室'})
 p.makeup.mode='CLASS';const switching=p.modeChanged();assert.equal(p.makeup.classDate,'');assert.equal(p.makeup.location,'');resolve({data:{}});await switching
})

test('deactivation discards a custom draft and any late options response',async()=>{
 let resolve;const {p,def}=harness(async()=>({data:{}}),{attendanceMakeupOptions:()=>new Promise(r=>{resolve=r})})
 const opening=p.openMakeup({}, {signInId:1,canArrange:true});Object.assign(p.makeup,{mode:'CUSTOM',classDate:'2099-01-01',location:'旧地点'})
 def.deactivated.call(p);resolve({data:{source:{studentName:'旧学员'},history:[{planId:2}]}});await opening
 assert.equal(p.makeup.visible,false);assert.equal(p.makeup.location,'');assert.equal(p.makeup.history.length,0);assert.equal(p.makeup.source.studentName,undefined);assert.equal(p.canManageMakeup(),false)
})

test('custom makeup accepts later today and rejects past or current China start times',async()=>{
 const RealDate=Date,now='2026-11-23T12:00:00+08:00'
 class FixedDate extends RealDate {constructor(...args){super(...(args.length?args:[now]))}static now(){return new RealDate(now).getTime()}}
 const writes=[],{p}=harness(async()=>({data:teacherReport()}),{Date:FixedDate,attendanceMakeupOptions:async()=>({data:{}}),arrangeAttendanceMakeup:async body=>{writes.push(body);return {code:200}}})
 p.$message={success(){}};await p.openMakeup({}, {signInId:1,canArrange:true})
 Object.assign(p.makeup,{mode:'CUSTOM',classDate:'2026-11-23',startTime:'11:00',endTime:'13:00',location:'教室'})
 for(const invalid of [{classDate:'2026-11-22',startTime:'12:05'},{classDate:'2026-11-23',startTime:'11:59'},{startTime:'12:00'}]){Object.assign(p.makeup,invalid);await p.submitMakeup();assert.equal(writes.length,0)}
 p.makeup.startTime='12:05';await p.submitMakeup();assert.equal(writes.length,1);assert.equal(writes[0].classDate,'2026-11-23');assert.equal(writes[0].startTime,'12:05')
})

test('refresh after uncertain commit closes an unavailable arrangement and blocks a replaced cancel plan',async()=>{
 let writes=0;const {p}=harness(async()=>({data:{canManageMakeup:true,readOnly:false,rows:[{lessons:[{signInId:1,canArrange:false,canCancel:true,makeup:{planId:9}}]}]}}),{attendanceMakeupOptions:async()=>({data:{}}),arrangeAttendanceMakeup:async()=>{writes++},cancelAttendanceMakeup:async()=>{writes++}})
 p.$message={success(){},warning(){}};await p.openMakeup({}, {signInId:1,canArrange:true});p._uncertain=true;p._uncertainToken='staff';p.makeup.uncertain=true
 await p.refreshMakeup();assert.equal(p.makeup.visible,false);assert.equal(writes,0)
 p.openCancel({signInId:1,canCancel:true,makeup:{planId:9}});p.cancel.reason='改期';p.report.rows[0].lessons[0].makeup.planId=10;await p.submitCancel();assert.equal(writes,0)
})
