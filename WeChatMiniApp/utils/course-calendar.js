// 教师日历只展示可确定的课次；不从考勤反推未来排课。
const DAY_NAMES = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const MINUTE_HEIGHT = 2.2 // rpx；两小时留足课程信息高度，时段间隔仍压缩显示。
const DEFAULT_SLOTS = ['08:00-10:00','10:15-12:15','13:15-15:15','15:30-17:30','18:30-20:30','20:30-22:30']

function parseDate(value) {
  const match = String(value || '').match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:$|[ T])/)
  if (!match) return null
  const date = new Date(+match[1], +match[2] - 1, +match[3])
  return date.getFullYear() === +match[1] && date.getMonth() === +match[2] - 1 && date.getDate() === +match[3] ? date : null
}
function formatDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function addDays(date, days) { const result = new Date(date); result.setDate(result.getDate() + days); return result }
function weekStart(value) {
  const date = parseDate(value) || new Date()
  date.setHours(0, 0, 0, 0)
  return addDays(date, -((date.getDay() + 6) % 7))
}
function timeMinutes(value) {
  const match = String(value || '').match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/)
  if (!match || +match[2] > 59 || +(match[3] || 0) > 59 || +match[1] > 24 || (+match[1] === 24 && (+match[2] || +(match[3] || 0)))) return null
  return +match[1] * 60 + +match[2] + +(match[3] || 0) / 60
}
function expandCourse(course) {
  const start = parseDate(course.startDate), end = parseDate(course.endDate)
  if (!start || !end) return { dates: [], reason: '排课起止日期待配置' }
  if (start > end) return { dates: [], reason: '排课起止日期顺序异常' }
  if ((end - start) / 86400000 > 3660) return { dates: [], reason: '排课日期跨度异常，请核对' }
  const pattern = course.classPattern || (['周六', '周日'].includes(course.periodName) ? 'WEEKLY' : '')
  if (!['WEEKLY', 'DAILY_5_1'].includes(pattern)) return { dates: [], reason: '重复规则待确认' }
  if (pattern === 'WEEKLY' && !DAY_NAMES.includes(course.periodName)) return { dates: [], reason: '每周上课的星期待确认' }
  const adjustments = new Map()
  for (const adjustment of course.adjustments || []) {
    const original = parseDate(adjustment.originalDate)
    const adjusted = adjustment.adjustedDate ? parseDate(adjustment.adjustedDate) : null
    if (!original || (adjustment.adjustedDate && !adjusted)) return { dates: [], reason: '调课日期异常，请核对' }
    const key = formatDate(original)
    if (adjustments.has(key)) return { dates: [], reason: '同一课次存在多条调课记录，请核对' }
    adjustments.set(key, adjusted)
  }
  const dates = new Set()
  for (let date = start, index = 0; date <= end; date = addDays(date, 1), index++) {
    const included = pattern === 'WEEKLY' ? date.getDay() === DAY_NAMES.indexOf(course.periodName) : index % 6 < 5
    if (!included) continue
    const key = formatDate(date)
    const actual = adjustments.has(key) ? adjustments.get(key) : date
    if (actual) dates.add(formatDate(actual))
  }
  return { dates: Array.from(dates).sort(), reason: '' }
}

// 同日重叠课程分列展示，避免后一条覆盖前一条。
function positionBlocks(blocks, timeline) {
  blocks.sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute || String(a.scheduleId).localeCompare(String(b.scheduleId)))
  let group = [], ends = [], groupEnd = -1
  function finish() {
    group.forEach(block => {
      block.overlap = ends.length > 1
      block.style = `${rangeStyle(timeline,block.startMinute,block.endMinute)}left:${block.lane * 100 / ends.length}%;width:${100 / ends.length}%;`
    })
  }
  blocks.forEach(block => {
    if (block.startMinute >= groupEnd) { finish(); group = []; ends = []; groupEnd = -1 }
    let lane = ends.findIndex(end => end <= block.startMinute)
    if (lane < 0) lane = ends.length
    ends[lane] = block.endMinute
    block.lane = lane
    group.push(block)
    groupEnd = Math.max(groupEnd, block.endMinute)
  })
  finish()
  return blocks
}

function clock(minute) { return `${String(Math.floor(minute/60)).padStart(2,'0')}:${String(Math.floor(minute%60)).padStart(2,'0')}` }
function buildTimeline(slots, blocks) {
  const ranges=[]
  for(const slot of slots === undefined ? DEFAULT_SLOTS : slots) {
    const parts=String(typeof slot==='string' ? slot : slot.value || slot.timeSlot || '').split(/[-–—]/)
    const start=timeMinutes(slot.startTime || parts[0]),end=timeMinutes(slot.endTime || parts[1])
    if(start !== null && end !== null && start<end) ranges.push({start,end})
  }
  ranges.sort((a,b)=>a.start-b.start || a.end-b.end)
  const merged=[]
  for(const range of ranges) {
    const last=merged[merged.length-1]
    if(last && range.start<last.end) last.end=Math.max(last.end,range.end)
    else merged.push({...range})
  }
  // 特殊时间的历史课程仍完整显示，不裁掉机构时段之外的真实上课时间。
  if(blocks.length) {
    const start=Math.min(...blocks.map(b=>b.startMinute)),end=Math.max(...blocks.map(b=>b.endMinute))
    if(!merged.length) merged.push({start,end})
    else {
      const first=merged[0].start,last=merged[merged.length-1].end
      if(start<first) merged.unshift({start,end:first})
      if(end>last) merged.push({start:last,end})
    }
  }
  let top=0
  const timeline=[],hours=[],breaks=[]
  merged.forEach((range,index)=>{
    const previous=merged[index-1]
    if(previous && previous.end<range.start) {
      timeline.push({start:previous.end,end:range.start,top,height:20});breaks.push({top,height:20});top+=20
    }
    const height=Math.max(120,(range.end-range.start)*MINUTE_HEIGHT)
    timeline.push({...range,top,height});hours.push({label:clock(range.start),endLabel:clock(range.end),top,height});top+=height
  })
  return {calendarTimeline:timeline,hours,calendarBreaks:breaks,calendarHeight:top+2}
}
function minuteTop(timeline,minute) {
  if(!timeline || !timeline.length) return minute*MINUTE_HEIGHT
  const range=timeline.find(r=>minute>=r.start && minute<=r.end)
  if(!range) return minute<timeline[0].start ? 0 : timeline[timeline.length-1].top+timeline[timeline.length-1].height
  return Math.round((range.top+(minute-range.start)*range.height/(range.end-range.start))*100)/100
}
function rangeStyle(timeline,start,end) {
  const a=typeof start==='number' ? start : timeMinutes(start),b=typeof end==='number' ? end : timeMinutes(end)
  const top=minuteTop(timeline,a),bottom=minuteTop(timeline,b)
  return `top:${top}rpx;height:${bottom-top}rpx;`
}

function singleLessonState(course, booking, date) {
  if (booking) {
    const status = String(booking.enrollmentStatus)
    if (String(booking.cancelRequestStatus) === '1') return { lessonState: 'cancel-pending', statusText: '取消申请待处理' }
    if (['1', '报名成功', '已确认'].includes(status) && [true, 1, '1'].includes(booking.lessonCompleted)) return { lessonState: 'completed', statusText: '已完成' }
    if (['0', '待确认'].includes(status)) return { lessonState: 'pending', statusText: '待老师确认' }
    if (['1', '报名成功', '已确认'].includes(status)) return { lessonState: 'confirmed', statusText: '已确认' }
    if (status === '2') return { lessonState: 'cancelled', statusText: '已取消' }
    if (status === '3') return { lessonState: 'rejected', statusText: '已拒绝' }
    return { lessonState: 'unavailable', statusText: '状态待核对' }
  }
  if (String(course.status) === '1') return { lessonState: 'unavailable', statusText: '已停用' }
  if (String(course.recruitStatus || '0') !== '0') return { lessonState: 'unavailable', statusText: '已停招' }
  const started = new Date(`${date}T${course.startTime || '00:00:00'}`) <= new Date()
  return { lessonState: 'empty', statusText: started ? '无人报名 · 已过期' : '无人报名 · 可报名' }
}

function buildWeek(courses, selectedDate, slots) {
  const monday = weekStart(selectedDate), sunday = addDays(monday, 6)
  const today = formatDate(new Date()), first = formatDate(monday), last = formatDate(sunday)
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = addDays(monday, index), key = formatDate(date)
    return { date: key, label: DAY_NAMES[date.getDay()], shortDate: key.slice(5), today: key === today, blocks: [] }
  })
  const byDate = new Map(days.map(day => [day.date, day]))
  const pending = [], stopped = []
  let plannedCount = 0, lessonCount = 0
  courses.forEach(course => {
    if (String(course.status) === '1') { stopped.push(course); return }
    const expanded = expandCourse(course)
    const start = timeMinutes(course.startTime), end = timeMinutes(course.endTime)
    const reason = expanded.reason || (start === null || end === null || start >= end ? '上课起止时间待核对' : '')
    if (reason) { pending.push({ ...course, pendingReason: reason }); return }
    plannedCount += expanded.dates.length
    expanded.dates.forEach(date => {
      const day = byDate.get(date)
      if (!day) return
      const booking = (course.singleBookings || []).find(item => String(item.classDate).slice(0, 10) === date)
      const isSingle = String(course.classMode) === '2'
      const state = isSingle ? singleLessonState(course, booking, date) : { lessonState: 'class-course', statusText: '班课' }
      day.blocks.push({ ...course, ...state, studentName: booking ? booking.studentName : '', enrollmentId: booking ? booking.enrollmentId : null, cancelRequestStatus: booking ? booking.cancelRequestStatus : '0', date, key: `${course.scheduleId}:${date}`, startMinute: start, endMinute: end,
        timeText: `${String(course.startTime).slice(0, 5)}–${String(course.endTime).slice(0, 5)}`,
        modeText: isSingle ? '一对一' : '班课' })
      lessonCount++
    })
  })
  const geometry=buildTimeline(slots,days.flatMap(day=>day.blocks))
  days.forEach(day => positionBlocks(day.blocks,geometry.calendarTimeline))
  return { ...geometry,weekStart: first, weekEnd: last, weekLabel: `${first} 至 ${last}`, weekDays: days, pendingCourses: pending,
    stoppedCourses: stopped, weekLessonCount: lessonCount, plannedLessonCount: plannedCount,
    overlapCount: days.reduce((count, day) => count + day.blocks.filter(block => block.overlap).length, 0) }
}
module.exports = { parseDate, formatDate, addDays, weekStart, timeMinutes, expandCourse, positionBlocks, singleLessonState, buildWeek,rangeStyle }
