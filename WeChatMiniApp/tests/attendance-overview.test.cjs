const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
const root=path.join(__dirname,'..'),plain=x=>JSON.parse(JSON.stringify(x)),visual=require('../utils/attendance-overview')
const course={scheduleId:'10',courseClassName:'化学班',teacherName:'刘老师全名',courseYear:2030,termName:'秋季',periodName:'周六',timeSlot:'08:00–10:00'}
const child={studentKey:'S:1',studentName:'同名学生',parentPhone:'12345678901',linked:true,stats:{attended:1,leave:0},cells:[{text:'到课',status:'1',review:'0',recorded:true},{text:'—',status:'empty',recorded:false}]}
const grid={course,dates:['2030-10-12','2030-10-19'],rows:[child,{...child,studentKey:'S:2',cells:[{text:'请假',status:'3',review:'1',recorded:true},child.cells[1]]}],stats:{attended:1,leave:1},occurrenceCount:2}
function harness(request,role='admin'){
 const storage={token:'admin-session',roles:[role]},routes=[];const wx={getStorageSync:k=>storage[k],navigateTo:o=>routes.push(o.url),stopPullDownRefresh(){},showToast(){}}
 const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/sign-review.js'),'utf8'),{module,wx,Set,require:id=>id==='./access'?{staff:()=>['admin','education'].some(r=>storage.roles.includes(r))}:{request}})
 let definition;vm.runInNewContext(fs.readFileSync(path.join(root,'pages/sign-review/sign-review.js'),'utf8'),{Page:p=>definition=p,require:id=>id.endsWith('/attendance-overview')?visual:id.endsWith('/sign-review')?module.exports:{request},wx,console})
 const p={...definition,data:structuredClone(definition.data),_visible:true};p.setData=o=>Object.assign(p.data,o);return {p,storage,routes}
}
test('student attendance opens overview by default and full teacher names remain in course options',async()=>{
 const calls=[];const {p}=harness(o=>{calls.push(o);return Promise.resolve({data:o.url.endsWith('/courses')?[course]:grid})});assert.equal(p.data.mode,'overview');await p.loadCourses();assert.match(p.data.courses[0].label,/刘老师全名/);assert.equal(p.data.courseId,'10');assert.equal(p.data.students.length,2);assert.equal(p.data.students[0].studentKey,'S:1');assert.equal(p.data.students[1].studentKey,'S:2');assert.equal(calls[1].data.scheduleId,'10')
})
test('education shares the complete read-only attendance overview while teacher and parent cannot load it',async()=>{
 for(const role of ['education','teacher','parent']){const calls=[];const {p}=harness(o=>{calls.push(o);return Promise.resolve({data:o.url.endsWith('/courses')?[course]:grid})},role);await p.loadCourses();assert.equal(p.data.overviewReady,role==='education');assert.equal(calls.length,role==='education'?2:0)}
})
test('teacher statistics labels do not treat missing attendance as absence or invent a completed review',()=>{
 const r=visual.row(child);assert.equal(r.cells[0].reviewText,'待复核');assert.equal(r.cells[1].text,'—');assert.equal(r.cells[1].reviewText,'');assert.equal(r.phoneHint,'家长尾号 8901');assert.equal(visual.filter([r],'6789').length,1);assert.equal(visual.filter([r],'不同姓名').length,0)
})
test('changing courses discards old grids even if the original request returns last',async()=>{
 const waits=[];const {p}=harness(()=>new Promise(resolve=>waits.push(resolve)));p.data.courseId='10';const first=p.loadOverview();p.data.courseId='20';const second=p.loadOverview();waits[1]({data:{...grid,course:{...course,scheduleId:'20'}}});await second;waits[0]({data:{...grid,rows:[]}});await first;assert.equal(p.data.students.length,2);assert.equal(p.data.courseId,'20')
})
test('identity changes and hidden pages reject overview responses',async()=>{
 let finish;const {p,storage}=harness(()=>new Promise(resolve=>finish=resolve));p.data.courseId='10';const work=p.loadOverview();storage.token='parent';storage.roles=['parent'];finish({data:grid});await work;assert.equal(p.data.overviewReady,false);assert.equal(p.data.students.length,0)
})
test('blank matrix cells cannot navigate; recorded cells open their exact class occurrence',async()=>{
 const {p,routes}=harness(()=>Promise.resolve({data:grid}));p.data.courseId='10';p.data.courses=[course];p.data.courseIndex=0;await p.loadOverview();p.openCell({currentTarget:{dataset:{key:'S:1',date:'2030-10-19'}}});assert.equal(routes.length,0);p.openCell({currentTarget:{dataset:{key:'S:1',date:'2030-10-12'}}});assert.match(routes[0],/scheduleId=10&classDate=2030-10-12/)
})
test('student statistics send stable identity and date scope; temporary rows cannot request cross-course aggregation',async()=>{
 const calls=[];const {p}=harness(o=>{calls.push(o);return Promise.resolve({data:{student:child,stats:{attended:2},rows:[],total:0}})});p.data.summaryOpen=true;p.data.summaryStudent=child;p.data.courseId='10';p.data.from='2030-10-01';p.data.to='2030-10-31';await p.loadSummary();assert.deepEqual(plain(calls[0].data),{scheduleId:'10',studentKey:'S:1',allCourses:false,from:'2030-10-01',to:'2030-10-31',pageNum:1});p.data.summaryStudent={...child,linked:false,studentKey:'R:20'};p.chooseSummaryScope({currentTarget:{dataset:{scope:'all'}}});assert.equal(calls.length,1);assert.equal(p.data.summaryScope,'course')
})
test('closing student statistics or changing accounts rejects delayed private records',async()=>{
 let finish;const {p}=harness(()=>new Promise(resolve=>finish=resolve));p.data.summaryOpen=true;p.data.summaryStudent=child;p.data.courseId='10';const work=p.loadSummary();p.closeSummary();finish({data:{stats:{attended:9},rows:[{studentName:'旧学生'}],total:1}});await work;assert.equal(p.data.summaryOpen,false);assert.equal(p.data.summaryRows.length,0);assert.equal(p.data.summaryStudent,null)
})
test('range filters are explicit and request failure clears previous totals and grid',async()=>{
 const {p}=harness(()=>Promise.reject(Error('日期范围无效')));p.data.courseId='10';p.data.students=[child];p.data.overviewReady=true;await p.loadOverview();assert.equal(p.data.overviewReady,false);assert.equal(p.data.students.length,0);assert.equal(p.data.overviewError,'日期范围无效');const source=fs.readFileSync(path.join(root,'pages/sign-review/sign-review.wxml'),'utf8');assert.match(source,/student-axis/);assert.match(source,/仅展示已有出勤记录的日期/);assert.match(source,/— 未记录/)
})
test('student pagination deduplicates occurrence rows and stops at the server page boundary',async()=>{
 const a={scheduleId:'10',classDate:'2030-10-12',statusText:'到课'},b={...a,scheduleId:'20'};const rows=visual.mergeLessons([a],[{...a,statusText:'请假'},b]);assert.equal(rows.length,2);assert.equal(rows[0].statusText,'请假');let calls=0;const {p}=harness(()=>{calls++;return Promise.resolve({data:{stats:{},rows:[a],total:31}})});p.data.summaryOpen=true;p.data.summaryStudent=child;p.data.summaryPage=2;await p.loadSummary();assert.equal(p.data.summaryHasMore,false);p.moreSummary();assert.equal(calls,1)
})
