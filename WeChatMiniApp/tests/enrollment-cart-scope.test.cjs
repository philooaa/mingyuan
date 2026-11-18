const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const root=path.join(__dirname,'..')
function setup(handler) {
 const store={token:'a',roles:['parent'],userInfo:{userId:'101'},enrollCart:[{scheduleId:'old'}]},app={globalData:{baseUrl:'https://local',userInfo:null}}, calls=[]
 const wx={getStorageSync:k=>store[k],setStorageSync:(k,v)=>store[k]=structuredClone(v),showToast(){}}
 const context=require('./helpers/child-context.cjs')(wx,()=>app)
 let def
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/enrollment/enrollment.js'),'utf8'),{Page:p=>def=p,wx,getApp:()=>app,require:id=>id.includes('child-context')?context:id.includes('course-term-filter')?require('../utils/course-term-filter'):id.includes('personal-account')?{requestKey:()=> 'key'}:{request:async o=>{calls.push(o);return handler?handler(o):{data:{}}}}})
 function page(child) {
  context.select({accountId:child})
  const p={...def,data:structuredClone(def.data),_token:store.token,_unloaded:false}
  p.setData=v=>Object.assign(p.data,v);p.data.scopeAccountId=String(child);p.data.selectedChildId=String(child);p.data.lessonAccounts=[{accountId:String(child),studentName:'孩子'}];p.data.newChild=false;p.data.form={studentName:'孩子',contactPhone:''};p.refreshCartSummary=()=>{};p.loadPage=async()=>{}
  p.loadCart();return p
 }
 return {store,app,context,page,calls}
}
test('carts are isolated by parent, backend and child and legacy cart is not imported',()=>{
 const h=setup(),a=h.page('10');assert.equal(a.data.cart.length,0);a.data.cart=[{scheduleId:'1'}];a.saveCart()
 const b=h.page('11');assert.equal(b.data.cart.length,0);b.data.cart=[{scheduleId:'2'}];b.saveCart()
 assert.equal(h.page('10').data.cart[0].scheduleId,'1')
 h.store.userInfo={userId:'102'};h.store.token='b';assert.equal(h.page('10').data.cart.length,0)
 h.store.userInfo={userId:'101'};h.store.token='a';h.app.globalData.baseUrl='https://other';assert.equal(h.page('10').data.cart.length,0)
 assert.equal(h.store.enrollCart[0].scheduleId,'old')
})
test('stale child or parent cannot submit or overwrite another cart',async()=>{
 const h=setup(),a=h.page('10');a.data.cart=[{scheduleId:'1'}];a.saveCart()
 h.page('11');a.data.cart=[];a.saveCart();await a.submitCart();assert.equal(h.calls.length,0)
 assert.equal(h.page('10').data.cart[0].scheduleId,'1')
 h.store.token='new';await a.submitCart();assert.equal(h.calls.length,0)
})
test('successful order clears only the current child cart',async()=>{
 const h=setup(),a=h.page('10');a.data.cart=[{scheduleId:'1'}];a.saveCart()
 const b=h.page('11');b.data.cart=[{scheduleId:'2'}];b.saveCart();await b.submitCart()
 assert.equal(h.calls[0].data.lessonAccountId,'11');assert.equal(h.page('11').data.cart.length,0);assert.equal(h.page('10').data.cart[0].scheduleId,'1')
})
test('changing child during an order response leaves the other cart untouched',async()=>{
 let finish;const h=setup(()=>new Promise(resolve=>{finish=resolve})),a=h.page('10');a.data.cart=[{scheduleId:'1'}];a.saveCart()
 const pending=a.submitCart(),b=h.page('11');b.data.cart=[{scheduleId:'2'}];b.saveCart();finish({data:{}});await pending
 assert.equal(h.page('11').data.cart[0].scheduleId,'2');assert.equal(h.page('10').data.cart[0].scheduleId,'1')
})
