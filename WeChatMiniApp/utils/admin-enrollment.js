const { request } = require('./request')
const root = '/miniapp/admin/enrollment'
function guard() {
  if ((wx.getStorageSync('roles') || []).includes('admin')) return true
  wx.showToast({ title: '需要管理员身份', icon: 'none' })
  wx.reLaunch({ url: '/pages/index/index' })
  return false
}
function label(value, labels) {
  return labels[String(value)] || value || '未知'
}
// 依学段、年级排序，避免直接比较汉字；未分年级置于最后。
const gradeAliases = [
  ['一年级', '小学一年级', '小一', '1年级'],
  ['二年级', '小学二年级', '小二', '2年级'],
  ['三年级', '小学三年级', '小三', '3年级'],
  ['四年级', '小学四年级', '小四', '4年级'],
  ['五年级', '小学五年级', '小五', '5年级'],
  ['六年级', '小学六年级', '小六', '6年级'],
  ['初一', '初中一年级', '七年级', '7年级'],
  ['初二', '初中二年级', '八年级', '8年级'],
  ['初三', '初中三年级', '九年级', '9年级'],
  ['高一', '高中一年级'], ['高二', '高中二年级'], ['高三', '高中三年级']
]
function gradeRank(value) {
  const grade = String(value || '').trim()
  if (!grade || grade === '未分年级') return 100
  const rank = gradeAliases.findIndex(aliases => aliases.includes(grade))
  return rank < 0 ? 99 : rank
}
function compareGrades(a, b) {
  const difference = gradeRank(a) - gradeRank(b)
  if (difference) return difference
  return String(a || '').localeCompare(String(b || ''), 'zh-CN')
}
function decorateEnrollment(item) {
  return { ...item,
    enrollmentText: label(item.enrollmentStatus, { 0: '待确认', 1: '报名成功', 2: '已取消', 3: '老师已拒绝' }),
    payText: label(item.payStatus, { 0: '未支付', 1: '已支付', 2: '已退款' }),
    cancelled: String(item.enrollmentStatus) === '2' || item.enrollmentStatus === '已取消',
    attendedLessonCount: item.attendedLessonCount || 0,
    pendingLessonCount: item.pendingLessonCount || 0,
    recordedLessonCount: item.recordedLessonCount || 0,
    leaveLessonCount: item.leaveLessonCount || 0
  }
}
function decorateSchedule(item) {
  const time = [item.startTime, item.endTime].filter(Boolean).map(v => String(v).slice(0, 5)).join('–')
  return { ...item,
    course: item.courseClassName || [item.gradeName, item.subjectName, item.classType].filter(Boolean).join(' · ') || '未命名课程',
    room: item.lessonLocation || [item.campusName, item.classroomName].filter(Boolean).join(' · ') || '教室未提供',
    time: [item.periodName, time || item.timeSlot].filter(Boolean).join(' · '),
    recruitText: label(item.recruitStatus, { 0: '招生中', 1: '停招', 2: '满班' })
  }
}
module.exports = { request, root, guard, decorateEnrollment, decorateSchedule, label, compareGrades }
