const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const root=path.join(__dirname,'..'),clone=x=>JSON.parse(JSON.stringify(x))
test('home referral badge counts pending and anomalies without surviving role switch',async()=>{
  let definition,resolve;const storage={token:'session',roles:['admin'],currentRole:'admin'}
  vm.runInNewContext(fs.readFileSync(path.join(root,'pages/index/index.js'),'utf8'),{Page:p=>definition=p,require:id=>id.endsWith('access')?require('./helpers/access.cjs')({getStorageSync:k=>storage[k]}):id.endsWith('request')?{request:()=>new Promise(r=>resolve=r)}:id.endsWith('teacher-course-state')?require('../utils/teacher-course-state'):{},wx:{getStorageSync:k=>storage[k]},console,setInterval:()=>0,clearInterval(){}})
  const p={...definition,data:{currentRole:'admin',quickActions:[{key:'referral'}]},_visible:true,_roleLoadVersion:1};p.setData=u=>Object.assign(p.data,u)
  let work=p.loadReferralPending();resolve({data:{pending:2,anomalies:3}});await work;assert.equal(p.data.quickActions[0].badgeText,'5')
  work=p.loadReferralPending();p.stopCoursePendingRefresh();storage.currentRole='parent';p.data.currentRole='parent';p.data.quickActions=[];resolve({data:{pending:9,anomalies:9}});await work;assert.deepEqual(p.data.quickActions,[])
})
function harness(admin,request,extra={}){
  const storage={token:'session',roles:[admin?'admin':'parent']},notices=[]
  const wx={getStorageSync:k=>storage[k],showToast:o=>notices.push(o),showModal:o=>o.success({confirm:true,content:'核查原因'}),stopPullDownRefresh(){},...extra}
  const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/referral.js'),'utf8'),{module,require:id=>id==='./coupon-display'?require('../utils/coupon-display'):({request}),wx,console,setInterval:()=>0,clearInterval(){}})
  const definition=module.exports.page(admin),p={...definition,data:structuredClone(definition.data),_alive:true,_visible:true,_token:'session',_version:1};p.setData=u=>Object.assign(p.data,u)
  return{p,wx,storage,notices}
}
const pick=value=>({detail:{value}}),click=(id,action)=>({currentTarget:{dataset:{id,action}}})
const response={items:[{recordId:'1',parentId:'7',recipientRole:'OLD'},{recordId:'2',parentId:'7',recipientRole:'OLD'}],recipients:[{parentId:'7',parentName:'家**'}],recipientCount:1,couponCount:2,template:{templateId:'9',couponName:'推荐券'},previewHash:'preview-proof'}
function ready(p){p.data.templates=[{templateId:'9'}];p.data.templateIndex=0;p.data.reason='审核通过';p.data.rewardRoleIndex=2;p.data.preview=clone(response);p.data.selectedParents=['7'];p._parentsPicked=true;p._draftIds=['1','2'];p._batchKey='original-batch'}
function baseReply(o){return Promise.resolve(o.url.endsWith('/counts')?{data:{pending:0,anomalies:0}}:o.url.includes('/records')||o.url==='/system/referral'?{rows:[],total:0}:{data:{canBind:true}})}

test('parent confirms server-resolved referrer without supplying parent identity',async()=>{
  const calls=[];const{p}=harness(false,o=>{calls.push(clone(o));return o.url.endsWith('/preview')?Promise.resolve({data:{referrerId:'7',referrerName:'家**'}}):baseReply(o)})
  p.data.home={canBind:true};p.data.code='0123456789ABCDEF';await p.bind();const post=calls.find(o=>o.url.endsWith('/bind'));assert.deepEqual(post.data,{code:'0123456789ABCDEF'});assert.equal(p.data.code,'')
})
test('referrer confirmation after identity change cannot submit binding',async()=>{
  let modal;const calls=[];const{p,storage}=harness(false,o=>{calls.push(o);return Promise.resolve({data:{referrerId:'7',referrerName:'家**'}})},{showModal:o=>modal=o});p.data.home={canBind:true};p.data.code='0123456789ABCDEF';const work=p.bind();await new Promise(r=>setImmediate(r));storage.token='another-family';modal.success({confirm:true});await work;assert.equal(calls.length,1)
})
test('preview retains two legitimate rewards for one deduplicated parent',async()=>{
  const calls=[];const{p}=harness(true,o=>{calls.push(clone(o));return Promise.resolve({data:clone(response)})});ready(p);p._parentsPicked=false;await p.previewGrant();assert.equal(p.data.preview.recipientCount,1);assert.equal(p.data.preview.couponCount,2);assert.equal(p.data.preview.items.length,2);assert.deepEqual(calls[0].data.recordIds,['1','2'])
})
test('deselecting every parent sends explicit empty list instead of restoring everyone',async()=>{
  const calls=[];const{p}=harness(true,o=>{calls.push(clone(o));return Promise.reject(Object.assign(new Error('请选择接收人'),{applicationRejected:true}))});ready(p);p.selectParents(pick([]));assert.equal(p.data.preview.previewHash,'');await p.previewGrant();assert.deepEqual(calls[0].data.parentIds,[])
})
test('uncertain award keeps exact original request and retries same batch',async()=>{
  const calls=[];let attempts=0;const{p}=harness(true,o=>{if(o.url.endsWith('/grant')){calls.push(clone(o));return ++attempts===1?Promise.reject(new Error('timeout')):Promise.resolve({data:{total:2}})}return baseReply(o)});ready(p);await p.grant();assert.equal(p.data.uncertain,true);p.chooseTemplate(pick(1));p.chooseRewardRole(pick(0));p.inputReason(pick('changed'));assert.equal(p.data.templateIndex,0);assert.equal(p.data.rewardRoleIndex,2);assert.equal(p.data.reason,'审核通过');await p.grant();assert.deepEqual(calls[0],calls[1]);assert.equal(p.data.uncertain,false)
})
test('known rolled-back rejection unlocks form and demands another preview',async()=>{
  const{p}=harness(true,()=>Promise.reject(Object.assign(new Error('库存不足'),{applicationRejected:true})));ready(p);await p.grant();assert.equal(p.data.uncertain,false);assert.equal(p.data.preview,null);assert.equal(p._frozen,null)
})
test('cancelled confirmation cannot freeze old selection for later submission',async()=>{
  let calls=0;const{p}=harness(true,()=>{calls++;return Promise.resolve({})},{showModal:o=>o.success({confirm:false})});ready(p);await p.grant();assert.equal(calls,0);assert.equal(p._frozen,null)
})
test('a late mutation cannot clear a new account draft after onShow',async()=>{
  let complete;const {p,storage}=harness(true,o=>o.url.endsWith('/grant')?new Promise(r=>complete=r):baseReply(o));ready(p);const work=p.grant();await new Promise(r=>setImmediate(r));p.onHide();storage.token='another-admin';p.onShow();await new Promise(r=>setImmediate(r));p.setData({reason:'新账号草稿',busy:true});p._frozen={newAccount:true};complete({data:{total:2}});await work;assert.equal(p.data.reason,'新账号草稿');assert.equal(p.data.busy,true);assert.equal(p._frozen.newAccount,true)
})
test('successful award refresh cannot leave the form permanently busy',async()=>{
  const {p}=harness(true,o=>o.url.endsWith('/grant')?Promise.resolve({data:{total:2}}):baseReply(o));ready(p);await p.grant();assert.equal(p.data.busy,false)
})
test('leaving during award preserves uncertainty and avoids permanent busy state',async()=>{
  let finish;const{p}=harness(true,()=>new Promise(r=>finish=r));ready(p);const work=p.grant();await new Promise(r=>setImmediate(r));p.onHide();assert.equal(p.data.busy,false);assert.equal(p.data.uncertain,true);finish({data:{total:2}});await work;assert.ok(p._frozen);assert.equal(p.data.uncertain,true)
})
test('late administrator count cannot appear after role/session switch',async()=>{
  let finish;const{p,storage}=harness(true,()=>new Promise(r=>finish=r));const work=p.loadCounts();storage.token='teacher';storage.roles=['teacher'];finish({data:{pending:3,anomalies:4}});await work;assert.equal(p.data.pendingBadge,'');assert.equal(p.data.anomalyBadge,'')
})
test('reward action chooses reward id even when record and binding ids are present',async()=>{
  const calls=[];const{p}=harness(true,o=>{calls.push(o);return baseReply(o)});p.data.kind='rewards';p.data.rows=[{recordId:'1',bindingId:'2',rewardId:'3',couponStatus:'AVAILABLE'}];await p.act(click('3','revoke'));assert.ok(calls.some(o=>o.url==='/system/referral/rewards/3/revoke'))
})
test('large stable IDs remain strings in grant preview payload',async()=>{
  const calls=[];const{p}=harness(true,o=>{calls.push(clone(o));return Promise.resolve({data:clone(response)})});ready(p);p._draftIds=['9007199254740993'];p.data.selectedParents=['9007199254740995'];await p.previewGrant();assert.equal(calls[0].data.recordIds[0],'9007199254740993');assert.equal(calls[0].data.parentIds[0],'9007199254740995')
})
test('same recommendation can have multiple reward rows without duplicate WXML keys',async()=>{
  const{p}=harness(true,o=>o.url.endsWith('/counts')?Promise.resolve({data:{pending:0,anomalies:0}}):Promise.resolve({rows:[{rewardId:'11',recordId:'1',bindingId:'2'},{rewardId:'12',recordId:'1',bindingId:'2'}],total:2}));p.data.kind='rewards';await p.load();assert.deepEqual(clone(p.data.rows.map(r=>r.rowId)),['11','12'])
})
