const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function setup(confirm=true,roles=['education']) {
 const source=fs.readFileSync(path.join(__dirname,'../src/components/CourseLessonCountEditor/index.vue'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import\s*\{[\s\S]*?\}\s*from\s*'[^']+'/g,'').replace('export default','module.exports=')
 const calls=[],messages=[],context={module:{exports:{}},updateCourseLessonCount:async(id,data)=>calls.push({id,data})};vm.createContext(context);vm.runInContext(source,context)
 const def=context.module.exports,p={...def.methods,data:null,$store:{getters:{roles}},$modal:{msgError:m=>messages.push(m),msgSuccess(){}},$emit(){},$confirm:async()=>{if(!confirm)throw 'cancel'}}
 Object.assign(p,def.data());p.canEditLessonCount=def.computed.canEditLessonCount.call(p)
 p.countOpen=true;p.countCourse={scheduleId:'1',systemLessonCount:14,lessonCountVersion:'fresh'};p.countMode='MANUAL';p.countValue=12;p.countReason='减免两讲';p.handlePreview=async()=>{}
 return {p,calls,messages}
}
test('mismatch cancellation does not send an update',async()=>{const {p,calls}=setup(false);await p.saveLessonCount();assert.equal(calls.length,0);assert.equal(p.countOpen,true);assert.equal(p.countSaving,false)})
test('confirmed mismatch sends reason and current server version',async()=>{const {p,calls}=setup();await p.saveLessonCount();assert.equal(calls.length,1);assert.equal(calls[0].data.confirmMismatch,true);assert.equal(calls[0].data.lessonCount,12);assert.equal(calls[0].data.version,'fresh');assert.equal(p.countOpen,false)})
test('reason and positive integer are required before confirmation',async()=>{const {p,calls}=setup();p.countReason=' ';await p.saveLessonCount();assert.equal(calls.length,0);p.countValue=1.5;await p.saveLessonCount();assert.equal(calls.length,0)})
test('reset automatic uses the calculated count and teachers cannot mutate',async()=>{const {p,calls}=setup();p.countMode='AUTO';await p.saveLessonCount();assert.equal(calls[0].data.mode,'AUTO');assert.equal(calls[0].data.confirmMismatch,false);const denied=setup(true,['teacher']);await denied.p.saveLessonCount();assert.equal(denied.calls.length,0)})
test('schedule entry waits for the editor ref before opening the selected course',async()=>{
 const source=fs.readFileSync(path.join(__dirname,'../src/views/system/schedule/index.vue'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'').replace('export default','module.exports=')
 const context={module:{exports:{}},OneToOneManager:{},CourseLessonCountEditor:{}};vm.createContext(context);vm.runInContext(source,context)
 const opened=[],p={form:{scheduleId:12},$refs:{},$modal:{msgError:m=>assert.fail(m)}}
 p.$nextTick=async()=>{p.$refs.lessonCounts={openLessonCount:async row=>opened.push(row)}}
 await context.module.exports.methods.openLessonCountEditor.call(p)
 assert.equal(opened.length,1);assert.equal(opened[0],p.form)
})
