const context = require('../../utils/child-context')
const t = require('../../utils/tuition')
const url = '/miniapp/parent/lesson-packages'
Page(context.page({
  data: { loading: false, quoting: false, submitting: false, error: '', child: null, catalog: null, quote: null, coupons: [], couponOptions: ['不使用优惠券'], couponIndex: 0, userCouponId: null, packageCount: 1, retrying: false, pending: null, showBreakdown:false,
    packages:[{count:1,credits:15,discount:'95折',label:'轻松起步'},{count:2,credits:30,discount:'92折',label:'稳步进阶'},{count:3,credits:45,discount:'9折',label:'长期成长'}] },
  onLoad(options = {}) {
    this.accountId = String(options.accountId || '')
    this._scope = context.snapshot()
    this._intentId = context.owner() + ':' + this.accountId
    this._visit = 0
  },
  onShow() {
    this._active = true; this._visit++
    this.setData({ submitting: !!this._posting })
    return this.load()
  },
  onHide() { this._active = false; this._visit++; this._quoteSequence = (this._quoteSequence || 0) + 1; this.setData({ quote: null, quoting: false }) },
  onUnload() { this.onHide() },
  current() { return this._active && context.current(this._scope) },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
  async load() {
    if (!this.current() || !t.auth('parent')) return
    if (!/^[1-9]\d*$/.test(this.accountId)) { this.setData({ error: '请从孩子详情进入' }); return }
    const visit = this._visit, sequence = this._loadSequence = (this._loadSequence || 0) + 1
    this._quoteSequence = (this._quoteSequence || 0) + 1
    const pending = t.savedIntent('package', this._intentId)
    this.setData({ loading: true, quote: null, error: '', retrying: !!pending, pending, ...(pending ? { packageCount: pending.packageCount, userCouponId: pending.userCouponId } : {}) })
    try {
      const [catalog, child] = await Promise.all([t.request({ url: url + '/catalog' }), t.request({ url: '/miniapp/parent/account/children/' + encodeURIComponent(this.accountId) })])
      if (!this.current() || visit !== this._visit || sequence !== this._loadSequence) return
      if (!child.data || !child.data.child || String(child.data.child.accountId) !== this.accountId) throw new Error('孩子信息已变化，请重新进入')
      this.setData({ catalog: catalog.data, child: child.data.child })
      if (!pending) await this.refreshQuote()
    } catch (error) { if (this.current() && visit === this._visit && sequence === this._loadSequence) this.setData({ error: error.message, quote: null }) }
    finally { if (this.current() && visit === this._visit && sequence === this._loadSequence) this.setData({ loading: false }) }
  },
  chooseCount(e) { return this.changeCount(String(e.currentTarget.dataset.count)) },
  adjustCount(e) {
    const delta=Number(e.currentTarget.dataset.delta), count=Number(this.data.packageCount)
    if(![-1,1].includes(delta) || !Number.isInteger(count) || count+delta<1 || count+delta>100) return
    return this.changeCount(String(count+delta))
  },
  toggleBreakdown() {this.setData({showBreakdown:!this.data.showBreakdown})},
  inputCount(e) { return this.changeCount(e.detail.value) },
  changeCount(value) {
    if (!this.current() || this.data.retrying || this.data.submitting) return
    this.setData({ packageCount: value, userCouponId: null, couponIndex: 0, quote: null })
    return this.refreshQuote()
  },
  chooseCoupon(e) {
    if (!this.current() || this.data.retrying || this.data.submitting) return
    const index = Number(e.detail.value), coupon = this.data.coupons[index - 1]
    if (!Number.isInteger(index) || index < 0 || index > this.data.coupons.length) return
    this.setData({ couponIndex: index, userCouponId: coupon ? String(coupon.userCouponId) : null, quote: null })
    return this.refreshQuote()
  },
  async refreshQuote() {
    if (!this.current() || this.data.retrying || this.data.submitting || !this.data.child || !t.auth('parent')) return
    const visit = this._visit, sequence = this._quoteSequence = (this._quoteSequence || 0) + 1
    this.setData({ quote: null, quoting: false, error: '' })
    const value = String(this.data.packageCount)
    if (!/^[1-9]\d*$/.test(value) || Number(value) > 100) { this.setData({ error: '购买包数须为1至100的整数' }); return }
    const body = { accountId: this.accountId, packageCount: Number(value), userCouponId: this.data.userCouponId }
    this.setData({ quoting: true })
    try {
      const res = await t.request({ url: url + '/quote', method: 'POST', data: body })
      if (!this.current() || visit !== this._visit || sequence !== this._quoteSequence) return
      const q = res.data
      if (!q || String(q.accountId) !== this.accountId || q.packageCount !== body.packageCount || !q.quoteHash || q.payableAmount == null) throw new Error('报价尚未确定，请刷新重试')
      const coupons = (q.coupons || []).map(t.coupon)
      this.setData({ quote: q, coupons, couponOptions: ['不使用优惠券'].concat(coupons.map(c => c.couponName + ' · ' + c.summaryText)) })
    } catch (error) { if (this.current() && visit === this._visit && sequence === this._quoteSequence) this.setData({ error: error.message, quote: null }) }
    finally { if (this.current() && visit === this._visit && sequence === this._quoteSequence) this.setData({ quoting: false }) }
  },
  async buy() {
    if (!this.current() || this._posting || this.data.submitting || !t.auth('parent')) return
    const saved = t.savedIntent('package', this._intentId), q = this.data.quote
    if (!saved && (this.data.loading || this.data.quoting || !q)) return
    const visit = this._visit
    let payload, completed = false
    this._posting = true; this.setData({ submitting: true, error: '' })
    try {
      payload = t.intent('package', this._intentId, saved || { accountId: this.accountId, packageCount: q.packageCount, userCouponId: this.data.userCouponId, quoteHash: q.quoteHash, expectedPayableAmount: q.payableAmount })
      this.setData({ retrying: true, pending: payload })
      const res = await t.request({ url: url + '/orders', method: 'POST', data: payload })
      if (!res.data || !/^[1-9]\d*$/.test(String(res.data.enrollmentId || ''))) { const error = new Error('购买结果尚未确定'); error.uncertain = true; throw error }
      if (!context.current(this._scope)) return
      t.clearIntent('package', this._intentId)
      completed = true
      if (this.current() && visit === this._visit) {
        this.setData({ retrying: false, pending: null, quote: null })
        wx.navigateTo({ url: '/pages/tuition-detail/tuition-detail?id=' + encodeURIComponent(res.data.enrollmentId) + '&accountId=' + encodeURIComponent(this.accountId) })
      }
    } catch (error) {
      if (!saved && error.applicationRejected && context.current(this._scope)) t.clearIntent('package', this._intentId)
      if (this.current() && visit === this._visit) {
        const pending = t.savedIntent('package', this._intentId)
        this.setData({ retrying: !!pending, pending, quote: null, error: pending ? '购买结果尚未确定，请重试原购买或联系教务核对。原包数、优惠券和请求标识已保留。' : error.message })
      }
    } finally {
      this._posting = false
      if (this.current()) {
        this.setData({ submitting: false })
        if (completed && visit !== this._visit) await this.load()
      }
    }
  }
}, { route: '/pages/lesson-packages/lesson-packages', detail: true }))
