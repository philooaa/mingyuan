const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm')
test('one-to-one date keys display a simple label while keeping the original filter value',async()=>{
 const response={data:{terms:[{label:'春季',value:'春季'},{label:'一对一2026-10-30',value:'一对一2026-10-30',extra:'Y'},{label:'未识别学期',value:'未识别学期',extra:'Y'}]}}
 const source=fs.readFileSync(path.join(__dirname,'../src/api/system/timetable.js'),'utf8').replace("import request from '@/utils/request'",'').replace(/export function /g,'function ')
 const context={request:async()=>structuredClone(response)};vm.createContext(context);vm.runInContext(source,context)
 const result=await context.getTimetableDimensions()
 assert.equal(result.data.terms[1].label,'一对一');assert.equal(result.data.terms[1].value,'一对一2026-10-30')
 assert.ok(!result.data.terms[1].extra);assert.equal(result.data.terms[0].label,'春季');assert.equal(result.data.terms[2].extra,'Y')
})
