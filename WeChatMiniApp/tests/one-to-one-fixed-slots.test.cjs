const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
const calendar = require('../utils/course-calendar')
function page(name, request, role='teacher', options={}) {
  const storage={token:'session',roles:[role]}, notices=[]
  let definition
  const account={exports:{}}
  const fakeWx={getStorageSync:key=>storage[key],showToast:p=>notices.push(p),stopPullDownRefresh(){},showModal(){},...options.wx}
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../utils/personal-account.js'),'utf8'),{module:account,require:()=>({request}),wx:fakeWx,getCurrentPages:()=>options.pages || []})
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages',name,name+'.js'),'utf8'),{
    Page:p=>{definition=p},require:id=>id.endsWith('child-context')?require('./helpers/child-context.cjs')(fakeWx):id.endsWith('/request')?{request}:id.endsWith('/personal-account')?account.exports:calendar,
    wx:{getStorageSync:key=>storage[key],getSystemInfoSync:()=>({windowWidth:375}),showToast:p=>notices.push(p),stopPullDownRefresh(){},showModal(){},...options.wx},getCurrentPages:()=>options.pages || [],console
  })
  const p={...definition,data:structuredClone(definition.data),_alive:true,_token:'session'}
  p.setData=updates=>{for(const [key,value] of Object.entries(updates)){const parts=key.split('.');let target=p.data;while(parts.length>1)target=target[parts.shift()];target[parts[0]]=value}}
  return {p,storage,notices}
}
const course=(id,start,end)=>({scheduleId:id,classMode:'1',status:'0',startDate:'2030-10-07',endDate:'2030-10-07',classPattern:'WEEKLY',periodName:'周一',startTime:start,endTime:end})
test('calendar axis contains six institution slots without midnight or late night blank rows',()=>{
  const week=calendar.buildWeek([],'2030-10-07')
  assert.equal(week.hours.length,6);assert.equal(week.hours[0].label,'08:00');assert.equal(week.hours[5].endLabel,'22:30')
  assert.ok(week.calendarHeight<1800)
})
test('course blocks and creation plus use the same slot coordinates, including cross-slot courses',()=>{
  const week=calendar.buildWeek([course(1,'13:15:00','15:15:00'),course(2,'09:00:00','11:00:00')],'2030-10-07')
  const block=week.weekDays[0].blocks.find(b=>b.scheduleId===1)
  assert.ok(block.style.startsWith(calendar.rangeStyle(week.calendarTimeline,'13:15','15:15')))
  assert.ok(block.style.startsWith('top:568rpx;height:264rpx;'))
  const cross=week.weekDays[0].blocks.find(b=>b.scheduleId===2)
  assert.ok(cross.style.startsWith('top:132rpx;height:251rpx;'))
  // 两行课名、两行教室、两行状态及时间、内边距需要242rpx。
  const height=Number(block.style.match(/height:([\d.]+)rpx/)[1])
  assert.ok(height>=242,'two-hour card must fit its information rows')
})
test('nonstandard historical course times outside the institution slots are not cropped',()=>{
  const week=calendar.buildWeek([course(1,'07:00:00','09:00:00'),course(2,'22:00:00','23:30:00')],'2030-10-07')
  assert.equal(week.hours[0].label,'07:00');assert.equal(week.hours.at(-1).endLabel,'23:30')
  for(const block of week.weekDays[0].blocks){const [,top,height]=block.style.match(/top:([\d.]+)rpx;height:([\d.]+)rpx/);assert.ok(Number(top)>=0 && Number(top)+Number(height)<=week.calendarHeight)}
})


test('scroll axis tracks fractional and reverse movements in the view layer without calling page methods',()=>{
  const module={exports:{}}, styles=[]
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../utils/course-calendar-scroll.wxs'),'utf8'),{module})
  const owner={selectComponent:selector=>{assert.equal(selector,'.time-axis');return {setStyle:style=>styles.push(style.transform)}},callMethod:()=>assert.fail('scroll must not cross to the logic layer')}
  for(const scrollTop of [0,0.25,84.75,200,199.5,20,0])module.exports.scroll({detail:{scrollTop}},owner)
  assert.deepEqual(styles,['translate3d(0,0px,0)','translate3d(0,-0.25px,0)','translate3d(0,-84.75px,0)','translate3d(0,-200px,0)','translate3d(0,-199.5px,0)','translate3d(0,-20px,0)','translate3d(0,0px,0)'])
  module.exports.reset('calendar-top-2','calendar-top-1',owner);assert.equal(styles.at(-1),'translate3d(0,0px,0)')
  assert.doesNotThrow(()=>module.exports.scroll({detail:{scrollTop:10}},{selectComponent:()=>null}))
})




test('uncertain administrator grant retains request identity for safe retry',async()=>{
  const sent=[];let attempts=0
  const {p}=page('admin-transactions',o=>{if(o.method==='POST'){sent.push(o.data);if(++attempts===1)return Promise.reject(new Error('timeout'));return Promise.resolve({})}return Promise.resolve({data:[]})},'admin')
  p.data.accounts=[{accountId:7}];p.openAdjust({currentTarget:{dataset:{id:7,action:'GRANT'}}});p.data.form.quantity='15';p.data.form.reason='线下已登记'
  await p.save();assert.equal(p.data.showForm,true);await p.save();assert.equal(sent.length,2);assert.equal(sent[0].requestKey,sent[1].requestKey);assert.equal(p.data.showForm,false)
})
