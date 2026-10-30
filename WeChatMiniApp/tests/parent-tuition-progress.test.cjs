const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const progress = require('../utils/course-payment-progress')

test('parent sees distinct pricing, offline payment, and administrator confirmation progress', () => {
  for (const [code, title, expected] of [
    ['WAIT_PRICE', '待教务核价', ['current','waiting','waiting']],
    ['WAIT_PAYMENT', '待线下缴费', ['done','current','waiting']],
    ['WAIT_CONFIRM', '收款待确认', ['done','done','current']]
  ]) {
    const view = progress.describe(code)
    assert.equal(view.title, title)
    assert.equal(view.showSteps, true)
    assert.deepEqual(view.steps.map(s => s.state), expected)
    assert.equal(view.steps.filter(s => s.state === 'current').length, 1)
  }
  assert.match(progress.describe('WAIT_CONFIRM').note, /管理员核对/)
})

test('completion collapses to a green result, exceptional states and credits do not appear unpaid', () => {
  for (const code of ['PAID','WAIVED']) {
    assert.equal(progress.describe(code).showSteps, false)
    assert.equal(progress.describe(code).tone, 'success')
  }
  for (const code of ['HISTORY_PENDING','PAYMENT_UNKNOWN','REFUND_PENDING','REFUND_UNKNOWN','PARTIAL_REFUND','REFUNDED','CLOSED','CREDIT_RESERVED','CREDIT_USED',undefined,'future-state']) {
    const view = progress.describe(code)
    assert.equal(view.showSteps, false)
    assert.doesNotMatch(view.title, /未支付|待线下缴费|缴费已完成/)
  }
})

function setup() {
  const storage = {token:'parent-a', roles:['parent']}, calls = []
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/timetable/timetable.js'),'utf8'), {
    wx:{getStorageSync:key=>storage[key]}, Page:value=>{definition=value},
    require:id=>id.endsWith('child-context') ? {page:value=>value} : id.endsWith('course-payment-progress') ? progress : {
      request:options=>new Promise(resolve=>calls.push({options,resolve}))
    }
  })
  const page={...definition,data:structuredClone(definition.data)}
  page.setData=patch=>Object.assign(page.data,patch)
  return {page, storage, calls}
}

test('returning to the timetable refreshes payment progress without duplicating initial load', async () => {
  const {page,calls}=setup()
  page.onLoad({accountId:'11'});page.onShow()
  assert.equal(calls.length,1)
  assert.equal(calls[0].options.data.accountId,'11')
  calls[0].resolve({data:[{enrollmentId:1, tuitionProgress:'WAIT_CONFIRM'}]})
  await new Promise(setImmediate)
  assert.equal(page.data.timetable[0].tuitionView.title,'收款待确认')
  page.onShow()
  assert.equal(calls.length,2)
  calls[1].resolve({data:[{enrollmentId:1, tuitionProgress:'PAID'}]})
  await new Promise(setImmediate)
  assert.equal(page.data.timetable[0].tuitionView.title,'✓ 缴费已完成')
  assert.equal(page.data.timetable[0].tuitionView.showSteps,false)
})

test('a late older refresh and another parent session cannot restore stale payment progress', async () => {
  const {page,calls,storage}=setup()
  const first=page.loadTimetable(), second=page.loadTimetable()
  calls[1].resolve({data:[{enrollmentId:1, tuitionProgress:'PAID'}]});await second
  calls[0].resolve({data:[{enrollmentId:1, tuitionProgress:'WAIT_PRICE'}]});await first
  assert.equal(page.data.timetable[0].tuitionView.title,'✓ 缴费已完成')
  const oldSession=page.loadTimetable()
  storage.token='parent-b'
  calls[2].resolve({data:[{enrollmentId:2, tuitionProgress:'PAID'}]});await oldSession
  assert.equal(page.data.timetable.length,0)
})

test('an older backend without progress facts is shown as unverified even if its label says paid', () => {
  const {page}=setup()
  const course=page.decorateTimetable([{enrollmentId:1,payStatus:'已支付'}])[0]
  assert.equal(course.tuitionView.title,'缴费进度待核实')
})
