const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
const calendar=require('../utils/course-calendar')
function load(page) {
  let definition
  const context={Page:p=>definition=p,wx:{},getApp:()=>({}),require:id=>id.endsWith('course-calendar')?calendar:{page:p=>p}}
  vm.createContext(context)
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../pages',page,page+'.js'),'utf8'),context)
  return {context,definition}
}
test('parent timetable and home use the custom snapshot including cancellations and moves outside bounds',()=>{
  const item={classPattern:'CUSTOM_DATES',startDate:'2099-12-26',endDate:'2100-01-02',startTime:'08:00',customDates:['2099-12-26','2099-12-28','2100-01-02'],adjustments:[{originalDate:'2099-12-26',adjustedDate:null},{originalDate:'2099-12-28',adjustedDate:'2100-01-05'}]}
  const {definition}=load('timetable')
  assert.deepEqual(Array.from(definition.buildDateList(item),calendar.formatDate),['2100-01-02','2100-01-05'])
  const {context}=load('index')
  assert.equal(context.computeNextDate(item).date,'2100-01-02')
  assert.equal(context.computeNextDate({...item,customDates:[]}),null)
})
