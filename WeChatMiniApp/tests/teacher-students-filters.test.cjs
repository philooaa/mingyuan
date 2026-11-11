const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path'),{createRequire}=require('node:module')
const file=path.join(__dirname,'../pages/teacher-students/teacher-students.js')
const rows=[{scheduleId:1,gradeName:'高一',campusName:'万达',termName:'寒假',courseYear:2027,status:'0'},
 {scheduleId:2,gradeName:'初一',campusName:'万达',termName:'春季',courseYear:2027,status:'0'},
 {scheduleId:3,gradeName:'初一',campusName:'百家湖',termName:'寒假',courseYear:2026,status:'1'},
 {scheduleId:4,gradeName:'一年级',campusName:'万达',termName:'寒假',courseYear:2027,status:'0'}]
function setup() {
 let definition;const requests=[],local=createRequire(file)
 const request=async o=>{requests.push(o);return o.url.endsWith('/schedules')?{data:rows}:o.url.includes('/schedule/')?{data:rows.find(r=>String(r.scheduleId)===o.url.split('/').pop())}:{rows:[],total:0}}
 const adminFile=path.join(__dirname,'../utils/admin-enrollment.js'),adminRequire=createRequire(adminFile),admin={module:{exports:{}},require:id=>id==='./request'?{request}:adminRequire(id)};vm.runInNewContext(fs.readFileSync(adminFile,'utf8'),admin)
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:p=>definition=p,wx:{getStorageSync:k=>k==='token'?'teacher-token':['teacher'],navigateTo(){},stopPullDownRefresh(){}},require:id=>id.endsWith('/admin-enrollment')?admin.module.exports:local(id)})
 const p={...definition,data:structuredClone(definition.data)};p.setData=c=>Object.assign(p.data,c);p.onLoad({});return {p,requests}
}
const pick=(p,key,value)=>{const f=p.data.filterFields.find(f=>f.key===key);return p.changeFilter({currentTarget:{dataset:{key}},detail:{value:f.options.findIndex(o=>o.value===value)}})}
test('normal courses are the default and grade options follow school order',async()=>{const {p}=setup();await p.load(true);assert.deepEqual(Array.from(p.data.courses,c=>c.scheduleId),[1,2,4]);assert.deepEqual(Array.from(p.data.filterFields.find(f=>f.key==='gradeName').options,o=>o.value),['','一年级','初一','高一'])})
test('filters combine, all and disabled states stay selectable, and reset restores normal courses',async()=>{const {p}=setup();await p.load(true);pick(p,'courseYear','2027');pick(p,'termName','寒假');pick(p,'campusName','万达');pick(p,'gradeName','高一');assert.deepEqual(Array.from(p.data.courses,c=>c.scheduleId),[1]);p.resetFilters();pick(p,'status','1');assert.deepEqual(Array.from(p.data.courses,c=>c.scheduleId),[3]);pick(p,'status','');assert.equal(p.data.courses.length,4)})
test('student search and paging carry the selected course filters',async()=>{const {p,requests}=setup();await p.load(true);pick(p,'gradeName','初一');pick(p,'campusName','万达');p.inputKeyword({detail:{value:'学生甲'}});await p.search();await p.load(false);const searches=requests.filter(r=>r.url.endsWith('/students'));assert.equal(searches.length,2);for(const r of searches){assert.equal(r.data.gradeName,'初一');assert.equal(r.data.campusName,'万达');assert.equal(r.data.courseStatus,'0');assert.equal(r.data.keyword,'学生甲')}assert.equal(searches[1].data.pageNum,2)})
test('opening a specific disabled course does not apply the overview default to its students',async()=>{const {p,requests}=setup();p.onLoad({scheduleId:'3'});await p.load(true);const r=requests.find(r=>r.url.endsWith('/students'));assert.equal(r.data.scheduleId,'3');assert.equal(r.data.courseStatus,undefined)})
