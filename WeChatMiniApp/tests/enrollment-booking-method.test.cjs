const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function setup() {
 let definition;const calls=[],storage={token:'parent',roles:['parent']},wx={getStorageSync:k=>storage[k],showToast(){},navigateTo(){}}
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/enrollment/enrollment.js'),'utf8'),{Page:p=>definition=p,wx,require:id=>id.includes('course-term-filter')?require('../utils/course-term-filter'):id.includes('child-context')?require('./helpers/child-context.cjs')(wx):id.includes('personal-account')?{requestKey:()=>''}:{request:async o=>{calls.push(o);return {data:[]}}}})
 const p={...definition,data:structuredClone(definition.data),_token:'parent',_unloaded:false,setData(v){Object.assign(this.data,v)}}
 p.data.courseTypeIndex=1;p.data.lessonAccounts=[{accountId:'11',studentName:'孩子',availableCredits:5}]
 p.data.schedules=[{viewKey:'single',scheduleId:'9',classDate:'2030-10-07',canEnroll:true},{viewKey:'class',scheduleId:'10',canEnroll:true}]
 p.loadPage=async()=>{};return {p,calls}
}
const choose=(p,method)=>p.chooseBookingMethod({currentTarget:{dataset:{method}}})
const open=p=>p.openEnroll({currentTarget:{dataset:{key:'single'}}})
test('page defaults to single booking and carries package selection into confirmation',()=>{
 const {p}=setup();assert.equal(p.data.bookingMethod,'C');choose(p,'P');open(p);assert.equal(p.data.settlementMethod,'P');p.closeEnroll();open(p);assert.equal(p.data.settlementMethod,'P')
 p.chooseSettlement({currentTarget:{dataset:{method:'C'}}});assert.equal(p.data.bookingMethod,'C');p.closeEnroll();open(p);assert.equal(p.data.settlementMethod,'C')
})
test('page selector does not change an open booking or accept invalid modes',()=>{
 const {p}=setup();choose(p,'invalid');assert.equal(p.data.bookingMethod,'C');open(p);choose(p,'P');assert.equal(p.data.settlementMethod,'C');assert.equal(p.data.bookingMethod,'C')
 p.closeEnroll();p.data.submitting=true;choose(p,'P');assert.equal(p.data.bookingMethod,'C')
})
test('each single-lesson booking submits its selected settlement without affecting class bookings',async()=>{
 for(const method of ['C','P']){
  const {p,calls}=setup();choose(p,method);open(p);await p.submitEnroll();const r=calls.find(r=>r.method==='POST');assert.equal(r.data.settlementMethod,method);assert.equal(r.data.classDate,'2030-10-07')
 }
 const {p,calls}=setup();choose(p,'P');p.data.courseTypeIndex=0;p.openEnroll({currentTarget:{dataset:{key:'class'}}});await p.submitEnroll();assert.equal(calls.find(r=>r.method==='POST').data.settlementMethod,'C')
})
