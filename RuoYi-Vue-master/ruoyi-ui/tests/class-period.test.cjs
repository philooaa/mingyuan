const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const load=()=>import('data:text/javascript;base64,'+fs.readFileSync(path.join(__dirname,'../src/utils/class-period.js')).toString('base64'))
test('weekly days and non-weekly periods remain separate, including imported periods',async()=>{
 const {classPeriodOptions}=await load()
 const days=['周一','周二','周三','周四','周五','周六','周日']
 const dims={periods:['一期','二期',...days].map(value=>({value,label:value})),periodMap:{秋季:['周六','四期']}}
 assert.deepEqual(classPeriodOptions(dims,{termName:'秋季',classPattern:'WEEKLY'}).map(x=>x.value),days)
 assert.deepEqual(classPeriodOptions(dims,{termName:'秋季',classPattern:'DAILY'}).map(x=>x.value),['一期','二期','四期'])
 assert.deepEqual(classPeriodOptions(dims,{termName:'秋季',classPattern:'OTHER'}).map(x=>x.value),['一期','二期','四期'])
})
