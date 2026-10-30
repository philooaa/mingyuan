const access = require('../../utils/access')
const t = require('../../utils/tuition')
const pad = value => String(value).padStart(2, '0')
const timeRanges = [24, 60, 60].map(count => Array.from({ length: count }, (_, i) => pad(i)))
function localTime(now) {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}
function billingStep(detail) {
  if (detail.activePayment) return 3
  if (detail.canPay) return 2
  if (detail.billingStatus === 'PENDING_PRICE') return 1
  if (detail.payments.some(p => p.paymentStatus === 'SUCCESS')) return 4
  return 0
}
function progress(detail) {
  let stage = 'WAIT_EDU_PRICE'
  if (detail.billingStatus === 'HISTORY_PENDING') stage = 'HISTORY'
  else if (detail.billingStatus === 'CLOSED' || ['2', '已取消', '3', '已拒绝'].includes(String(detail.enrollmentStatus))) stage = 'CLOSED'
  else if (Number(detail.successCount) > 0 || (detail.payments || []).some(p => p.paymentStatus === 'SUCCESS') || ['已支付', '部分退款', '已退款', '已减免', '1', '2'].includes(String(detail.payStatus))) stage = 'COMPLETED'
  else if (detail.activePayment || Number(detail.pendingPaymentCount) > 0) stage = 'WAIT_ADMIN'
  else if (detail.billingStatus === 'OPEN') stage = 'WAIT_EDU_COLLECT'
  const labels = { WAIT_EDU_PRICE: '待教务核价', WAIT_EDU_COLLECT: '教务已核价 · 待登记收款', WAIT_ADMIN: '教务已登记 · 待管理员确认', COMPLETED: '管理员已确认 · 已完成', CLOSED: '账单已关闭', HISTORY: '历史账单待核验' }
  return { ...detail, progressStage: stage, progressText: labels[stage], requiresConfirmation: stage === 'WAIT_ADMIN' }
}
Page({
  // Keep the same yyyy-MM-dd HH:mm:ss value used by the web form and API.
  data: { loading: false, submitting: false, error: '', rows: [], total: 0, pageNum: 1, searchText: '', filterIndex: 0, confirmationOnly: false, filters: ['全部账单', '待核价', '已核价', '历史待核验', '已关闭', '待管理员确认', '已完成'], detail: null, allowed: {}, price: '', discountAmount: '', discountReason: '', priceBaseText: '待填写', priceDiscountText: '¥0.00', pricePayableText: '待填写', modeIndex: 0, modes: ['REAL', 'MOCK'], modeLabels: ['真实线下收费', '本地模拟（仅本地可用）'], channelIndex: 0, channels: ['CASH', 'BANK_TRANSFER', 'WECHAT_OFFLINE'], channelLabels: ['现金', '银行转账', '微信线下收款'], payerName: '', providerTradeNo: '', paidTime: '', evidenceKey: '', evidenceName: '', closeReason: '' },
  onLoad(options) {
    this.id = String(options.id || ''); this._visit = 0
    this.setData({ quote: null, quoteLoading: false, quoteError: '', couponOptions: ['不使用优惠券'], availableCoupons: [], couponIndex: 0, selectedCouponId: null })
    const now = localTime(new Date())
    this.setData({ paidDate: '', paidClock: '', pickerDate: now.slice(0, 10), timeRanges, timeIndex: now.slice(11).split(':').map(Number) })
  },
  onShow() { this._active = true; this._visit++; this.setData({ submitting: false }); return this.load(true) },
  onHide() { this._active = false; this._visit++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { return this.load(true).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (!this.id && !this.data.loading && this.data.rows.length < this.data.total) return this.load(false) },
  paymentAdministrator() {
    const roles=getApp().globalData.roles || []
    return roles.some(r=>['admin','business_admin'].includes(r)) && !['education','teacher','parent'].includes(access.selected())
  },
  educationWorkspace() {
    return (getApp().globalData.roles || []).includes('education') && access.selected() === 'education'
  },
  permitted(action) {
    if (['price', 'collect'].includes(action) && !this.educationWorkspace()) return false
    return t.auth('staff') && t.can(`system:tuition:${action}`)
  },
  async load(reset = true) {
    if (!t.auth('staff')) { this.setData({ error: '仅授权管理员或教务可处理收费' }); return }
    const visit = this._visit, sequence = this._loadSequence = (this._loadSequence || 0) + 1, pageNum = reset ? 1 : this.data.pageNum + 1
    const selectedCouponId = this._couponToken === wx.getStorageSync('token') ? this.data.selectedCouponId : null
    this._couponToken = wx.getStorageSync('token')
    this._quoteRequest = (this._quoteRequest || 0) + 1
    this.setData({ loading: true, error: '', allowed: {}, quote: null, quoteLoading: false, quoteError: '', ...(reset ? { detail: null, rows: [] } : {}) })
    try {
      const info = await t.request({ url: '/getInfo' })
      if (!this._active || visit !== this._visit || sequence !== this._loadSequence) return
      getApp().globalData.permissions = info.permissions || []
      getApp().globalData.roles = info.roles || []
      if (!t.auth('staff')) throw new Error('收费角色权限已失效')
      const allowed = {}; ['list', 'query', 'price', 'collect', 'confirm'].forEach(p => { allowed[p] = t.can(`system:tuition:${p}`) }); allowed.confirm = allowed.confirm && this.paymentAdministrator(); allowed.close = allowed.collect && this.paymentAdministrator(); allowed.price = allowed.price && this.educationWorkspace(); allowed.collect = allowed.collect && this.educationWorkspace(); allowed.download = t.can('system:tuitionReceipt:download')
      allowed.refundList = t.can('system:tuitionRefund:list'); allowed.refundQuery = t.can('system:tuitionRefund:query')
      this.setData({ allowed, confirmationOnly: this.paymentAdministrator() })
      if (!allowed[this.id ? 'query' : 'list']) throw new Error('当前账号没有收费查询权限')
      if (this.id) {
        const res = await t.request({ url: `${t.adminUrl}/${encodeURIComponent(this.id)}` })
        if (!this._active || visit !== this._visit || sequence !== this._loadSequence) return
        const detail = progress(t.bill(res.data)), pending = t.savedIntent('admin-payment', this.id)
        this.setData({ billingStep: billingStep(detail), retryPayment: !!pending, allowed: { ...allowed, price: allowed.price && detail.billType !== 'CREDIT_PACKAGE' } })
        this.setData({ detail, price: detail.originalAmount || '', discountAmount: detail.discountAmount ? String(detail.discountAmount) : '', discountReason: detail.remark || '', modeIndex: detail.financeMode === 'MOCK' ? 1 : 0, channels: detail.financeMode === 'MOCK' && detail.mockAllowed ? ['MOCK'] : ['CASH', 'BANK_TRANSFER', 'WECHAT_OFFLINE'], channelLabels: detail.financeMode === 'MOCK' && detail.mockAllowed ? ['本地模拟'] : ['现金', '银行转账', '微信线下收款'], channelIndex: 0, ...(pending ? { payerName: pending.payerName, providerTradeNo: pending.providerTradeNo || '', paidTime: pending.paidTime || '', evidenceKey: pending.evidenceKey || '', evidenceName: pending.evidenceKey ? '已上传凭证（待确认绑定）' : '' } : {}) })
        this.pricePreview()
        if (pending) this.setPaidTime(pending.paidTime || '')
        this.setData({ selectedCouponId: detail.billType === 'CREDIT_PACKAGE' ? detail.userCouponId || null : selectedCouponId || null, couponIndex: 0, availableCoupons: [], couponOptions: ['不使用优惠券'] })
        if (detail.canPay && allowed.collect && !pending) await this.refreshQuote(true)
      } else {
        const statuses = ['', 'PENDING_PRICE', 'OPEN', 'HISTORY_PENDING', 'CLOSED']
        const res = await t.request({ url: `${t.adminUrl}/list`, data: { pageNum, pageSize: 20, billingStatus: statuses[this.data.filterIndex] || '', studentName: this.data.searchText.trim(), paymentStage: this.data.filterIndex === 5 ? 'WAIT_ADMIN' : this.data.filterIndex === 6 ? 'SUCCESS' : '' } })
        if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ rows: (reset ? [] : this.data.rows).concat((res.rows || []).map(row => progress(t.bill(row)))), total: res.total || 0, pageNum })
      }
    } catch (error) { if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ error: error.message }) }
    finally { if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ loading: false }) }
  },
  async refreshQuote(fromLoad) {
    if (!this._active || (this.data.submitting && fromLoad !== true) || this.data.retryPayment || !this.data.detail || !this.data.detail.canPay || !this.permitted('collect')) return
    const visit = this._visit, request = this._quoteRequest = (this._quoteRequest || 0) + 1
    const userCouponId = this.data.selectedCouponId || null
    this.setData({ quote: null, quoteLoading: true, quoteError: '' })
    try {
      const res = await t.request({ url: `${t.adminUrl}/${encodeURIComponent(this.id)}/quote`, method: 'POST', data: { userCouponId } })
      if (!this._active || visit !== this._visit || request !== this._quoteRequest) return
      const quote = res.data
      if (!quote || !['originalAmount', 'discountAmount', 'payableAmount'].every(k => /^\d+\.\d{2}$/.test(String(quote[k]))) || quote.financialVersion == null || String(quote.userCouponId || '') !== String(userCouponId || '')) throw new Error('报价无效，请刷新报价后重试')
      const availableCoupons = (quote.coupons || []).map(t.coupon)
      this.setData({ quote, availableCoupons, couponOptions: ['不使用优惠券'].concat(availableCoupons.map(c => `${c.couponName} · ${c.summaryText}`)), couponIndex: userCouponId ? availableCoupons.findIndex(c => String(c.userCouponId) === String(userCouponId)) + 1 : 0 })
    } catch (error) { if (this._active && visit === this._visit && request === this._quoteRequest) this.setData({ quote: null, quoteError: error.message || '试算失败，请刷新报价' }) }
    finally { if (this._active && visit === this._visit && request === this._quoteRequest) this.setData({ quoteLoading: false }) }
  },
  chooseCoupon(e) {
    if (this.data.submitting || this.data.retryPayment || !this.data.detail || this.data.detail.billType === 'CREDIT_PACKAGE' || !this.permitted('collect')) return
    const index = Number(e.detail.value)
    if (!Number.isInteger(index) || index < 0 || index > this.data.availableCoupons.length) return
    this.setData({ couponIndex: index, selectedCouponId: index ? String(this.data.availableCoupons[index - 1].userCouponId) : null })
    return this.refreshQuote()
  },
  pricePreview() {
    const detail = this.data.detail || {}
    const cartOrder = detail.billType === 'CART_ORDER'
    const parse = value => { const text = String(value === null || value === undefined ? '' : value).trim(); return /^\d{1,8}(\.\d{1,2})?$/.test(text) ? Number(text) : null }
    const base = cartOrder ? parse(detail.originalAmount) : parse(this.data.price)
    const discountText = String(this.data.discountAmount === null || this.data.discountAmount === undefined ? '' : this.data.discountAmount).trim()
    const discount = discountText === '' ? 0 : parse(discountText)
    const payable = base === null || discount === null ? null : Math.round((base - discount) * 100) / 100
    this.setData({ priceBaseText: base === null ? '待填写' : `¥${base.toFixed(2)}`, priceDiscountText: discount === null ? '待填写' : `¥${discount.toFixed(2)}`, pricePayableText: payable === null ? '待填写' : `¥${payable.toFixed(2)}` })
  },
  input(e) { const field = e.currentTarget.dataset.field; if (!this.data.submitting && ['searchText', 'price', 'discountAmount', 'discountReason', 'payerName', 'providerTradeNo', 'paidTime', 'closeReason'].includes(field)) { this.setData({ [field]: e.detail.value }); if (field === 'price' || field === 'discountAmount') this.pricePreview() } },
  setPaidTime(value) {
    this.setData({ paidTime: value, paidDate: value.slice(0, 10), paidClock: value.slice(11), ...(value ? { timeIndex: value.slice(11).split(':').map(Number) } : {}) })
  },
  paidDateChange(e) {
    if (this.data.submitting) return
    this.setPaidTime(`${e.detail.value} ${this.data.timeIndex.map(pad).join(':')}`)
  },
  paidClockChange(e) {
    if (this.data.submitting) return
    this.setPaidTime(`${this.data.paidDate || localTime(new Date()).slice(0, 10)} ${e.detail.value.map(pad).join(':')}`)
  },
  paidTimeNow() { if (!this.data.submitting) this.setPaidTime(localTime(new Date())) },
  mode(e) { if (!this.data.submitting) this.setData({ modeIndex: Number(e.detail.value) }) },
  channel(e) { if (!this.data.submitting) this.setData({ channelIndex: Number(e.detail.value) }) },
  filter(e) { this._visit++; this.setData({ filterIndex: Number(e.detail.value), rows: [] }); return this.load(true) },
  showPending() { if (!this.paymentAdministrator() || this.data.submitting) return; this.setData({ filterIndex: 5, rows: [] }); return this.search() },
  search() { this._visit++; return this.load(true) },
  detail(e) { if (this.data.allowed.query) wx.navigateTo({ url: `/pages/admin-tuition/admin-tuition?id=${encodeURIComponent(String(e.currentTarget.dataset.id))}` }) },
  refunds(e) {
    if (!this._active || this.data.submitting || !t.auth('staff')) return
    const id = e.currentTarget.dataset.id
    if (!t.can('system:tuitionRefund:' + (id ? 'query' : 'list'))) return
    wx.navigateTo({ url: '/pages/admin-tuition-refunds/admin-tuition-refunds' + (id ? '?id=' + encodeURIComponent(String(id)) : this.id ? '?enrollmentId=' + encodeURIComponent(this.id) : '') })
  },
  editPrice() {
    if (this.data.detail && this.data.detail.billType === 'CREDIT_PACKAGE') return
    if (!this.data.submitting && this.permitted('price') && this.data.detail && this.data.detail.canPay && !this.data.detail.payments.length && !this.data.retryPayment) this.setData({ billingStep: 1 })
  },
  nextStep(title, selector) {
    wx.showToast({ title, icon: 'none', duration: 2500 })
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector, duration: 300 })
  },
  async savePrice() {
    if (!this._active || this.data.submitting || !this.permitted('price') || !this.data.detail) return
    const detail = this.data.detail
    if (detail.billType === 'CREDIT_PACKAGE') return
    const visit = this._visit
    if (detail.billingStatus === 'HISTORY_PENDING') { t.message(new Error('历史账单请在管理网页凭证核验')); return }
    this.setData({ submitting: true })
    try {
      const cartOrder = detail.billType === 'CART_ORDER'
      // 购物车订单原价由订单内课程总价决定，教务只填优惠；普通课程由教务核定原价后再填优惠。
      const originalAmount = cartOrder ? t.amount(detail.originalAmount) : t.amount(this.data.price)
      const discountAmount = String(this.data.discountAmount === null || this.data.discountAmount === undefined ? '' : this.data.discountAmount).trim() === '' ? '0.00' : t.amount(this.data.discountAmount)
      const discountReason = String(this.data.discountReason || '').trim()
      if (t.cents(discountAmount) > t.cents(originalAmount)) throw new Error(`优惠金额不能超过${cartOrder ? '课程总价' : '课程原价'} ${t.money(originalAmount)}`)
      // 优惠金额被改动时必须说明原因；沿用原优惠（金额未变）可保留账单上已登记的原因。
      const stored = Number(detail.discountAmount)
      const unchanged = Number.isFinite(stored) && Math.round(stored * 100) === t.cents(discountAmount)
      if (t.cents(discountAmount) > 0 && !discountReason && !unchanged) throw new Error('请填写优惠原因')
      const payableAmount = ((t.cents(originalAmount) - t.cents(discountAmount)) / 100).toFixed(2)
      const financeMode = this.data.modes[this.data.modeIndex]
      const summary = t.cents(discountAmount) > 0 ? `${t.money(originalAmount)} − 优惠 ${t.money(discountAmount)} = 实际应收 ${t.money(payableAmount)}` : t.money(originalAmount)
      const data = { discountAmount, discountReason, financialVersion: detail.financialVersion }
      if (!cartOrder) { data.originalAmount = originalAmount; data.financeMode = financeMode }
      if (!await t.confirm('核定账单', `${summary} · ${financeMode === 'MOCK' ? '本地模拟，不涉及真实资金' : '真实线下收费'}，确认核价？`) || !this._active || visit !== this._visit) return
      await t.request({ url: `${t.adminUrl}/${encodeURIComponent(this.id)}/price`, method: 'PUT', data })
      if (this._active && visit === this._visit) {
        await this.load()
        if (this._active && visit === this._visit && !this.data.error) this.nextStep('核价成功，请登记实际收款', '#collection-form')
      }
    } catch (error) { if (this._active && visit === this._visit) { this.setData({ error: error.message }); t.message(error) } }
    finally { if (this._active && visit === this._visit) this.setData({ submitting: false }) }
  },
  chooseEvidence() {
    if (this.data.submitting || !this.permitted('collect')) return
    const token = wx.getStorageSync('token'), id = this.id
    const current = () => token === wx.getStorageSync('token') && id === this.id
    const fail = error => {
      if (current() && !/cancel/i.test(error.errMsg || '')) t.message(new Error('无法选择凭证，请检查相册／相机权限或稍后重试'))
    }
    const upload = async file => {
      if (!file || !current() || this.data.submitting || !this.permitted('collect')) return
      this.setData({ submitting: true })
      try {
        const key = await t.uploadEvidence(file, 'PAYMENT', id)
        if (current()) this.setData({ evidenceKey: key, evidenceName: file.name || '凭证已上传' })
      } catch (error) { if (current()) t.message(error) }
      finally { if (current()) this.setData({ submitting: false }) }
    }
    wx.showActionSheet({ itemList: ['从相册选择图片', '拍照上传', '从微信聊天选择PDF／图片'], fail, success: choice => {
      if (!current() || this.data.submitting || !this.permitted('collect')) return
      if (choice.tapIndex === 2) {
        wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'jpg', 'jpeg', 'png'], fail, success: res => upload(res.tempFiles[0]) })
      } else if (choice.tapIndex === 0 || choice.tapIndex === 1) {
        wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: [choice.tapIndex === 0 ? 'album' : 'camera'], sizeType: ['compressed'], fail, success: res => {
          const file = res.tempFiles[0]
          if (file) upload({ path: file.tempFilePath, size: file.size, name: choice.tapIndex === 0 ? '相册图片凭证' : '拍照凭证' })
        } })
      }
    } })
  },
  async collect() {
    if (!this._active || this.data.submitting || !this.permitted('collect') || !this.data.detail) return
    const visit = this._visit, detail = this.data.detail
    this.setData({ submitting: true })
    try {
      const saved = t.savedIntent('admin-payment', this.id)
      if (!saved && !detail.canPay) throw new Error('当前账单不能登记新收款，请核对现有记录')
      const quote = this.data.quote
      if (!saved && (this.data.quoteLoading || !quote || this.data.quoteError)) throw new Error('请先完成优惠券金额试算，失败时请刷新报价')
      const channel = (saved ? saved.expectedPayableAmount : quote.payableAmount) === '0.00' ? 'FREE' : this.data.channels[this.data.channelIndex]
      if (!saved && !this.data.payerName.trim()) throw new Error('请填写实际付款人')
      if (!saved && !['MOCK', 'FREE'].includes(channel) && (!this.data.evidenceKey || !this.data.paidTime.trim())) throw new Error('线下登记须上传凭证并填写实际收款时间')
      if (!saved && ['BANK_TRANSFER', 'WECHAT_OFFLINE'].includes(channel) && !this.data.providerTradeNo.trim()) throw new Error('转账须填写完整流水参考号')
      const body = saved || { channel, userCouponId: quote.userCouponId || null, expectedPayableAmount: quote.payableAmount, financialVersion: quote.financialVersion, payerName: this.data.payerName.trim(), providerTradeNo: this.data.providerTradeNo.trim() || null, evidenceKey: this.data.evidenceKey || null, paidTime: this.data.paidTime.trim() || null }
      if (!await t.confirm('登记收款', `${t.money(body.expectedPayableAmount)} · ${t.status(body.channel)}。登记后须核对确认。`) || !this._active || visit !== this._visit) return
      await t.request({ url: `${t.adminUrl}/${encodeURIComponent(this.id)}/payments`, method: 'POST', data: t.intent('admin-payment', this.id, body) })
      t.clearIntent('admin-payment', this.id)
      if (this._active && visit === this._visit) {
        await this.load()
        if (this._active && visit === this._visit && !this.data.error) this.nextStep('已登记，等待管理员确认', '#payment-records')
      }
    } catch (error) {
      if (error.applicationRejected) t.clearIntent('admin-payment', this.id)
      if (this._active && visit === this._visit) this.setData({ error: error.uncertain ? '登记结果尚未确定，请保留凭证并重试原登记。' : error.message, retryPayment: !!t.savedIntent('admin-payment', this.id), ...(error.applicationRejected ? { quote: null, quoteError: '登记被拒绝，请刷新报价后重新核对' } : {}) })
    }
    finally { if (this._active && visit === this._visit) this.setData({ submitting: false }) }
  },
  async paymentAction(e) {
    if (!this._active || this.data.submitting || !this.paymentAdministrator()) return
    const action = e.currentTarget.dataset.action, id = String(e.currentTarget.dataset.id), visit = this._visit
    if (!['confirm', 'close'].includes(action) || !t.auth('staff') || !t.can(`system:tuition:${action === 'confirm' ? 'confirm' : 'collect'}`)) return
    const payment = this.data.detail.payments.find(p => String(p.paymentId) === id)
    if (!payment || !['PENDING', 'UNKNOWN'].includes(payment.paymentStatus)) return
    const reason = this.data.closeReason.trim()
    if (action === 'close' && !reason) { t.message(new Error('请填写关闭原因；结果未知时请先人工核对')); return }
    this.setData({ submitting: true })
    try {
      const data = action === 'close' ? { reason } : { evidenceKey: this.data.evidenceKey || payment.evidenceKey || null, providerTradeNo: this.data.providerTradeNo.trim() || payment.providerTradeNo || null, paidTime: this.data.paidTime.trim() || payment.paidTime || null }
      if (await t.confirm(action === 'confirm' ? '确认实际收款' : '关闭付款', action === 'confirm' ? `请核对 ${payment.amountText}、实际时间与凭证。确认后形成资金记录和收据。` : `确认此付款没有成功收款后关闭：${reason}`) && this._active && visit === this._visit && this.paymentAdministrator()) {
        await t.request({ url: `${t.adminUrl}/payments/${encodeURIComponent(id)}/${action}`, method: 'POST', data })
        if (this._active && visit === this._visit) {
          await this.load()
          if (this._active && visit === this._visit && !this.data.error) this.nextStep(action === 'confirm' ? '收款已确认，请查看收据' : '付款记录已关闭', action === 'confirm' ? '#receipt-records' : '#collection-form')
        }
      }
    } catch (error) { if (this._active && visit === this._visit) t.message(error) }
    finally { if (this._active && visit === this._visit) this.setData({ submitting: false }) }
  },
  async receipt(e) { if (!t.can('system:tuitionReceipt:download')) return; try { await t.downloadReceipt(String(e.currentTarget.dataset.id), true) } catch (error) { t.message(error) } }
})
