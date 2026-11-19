const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function harness(request) {
 let page;const calls=[],wx={getStorageSync:()=> 'token',showToast(){},showModal:o=>o.success({confirm:true})}
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/sign-in-edit/sign-in-edit.js'),'utf8'),{Page:p=>page=p,require:()=>({request:async o=>{calls.push(o);return request(o)}}),wx,setTimeout})
 const p={...page,data:structuredClone(page.data),setData(v){Object.assign(this.data,v)}}
 p._loadedToken='token';Object.assign(p.data,{ready:true,scheduleId:'1',classDate:'2026-11-15'})
 return {p,calls}
}
test('trial picker searches first and creates a name-only profile only on explicit action',async()=>{
 const {p,calls}=harness(async o=>({data:o.method==='POST'?{studentId:'91',guestId:'17',studentName:'试听甲',source:'TRIAL'}:[]}))
 p.addAudited();assert.equal(p.data.guestOpen,true)
 p.guestKeywordInput({detail:{value:'试听甲'}});await p.searchGuests();assert.equal(calls[0].data.keyword,'试听甲')
 await p.createTrial();const post=calls.find(o=>o.method==='POST')
 assert.equal(post.data.studentName,'试听甲');assert.equal(post.data.source,'TRIAL');assert.equal(p.data.rows[0].studentId,'91');assert.equal(p.data.rows[0].signStatus,'4')
})
test('temporary transfer requires original occurrence and retains stable student identity',async()=>{
 const {p,calls}=harness(async o=>({data:o.url.endsWith('/sources')?[{lessonId:'22',courseName:'原班',lessonNo:3}]:{guestId:'18',studentId:'92',studentName:'调课甲',source:'TRANSFER'}}))
 p.addTransferred();p.data.guestCandidates=[{studentId:'92',studentName:'调课甲'}]
 await p.pickGuest({currentTarget:{dataset:{index:0}}});assert.equal(p.data.guestSources.length,1)
 await p.saveGuest();assert.equal(calls.filter(o=>o.method==='POST').length,0)
 p.pickGuestSource({detail:{value:0}});await p.saveGuest()
 const post=calls.find(o=>o.method==='POST');assert.equal(post.data.sourceLessonId,'22');assert.equal(post.data.studentId,'92');assert.equal(p.data.rows[0].signStatus,'5')
})
test('removal persists before changing visible roster; blocked submission cannot add',async()=>{
 const {p,calls}=harness(async()=>({}));p.data.rows=[{rowKey:'r1',guestId:'18',added:true,studentName:'甲',signStatus:'4'}]
 await p.removeGuest(p.data.rows[0]);assert.equal(calls[0].method,'DELETE');assert.equal(p.data.rows.length,0)
 p.data.locked=true;p.addAudited();assert.equal(p.data.guestOpen,false)
})

test('future lecture roster remains unmarked and never pretends students attended',async()=>{
 const {p}=harness(async o=>({data:o.url.endsWith('/teacher-lesson')?{classDate:'2027-02-27',lessonNo:1,courseName:'测试课程',upcoming:true,startTime:'18:30:00',endTime:'20:00:00'}:o.url.endsWith('/detail')?{reviewStage:'NEW',review:{reviewStage:'NEW'},details:[]}:o.url.endsWith('/students')?[{studentId:'191',enrollmentId:'476',studentName:'学员甲',source:'ENROLLED'}]:[]}))
 Object.assign(p.data,{lessonId:'2084',classDate:'2027-02-27'})
 await p.loadDetail()
 assert.equal(p.data.ready,true);assert.equal(p.data.upcoming,true);assert.equal(p.canEdit(),false)
 assert.equal(p.data.rows[0].signStatus,'');assert.equal(p.data.rows[0].statusText,'未点名')
 assert.equal(p.data.stats.actual,0);assert.equal(p.data.stats.unmarked,1);assert.equal(p.data.stats.total,1)
})
test('started lecture requires explicit marking before submitting; saved facts remain unchanged',async()=>{
 const {p,calls}=harness(async o=>({data:o.url.endsWith('/detail')?{reviewStage:'NEW',details:[]}:o.url.endsWith('/students')?[{studentId:'1',studentName:'学员甲',source:'ENROLLED'}]:[]}))
 await p.loadDetail();await p.submit();assert.equal(calls.some(o=>o.method==='POST'),false)
 p.setStatus({currentTarget:{dataset:{rowKey:p.data.rows[0].rowKey,key:'1'}}});assert.equal(p.data.stats.actual,1);assert.equal(p.data.stats.unmarked,0)
 const saved=harness(async()=>({data:{reviewStage:'PENDING_FIRST',details:[{studentId:'1',studentName:'学员甲',sourceType:'1',signStatus:'1'}]}}))
 await saved.p.loadDetail();assert.equal(saved.p.data.rows[0].signStatus,'1');assert.equal(saved.p.data.stats.actual,1);assert.equal(saved.p.data.locked,true)
})

test('teacher leave selection updates leave count immediately and submits the leave status',()=>{
 const {p}=harness(async()=>({}))
 const leave=p.decorateRow({studentId:'1',studentName:'甲',signStatus:'3'},'1')
 const absent=p.decorateRow({studentId:'2',studentName:'乙',signStatus:'6'},'1')
 const unmarked=p.decorateRow({studentId:'3',studentName:'丙'},'1')
 assert.deepEqual(Array.from(leave.statusOptions,o=>o.key),['1','2','3'])
 assert.equal(leave.signStatus,'3');assert.equal(leave.statusText,'请假')
 p.data.rows=[leave,absent,unmarked]
 assert.equal(p.buildStats(p.data.rows).leave,2)
 p.setStatus({currentTarget:{dataset:{rowKey:leave.rowKey,key:'3'}}})
 assert.equal(p.data.rows[0].signStatus,'3')
 p.setStatus({currentTarget:{dataset:{rowKey:unmarked.rowKey,key:'3'}}})
 assert.equal(p.data.rows[2].signStatus,'3');assert.equal(p.data.stats.leave,3)
 assert.equal(p.data.stats.actual,0)
 p.setStatus({currentTarget:{dataset:{rowKey:unmarked.rowKey,key:'1'}}})
 assert.equal(p.data.stats.leave,2);assert.equal(p.data.stats.actual,1)
 for(const type of ['2','3'])assert.equal(p.decorateRow({},type).statusOptions.filter(o=>o.key==='3'||o.key==='6').length,1)
})
