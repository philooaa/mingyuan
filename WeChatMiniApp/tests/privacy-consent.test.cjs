const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
const root=path.resolve(__dirname,'..')
function harness(){
 let definition;const calls=[],routes=[],store=new Map(),app={globalData:{baseUrl:'https://test-admin.mingyuantostars.online/prod-api'}}
 const wx={getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k),reLaunch:o=>routes.push(o.url),showToast(){},openPrivacyContract:o=>o.success&&o.success(),getDeviceInfo:()=>({platform:'ios'})}
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/register/register.js'),'utf8'),{Page:p=>definition=p,wx,getApp:()=>app,clearInterval(){},setInterval(){},require:id=>id.includes('/access')?{developmentBuild:()=>false,developmentTool:()=>false}:id.includes('dev-session')?{}:{request:async o=>{calls.push(o);return {data:{phoneLoginEnabled:true}}}}})
 const p={...definition,data:structuredClone(definition.data),setData(o){Object.assign(this.data,o)}}
 return {p,calls,routes,store}
}
test('privacy consent is unchecked on entry and opening the policy does not grant consent',async()=>{
 const {p,store}=harness();store.set('privacyAgreed',true);p.setData({privacyAgreed:true});await p.onLoad()
 assert.equal(p.data.privacyAgreed,false);p.openPrivacy();assert.equal(p.data.privacyAgreed,false)
 p.changeConsent({detail:{value:['privacy']}});assert.equal(p.data.privacyAgreed,true)
 p.changeConsent({detail:{value:[]}});assert.equal(p.data.privacyAgreed,false)
})
test('phone authorization and token requests are blocked until the user explicitly consents',async()=>{
 const {p,calls,routes}=harness();let logins=0;p.loginWithWechat=()=>logins++
 p.handlePhoneLogin({detail:{code:'phone-code'}});assert.equal(logins,0)
 p.changeConsent({detail:{value:['privacy']}});p.handlePhoneLogin({detail:{code:'phone-code'}});assert.equal(logins,1)
 p.changeConsent({detail:{value:[]}});p.handlePhoneLogin({detail:{code:'phone-code'}});assert.equal(logins,1)
 p.browse();assert.equal(routes[0],'/pages/browse/browse');assert.equal(calls.length,0)
 const real=harness();await real.p.loginWithWechat('phone-code');assert.equal(real.calls.length,0)
 const wxml=fs.readFileSync(path.join(root,'pages/register/register.wxml'),'utf8')
 assert.match(wxml,/wx:if="\{\{!localPreviewEnabled && privacyAgreed\}\}"[^>]*open-type="getPhoneNumber"/)
 assert.match(wxml,/<checkbox[^>]*checked="\{\{privacyAgreed\}\}"/)
})
