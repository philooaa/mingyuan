const test = require('node:test')
const assert = require('node:assert/strict')
const calendar = require('../utils/course-calendar')

// 与后端 ScheduleCalendar 保持一致：模式 → 一轮天数 / 上课天数
const cases = [
  { pattern: 'DAILY', cycle: 1, study: 1 },
  { pattern: 'DAILY_5_1', cycle: 6, study: 5 },
  { pattern: 'DAILY_6_1', cycle: 7, study: 6 },
  { pattern: 'DAILY_5_2', cycle: 7, study: 5 },
  { pattern: 'DAILY_4_1', cycle: 5, study: 4 }
]

function course(pattern, startDate, endDate, periodName = '一期') {
  return { scheduleId: 1, classPattern: pattern, periodName, startDate, endDate, startTime: '08:00', endTime: '10:00' }
}

// 2026-01-01 起 20 天，逐日核对「上 N 休 M」的落点
for (const { pattern, cycle, study } of cases) {
  test(`${pattern} expands to ${study} lessons per ${cycle}-day cycle`, () => {
    const { dates, reason } = calendar.expandCourse(course(pattern, '2026-01-01', '2026-01-20'))
    assert.equal(reason, '')
    let expected = 0
    for (let index = 0; index < 20; index++) {
      const day = calendar.formatDate(calendar.addDays(calendar.parseDate('2026-01-01'), index))
      const shouldStudy = index % cycle < study
      if (shouldStudy) expected++
      assert.equal(dates.includes(day), shouldStudy, `${day} 的排课状态不符合 ${pattern}`)
    }
    assert.equal(dates.length, expected)
  })
}

test('OTHER never produces lesson dates', () => {
  const { dates, reason } = calendar.expandCourse(course('OTHER', '2026-01-01', '2026-01-31'))
  assert.deepEqual(dates, [])
  assert.equal(reason, '其他（暂不排课）')
})

test('weekly still follows the period weekday', () => {
  // 2026-03-02 是周一
  const { dates } = calendar.expandCourse(course('WEEKLY', '2026-03-02', '2026-03-31', '周六'))
  assert.deepEqual(dates, ['2026-03-07', '2026-03-14', '2026-03-21', '2026-03-28'])
})

test('missing or reversed range stays unscheduled', () => {
  assert.equal(calendar.expandCourse(course('DAILY_4_1', '', '')).reason, '排课起止日期待配置')
  assert.equal(calendar.expandCourse(course('DAILY_4_1', '2026-01-10', '2026-01-01')).reason, '排课起止日期顺序异常')
})

test('cancel and move adjustments still apply on new patterns', () => {
  const moved = {
    ...course('DAILY_4_1', '2026-01-01', '2026-01-10'),
    adjustments: [
      { originalDate: '2026-01-02', adjustedDate: '2026-01-11' },
      { originalDate: '2026-01-03', adjustedDate: null }
    ]
  }
  const { dates } = calendar.expandCourse(moved)
  assert.ok(dates.includes('2026-01-11'))
  assert.ok(!dates.includes('2026-01-02'))
  assert.ok(!dates.includes('2026-01-03'))
})
