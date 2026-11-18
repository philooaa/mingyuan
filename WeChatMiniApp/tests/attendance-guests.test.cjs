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
