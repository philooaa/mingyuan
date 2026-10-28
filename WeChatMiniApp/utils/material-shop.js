const { request } = require('./request')
const ordersUrl = '/miniapp/parent/material/orders'
const addressUrl = '/miniapp/parent/addresses'
const imageUrl = require('./public-file-url')
function cents(value) {
  if (!/^\d+(\.\d{1,2})?$/.test(String(value))) throw new Error('金额格式不正确')
  const [whole, fraction = ''] = String(value).split('.')
  const result = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (!Number.isSafeInteger(result)) throw new Error('金额超出范围')
  return result
}
function calculateTotal(price, quantity, shipping) {
  const total = cents(price) * quantity + cents(shipping)
  if (!Number.isSafeInteger(total) || total < 0) throw new Error('金额超出范围')
  return `${Math.floor(total / 100)}.${String(total % 100).padStart(2, '0')}`
}
function normalizeQuantity(value, stock, limit) {
  const quantity = Number(value)
  if (!Number.isSafeInteger(quantity) || quantity < 1 || quantity > Number(stock) || (Number(limit) > 0 && quantity > Number(limit))) throw new Error('数量超出库存或单次限购')
  return quantity
}
function product(item) {
  const images = (item.images || []).map(image => ({ ...image, imageUrl: imageUrl(image.imageUrl) }))
  const coverUrl = imageUrl(item.coverUrl)
  return { ...item, coverUrl, images, bought: item.bought === true || item.bought === '1' || item.bought === 1,
    priceText: `¥${item.price || '0.00'}`, deliveryText: item.deliveryType === 'PHYSICAL' ? '纸质配送' : '电子下载',
    gallery: (coverUrl ? [{ imageUrl: coverUrl }] : []).concat(images.filter(image => image.imageType === 'GALLERY')),
    detailImages: images.filter(image => image.imageType === 'DETAIL') }
}
const statuses = { WAIT_PAY: '待支付', WAIT_SHIP: '待发货', WAIT_RECEIVE: '待收货', COMPLETED: '已完成', CANCELLED: '已取消', CLOSED: '已关闭' }
const events = { CREATE: '订单已创建', PAY_SUCCESS: '模拟支付成功', CANCEL: '订单已取消', EXPIRE: '支付超时关闭', SHIP: '资料已发货', RECEIVE: '确认收货', MOCK_REFUND: '模拟退款完成' }
function order(item) {
  return { ...item, items: (item.items || []).map(line => ({ ...line, coverUrl: imageUrl(line.coverUrl) })),
    logs: (item.logs || []).map(log => ({ ...log, eventText: events[log.eventType] || '订单已更新', statusText: statuses[log.toOrderStatus] || '' })),
    statusText: String(item.payStatus) === '2' ? '已退款' : (statuses[item.orderStatus] || item.orderStatus || '历史订单'),
    canPay: item.orderStatus === 'WAIT_PAY' && String(item.payStatus) === '0',
    canReceive: item.orderStatus === 'WAIT_RECEIVE' && String(item.payStatus) === '1',
    canDownload: item.deliveryType === 'DIGITAL' && String(item.payStatus) === '1' && item.aftersaleStatus !== 'REFUNDED' }
}
function message(error) { wx.showToast({ title: error.message || '操作失败，请重试', icon: 'none' }) }
function confirm(title, content) {
  return new Promise((resolve, reject) => wx.showModal({ title, content, success: result => resolve(result.confirm), fail: () => reject(new Error('无法显示确认窗口，请重试')) }))
}
async function pay(id, isActive = () => true) {
  const base = `${ordersUrl}/${id}`
  const prepared = await request({ url: `${base}/payment`, method: 'POST' })
  if (!isActive()) return { state: 'cancelled' }
  if (!prepared.data || prepared.data.mode !== 'MOCK') throw new Error('当前支付方式不可用')
  if (!await confirm('模拟支付', '仅本地/测试环境使用，不会真实扣款。确认模拟支付此订单？')) return { state: 'cancelled' }
  if (!isActive()) return { state: 'cancelled' }
  let failure
  try { await request({ url: `${base}/mock-pay`, method: 'POST' }) } catch (error) { failure = error }
  const refreshed = await request({ url: base })
  const detail = order(refreshed.data)
  if (String(detail.payStatus) === '1') return { state: 'success', order: detail }
  if (failure && !failure.network) throw failure
  return { state: 'pending', order: detail }
}
function upload(url, file) {
  if (!file || !file.path) return Promise.reject(new Error('请选择文件'))
  if (!Number.isFinite(Number(file.size)) || file.size <= 0 || file.size > 10 * 1024 * 1024) return Promise.reject(new Error('文件不能超过10MB'))
  return new Promise((resolve, reject) => wx.uploadFile({ url: `${getApp().globalData.baseUrl}${url}`, filePath: file.path, name: 'file', header: { Authorization: `Bearer ${wx.getStorageSync('token') || ''}` },
    success(response) {
      try {
        const body = JSON.parse(response.data)
        if (response.statusCode !== 200 || body.code !== 200) { reject(new Error(body.msg || '上传失败')); return }
        resolve(body.data || body)
      } catch (error) { reject(new Error('上传响应解析失败')) }
    }, fail: error => reject(new Error(error.errMsg || '上传失败')) }))
}
function download(id) {
  wx.showLoading({ title: '准备下载', mask: true })
  wx.downloadFile({ url: `${getApp().globalData.baseUrl}/miniapp/parent/material/download/${id}`, header: { Authorization: `Bearer ${wx.getStorageSync('token') || ''}` },
    success(response) {
      wx.hideLoading()
      if (response.statusCode !== 200) { message(new Error('下载失败，可能已退款或文件不可用')); return }
      const files = wx.getFileSystemManager()
      files.readFile({ filePath: response.tempFilePath, success(result) {
        const prefix = String.fromCharCode.apply(null, new Uint8Array(result.data).slice(0, 1024))
        if (prefix.includes('%PDF-')) {
          wx.openDocument({ filePath: response.tempFilePath, fileType: 'pdf', showMenu: true, fail: () => message(new Error('无法打开PDF文件')) })
          return
        }
        files.readFile({ filePath: response.tempFilePath, encoding: 'utf8', success(text) {
          let reason = '文件不可用，请联系管理员'
          try { reason = JSON.parse(text.data).msg || reason } catch (error) { /* Invalid payload is not a PDF. */ }
          message(new Error(reason))
        }, fail: () => message(new Error('无法读取下载结果，请重试')) })
      }, fail: () => message(new Error('无法读取下载文件，请重试')) })
    }, fail: () => { wx.hideLoading(); message(new Error('下载失败，请重试')) } })
}
module.exports = { request, ordersUrl, addressUrl, imageUrl, cents, calculateTotal, normalizeQuantity, product, order, message, confirm, pay, upload, download }
