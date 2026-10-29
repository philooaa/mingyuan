const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
const root = path.resolve(__dirname, '..')
function harness(getCourses = () => [{scheduleId:'1',courseClassName:'数学班',gradeName:'五年级',subjectName:'数学',campusName:'万达'}]) {
 let page; const calls=[],routes=[],store=new Map()
 const wx={getStorageSync:k=>store.get(k),navigateTo:o=>routes.push(o.url),reLaunch:o=>routes.push(o.url),stopPullDownRefresh(){}}
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/browse/browse.js'),'utf8'),{Page:p=>page=p,wx,require:id=>id.includes('course-term-filter')?require('../utils/course-term-filter'):id.includes('grade-order')?require('../utils/grade-order'):id.includes('material-shop')?{product:x=>x}: {request:async o=>{calls.push(o);return {data:o.url.endsWith('/courses')?getCourses():{rows:[{materialId:'2',title:'练习册'}],total:1}}}}})
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

test('guest grade choices follow school order regardless of server order and duplicates',async()=>{
 const grades=['高三','初二','六年级','一年级','高一','初一','三年级','二年级','五年级','初三','四年级','高二','五年级','未分年级','其他班','幼小衔接']
 const {p,calls}=harness(()=>grades.map((gradeName,i)=>({scheduleId:String(i),gradeName,subjectName:'数学'})))
 await p.onShow()
 assert.deepEqual(Array.from(p.data.gradeOptions),['全部年级','幼小衔接','一年级','二年级','三年级','四年级','五年级','六年级','初一','初二','初三','高一','高二','高三','其他班','未分年级'])
 p.chooseGrade({detail:{value:p.data.gradeOptions.indexOf('五年级')}})
 assert.equal(p.data.visibleCourses.length,2)
 assert.ok(p.data.visibleCourses.every(c=>c.gradeName==='五年级'))
 assert.ok(calls.every(o=>o.url.startsWith('/miniapp/public/')))
})

test('refresh retains the chosen grade by name and resets a removed grade to all',async()=>{
 let courses=[{scheduleId:'1',gradeName:'高一',subjectName:'数学'},{scheduleId:'2',gradeName:'初一',subjectName:'语文'}]
 const {p}=harness(()=>courses);await p.onShow()
 p.chooseGrade({detail:{value:p.data.gradeOptions.indexOf('高一')}})
 courses=[{scheduleId:'3',gradeName:'一年级',subjectName:'英语'},...courses].reverse()
 await p.load();assert.equal(p.data.gradeOptions[p.data.gradeIndex],'高一');assert.equal(p.data.visibleCourses[0].scheduleId,'1')
 courses=courses.filter(c=>c.gradeName!=='高一');await p.load()
 assert.equal(p.data.gradeIndex,0);assert.equal(p.data.visibleCourses.length,2)
})

test('shared grade ordering supports existing aliases without changing grade labels',()=>{
 const {compareGrades}=require('../utils/grade-order')
 assert.deepEqual(['高中三年级','8年级','小学二年级','小一','七年级','高二'].sort(compareGrades),['小一','小学二年级','七年级','8年级','高二','高中三年级'])
})

test('guest term selector has six seasons plus all and combines with grade, subject and search',async()=>{
 const periods=[['春季','周六'],['秋季','周日'],['寒假','一期'],['寒假','二期'],['暑假','一期'],['暑假','二期'],['暑假','三期'],['测试学期','测试期']]
 const courses=periods.map(([termName,periodName],i)=>({scheduleId:String(i),termName,periodName,gradeName:'高二',subjectName:'物理',courseClassName:'物理班'}))
 courses.push({...courses[2],scheduleId:'other-grade',gradeName:'高一'},{...courses[2],scheduleId:'other-subject',subjectName:'数学'})
 const {p,calls}=harness(()=>courses);await p.onShow()
 assert.deepEqual(Array.from(p.data.termOptions),['全部时段','春季','秋季','寒假一期','寒假二期','暑假一期','暑假二期'])
 p.chooseGrade({detail:{value:p.data.gradeOptions.indexOf('高二')}});p.chooseSubject({detail:{value:p.data.subjectOptions.indexOf('物理')}})
 for(let index=1;index<=6;index++){p.chooseTerm({detail:{value:String(index)}});assert.deepEqual(Array.from(p.data.visibleCourses,c=>c.scheduleId),[String(index-1)])}
 p.chooseTerm({detail:{value:3}});await p.load();assert.equal(p.data.termIndex,3);assert.equal(p.data.visibleCourses.length,1)
 p.onKeyword({detail:{value:'不存在'}});assert.equal(p.data.visibleCourses.length,0)
 p.onKeyword({detail:{value:''}});p.chooseTerm({detail:{value:0}});assert.equal(p.data.visibleCourses.length,8)
 assert.ok(calls.every(o=>o.url.startsWith('/miniapp/public/')))
})
