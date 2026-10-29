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
const { compareGrades } = require('./grade-order')
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
