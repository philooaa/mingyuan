const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const loadContext=require('./helpers/child-context.cjs'),root=path.join(__dirname,'..')
function env(){const store={token:'parent-a',roles:['parent'],userInfo:{userId:'100'}},routes=[],app={globalData:{baseUrl:'http://localhost',userInfo:null}};const wx={getStorageSync:k=>store[k],setStorageSync:(k,v)=>store[k]=v,navigateTo:o=>routes.push(o.url),redirectTo:o=>routes.push(o.url),showToast(){}};const context=loadContext(wx,()=>app);return{store,routes,app,wx,context}}
const a={accountId:'11',studentName:'学生甲',gradeName:'初二',remainingCredits:7,reservedCredits:2,courseCount:3,actionPendingCount:1},b={accountId:'22',studentName:'学生乙',gradeName:'高一',remainingCredits:4,reservedCredits:3,courseCount:1,actionPendingCount:2}
function home(request){const e=env();let definition;vm.runInNewContext(fs.readFileSync(path.join(root,'pages/index/index.js'),'utf8'),{Page:p=>definition=p,require:id=>id.endsWith('access')?require('./helpers/access.cjs')(e.wx):id.endsWith('child-context')?e.context:id.endsWith('request')?{request}:id.endsWith('teacher-course-state')?require('../utils/teacher-course-state'):{},wx:e.wx,getApp:()=>e.app,setInterval:()=>0,clearInterval(){}});const p={...definition,data:structuredClone(definition.data),_visible:true,_roleLoadVersion:1};p.setData=u=>Object.assign(p.data,u);return{...e,p}}
test('selection is saved per parent and backend, survives role round trips, and reconciles deleted children',()=>{
 const{context,store,app}=env();context.reconcile([a,b]);assert.equal(context.id(),'11');context.select(b);store.userInfo={userId:'200'};assert.equal(context.id(),'');context.reconcile([a]);store.userInfo={userId:'100'};assert.equal(context.id(),'22');store.roles=['teacher'];assert.equal(context.id(),'');store.roles=['parent'];assert.equal(context.id(),'22');context.reconcile([a]);assert.equal(context.id(),'11');app.globalData.baseUrl='http://other';assert.equal(context.id(),'')
})
test('child requests require a selection and reject mismatches while parent services remain shared',()=>{
 const{context}=env();assert.throws(()=>context.prepare({url:'/miniapp/parent/timetable'}),/选择/);context.select(a)
 assert.equal(context.prepare({url:'/miniapp/parent/tuition'}).options.data.accountId,'11');assert.throws(()=>context.prepare({url:'/miniapp/parent/homework/list',data:{accountId:'22'}}),/切换/)
 for(const url of ['/miniapp/parent/coupons','/miniapp/parent/referral','/miniapp/parent/account','/miniapp/parent/account/records?kind=orders'])assert.equal(context.prepare({url}).ticket,null)
 assert.equal(context.prepare({url:'/miniapp/parent/tuition/9',parentScope:true}).ticket,null)
 assert.match(context.prepare({url:'/miniapp/parent/enrollment/9',method:'DELETE'}).options.url,/accountId=11/)
})
test('an A to B to A switch invalidates the original request, including after returning to the same ID',()=>{const{context}=env();context.select(a);const ticket=context.snapshot();context.select(b);context.select(a);assert.equal(context.current(ticket),false)})
test('network responses from the previous child are rejected before reaching a page',async()=>{
 const e=env();e.context.select(a);let pending;e.wx.request=o=>pending=o;const module={exports:{}}
 vm.runInNewContext(fs.readFileSync(path.join(root,'utils/request.js'),'utf8'),{module,require:()=>e.context,getApp:()=>e.app,wx:e.wx,setTimeout(){}})
 const work=module.exports.request({url:'/miniapp/parent/timetable'});assert.equal(pending.data.accountId,'11');e.context.select(b);pending.success({statusCode:200,data:{code:200,data:[{private:'child A'}]}});await assert.rejects(work,/切换/)
})
test('a stale page blanks its data and reopens in the current child; old async setData cannot restore it',()=>{
 const e=env();e.context.select(a);const def=e.context.page({data:{rows:[]},onLoad(q){this.accountId=q.accountId},onShow(){}},{route:'/pages/homework/homework'}),p={...def,data:{rows:[]},setData:u=>Object.assign(p.data,u)}
 p.onLoad({accountId:'999'});assert.equal(p.accountId,'11');p.setData({rows:['child A']});e.context.select(b);p.setData({rows:['late A']});assert.deepEqual(p.data.rows,['child A']);p.onShow();assert.deepEqual(Array.from(p.data.rows),[]);assert.equal(e.routes[0],'/pages/homework/homework?accountId=22')
})
test('a parent without children is guided to the existing add form without requesting a family-wide list',()=>{
 const e=env();let loads=0;const def=e.context.page({data:{rows:[]},onLoad(){loads++},onShow(){}},{route:'/pages/homework/homework'}),p={...def,data:{rows:[]},setData:u=>Object.assign(p.data,u)};p.onLoad({});p.onShow();assert.equal(loads,0);assert.match(e.routes[0],/parent-account.*create=1/)
})
test('unlinked history survives child switches but is cleared when the parent account changes',()=>{
 const e=env();e.context.select(a);const def=e.context.page({data:{rows:[]},onLoad(){},onShow(){}},{route:'/pages/tuition-list/tuition-list'}),p={...def,data:{rows:[]},setData:u=>Object.assign(p.data,u)};p.onLoad({unlinked:'1'});p.setData({rows:['legacy A']});e.context.select(b);p.onShow();assert.equal(e.routes.length,0);e.store.userInfo={userId:'200'};p.onShow();assert.equal(p.data.rows.length,0);assert.match(e.routes[0],/parent-account/)
})
test('home shows real selected-child allowance and links child actions separately from parent services',async()=>{
 const calls=[];const e=home(o=>{calls.push(o);return Promise.resolve({data:o.url.endsWith('/account')?{children:[a,b]}:[]})});await e.p.loadParentTag();assert.equal(e.p.data.currentChild.studentName,'学生甲');assert.equal(e.p.data.stats[0].value,'5');assert.equal(e.p.data.schedule.length,0)
 e.p.chooseChild({currentTarget:{dataset:{id:'22'}}});assert.equal(e.p.data.currentChild.studentName,'学生乙');assert.equal(e.p.data.stats[0].value,'1');assert.equal(e.p.data.stats[2].value,'2')
 e.p.setData({quickActions:[{title:'我的作业',route:'/pages/homework/homework'},{title:'邀请好友',route:'/pages/parent-referral/parent-referral'}]})
 e.p.handleAction({currentTarget:{dataset:{title:'我的作业',route:'/pages/homework/homework'}}});e.p.handleAction({currentTarget:{dataset:{title:'邀请好友',route:'/pages/parent-referral/parent-referral'}}});assert.equal(e.routes[0],'/pages/homework/homework?accountId=22');assert.equal(e.routes[1],'/pages/parent-referral/parent-referral')
 e.p.addChild();assert.match(e.routes.at(-1),/create=1&returnHome=1/);assert.ok(calls.some(o=>o.data&&o.data.accountId==='22'))
})
test('empty child lists and failed requests never show sample courses or the previous child',async()=>{
 let fail=false;const e=home(()=>fail?Promise.reject(new Error('offline')):Promise.resolve({data:{children:[]}}));await e.p.loadParentTag();assert.equal(e.p.data.currentChild,null);assert.equal(e.p.data.schedule.length,0);assert.ok(e.p.data.stats.every(s=>s.value==='—'));fail=true;await e.p.loadParentTag();assert.equal(e.p.data.currentChild,null);assert.equal(e.p.data.parentChildren.length,0);assert.match(e.p.data.childError,/加载失败/)
})
test('adding through the reused child form selects the new child once and returns home',async()=>{
 const e=env(),children=[a];let returned=0;const request=o=>{if(o.method==='POST'){children.push(b);return Promise.resolve({data:'22'})}return Promise.resolve({data:o.url.endsWith('/dict')?{grades:[{dictLabel:'高一'}]}:{children,parent:{parentId:'100'}}})}
 e.wx.navigateBack=()=>returned++;const personal={exports:{}},child={exports:{}}
 vm.runInNewContext(fs.readFileSync(path.join(root,'utils/personal-account.js'),'utf8'),{module:personal,require:()=>({request}),wx:e.wx,getCurrentPages:()=>[]})
 vm.runInNewContext(fs.readFileSync(path.join(root,'utils/child-account.js'),'utf8'),{module:child,require:id=>id.endsWith('child-context')?e.context:id.endsWith('personal-account')?personal.exports:{request},wx:e.wx})
 const def=child.exports.page('list'),p={...def,data:structuredClone(def.data)};p.setData=u=>Object.assign(p.data,u);p.onLoad({create:'1',returnHome:'1'});await p.load();assert.equal(p.data.showForm,true)
 p.data.grades=['高一'];p.data.gradeIndex=0;p.data.form.studentName='学生乙';await p.save();assert.equal(e.context.id(),'22');assert.equal(returned,1)
 e.context.select(a);p._returnHome=false;await p.load();assert.equal(e.context.id(),'11');p.openChild({currentTarget:{dataset:{id:'22'}}});assert.equal(e.context.id(),'22')
})
