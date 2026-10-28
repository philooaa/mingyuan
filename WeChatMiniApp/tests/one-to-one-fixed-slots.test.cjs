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
const cells=[{date:'2030-10-07',timeSlot:'8:00-10:00',startTime:'08:00',endTime:'10:00',canPublish:true,classrooms:[{classroomId:11,campusName:'校区',classroomName:'A'}]},{date:'2030-10-07',timeSlot:'10:15-12:15',canPublish:false,classrooms:[]}]
const fixture=o=>Promise.resolve({data:o.url.endsWith('/availability')?{cells}:o.url.endsWith('/profile')?{teacherSubject:'化学'}:o.url.endsWith('/dict')?{grades:[{dictLabel:'高一'}]}:[]})
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
test('new course request omits excludeId entirely, including before WeChat GET serialization',async()=>{
  let query
  const {p}=page('teacher-schedule-create',o=>{if(o.url.endsWith('/availability'))query=o.data;return fixture(o)})
  p.data.weekStart='2030-10-07';await p.load()
  assert.equal(Object.hasOwn(query,'excludeId'),false)
  assert.equal(new URLSearchParams(query).toString(),'date=2030-10-07')
  assert.equal(p.data.subject,'化学')
})
test('changing calendar geometry targets a new top anchor while refreshes preserve the viewport',async()=>{
  const {p}=page('teacher-schedule-create',fixture)
  p.data.weekStart='2030-10-07'
  await p.load();const anchor=p.data.calendarTopAnchor
  assert.match(anchor,/^calendar-top-\d+$/);assert.equal(p.data.hours[0].label,'08:00')
  await p.load();assert.equal(p.data.calendarTopAnchor,anchor)
  p._axisKey='old full day geometry';await p.load();assert.notEqual(p.data.calendarTopAnchor,anchor)
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
test('reselect request sends only the existing course ID as exclusion',async()=>{
  let query
  const {p}=page('teacher-schedule-create',o=>{
    if(o.url.endsWith('/availability'))query=o.data
    return o.url.endsWith('/schedules')?Promise.resolve({data:[{scheduleId:12,classMode:'2'}]}):fixture(o)
  })
  p._id='12';p.data.weekStart='2030-10-07';await p.load()
  assert.equal(query.excludeId,'12');assert.equal(p.data.loadSucceeded,true)
})
test('creation grid exposes only server approved slots and sends fixed slot and real classroom',async()=>{
  let sent
  const {p}=page('teacher-schedule-create',o=>{if(o.method==='POST'){sent=o.data;return Promise.resolve({})}return fixture(o)})
  p.data.weekStart='2030-10-07';await p.load()
  assert.equal(p.data.weekDays[0].publishSlots.length,1)
  p.selectSlot({currentTarget:{dataset:{date:cells[1].date,slot:cells[1].timeSlot}}});assert.equal(p.data.showSheet,false)
  p.selectSlot({currentTarget:{dataset:{date:cells[0].date,slot:cells[0].timeSlot}}});p.data.courseClassName='化学答疑';await p.save()
  assert.deepEqual(JSON.parse(JSON.stringify(sent)),{courseClassName:'化学答疑',gradeName:'高一',classroomId:11,startDate:'2030-10-07',timeSlot:'8:00-10:00'})
})
test('availability failure hides grid instead of presenting false free time',async()=>{
  const {p}=page('teacher-schedule-create',o=>o.url.endsWith('/availability')?Promise.reject(new Error('offline')):fixture(o))
  p.data.weekStart='2030-10-07';await p.load();assert.equal(p.data.loadSucceeded,false);assert.equal(p.data.weekDays.length,0);assert.match(p.data.loadError,/加载失败/)
})
test('late responses cannot populate a different teacher session',async()=>{
  const waiting=[];const {p,storage}=page('teacher-schedule-create',o=>new Promise(resolve=>waiting.push(()=>fixture(o).then(resolve))))
  p.data.weekStart='2030-10-07';const loading=p.load();storage.token='different-session';await Promise.all(waiting.map(resolve=>resolve()));await loading
  assert.equal(p.data.loadSucceeded,false);assert.equal(p.data.weekDays.length,0)
})
test('uncertain administrator grant retains request identity for safe retry',async()=>{
  const sent=[];let attempts=0
  const {p}=page('admin-transactions',o=>{if(o.method==='POST'){sent.push(o.data);if(++attempts===1)return Promise.reject(new Error('timeout'));return Promise.resolve({})}return Promise.resolve({data:[]})},'admin')
  p.data.accounts=[{accountId:7}];p.openAdjust({currentTarget:{dataset:{id:7,action:'GRANT'}}});p.data.form.quantity='15';p.data.form.reason='线下已登记'
  await p.save();assert.equal(p.data.showForm,true);await p.save();assert.equal(sent.length,2);assert.equal(sent[0].requestKey,sent[1].requestKey);assert.equal(p.data.showForm,false)
})

const emptySingle=()=>({...course(12,'08:00:00','10:00:00'),classMode:'2',enrolledCount:0,singleBookings:[],courseClassName:'化学一对一'})
const clickCourse={currentTarget:{dataset:{id:12,date:'2030-10-07'}}}
test('creation calendar withdraws through the existing status update, retaining canceled history and reloading availability',async()=>{
  let withdrawn=false;const requests=[],modals=[],otherPage={}
  const schedule=emptySingle()
  const {p}=page('teacher-schedule-create',o=>{
    requests.push(o)
    if(o.method==='DELETE')assert.fail('withdrawing must never delete historical references')
    if(o.method==='PUT'){withdrawn=true;schedule.status=o.data.status;return Promise.resolve({})}
    if(o.url.endsWith('/schedules'))return Promise.resolve({data:[schedule]})
    if(o.url.endsWith('/availability'))return Promise.resolve({data:{cells:[{...cells[0],canPublish:withdrawn}]}})
    return fixture(o)
  },'teacher',{pages:[otherPage],wx:{showModal:o=>{modals.push(o);o.success({confirm:true})}}})
  p.data.weekStart='2030-10-07';await p.load()
  assert.equal(p.data.weekDays[0].publishSlots.length,0)
  await p.showCourse(clickCourse)
  const updates=requests.filter(o=>o.method==='PUT')
  assert.equal(updates.length,1);assert.equal(updates[0].url,'/miniapp/teacher/course/schedule')
  assert.deepEqual(JSON.parse(JSON.stringify(updates[0].data)),{scheduleId:12,status:'1'})
  assert.equal(modals[0].confirmText,'取消课程');assert.equal(p.data.weekLessonCount,0)
  assert.match(modals[0].content,/已取消的报名和上课历史保留/)
  assert.equal(p._courses.length,1);assert.equal(p.data.stoppedCourses.length,1)
  assert.equal(p.data.weekDays[0].publishSlots.length,1);assert.equal(otherPage._scheduleDirty,true)
})
test('class, booked, legacy counted and incompletely loaded courses offer details only',async()=>{
  const variants=[{classMode:'1'},{singleBookings:[{enrollmentStatus:'0'}]},{enrolledCount:1},{enrolledCount:undefined},{singleBookings:undefined},{status:'1'}]
  for(const changes of variants) {
    const modals=[],requests=[]
    const {p}=page('teacher-schedule-create',o=>{requests.push(o);return fixture(o)},'teacher',{wx:{showModal:o=>modals.push(o)}})
    p.data.loadSucceeded=true;p._courses=[{...emptySingle(),...changes}]
    await p.showCourse(clickCourse)
    assert.equal(modals[0].showCancel,false);assert.equal(requests.length,0)
  }
})
test('keeping a single course or changing teacher during its confirmation sends no withdrawal',async()=>{
  for(const changeTeacher of [false,true]) {
    let modal;const requests=[]
    const {p,storage}=page('teacher-schedule-create',o=>{requests.push(o);return fixture(o)},'teacher',{wx:{showModal:o=>modal=o}})
    p.data.loadSucceeded=true;p._courses=[emptySingle()]
    const running=p.showCourse(clickCourse)
    if(changeTeacher)storage.token='other-teacher'
    modal.success({confirm:changeTeacher});await running
    assert.equal(requests.length,0);assert.equal(p._courseDialogOpen,false)
  }
})
test('repeated course taps open only one cancellation confirmation',async()=>{
  let modal,count=0;const requests=[]
  const {p}=page('teacher-schedule-create',o=>{requests.push(o);return fixture(o)},'teacher',{wx:{showModal:o=>{count++;modal=o}}})
  p.data.loadSucceeded=true;p._courses=[emptySingle()]
  const running=p.showCourse(clickCourse);await p.showCourse(clickCourse)
  assert.equal(count,1);modal.success({confirm:false});await running;assert.equal(requests.length,0)
})
test('new booking rejected by the backend is preserved and the refreshed calendar shows it',async()=>{
  const active={...emptySingle(),enrolledCount:1,singleBookings:[{classDate:'2030-10-07',enrollmentStatus:'0'}]}
  const {p,notices}=page('teacher-schedule-create',o=>{
    if(o.method==='PUT')return Promise.reject(Object.assign(new Error('仍有有效报名，请先处理报名后再撤回课程'),{applicationRejected:true}))
    if(o.url.endsWith('/schedules'))return Promise.resolve({data:[active]})
    return fixture(o)
  },'teacher',{wx:{showModal:o=>o.success({confirm:true})}})
  p.data.loadSucceeded=true;p.data.weekStart='2030-10-07';p._courses=[emptySingle()]
  await p.showCourse(clickCourse)
  assert.match(notices[0].title,/仍有有效报名/);assert.equal(p._courses[0].enrolledCount,1)
  assert.equal(p.data.weekLessonCount,1);assert.equal(p.data.submitting,false)
})
test('legacy repeating course confirmation explicitly covers all lessons and stale week dialogs cannot delete',async()=>{
  let modal;const requests=[]
  const {p}=page('teacher-schedule-create',o=>{requests.push(o);return fixture(o)},'teacher',{wx:{showModal:o=>modal=o}})
  p.data.loadSucceeded=true;p._version=1;p._courses=[{...emptySingle(),endDate:'2030-10-28'}]
  const running=p.showCourse(clickCourse)
  assert.match(modal.content,/整门课程（包含全部4个课次）/)
  p._version=2;modal.success({confirm:true});await running;assert.equal(requests.length,0)
})
