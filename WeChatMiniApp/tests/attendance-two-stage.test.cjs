const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
const root=path.resolve(__dirname,'..')
function harness({stage='PENDING_FIRST',initial=true,final=false,correct=false,uploadImage=async()=>'/profile/p.png'}={}){
 let page;const calls=[];const wx={getStorageSync:()=> 'token',showToast(){},showModal:o=>o.success({confirm:true})}
 const sheet={submissionVersion:'snapshot-7',canInitialReview:initial,canFinalReview:final,canCorrect:correct,review:{reviewStage:stage,reviewStatus:stage==='RETURNED'?'2':'0',reviewVersion:7},stats:{totalCount:2,actualCount:1},details:[{signInId:'1',studentName:'同名',sourceType:'1',signStatus:'1'}]}
 const api={canReviewStage:stage=>stage==='initial'?initial:final,base:'/miniapp/education/sign',authorize:async()=>true,images:()=>[],confirm:async()=>true,request:async o=>{calls.push(o);return {data:sheet}}}
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/sign-review-detail/sign-review-detail.js'),'utf8'),{Page:p=>page=p,require:id=>id.endsWith('/sign-review')?api:id.endsWith('/public-file-url')?value=>'http://localhost'+value:{uploadImage},wx,console})
 const p={...page,data:structuredClone(page.data),setData(v){Object.assign(this.data,v)}};p.onLoad({scheduleId:'123',classDate:'2026-11-06'});return {p,calls,wx}
}
test('initial requires uploaded images and posts both inspected versions',async()=>{const {p,calls}=harness();await p.onShow();await p.confirmReview();assert.match(p.data.error,/监控/);assert.equal(calls.filter(x=>x.method==='POST').length,0);p.data.pendingImages=['/profile/p.png'];await p.confirmReview();const post=calls.find(x=>x.method==='POST');assert.equal(post.data.action,'APPROVE');assert.equal(post.data.reviewVersion,7);assert.equal(post.data.submissionVersion,'snapshot-7');assert.equal(post.data.images[0],'/profile/p.png')})
test('final reviewer submits approval without replacing initial images',async()=>{const {p,calls}=harness({stage:'PENDING_FINAL',initial:false,final:true});await p.onShow();assert.equal(p.data.canInitialReview,false);assert.equal(p.data.canFinalReview,true);await p.confirmReview();assert.equal(calls.find(x=>x.method==='POST').data.images.length,0)})
test('server permission fails closed; return uses same version guarded endpoint',async()=>{const a=harness({initial:false});await a.p.onShow();await a.p.confirmReview();assert.equal(a.calls.filter(x=>x.method==='POST').length,0);const b=harness();await b.p.onShow();b.p.data.reason='重新核对';await b.p.rejectReview();const post=b.calls.find(x=>x.method==='POST');assert.equal(post.url,'/miniapp/education/sign/review');assert.equal(post.data.action,'RETURN');assert.equal(post.data.reviewVersion,7)})
test('roster range validation and todos preserve linked identity and role',()=>{const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/attendance-workflow.js'),'utf8'),{module,require:()=>({request(){}})});const u=module.exports,form={scheduleId:'9',studentId:'101',startLesson:3,endLesson:8,source:'OFFLINE',paymentStatus:'UNPAID',reason:'插班'};assert.equal(u.range(form).studentId,'101');assert.throws(()=>u.range({...form,endLesson:2}),/起止/);assert.throws(()=>u.range({...form,source:'ENROLLED'}),/报名编号/);assert.match(u.todoRoute({scheduleId:'9',classDate:'2026-11-06'},'teacher'),/sign-in-edit/);assert.match(u.todoRoute({scheduleId:'9',classDate:'2026-11-06'},'education'),/sign-review-detail/)})
test('roster editor selects same-name children by stable ID and removes from a future lecture',async()=>{
 let page;const calls=[];const wx={getStorageSync:()=> 'token',showToast(){},showModal:o=>o.success({confirm:true,content:'5'})}
 const base='/miniapp/staff/attendance',request=async o=>{calls.push(o);if(o.url.endsWith('/students'))return {data:[{studentId:'101',studentName:'同名'},{studentId:'202',studentName:'同名'}]};return {data:[]}}
 const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/attendance-workflow.js'),'utf8'),{module,require:()=>({request})})
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/attendance-roster/attendance-roster.js'),'utf8'),{Page:p=>page=p,require:()=>module.exports,wx})
 const p={...page,data:structuredClone(page.data),setData(v){Object.assign(this.data,v)}};p._active=true;p._token='token';p.data.lessons=[{lessonNo:3},{lessonNo:8}];p.data.form={scheduleId:'9',studentId:'',enrollmentId:'',startLesson:3,endLesson:8,source:'OFFLINE',paymentStatus:'UNPAID',reason:'季度插班'}
 await p.search();p.pickStudent({detail:{value:1}});await p.add();const added=calls.find(o=>o.method==='POST');assert.equal(added.data.studentId,'202');assert.equal(added.data.paymentStatus,'UNPAID');p.data.rows=[{membershipId:'55',studentId:'202'}];p.remove({currentTarget:{dataset:{index:0}}});await new Promise(r=>setImmediate(r));const removed=calls.find(o=>o.method==='PUT');assert.equal(removed.url,base+'/roster/55');assert.equal(removed.data.effectiveLesson,5)
})
test('todos load current workspace and navigate the exact occurrence',async()=>{
 let page,role='education';const navigations=[],calls=[];const wx={getStorageSync:()=> 'token',navigateTo:o=>navigations.push(o.url)}
 const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/attendance-workflow.js'),'utf8'),{module,require:()=>({request:async o=>{calls.push(o);return {data:{count:1,items:[{scheduleId:'99',classDate:'2026-11-06'}]}}}})})
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/attendance-todos/attendance-todos.js'),'utf8'),{Page:p=>page=p,require:id=>id.endsWith('/attendance-filters')?require('../utils/attendance-filters'):id.endsWith('/access')?{selected:()=>role}:module.exports,wx})
 const p={...page,data:structuredClone(page.data),setData(v){Object.assign(this.data,v)}};await p.onShow();assert.equal(calls[0].data.role,'education');p.open({currentTarget:{dataset:{index:0}}});assert.match(navigations[0],/sign-review-detail.*scheduleId=99&classDate=2026-11-06/);role='teacher';p.open({currentTarget:{dataset:{index:0}}});assert.equal(navigations.length,1);await p.onShow();p.open({currentTarget:{dataset:{index:0}}});assert.match(navigations[1],/sign-in-edit/)
})

test('monitor screenshot picker resumes without refreshing away uploads or leaving button busy',async()=>{
 let finish;const h=harness({uploadImage:()=>new Promise(resolve=>finish=resolve)});await h.p.onShow()
 let picker;h.wx.chooseMedia=o=>{picker=o;h.p.onHide()}
 h.p.chooseImages();await h.p.onShow()
 const uploading=picker.success({tempFiles:[{tempFilePath:'/tmp/screenshot.png'}]})
 finish('/profile/monitor.png');await uploading
 assert.equal(h.p.data.uploading,false)
 assert.deepEqual(Array.from(h.p.data.pendingImages),['/profile/monitor.png'])
 assert.equal(h.calls.filter(o=>o.url.endsWith('/detail')).length,1)
})
test('monitor selection failure clears busy state and can be retried',async()=>{
 const h=harness();await h.p.onShow();let picker;h.wx.chooseMedia=o=>picker=o
 h.p.chooseImages();picker.fail({errMsg:'chooseMedia:fail permission denied'})
 assert.equal(h.p.data.uploading,false);assert.match(h.p.data.error,/相册|图片|选择/)
 h.p.chooseImages();picker.fail({errMsg:'chooseMedia:fail cancel'});assert.equal(h.p.data.uploading,false)
})

test('successful screenshots survive later upload failure and preview round trip',async()=>{
 let attempt=0;const h=harness({uploadImage:async()=>{if(attempt++)throw new Error('网络断开');return '/profile/first.png'}});await h.p.onShow()
 let picker;h.wx.chooseMedia=o=>picker=o;h.p.chooseImages()
 await picker.success({tempFiles:[{tempFilePath:'a.png'},{tempFilePath:'b.png'}]})
 assert.equal(h.p.data.uploading,false);assert.equal(h.p.data.pendingImages.length,1);assert.match(h.p.data.error,/网络/)
 h.wx.previewImage=()=>h.p.onHide();h.p.previewImage({currentTarget:{dataset:{urls:h.p.data.pendingImageUrls,current:h.p.data.pendingImageUrls[0]}}});await h.p.onShow()
 assert.equal(h.p.data.pendingImages.length,1)
})
test('leaving detail permanently discards late upload responses',async()=>{
 let finish;const h=harness({uploadImage:()=>new Promise(resolve=>finish=resolve)});await h.p.onShow();let picker;h.wx.chooseMedia=o=>picker=o;h.p.chooseImages()
 const uploading=picker.success({tempFiles:[{tempFilePath:'a.png'}]});h.p.onUnload();finish('/profile/late.png');await uploading;assert.equal(h.p.data.pendingImages.length,0)
})
test('upload uses bounded timeout and reports network failure',async()=>{
 const module={exports:{}};let upload;const wx={getStorageSync:()=> 'token',uploadFile:o=>upload=o}
 vm.runInNewContext(fs.readFileSync(path.join(root,'utils/attendance-workflow.js'),'utf8'),{module,require:()=>({}),wx,getApp:()=>({globalData:{baseUrl:'http://localhost:8080'}})})
 const pending=module.exports.uploadImage('/tmp/a.png');assert.equal(upload.timeout,30000);upload.fail({errMsg:'uploadFile:fail timeout'});await assert.rejects(pending,/超时/)
})
