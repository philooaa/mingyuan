const { request } = require('./request')
const couponDisplay = require('./coupon-display')
const parentUrl = '/miniapp/parent/tuition'
const adminUrl = '/system/tuition'
const labels = { PENDING_PRICE: '待核价', HISTORY_PENDING: '历史待核验', OPEN: '已核价', CLOSED: '已关闭', PENDING: '待确认收款', SUCCESS: '已成功', FAILED: '已失败', UNKNOWN: '结果待核查', WAIT_EDU: '等待教务确认金额', WAIT_ADMIN: '教务已阅，等待管理员退款', PENDING_REVIEW: '历史待审核', APPROVED: '审核通过，尚未退款', PROCESSING: '退款处理中', REJECTED: '已驳回', CANCELLED: '已撤回', AVAILABLE: '可使用', LOCKED: '缴费占用中', USED: '已使用', REVOKED: '已撤回', EXPIRED: '已过期', NOT_STARTED: '未生效', ISSUED: '有效', VOID: '已作废', READY: '文件可下载', GENERATING: '文件生成中', CASH: '现金', BANK_TRANSFER: '银行转账', WECHAT_OFFLINE: '微信线下收款', MOCK: '本地模拟', FREE: '费用减免' }
function status(value) { return labels[value] || value || '待核实' }
function money(value) { return value === null || value === undefined || value === '' ? '待核实' : `¥${value}` }
function amount(value) {
  const text = String(value).trim()
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(text)) throw new Error('请输入有效金额，最多两位小数')
  const [whole, part = ''] = text.split('.')
  return `${whole.replace(/^0+(?=\d)/, '')}.${part.padEnd(2, '0')}`
}
function cents(value) { const [whole, part] = amount(value).split('.'); return Number(whole) * 100 + Number(part) }
function bill(item) {
  const successful = (item.payments || []).some(p => p.paymentStatus === 'SUCCESS') || ['已支付', '已退款', '部分退款', '已减免', '1', '2'].includes(String(item.payStatus))
  const activePayment = (item.payments || []).find(p => ['PENDING', 'UNKNOWN'].includes(p.paymentStatus))
  const canPay = !['2','已取消','3','已拒绝'].includes(String(item.enrollmentStatus)) && item.billingStatus === 'OPEN' && item.payableAmount != null && !successful && !activePayment
  return { ...item, statusText: status(item.billingStatus), modeText: item.financeMode === 'MOCK' ? '模拟 · 不涉及真实资金' : '真实线下收费',
    originalText: money(item.originalAmount), discountText: money(item.discountAmount), payableText: money(item.payableAmount), receivedText: money(item.receivedAmount), refundedText: money(item.refundedAmount), netText: money(item.netAmount), dueText: money(item.dueAmount), refundableText: money(item.refundableAmount), canPay, activePayment, canCancel: !item.classDate && ['OPEN', 'PENDING_PRICE'].includes(item.billingStatus) && !successful && !activePayment && !['2', '已取消'].includes(String(item.enrollmentStatus)),
    canRefund: item.billType !== 'CREDIT_PACKAGE' && item.canApplyRefund === true,
    payments: (item.payments || []).map(p => ({ ...p, statusText: status(p.paymentStatus), channelText: status(p.channel), amountText: money(p.amount) })),
    refunds: (item.refunds || []).map(refund), receipts: (item.receipts || []).map(receipt) }
}
function refund(item) {
  const completed = item.refundStatus === 'SUCCESS'
  const legacy = item.refundWorkflow === 'LEGACY' || (!item.refundWorkflow && !['WAIT_EDU', 'WAIT_ADMIN'].includes(item.refundStatus))
  return { ...item, statusText: completed ? '已退款' : status(item.refundStatus), kindText: { PARTIAL: '费用调整', WITHDRAWAL: '退出此课程', SESSION: '单次请假' }[item.refundKind] || '历史退费',
    requestedText: '', approvedText: completed ? money(item.approvedAmount) : '', canCancel: false,
    workflowText: legacy ? '历史流程，请联系管理员核对处理；旧记录不会自动转入新流程。' : '',
    progressText: legacy ? '' : item.refundStatus === 'WAIT_EDU' ? '教务核定实际退款金额后提交管理员。' : item.refundStatus === 'WAIT_ADMIN' ? '教务已阅，管理员登记线下实际退款后完成。' : completed ? '线下退款已登记完成。' : '' }
}
function receipt(item) { return { ...item, amountText: money(item.amount), statusText: status(item.receiptStatus), fileText: status(item.fileStatus), title: item.documentType === 'WAIVER' ? '费用减免确认单' : '课程收款收据', canDownload: item.receiptStatus === 'ISSUED' && item.fileStatus === 'READY' } }
function coupon(item) { return { ...item, subjectText:couponDisplay.subjectText(item),summaryText:couponDisplay.summary(item), amountText: money(item.discountAmount), minSpendText: money(item.minSpendAmount), statusText: status(item.displayStatus || item.couponStatus), scopeText: couponDisplay.scopeText(item) } }
function message(error) { wx.showToast({ title: error.message || '操作失败，请重试', icon: 'none' }) }
function auth(role) {
  const data = getApp().globalData
  if (!wx.getStorageSync('token')) { wx.reLaunch({ url: '/pages/register/register' }); return false }
  if (role === 'staff' ? !(data.roles || []).some(r => ['admin', 'education'].includes(r)) : !(data.roles || []).includes(role)) { message(new Error(role === 'staff' || role === 'admin' ? '仅授权管理员或教务可处理收费' : '仅家长可查看本人财务')); return false }
  return true
}
function can(permission) { return (getApp().globalData.roles || []).some(r => ['admin', 'education'].includes(r)) && (getApp().globalData.permissions || []).some(p => p === '*:*:*' || p === permission) }
function confirm(title, content) { return new Promise((resolve, reject) => wx.showModal({ title, content, success: r => resolve(r.confirm), fail: () => reject(new Error('无法显示确认窗口')) })) }
function intentKey(type, id) { const user = getApp().globalData.userInfo || {}; if (!user.userId) throw new Error('请重新登录以确认账户'); return `tuition:${type}:${user.userId}:${id}` }
function intent(type, id, payload) {
  const key = intentKey(type, id), saved = wx.getStorageSync(key)
  if (saved) return saved
  const value = { ...payload, idempotencyKey: `tf-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}` }
  wx.setStorageSync(key, value)
  return value
}
function clearIntent(type, id) { wx.removeStorageSync(intentKey(type, id)) }
function savedIntent(type, id) { return wx.getStorageSync(intentKey(type, id)) || null }
function downloadReceipt(id, admin = false) {
  return new Promise((resolve, reject) => wx.downloadFile({ url: `${getApp().globalData.baseUrl}${admin ? adminUrl : parentUrl}/receipts/${encodeURIComponent(String(id))}/file`, header: { Authorization: `Bearer ${wx.getStorageSync('token') || ''}` },
    success(res) {
      if (res.statusCode !== 200) { reject(new Error('收据下载失败，请刷新状态')); return }
      wx.getFileSystemManager().readFile({ filePath: res.tempFilePath, success(file) {
        const prefix = String.fromCharCode.apply(null, new Uint8Array(file.data).slice(0, 1024))
        if (!prefix.includes('%PDF-')) { reject(new Error('下载内容不是PDF，请刷新状态或联系管理员')); return }
        wx.openDocument({ filePath: res.tempFilePath, fileType: 'pdf', showMenu: true, success: resolve, fail: () => reject(new Error('无法打开PDF，请重试')) })
      }, fail: () => reject(new Error('无法读取收据文件')) })
    }, fail: () => reject(new Error('下载失败，请重试')) }))
}
function uploadEvidence(file, businessType, businessId) {
  if (!file || !file.path || !file.size || file.size > 10 * 1024 * 1024) return Promise.reject(new Error('请选择10MB以内的PDF或图片凭证'))
  return new Promise((resolve, reject) => wx.uploadFile({ url: `${getApp().globalData.baseUrl}${adminUrl}/evidence`, filePath: file.path, name: 'file', formData: { businessType, businessId: String(businessId) }, header: { Authorization: `Bearer ${wx.getStorageSync('token') || ''}` }, success(res) {
    try { const data = JSON.parse(res.data); if (res.statusCode !== 200 || data.code !== 200 || !data.data.evidenceKey) throw new Error(data.msg || '上传失败'); resolve(data.data.evidenceKey) } catch (error) { reject(error) }
  }, fail: () => reject(new Error('凭证上传失败，请重试')) }))
}
module.exports = { request, parentUrl, adminUrl, status, money, amount, cents, bill, refund, receipt, coupon, message, auth, can, confirm, intent, savedIntent, clearIntent, downloadReceipt, uploadEvidence }
