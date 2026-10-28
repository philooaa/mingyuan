const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const root=path.join(__dirname,'..'),plain=x=>JSON.parse(JSON.stringify(x))
function sessions(request){const store={},app={globalData:{baseUrl:'http://localhost'}},module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'utils/dev-session.js'),'utf8'),{module,require:()=>({request}),getApp:()=>app,wx:{getStorageSync:k=>store[k],setStorageSync:(k,v)=>store[k]=v,removeStorageSync:k=>delete store[k]}});return {session:module.exports,store,app}}
const info=id=>({user:{userId:id},roles:['parent']})
test('development caches separate parents and restores the last selected parent across admin role',async()=>{
 const {session,store}=sessions(o=>Promise.resolve(o.url.endsWith('/session')?{data:{version:'v1'}}:info(o.header.Authorization==='Bearer a'?'30':'31')))
 session.activate('parent','a',info('30'),'v1');session.activate('parent','b',info('31'),'v1');assert.equal(store.miniappDevSessions.sessions['parent:30'].token,'a');assert.equal(store.miniappDevSessions.sessions['parent:31'].token,'b')
 store.token='admin';assert.equal((await session.restore('parent',undefined)).restored,true);assert.equal(store.token,'b');assert.equal((await session.restore('parent','30')).restored,true);assert.equal(store.token,'a')
})
test('backend restart discards all parent identities and never restores an expired account',async()=>{
 const {session,store}=sessions(()=>Promise.resolve({data:{version:'v2'}}));session.activate('parent','a',info('30'),'v1');const r=await session.restore('parent','30');assert.equal(r.restarted,true);assert.equal(store.token,undefined);assert.deepEqual(plain(store.miniappDevSessions.sessions),{})
})
test('legacy single parent cache remains usable',async()=>{
 const {session,store}=sessions(o=>Promise.resolve(o.url.endsWith('/session')?{data:{version:'v1'}}:info('30')));store.devSessionVersion='v1';store.miniappDevSessions={baseUrl:'http://localhost',version:'v1',sessions:{parent:{token:'legacy',userId:'30'}}};assert.equal((await session.restore('parent')).restored,true);assert.equal(store.token,'legacy')
})
test('a swapped parent token cannot restore as the selected family',async()=>{
 const {session,store}=sessions(o=>Promise.resolve(o.url.endsWith('/session')?{data:{version:'v1'}}:info('999')));session.activate('parent','a',info('30'),'v1');store.token='admin';assert.equal((await session.restore('parent','30')).restored,false);assert.equal(store.token,'admin')
})
function register(request){let definition,activated;const storage={token:'old',roles:['parent'],userInfo:{userId:'30'}};const wx={getStorageSync:k=>storage[k],setStorageSync:(k,v)=>storage[k]=v,showToast(){},login:o=>o.success({code:'mock'}),reLaunch(){}};vm.runInNewContext(fs.readFileSync(path.join(root,'pages/register/register.js'),'utf8'),{Page:p=>definition=p,require:id=>id.endsWith('access')?{developmentTool:()=>true}:id.endsWith('request')?{request}:{restore:()=>Promise.resolve({restored:false}),activate:(...args)=>activated=args},wx});const p={...definition,data:{...structuredClone(definition.data),developmentLoginEnabled:true},_unloaded:false};p.setData=x=>Object.assign(p.data,x);return {p,storage,activated:()=>activated}}
test('parent selector submits stable user ID and verifies the returned identity',async()=>{
 const sent=[];const h=register(o=>{sent.push(o);return Promise.resolve(o.url==='/getInfo'?info('31'):{token:'b',devSessionVersion:'v1'})});h.p.data.devParentId='31';await h.p.loginWithWechat('mock','parent');const login=sent.find(o=>o.method==='POST');assert.equal(login.data.devParentId,'31');assert.equal(login.data.devTeacherId,undefined);assert.equal(h.activated()[1],'b')
})
test('wrong parent response cannot activate another family',async()=>{
 const h=register(o=>Promise.resolve(o.url==='/getInfo'?info('999'):{token:'forged',devSessionVersion:'v1'}));h.p.data.devParentId='31';await h.p.loginWithWechat('mock','parent');assert.equal(h.activated(),undefined);assert.equal(h.p.data.submitting,false)
})
test('login page no longer loads development identities even with a legacy switch URL',async()=>{
 const sent=[];const h=register(o=>{sent.push(o.url);return Promise.resolve({data:{phoneLoginEnabled:true,developmentCodeMode:true}})});h.p.onLoad({selectParent:'1'});await new Promise(r=>setImmediate(r));assert.deepEqual(sent,['/miniapp/auth/config']);assert.equal(h.p.data.devParentId,'');assert.equal(h.p.data.phoneLoginEnabled,true)
})
