const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
const source=fs.readFileSync(path.join(__dirname,'../src/views/system/signReview/index.vue'),'utf8')
const course={scheduleId:'10',courseClassName:'化学',teacherName:'刘老师全名'},child={studentKey:'S:1',studentName:'同名学生',parentPhone:'12345678901',linked:true,stats:{attended:1},cells:[{recorded:true,text:'到课',status:'1',review:'0'}]}
const grid={course,dates:['2030-10-12'],rows:[child,{...child,studentKey:'S:2'}],stats:{attended:2},occurrenceCount:1}
function harness(overrides={}){
 const storage={token:'session'},calls=[];const api={listReview:()=>Promise.resolve({data:[]}),getDetail:()=>Promise.resolve({data:{details:[],review:{}}}),confirmReview:()=>Promise.resolve({}),attendanceCourses:()=>Promise.resolve({data:[course]}),attendanceOverview:()=>Promise.resolve({data:grid}),attendanceStudent:p=>{calls.push(p);return Promise.resolve({data:{stats:{attended:2},rows:[],total:0}})},...overrides}
 const module={exports:{}};vm.runInNewContext(source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'').replace('export default','module.exports ='),{module,...api,getToken:()=>storage.token,console});const def=module.exports,p={...def.data(),...def.methods,_alive:true,_token:'session'};for(const [k,get] of Object.entries(def.computed))Object.defineProperty(p,k,{get:()=>get.call(p)});return {p,storage,calls}
}
test('web attendance starts with overview, retains stable same-name rows and full teacher name',async()=>{
 const {p}=harness();assert.equal(p.mode,'overview');await p.loadCourses();assert.equal(p.courseId,'10');assert.equal(p.visibleStudents.length,2);assert.equal(p.visibleStudents[0].studentKey,'S:1');assert.equal(p.visibleStudents[1].studentKey,'S:2');assert.match(p.courseLabel(course),/刘老师全名/);p.studentKeyword='6789';assert.equal(p.visibleStudents.length,2)
})
test('web course changes reject stale grid responses',async()=>{
 const pending=[];const {p}=harness({attendanceOverview:()=>new Promise(resolve=>pending.push(resolve))});p.courseId='10';const first=p.loadOverview();p.courseId='20';const second=p.loadOverview();pending[1]({data:{...grid,course:{...course,scheduleId:'20'}}});await second;pending[0]({data:grid});await first;assert.equal(p.overview.course.scheduleId,'20')
})
test('web student summary keeps current identity and changes scope with explicit server parameters',async()=>{
 const {p,calls}=harness();p.courseId='10';p.dateRange=['2030-10-01','2030-10-31'];p.selectedStudent=child;p.studentDialog=true;await p.loadStudent();assert.equal(calls[0].studentKey,'S:1');assert.equal(calls[0].allCourses,false);assert.equal(calls[0].from,'2030-10-01');p.studentScope='all';await p.loadStudent();assert.equal(calls[1].allCourses,true)
})
test('web student summary cannot repopulate after its dialog closes or identity changes',async()=>{
 let finish;const {p,storage}=harness({attendanceStudent:()=>new Promise(resolve=>finish=resolve)});p.courseId='10';p.selectedStudent=child;p.studentDialog=true;const work=p.loadStudent();p.closeStudent();storage.token='other';finish({data:{stats:{attended:9},rows:[{studentName:'旧孩子'}],total:1}});await work;assert.equal(p.selectedStudent,null);assert.equal(p.studentRows.length,0);assert.equal(p.studentDialog,false)
})
test('web overview keeps frozen student column and unrecorded cells never open review dialogs',async()=>{
 const {p}=harness();p.overview=grid;p.openCell({cells:[{recorded:false}]},0);assert.equal(p.opened,false);assert.match(source,/label="学生 \/ 统计" fixed="left"/);assert.match(source,/— 表示未记录/);assert.match(source,/出勤复核/)
})
test('web navigation renames only the attendance view while preserving route and permissions',()=>{
 const text=fs.readFileSync(path.join(__dirname,'../src/store/modules/permission.js'),'utf8');const fn=text.slice(text.indexOf('function filterAsyncRouter('),text.indexOf('function filterChildren('));const module={exports:{}};vm.runInNewContext(fn+'\nmodule.exports=filterAsyncRouter',{module,Layout:'layout',ParentView:'parent',InnerLink:'link',loadView:v=>'loaded:'+v});const routes=module.exports([{path:'signReview',name:'SignReview',component:'system/signReview/index',meta:{title:'签到复核',icon:'check'},permissions:['system:signReview:list']},{path:'wifi',component:'system/wifi/index',meta:{title:'教师打卡复核'}}]);assert.equal(routes[0].meta.title,'学生出勤');assert.equal(routes[0].meta.icon,'check');assert.equal(routes[0].path,'signReview');assert.equal(routes[0].permissions[0],'system:signReview:list');assert.equal(routes[1].meta.title,'教师打卡复核')
})
