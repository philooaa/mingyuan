const shop = require('../../utils/material-shop')
Page({
  data: { loading: false, busy: false, error: '', detail: null, paymentText: '' },
  onLoad(options) { this.orderId = options.id; this._sequence = 0; this._operation = 0 },
  onShow() { this._hidden = false; this.setData({ busy: false, paymentText: '' }); return this.loadDetail() },
  onHide() { this._hidden = true; this._sequence++; this._operation++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { this.loadDetail().finally(() => wx.stopPullDownRefresh()) },
  async loadDetail() {
    const sequence = ++this._sequence
    this.setData({ loading: true, error: '', detail: null })
    try {
      if (!this.orderId) throw new Error('订单不存在')
      const response = await shop.request({ url: `${shop.ordersUrl}/${this.orderId}` })
      if (sequence === this._sequence && !this._hidden) this.setData({ detail: shop.order(response.data) })
    } catch (error) { if (sequence === this._sequence && !this._hidden) this.setData({ error: error.message }) }
    finally { if (sequence === this._sequence && !this._hidden) this.setData({ loading: false }) }
  },
  async pay() {
    if (this.data.busy || !this.data.detail || !this.data.detail.canPay) return
    const operation = ++this._operation
    this.setData({ busy: true, paymentText: this.data.detail.payment && this.data.detail.payment.channel === 'FREE' ? '准备免费领取…' : '准备模拟支付…' })
    try {
      const result = await shop.pay(this.orderId, () => !this._hidden && operation === this._operation)
      if (this._hidden || operation !== this._operation) return
      if (result.order) this.setData({ detail: result.order })
      this.setData({ paymentText: { success: result.free ? '免费领取成功' : '模拟支付成功，未真实扣款', cancelled: '已取消操作，订单仍待处理', pending: '支付结果处理中，请刷新核对订单状态' }[result.state] })
    } catch (error) {
      if (!this._hidden && operation === this._operation) { this.setData({ paymentText: error.network ? '结果暂未确认，请刷新订单状态' : `订单处理失败：${error.message}` }); await this.loadDetail() }
    } finally { if (!this._hidden && operation === this._operation) this.setData({ busy: false }) }
  },
  async action(e) {
    const action = e.currentTarget.dataset.action
    if (this.data.busy || !['cancel', 'receive'].includes(action)) return
    const operation = ++this._operation
    this.setData({ busy: true })
    try {
      if (!await shop.confirm(action === 'cancel' ? '取消订单' : '确认收货', action === 'cancel' ? '确认取消此未支付订单？' : '确认已收到全部纸质资料？')) return
      if (this._hidden || operation !== this._operation) return
      const response = await shop.request({ url: `${shop.ordersUrl}/${this.orderId}/${action}`, method: 'POST' })
      if (!this._hidden && operation === this._operation) this.setData({ detail: shop.order(response.data) })
    } catch (error) { if (!this._hidden && operation === this._operation) { shop.message(error); await this.loadDetail() } }
    finally { if (!this._hidden && operation === this._operation) this.setData({ busy: false }) }
  },
  download() { if (this.data.detail && this.data.detail.canDownload) shop.download(this.orderId) }
})
