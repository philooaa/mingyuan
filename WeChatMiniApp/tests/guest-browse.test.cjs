const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
const root = path.resolve(__dirname, '..')
function harness() {
 let page; const calls=[],routes=[],store=new Map()
 const wx={getStorageSync:k=>store.get(k),navigateTo:o=>routes.push(o.url),reLaunch:o=>routes.push(o.url),stopPullDownRefresh(){}}
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/browse/browse.js'),'utf8'),{Page:p=>page=p,wx,require:id=>id.includes('material-shop')?{product:x=>x}: {request:async o=>{calls.push(o);return {data:o.url.endsWith('/courses')?[{scheduleId:'1',courseClassName:'数学班',gradeName:'五年级',subjectName:'数学',campusName:'万达'}]:{rows:[{materialId:'2',title:'练习册'}],total:1}}}}})
 const p={...page,data:structuredClone(page.data),setData(v){Object.assign(this.data,v)}}
 return {p,calls,routes,store}
}
test('startup opens a public browsing page and does not force phone authorization',()=>{
 const app=JSON.parse(fs.readFileSync(path.join(root,'app.json')))
 assert.equal(app.pages[0],'pages/browse/browse')
})
test('guest can browse/filter real courses and materials before choosing login',async()=>{
 const {p,calls,routes}=harness();await p.onShow()
 assert.equal(routes.length,0);assert.equal(p.data.courses[0].courseClassName,'数学班')
 p.onKeyword({detail:{value:'不存在'}});assert.equal(p.data.visibleCourses.length,0)
 p.onKeyword({detail:{value:'数学'}});assert.equal(p.data.visibleCourses.length,1)
 await p.chooseTab({currentTarget:{dataset:{tab:'materials'}}});assert.equal(p.data.materials[0].title,'练习册')
 assert.ok(calls.every(o=>o.url.startsWith('/miniapp/public/')))
 p.login();assert.equal(routes[0],'/pages/register/register')
})
