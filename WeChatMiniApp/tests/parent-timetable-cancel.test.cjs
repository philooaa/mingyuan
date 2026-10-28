const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')

function setup({single=true,pending=false,confirm=true,requestError=null,holdModal=false}={}) {
  const storage={token:'parent-session',roles:['parent']}, calls=[],modals=[],toasts=[],otherPage={}
  let definition,modalCallback
  const row={enrollmentId:42,scheduleId:9,studentName:'学生甲',courseClassName:'化学课',enrollmentStatus:'1',cancelRequestStatus:pending?'1':'0',classDate:single?'2030-10-07':null}
  const wx={getStorageSync:key=>storage[key],showToast:o=>toasts.push(o),showLoading(){},hideLoading(){},
    showModal:o=>{modals.push(o);modalCallback=()=>o.success({confirm});if(!holdModal)modalCallback()},
    navigateTo:()=>assert.fail('cancellation must stay on timetable')}
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/timetable/timetable.js'),'utf8'),{
    Page:p=>definition=p,wx,getCurrentPages:()=>[otherPage],
    require:id=>id.endsWith('child-context')?require('./helpers/child-context.cjs')(wx):({request:async options=>{calls.push(options);if(requestError)throw requestError;return {data:[]}}})
  })
  const p={...definition,data:structuredClone(definition.data),_loadedToken:storage.token}
  p.setData=o=>Object.assign(p.data,o)
  p.data.loadSucceeded=true;p.data.timetable=[row]
  let refreshes=0
  p.loadTimetable=async()=>{refreshes++;if(single)row.cancelRequestStatus='1';p.data.timetable=p.decorateTimetable([row])}
  return {p,storage,calls,modals,toasts,otherPage,row,confirmModal:()=>modalCallback(),refreshes:()=>refreshes,
    event:{currentTarget:{dataset:{enrollmentId:42}}}}
}

test('single cancellation confirms in place and reuses cancellation request without deleting enrollment',async()=>{
  const s=setup();await s.p.handleCancel(s.event)
  assert.equal(s.modals.length,1);assert.match(s.modals[0].content,/老师同意后/)
  assert.equal(s.modals[0].editable,undefined)
  assert.equal(s.calls.length,1);assert.equal(s.calls[0].method,'POST')
  assert.equal(s.calls[0].url,'/miniapp/parent/enrollment/42/cancel-request')
  assert.ok(s.calls[0].data.reason.trim());assert.equal(s.refreshes(),1)
  assert.equal(s.p.data.timetable.length,1);assert.equal(s.p.data.timetable[0].cancelRequestPending,true)
  assert.equal(s.otherPage._scheduleDirty,true);assert.equal(s.p.data.cancelingId,'')
})
test('class cancellation retains direct cancellation and original confirmation',async()=>{
  const s=setup({single:false});await s.p.handleCancel(s.event)
  assert.match(s.modals[0].content,/立即从课表中移除/)
  assert.equal(s.calls[0].method,'DELETE');assert.equal(s.calls[0].url,'/miniapp/parent/enrollment/42')
  assert.equal(s.refreshes(),1)
})
test('dismissing confirmation sends no cancellation',async()=>{
  const s=setup({confirm:false});await s.p.handleCancel(s.event)
  assert.equal(s.calls.length,0);assert.equal(s.refreshes(),0);assert.equal(s.p.data.cancelingId,'')
})
test('pending cancellation does not open another confirmation or resend',async()=>{
  const s=setup({pending:true});await s.p.handleCancel(s.event)
  assert.equal(s.calls.length,0);assert.equal(s.modals.length,0)
})
test('repeated taps while confirmation is open produce only one request',async()=>{
  const s=setup({holdModal:true}),running=s.p.handleCancel(s.event)
  await s.p.handleCancel(s.event);assert.equal(s.modals.length,1)
  s.confirmModal();await running;assert.equal(s.calls.length,1)
})
test('changing parent or leaving page during confirmation cannot cancel with the new session',async()=>{
  for(const leave of [false,true]) {
    const s=setup({holdModal:true}),running=s.p.handleCancel(s.event)
    if(leave)s.p.onUnload();else s.storage.token='other-parent-session'
    s.confirmModal();await running;assert.equal(s.calls.length,0)
  }
})
test('request failure preserves the course and allows retry',async()=>{
  const s=setup({requestError:new Error('课程已开始，不能申请取消')});await s.p.handleCancel(s.event)
  assert.equal(s.p.data.timetable[0].cancelRequestStatus,'0');assert.equal(s.refreshes(),0)
  assert.match(s.toasts.at(-1).title,/不能申请取消/);assert.equal(s.p._cancelBusy,false)
})
test('teacher, unloaded data and unlisted enrollment cannot send cancellation',async()=>{
  for(const condition of ['teacher','loading','missing']) {
    const s=setup()
    if(condition==='teacher')s.p.data.teacherMode=true
    if(condition==='loading')s.p.data.loading=true
    if(condition==='missing')s.p.data.timetable=[]
    await s.p.handleCancel(s.event);assert.equal(s.calls.length,0);assert.equal(s.modals.length,0)
  }
})
