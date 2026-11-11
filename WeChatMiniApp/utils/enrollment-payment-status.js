function paymentStatus(row) {
  const enrollment = String(row.enrollmentStatus == null ? '' : row.enrollmentStatus)
  if (['2', '已取消'].includes(enrollment)) return '已取消'
  if (['3', '已拒绝', '老师已拒绝'].includes(enrollment)) return '已拒绝'
  if (row.settlementMethod === 'P') return '课包结算'
  const billing = String(row.billingStatus || '')
  const pay = String(row.billingPayStatus == null ? '' : row.billingPayStatus)
  if (billing === 'HISTORY_PENDING') return '历史待核验'
  if (['2', '已退款'].includes(pay)) return '已退款'
  if (['1', '已支付'].includes(pay)) return '已支付'
  if (billing === 'PENDING_PRICE') return '待核价'
  if (billing === 'OPEN' && ['0', '未支付'].includes(pay)) return '未支付'
  return '待核对'
}
module.exports = { paymentStatus }
