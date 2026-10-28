// 首页角标与课程页面共用待处理口径；权限和最终状态仍由后端校验。
function bookingFlags(row, now = new Date()) {
  const status = String(row.enrollmentStatus)
  const isPending = ['0', '待确认'].includes(status)
  const active = isPending || ['1', '已确认', '报名成功'].includes(status)
  const isCancel = active && String(row.cancelRequestStatus) === '1'
  const start = new Date(`${String(row.classDate || '').slice(0, 10)}T${row.startTime || ''}`)
  const canHandle = active && ([true, 1, '1'].includes(row.canProcess) ||
    (row.canProcess == null && start > now && ![true, 1, '1'].includes(row.lessonCompleted)))
  return { isPending, isCancel, canHandle, needsHandling: canHandle && (isPending || isCancel) }
}

function pendingCount(rows, now = new Date()) {
  const ids = new Set()
  for (const row of (rows || [])) {
    if (row.enrollmentId != null && bookingFlags(row, now).needsHandling) ids.add(String(row.enrollmentId))
  }
  return ids.size
}

function badgeText(count) { return count > 0 ? (count > 99 ? '99+' : String(count)) : '' }

module.exports = { bookingFlags, pendingCount, badgeText }
