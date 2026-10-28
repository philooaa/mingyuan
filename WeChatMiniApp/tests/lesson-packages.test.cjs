const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const root=path.resolve(__dirname,'..'),file=path.join(root,'pages/lesson-packages/lesson-packages.js')
let definition,storage,app,calls,routes,handler
global.getApp=()=>app
global.Page=d=>definition=d
function ok(o,data){o.success({statusCode:200,data:{code:200,data}})}
const quote={accountId:'22',studentName:'乙',gradeName:'初二',packageCount:1,credits:15,unitPrice:'500.00',grossAmount:'7500.00',packageDiscount:'375.00',subtotal:'7125.00',couponDiscount:'0.00',payableAmount:'7125.00',coupons:[],quoteHash:'one'}
function env(){
 storage=new Map([['token','jwt'],['roles',['parent']],['userInfo',{userId:'7'}]])
 app={globalData:{baseUrl:'http://local',roles:['parent'],userInfo:{userId:'7'}}};calls=[];routes=[]
 global.wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,structuredClone(v)),removeStorageSync:k=>storage.delete(k),showToast(){},reLaunch(){},redirectTo:o=>routes.push(o.url),navigateTo:o=>routes.push(o.url),stopPullDownRefresh(){},request:o=>{calls.push(o);handler(o)}}
 for(const name of ['request','tuition','child-context'])delete require.cache[require.resolve('../utils/'+name)]
 require('../utils/child-context').select({accountId:'22'})
 handler=o=>ok(o,o.url.endsWith('/catalog')?{prices:[],discounts:[],creditsPerPackage:15}:o.url.includes('/children/')?{child:{accountId:'22',studentName:'乙',gradeName:'初二'}}:quote)
}
function page(){assert.ok(fs.existsSync(file),'purchase page is implemented');delete require.cache[require.resolve(file)];require(file);const p={...definition,data:structuredClone(definition.data),setData(v){Object.assign(this.data,v)}};p.onLoad({accountId:'22'});return p}
const tick=()=>new Promise(r=>setImmediate(r))
test('quantity stepper enforces bounds, refreshes server quote and freezes unknown purchases',async()=>{
 env();const p=page();await p.onShow();handler=o=>ok(o,{...quote,packageCount:o.data.packageCount,credits:o.data.packageCount*15})
 await p.adjustCount({currentTarget:{dataset:{delta:-1}}});assert.equal(Number(p.data.packageCount),1)
 await p.adjustCount({currentTarget:{dataset:{delta:1}}});assert.equal(Number(p.data.packageCount),2);assert.equal(p.data.quote.credits,30)
 p.setData({packageCount:100});await p.adjustCount({currentTarget:{dataset:{delta:1}}});assert.equal(Number(p.data.packageCount),100)
 p.setData({retrying:true});await p.adjustCount({currentTarget:{dataset:{delta:-1}}});assert.equal(Number(p.data.packageCount),100)
})
test('purchase quote drops superseded responses and invalid counts cannot buy',async()=>{
 env();const p=page();await p.onShow();const waiting=[];handler=o=>waiting.push(o)
 const a=p.chooseCount({currentTarget:{dataset:{count:2}}});const b=p.chooseCount({currentTarget:{dataset:{count:3}}})
 assert.equal(p.data.quote,null);await p.buy();assert.equal(calls.some(o=>o.url.endsWith('/orders')),false)
 ok(waiting[1],{...quote,packageCount:3,quoteHash:'three'});await b;ok(waiting[0],{...quote,packageCount:2,quoteHash:'two'});await a;assert.equal(p.data.quote.quoteHash,'three')
 await p.inputCount({detail:{value:'1.5'}});await p.buy();assert.equal(p.data.quote,null);assert.equal(calls.some(o=>o.url.endsWith('/orders')),false)
})
test('unknown create freezes parameters and key across duplicate clicks and reopened page',async()=>{
 env();const p=page();await p.onShow();let pending
 handler=o=>{pending=o};const first=p.buy();await tick();await p.buy();assert.equal(calls.filter(o=>o.url.endsWith('/orders')).length,1)
 const original=structuredClone(pending.data);pending.fail({errMsg:'timeout'});await first;assert.equal(p.data.retrying,true)
 await p.chooseCount({currentTarget:{dataset:{count:3}}});assert.equal(p.data.packageCount,1)
 const retry=page();handler=o=>ok(o,o.url.endsWith('/catalog')?{prices:[],discounts:[]}:o.url.includes('/children/')?{child:{accountId:'22'}}:quote);await retry.onShow();handler=o=>{assert.deepEqual(o.data,original);ok(o,{enrollmentId:'99'})};await retry.buy()
 assert.equal(retry.data.retrying,false);assert.match(routes.at(-1),/tuition-detail\?id=99&accountId=22/)
})
test('hidden, switched child and changed session responses never display or navigate',async()=>{
 for(const change of [p=>p.onHide(),()=>require('../utils/child-context').select({accountId:'33'}),()=>storage.set('token','other')]){
  env();const p=page();await p.onShow();let pending;handler=o=>pending=o;const work=p.chooseCount({currentTarget:{dataset:{count:2}}});change(p);ok(pending,{...quote,packageCount:2});await work;assert.equal(p.data.quote,null);await p.buy();assert.equal(calls.some(o=>o.url.endsWith('/orders')),false)
 }
})
test('quote failure disables purchase and malformed order success preserves original intent',async()=>{
 env();const p=page();await p.onShow();handler=o=>o.fail({errMsg:'offline'});await p.refreshQuote();await p.buy();assert.equal(calls.some(o=>o.url.endsWith('/orders')),false)
 handler=o=>ok(o,quote);await p.refreshQuote();handler=o=>ok(o,{});await p.buy();assert.equal(p.data.retrying,true);assert.equal(routes.length,0)
})
test('returning while create is pending releases busy state and does not navigate from old visit',async()=>{
 env();const p=page();await p.onShow();let pending;const normal=handler;handler=o=>o.url.endsWith('/orders')?(pending=o):normal(o)
 const work=p.buy();await tick();p.onHide();await p.onShow();assert.equal(p.data.submitting,true);ok(pending,{enrollmentId:'99'});await work;await tick();assert.equal(p.data.submitting,false);assert.equal(p.data.retrying,false);assert.equal(routes.length,0)
})
test('superseded quote cannot revive purchase after child refresh fails',async()=>{
 env();const p=page();await p.onShow();let pending;handler=o=>pending=o;const work=p.refreshQuote();handler=o=>o.fail({errMsg:'offline'});await p.load();ok(pending,quote);await work;await p.buy();assert.equal(p.data.quote,null);assert.equal(calls.some(o=>o.url.endsWith('/orders')),false)
})
test('coupon changes invalidate the quote and an uncertain conflicting retry keeps original coupon',async()=>{
 env();handler=o=>ok(o,o.url.endsWith('/catalog')?{prices:[],discounts:[]}:o.url.includes('/children/')?{child:{accountId:'22'}}:{...quote,coupons:[{userCouponId:'88',couponName:'通用券',discountAmount:'100.00',couponStatus:'AVAILABLE'}]})
 const p=page();await p.onShow();let pending;handler=o=>pending=o;const work=p.chooseCoupon({detail:{value:'1'}});assert.equal(p.data.quote,null);assert.equal(pending.data.userCouponId,'88');ok(pending,{...quote,couponDiscount:'100.00',payableAmount:'7025.00',quoteHash:'coupon',coupons:[{userCouponId:'88',couponName:'通用券',discountAmount:'100.00'}]});await work
 handler=o=>o.fail({errMsg:'timeout'});await p.buy();const original=structuredClone(calls.at(-1).data);await p.chooseCoupon({detail:{value:'0'}});assert.equal(p.data.userCouponId,'88')
 handler=o=>o.success({statusCode:200,data:{code:409,msg:'报价已变化，请联系教务核对'}});await p.buy();assert.equal(p.data.retrying,true);assert.deepEqual(calls.at(-1).data,original)
})
