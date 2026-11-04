const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function load(confirm=true){
 let definition;const calls=[],toasts=[]
 const wx={getStorageSync:k=>k==='roles'?['education']:null,showToast:o=>toasts.push(o),showModal:o=>o.success({confirm})}
 const api={root:'/miniapp/admin/enrollment',guard:()=>true,decorateEnrollment:e=>e,decorateSchedule:e=>e,label:v=>v,
 request:async o=>{calls.push(o);return {data:{rows:[{scheduleId:'20',courseClassName:'目标班',teacherName:'李老师',timeSlot:'8:30-10:00'}],total:1,history:[]}}}}
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/admin-enrollment-detail/admin-enrollment-detail.js'),'utf8'),{Page:p=>definition=p,wx,require:()=>api})
 const p={...definition,data:structuredClone(definition.data)};p.setData=x=>Object.assign(p.data,x);p.data.id='123';p.data.enrollment={scheduleId:'10',studentName:'学生甲',payStatus:'已支付'};p.load=async()=>{};
 return {p,calls,toasts}
}
test('已缴费报名选择目标班并确认后发送独立换课请求',async()=>{
 const {p,calls}=load();await p.openTransfer();assert.equal(p.data.transferRows.length,1)
 p.chooseTransfer({currentTarget:{dataset:{id:'20'}}});p.transferReason({detail:{value:'家长申请换班'}})
 await p.submitTransfer();const put=calls.find(c=>c.method==='PUT');assert.ok(put);assert.equal(put.url,'/miniapp/admin/enrollment/123/transfer')
 assert.deepEqual(JSON.parse(JSON.stringify(put.data)),{fromScheduleId:'10',targetScheduleId:'20',reason:'家长申请换班'})
 assert.equal(p.data.transferOpen,false)
})
test('未选课程、未填原因或者取消确认均不换课',async()=>{
 const {p,calls}=load(false);await p.openTransfer();await p.submitTransfer();assert.equal(calls.filter(c=>c.method==='PUT').length,0)
 p.chooseTransfer({currentTarget:{dataset:{id:'20'}}});p.transferReason({detail:{value:'换班'}});await p.submitTransfer()
 assert.equal(calls.filter(c=>c.method==='PUT').length,0)
})
