// Money stays decimal text; totals and final eligibility belong to the server.
export function amount(value, positive = false) {
  const text = String(value == null ? '' : value).trim()
  if (!/^(0|[1-9]\d{0,7})(\.\d{1,2})?$/.test(text))
    throw new Error('金额须为非负数，最多两位小数、八位整数')
  const parts = text.split('.')
  const result = parts[0] + '.' + (parts[1] || '').padEnd(2, '0')
  if (positive && result === '0.00') throw new Error('金额必须大于零')
  return result
}
export function money(value) {
  if (value == null || value === '') return '待核验'
  const text = String(value).trim()
  if (!/^(0|[1-9]\d*)(\.\d{1,2})?$/.test(text)) return '金额异常'
  const parts = text.split('.')
  return parts[0] + '.' + (parts[1] || '').padEnd(2, '0')
}
export function compareAmount(left, right) {
  const a = amount(left).replace('.', '').padStart(10, '0')
  const b = amount(right).replace('.', '').padStart(10, '0')
  return a === b ? 0 : a > b ? 1 : -1
}
export function required(value, label) {
  if (value == null || !String(value).trim()) throw new Error('请填写' + label)
  return String(value).trim()
}
export function requestKey() {
  return (
    'fin-' +
    Date.now().toString(36) +
    '-' +
    Math.random().toString(36).slice(2) +
    '-' +
    Math.random().toString(36).slice(2)
  )
}
export function canCollect(bill) {
  return (
    !!bill &&
    bill.billingStatus === 'OPEN' &&
    !(bill.payments || []).some((p) => !['FAILED', 'CLOSED'].includes(p.paymentStatus))
  )
}
export function canPrice(bill) {
  return (
    !!bill &&
    bill.billType !== 'CREDIT_PACKAGE' &&
    ['OPEN', 'PENDING_PRICE'].includes(bill.billingStatus) &&
    !(bill.payments || []).some((p) => !['FAILED', 'CLOSED'].includes(p.paymentStatus))
  )
}
export function canClosePayment(payment) {
  return !!payment && payment.paymentStatus === 'PENDING'
}
export function canReview(refund, actorId) {
  return (
    !!refund &&
    actorId != null &&
    refund.refundStatus === 'WAIT_EDU'
  )
}
export function validateEvidenceFile(file) {
  if (!file || !/\.(pdf|png|jpe?g)$/i.test(file.name)) throw new Error('凭证仅支持 PDF、PNG、JPEG')
  if (file.size <= 0 || file.size > 10 * 1024 * 1024)
    throw new Error('凭证须小于或等于 10 MB，且不能为空')
  return true
}
export function evidenceKey(response) {
  if (
    !response ||
    response.code !== 200 ||
    !response.data ||
    typeof response.data.evidenceKey !== 'string' ||
    !response.data.evidenceKey
  )
    throw new Error((response && response.msg) || '凭证上传失败')
  return response.data.evidenceKey
}
export function refundConfirmation(form, channel) {
  const outcome = form.outcome || 'SUCCESS'
  if (!['SUCCESS', 'FAILED', 'UNKNOWN'].includes(outcome)) throw new Error('请选择退款结果')
  if (outcome !== 'SUCCESS') return { outcome, reason: required(form.reason, '结果原因') }
  return {
    outcome,
    evidenceKey: required(form.evidenceKey, '退款凭证'),
    completedTime: required(form.completedTime, '实际退款时间'),
    providerRefundNo:
      channel === 'CASH'
        ? form.providerRefundNo || null
        : required(form.providerRefundNo, '退款流水号'),
  }
}
export function grantBody(form) {
  const parentIds = [...new Set((form.parentIds || []).map((id) => String(id)))]
  if (!parentIds.length) throw new Error('请选择现有家长账号')
  if (parentIds.length > 100) throw new Error('一次最多发给 100 位家长')
  return {
    parentIds,
    reason: required(form.reason, '发放原因'),
    idempotencyKey: required(form.idempotencyKey, '批次标识'),
  }
}
export async function checkedBlob(blob) {
  if (!blob || !blob.size) throw new Error('文件为空，请重试')
  if (/json|text\/|html/.test(blob.type || '')) {
    const text = await blob.text()
    let result
    try {
      result = JSON.parse(text)
    } catch (_) {
      throw new Error('文件下载失败')
    }
    throw new Error(result.msg || '文件下载失败')
  }
  return blob
}
export const labels = {
  PENDING_PRICE: '待核价',
  OPEN: '已核价',
  CLOSED: '已关闭',
  HISTORY_PENDING: '历史待核验',
  REAL: '真实',
  MOCK: '模拟／不涉及真实资金',
  FREE: '零元减免',
  CASH: '现金',
  BANK_TRANSFER: '银行转账',
  WECHAT_OFFLINE: '微信线下收款',
  PENDING: '待确认',
  UNKNOWN: '结果未知／待核对',
  SUCCESS: '成功',
  FAILED: '失败',
  WAIT_EDU: '等待教务确认金额',
  WAIT_ADMIN: '教务已阅，等待管理员退款',
  PENDING_REVIEW: '历史待审核',
  APPROVED: '审核通过／待执行',
  PROCESSING: '处理中',
  REJECTED: '驳回',
  CANCELLED: '已撤回',
  PARTIAL: '费用调整',
  WITHDRAWAL: '退出此课程',
  SESSION: '单次请假',
  ISSUED: '有效',
  VOID: '作废',
  READY: '文件可用',
  GENERATING: '文件生成中',
  RECEIPT: '收款收据',
  WAIVER: '费用减免确认单',
  DRAFT: '草稿',
  ACTIVE: '启用',
  PAUSED: '暂停发放',
  AVAILABLE: '可用',
  LOCKED: '已锁定',
  USED: '已核销',
  REVOKED: '已撤回',
  EXPIRED: '已过期',
  NOT_STARTED: '未生效',
}
export function label(value) {
  return labels[value] || value || '待核对'
}
export function statusType(value) {
  if (['SUCCESS', 'READY', 'OPEN', 'ACTIVE', 'ISSUED', 'AVAILABLE'].includes(value))
    return 'success'
  if (['WAIT_EDU', 'WAIT_ADMIN', 'UNKNOWN', 'HISTORY_PENDING', 'PENDING_REVIEW', 'PENDING', 'PROCESSING'].includes(value))
    return 'warning'
  if (['FAILED', 'REJECTED', 'VOID'].includes(value)) return 'danger'
  return 'info'
}
