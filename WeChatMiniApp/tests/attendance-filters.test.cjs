const test = require('node:test'), assert = require('node:assert/strict')
const filters = require('../utils/attendance-filters')
const rows = [
 {scheduleId:'1',classDate:'2026-11-06',subjectName:'语文',gradeName:'一年级',teacherName:'王老师',reviewStatus:'0'},
 {scheduleId:'2',classDate:'2026-11-06',subjectName:'数学',gradeName:'一年级',teacherName:'李老师',reviewStatus:'1'},
 {scheduleId:'3',classDate:'2026-11-07',subjectName:'数学',gradeName:'二年级',teacherName:'王老师',reviewStatus:'0'}
]
test('attendance category choices deduplicate values and include all',()=>{
 const state=filters.state(rows)
 assert.deepEqual(state.filterOptions.subjectName,['全部学科','语文','数学'])
 assert.deepEqual(state.filterOptions.teacherName,['全部老师','李老师','王老师'])
})
test('attendance filters combine exact date subject grade and teacher without changing records',()=>{
 const selected={filterDate:'2026-11-06',subjectName:'数学',gradeName:'一年级',teacherName:'李老师'}
 assert.deepEqual(rows.filter(r=>filters.matches(r,selected)).map(r=>r.scheduleId),['2'])
 assert.equal(rows.filter(r=>filters.matches(r,{...selected,teacherName:'王老师'})).length,0)
 assert.equal(rows.filter(r=>filters.matches(r,{})).length,3)
 assert.equal(rows[1].reviewStatus,'1')
})

test('todo filters show matching counts and keep navigation on the filtered lesson',async()=>{
 const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');let definition;const navigations=[]
 const wx={getStorageSync:()=> 'session',navigateTo:o=>navigations.push(o.url)}
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/attendance-todos/attendance-todos.js'),'utf8'),{Page:p=>definition=p,wx,require:id=>id.endsWith('/attendance-filters')?filters:id.endsWith('/access')?{selected:()=> 'education'}:{request:async()=>({data:{items:rows}}),todoRoute:item=>item.scheduleId}})
 const p={...definition,data:structuredClone(definition.data),setData(v){Object.assign(this.data,v)}};await p.onShow();assert.equal(p.data.count,3)
 p.onAttendanceFilter({currentTarget:{dataset:{field:'subjectName'}},detail:{value:p.data.filterOptions.subjectName.indexOf('数学')}});p.onDateFilter({detail:{value:'2026-11-07'}})
 assert.equal(p.data.count,1);p.open({currentTarget:{dataset:{index:0}}});assert.deepEqual(navigations,['3'])
 p.resetAttendanceFilters();assert.equal(p.data.count,3);assert.equal(p.data.filterDate,'')
})

test('attendance subject and grade choices follow the established teaching order',()=>{
 const subjects=['生物','历史','数学','物理','语文','地理','英语','化学','政治','其他学科']
 const grades=['高三','初二','六年级','一年级','高一','初一','三年级','二年级','五年级','初三','四年级','高二','未分年级','幼小衔接']
 const options=filters.state([...subjects.map(subjectName=>({subjectName})),...grades.map(gradeName=>({gradeName}))]).filterOptions
 assert.deepEqual(options.subjectName,['全部学科','语文','数学','英语','政治','历史','地理','物理','化学','生物','其他学科'])
 assert.deepEqual(options.gradeName,['全部年级','幼小衔接','一年级','二年级','三年级','四年级','五年级','六年级','初一','初二','初三','高一','高二','高三','未分年级'])
})
