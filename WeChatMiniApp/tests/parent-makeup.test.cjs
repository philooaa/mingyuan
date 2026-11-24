const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function harness(request){
 let def,child='101',token='parent';const notices=[]
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../components/parent-makeup/parent-makeup.js'),'utf8'),{Component:d=>def=d,wx:{getStorageSync:()=>token},require:p=>p.endsWith('child-context')?{snapshot:()=>({id:child,token}),current:t=>t.id===child&&t.token===token}:{request}})
 const c={...def.methods,data:{...structuredClone(def.data),accountId:'101',mode:'attendance'},setData(x){Object.assign(this.data,x)}}
 def.lifetimes.attached.call(c)
 return {c,def,switchChild:x=>child=x,logout:()=>token='other'}
}
test('parent makeup loads bound account and keeps progress separate from financial enrollment',async()=>{
 const calls=[],{c}=harness(async q=>{calls.push(q);return {data:{rows:[{rowKey:'1',state:'pending',stateText:'待安排'},{rowKey:'2',state:'scheduled',makeup:{classDate:'2026-11-25',startTime:'15:30',endTime:'17:30'}}],pendingCount:2}}})
 await c.refresh();assert.equal(calls.at(-1).data.accountId,'101');assert.equal(c.data.rows.length,2)
 c.data.mode='timetable';await c.refresh();assert.equal(c.data.rows.length,1);assert.equal(c.data.rows[0].state,'scheduled')
 assert.ok(calls.every(q=>q.url==='/miniapp/parent/attendance/makeup'&&!q.method))
})
test('child switch, logout, detached component and older responses cannot reveal stale records',async()=>{
 const pending=[],h=harness(()=>new Promise(resolve=>pending.push(resolve)));h.switchChild('102');pending[0]({data:{rows:[{rowKey:'old'}]}});await new Promise(r=>setImmediate(r));assert.equal(h.c.data.rows.length,0)
 h.c.data.accountId='102';const first=h.c.refresh(),second=h.c.refresh();pending[2]({data:{rows:[{rowKey:'new'}]}});await second;pending[1]({data:{rows:[{rowKey:'older'}]}});await first;assert.equal(h.c.data.rows[0].rowKey,'new')
 const work=h.c.refresh();h.def.lifetimes.detached.call(h.c);pending[3]({data:{rows:[{rowKey:'detached'}]}});await work;assert.equal(h.c.data.rows.length,0)
})

test('logout prevents a pending makeup response from populating the old family',async()=>{
 let finish;const h=harness(()=>new Promise(r=>finish=r));h.logout();finish({data:{rows:[{rowKey:'private'}]}});await new Promise(r=>setImmediate(r));assert.equal(h.c.data.rows.length,0)
})

test('compact progress labels keep history collapsed until tapped and reject stale-child expansion',async()=>{
 const h=harness(async()=>({data:{rows:[{rowKey:'1',state:'scheduled',stateText:'补课出勤确认中',history:[{rowKey:'p1'}]},{rowKey:'2',state:'failed',stateText:'补课未完成 · 待重新安排'},{rowKey:'3',state:'pending',stateText:'已请假 · 待重新安排'}]}}))
 await h.c.refresh();assert.equal(h.c.data.rows[0].statusLabel,'确认中');assert.equal(h.c.data.rows[1].statusLabel,'待重排');assert.equal(h.c.data.rows[2].statusLabel,'待重排')
 assert.equal(h.c.data.rows[0].historyExpanded,false)
 h.c.toggleHistory({currentTarget:{dataset:{key:'1'}}});assert.equal(h.c.data.rows[0].historyExpanded,true)
 h.switchChild('102');h.c.toggleHistory({currentTarget:{dataset:{key:'1'}}});assert.equal(h.c.data.rows[0].historyExpanded,true)
})
