const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const root=path.join(__dirname,'..'),plain=x=>JSON.parse(JSON.stringify(x))
function harness(view,request,extra={}){
 const store={token:'parent-a',roles:['parent'],userInfo:{}},routes=[]
 const wx={getStorageSync:k=>store[k],showToast(){},showModal:o=>o.success({confirm:true}),stopPullDownRefresh(){},navigateTo:o=>routes.push(o.url),setNavigationBarTitle(){},...extra}
 const personal={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/personal-account.js'),'utf8'),{module:personal,require:()=>({request}),wx,getCurrentPages:()=>[]})
 const child={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/child-account.js'),'utf8'),{module:child,require:id=>id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):id.endsWith('personal-account')?personal.exports:{request},wx})
 const def=child.exports.page(view),p={...def,data:structuredClone(def.data),_alive:true,_visible:true,_token:'parent-a'};p.setData=u=>{for(const[k,v]of Object.entries(u)){const a=k.split('.');let o=p.data;while(a.length>1)o=o[a.shift()];o[a[0]]=v}}
 return{p,store,routes,helper:child.exports}
}
const kid={accountId:'9007199254740993',studentName:'同名学生',gradeName:'初二',remainingCredits:7,reservedCredits:2,availableCredits:999,consumedCredits:3,courseCount:2,actionPendingCount:1,paymentPendingCount:1,homeworkPendingCount:0}
const rows={rows:[{recordKey:'E:9',enrollmentId:'9',courseClassName:'课程甲',payStatus:'未支付',billingStatus:'OPEN',actionable:true}],total:1}
const click=(key,value)=>({currentTarget:{dataset:{[key]:value}}})

test('only fees include credit packages and purchase entry carries the selected child',async()=>{
 const calls=[];const{p,routes}=harness('detail',o=>{calls.push(plain(o));return Promise.resolve({data:o.url.includes('/children/')?{child:kid}:rows})});p.data.accountId=kid.accountId
 await p.load();assert.equal(calls.at(-1).data.includePackages,undefined)
 p.data.section='fees';await p.load();assert.equal(calls.at(-1).data.includePackages,true)
 assert.equal(typeof p.purchasePackage,'function');p.purchasePackage();assert.equal(routes.at(-1),'/pages/lesson-packages/lesson-packages?accountId='+kid.accountId)
})
test('package fee records use package progress instead of displaying empty class times',()=>{
 const{helper}=harness('list',()=>{});const row=helper.record({billType:'CREDIT_PACKAGE',enrollmentId:'9',payStatus:'未支付',billingStatus:'OPEN'},'enrollments');assert.equal(row.timeText,'一对一课包 · 线下确认收款后入账')
})

function feature(name,request){
 let definition;const routes=[],store={token:'parent-a',roles:['parent'],userInfo:{}}
 const wx={getStorageSync:k=>store[k],navigateTo:o=>routes.push(o.url),showToast(){},stopPullDownRefresh(){}}
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages',name,name+'.js'),'utf8'),{Page:d=>definition=d,require:id=>id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):{request,requestKey:()=> 'stable-key'},wx,getApp:()=>({globalData:{baseUrl:''}}),getCurrentPages:()=>[]})
 const p={...definition,data:structuredClone(definition.data)};p.setData=u=>{for(const[k,v]of Object.entries(u)){const a=k.split('.');let o=p.data;while(a.length>1)o=o[a.shift()];o[a[0]]=v}}
 return{p,routes,store}
}

test('scoped enrollment keeps the selected sibling and never creates a replacement for a missing child',async()=>{
 const calls=[];const{p,routes}=feature('enrollment',o=>{calls.push(o);return Promise.resolve({data:[]})});p.loadDict=()=>Promise.resolve();p.loadPage=()=>Promise.resolve();p.onLoad({accountId:'22'})
 p.data.lessonAccounts=[{accountId:'11',studentName:'甲'},{accountId:'22',studentName:'乙'}];p.data.schedules=[{viewKey:'7',scheduleId:'7',canEnroll:true}]
 p.openEnroll(click('key','7'));assert.equal(p.data.selectedChildId,'22');assert.equal(p.data.form.studentName,'乙');p.chooseLessonAccount({detail:{value:0}});assert.equal(p.data.selectedChildId,'22')
 await p.submitEnroll();assert.equal(calls.find(o=>o.method==='POST').data.lessonAccountId,'22');p.openTuition(click('id','9'));assert.ok(routes[0].endsWith('&accountId=22'))
 p.data.lessonAccounts=[];p.openEnroll(click('key','7'));assert.equal(p.data.newChild,false);assert.equal(p.data.childMissing,true);await p.submitEnroll();assert.equal(calls.filter(o=>o.method==='POST').length,1)
})

test('child homework rows retain distinct enrollment identities and discard delayed foreign-parent responses',async()=>{
 let finish;const calls=[];const{p,routes,store}=feature('homework',o=>{calls.push(o);return new Promise(r=>finish=r)});p.onLoad({accountId:'22',status:'todo'});const loading=p.loadList()
 finish({data:[{homeworkId:'7',matchedScheduleId:'8',enrollmentId:'9',status:'1',submissionCount:0}]});await loading
 assert.equal(calls[0].data.accountId,'22');p.openDetail(click('key',p.data.list[0].rowKey));assert.match(routes[0],/enrollmentId=9&accountId=22/)
 const delayed=p.loadList();store.token='parent-b';finish({data:[{homeworkId:'secret',status:'1'}]});await delayed;assert.equal(p.data.list[0].homeworkId,'7')
})

test('child homework detail and submission carry the same account and enrollment',async()=>{
 const calls=[];const{p}=feature('homework-detail',o=>{calls.push(o);return Promise.resolve({data:{homework:{enrollmentId:'9'},files:[]}})});p.onLoad({id:'7',scheduleId:'8',enrollmentId:'9',accountId:'22'});await p.loadDetail();await p.submit()
 assert.match(calls[0].url,/enrollmentId=9&accountId=22/);const body=calls.find(o=>o.method==='POST').data;assert.equal(body.enrollmentId,'9');assert.equal(body.accountId,'22')
})
test('child cards calculate available allowance rather than trusting an inconsistent display value',()=>{const{helper}=harness('list',()=>{});const c=helper.child(kid);assert.equal(c.availableText,'5');assert.equal(c.pendingBadge,'1');assert.equal(helper.child({...kid,actionPendingCount:0,remainingCredits:0,reservedCredits:0}).pendingBadge,'');assert.equal(helper.child({...kid,remainingCredits:3,reservedCredits:3}).availableText,'0')})
test('account homepage loads children without fetching family-wide payment or transaction tabs',async()=>{const calls=[];const{p}=harness('list',o=>{calls.push(o);return Promise.resolve({data:{children:[kid],parent:{},unlinkedCount:3,actionPendingCount:1}})});await p.load();assert.equal(calls.length,1);assert.equal(calls[0].url,'/miniapp/parent/account');assert.equal(p.data.accounts[0].availableText,'5');assert.equal(p.data.unlinkedCount,3)})
test('child detail queries only a stable child scope including pagination',async()=>{const calls=[];const{p}=harness('detail',o=>{calls.push(plain(o));return Promise.resolve({data:o.url.includes('/children/')?{child:kid,pendingCount:1}:rows})});p.data.accountId=kid.accountId;await p.load();assert.equal(calls[0].url,'/miniapp/parent/account/children/'+kid.accountId);assert.equal(calls[1].data.accountId,kid.accountId);assert.equal(calls[1].data.kind,'enrollments');assert.equal(p.data.child.studentName,kid.studentName)})
test('all child feature links preserve the selected identity while parent services do not carry a child',()=>{const{p,routes}=harness('detail',()=>{});p.data.accountId=kid.accountId;p.data.child=kid;p.enroll();p.timetable();p.homework(click('todo',1));p.leave();assert.ok(routes.every(url=>url.includes('accountId='+kid.accountId)));assert.ok(routes[2].includes('status=todo'));p.openService(click('key','referral'));assert.equal(routes.at(-1),'/pages/parent-referral/parent-referral')})
test('payment TODO has an explicit actionable query and preserves child selection',async()=>{let query;const{p}=harness('detail',o=>{if(o.url.endsWith('/records'))query=o.data;return Promise.resolve({data:o.url.includes('/children/')?{child:kid,pendingCount:1}:rows})});p.data.accountId=kid.accountId;await p.handlePayment();assert.equal(query.pendingOnly,true);assert.equal(query.accountId,kid.accountId);assert.equal(p.data.section,'fees')})
test('pending receipt confirmation remains progress rather than a parent action',()=>{const{helper}=harness('list',()=>{});const r=helper.record({payStatus:'未支付',billingStatus:'OPEN',paymentPendingCount:1,actionable:false,enrollmentId:9},'enrollments');assert.equal(r.progressText,'待管理员确认收款');assert.equal(r.actionable,false);assert.equal(helper.record({payStatus:'未支付',billingStatus:'PENDING_PRICE',enrollmentId:10},'enrollments').progressText,'待核价')})
test('unlinked historical records stay in a separate parent scope with no child assignment',async()=>{let query;const{p}=harness('list',o=>{if(o.url.endsWith('/records'))query=o.data;return Promise.resolve({data:o.url.endsWith('/records')?rows:{children:[kid],parent:{}}})});p.data.legacy=true;await p.load();assert.equal(query.unlinkedOnly,true);assert.equal(query.accountId,undefined)})
test('late child data from another parent cannot populate child cards',async()=>{let finish;const{p,store}=harness('list',()=>new Promise(r=>finish=r));const work=p.load();store.token='parent-b';finish({data:{children:[kid],parent:{}}});await work;assert.equal(p.data.accounts.length,0);p.onShow();assert.equal(p.data.child,null);assert.match(p.data.error,/身份已变化/)})
test('returning after a deletion dialog does not approve a stale delete',async()=>{let modal,posts=0;const{p}=harness('detail',o=>{if(o.method==='DELETE')posts++;return Promise.resolve({data:o.url.includes('/children/')?{child:kid,pendingCount:1}:rows})},{showModal:o=>modal=o});p.data.accountId=kid.accountId;p.data.accounts=[kid];p.data.child=kid;const work=p.removeChild(click('id',kid.accountId));p.onHide();p.onShow();modal.success({confirm:true});await work;assert.equal(posts,0)})
test('financial detail navigation carries both enrollment and child identity',()=>{const{p,routes}=harness('detail',()=>{});p.data.accountId=kid.accountId;p.data.child=kid;p.data.records=[{enrollmentId:'9'}];p.openFinance(click('id','9'));assert.equal(routes[0],'/pages/tuition-detail/tuition-detail?id=9&accountId='+kid.accountId)})
test('new view retains original form implementation and referenced-child deletion protection messaging',()=>{const s=fs.readFileSync(path.join(root,'utils/child-account.js'),'utf8');assert.match(s,/personal\.page\(false,'children'\)/);const w=fs.readFileSync(path.join(root,'templates/child-account.wxml'),'utf8');assert.match(w,/资深教师每次扣 2 个额度/);assert.match(w,/额度不等于可上课节数/);assert.match(w,/未关联历史记录/);assert.match(w,/家长服务/)})
