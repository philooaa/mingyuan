const childContext = require('../../utils/child-context')
const t = require('../../utils/tuition')
Page(childContext.page({
  data: { loading: false, submitting: false, error: '', detail: null, coupons: [], paymentUncertain: false },
  onLoad(options={}) { this.accountId=options.accountId?String(options.accountId):''; this.id = String(options.id || ''); this._visit = 0 },
  onShow() { this._active = true; this._visit++; this.setData({ submitting: false }); return this.load() },
  onHide() { this._active = false; this._visit++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
  async load() {
    if (!t.auth('parent') || !this.id) return
    const visit = this._visit, sequence = this._loadSequence = (this._loadSequence || 0) + 1
    const active = () => this._active && visit === this._visit && sequence === this._loadSequence
    this.setData({ loading: true, error: '', detail: null })
    try {
      const res = await t.request({parentScope:this._parentScope, url: `${t.parentUrl}/${encodeURIComponent(this.id)}`,...(this.accountId?{data:{accountId:this.accountId}}:{}) })
      if (!active()) return
      // A previous unknown payment must be checked by staff; do not discard its key.
      this.setData({ detail: t.bill(res.data), coupons: (res.data.coupons || []).map(t.coupon), paymentUncertain: !!t.savedIntent('payment', this.id) })
    } catch (error) { if (active()) this.setData({ error: error.message }) }
    finally { if (active()) this.setData({ loading: false }) }
  },
  async cancelEnrollment() {
    if (!this._active || this.data.submitting || !t.auth('parent')) return
    const detail = this.data.detail, visit = this._visit
    if (!detail || !detail.canCancel || t.savedIntent('payment', this.id)) { t.message(new Error('已有收款、待核验或结果未知，请先联系教务核对或申请退费')); return }
    this.setData({ submitting: true })
    const packageOrder = detail.billType === 'CREDIT_PACKAGE'
    try {
      if (await t.confirm(packageOrder ? '取消未收款课包订单' : '取消未缴费报名', packageOrder ? '取消此订单并释放占用的优惠券？后端将再次核对收款状态。' : '取消此报名并释放课程名额？后端将再次核对收款状态。') && this._active && visit === this._visit && childContext.current(this._childTicket)) {
        await t.request(packageOrder ? { url: `/miniapp/parent/lesson-packages/orders/${encodeURIComponent(this.id)}/cancel`, method: 'POST', data: { accountId: this.accountId } } : { parentScope: this._parentScope, url: `/miniapp/parent/enrollment/${encodeURIComponent(this.id)}`, method: 'DELETE' })
        if (this._active && visit === this._visit) await this.load()
      }
    } catch (error) { if (this._active && visit === this._visit) t.message(error) }
    finally { if (this._active && visit === this._visit) this.setData({ submitting: false }) }
  },
  receipts() { wx.navigateTo({ url: `/pages/tuition-receipt/tuition-receipt?id=${encodeURIComponent(this.id)}${this.accountId?'&accountId='+encodeURIComponent(this.accountId):this._parentScope?'&unlinked=1':''}` }) },
  refunds() { if (this.data.detail && this.data.detail.billType === 'CREDIT_PACKAGE') return; wx.navigateTo({ url: `/pages/tuition-refund/tuition-refund?id=${encodeURIComponent(this.id)}${this.accountId?'&accountId='+encodeURIComponent(this.accountId):this._parentScope?'&unlinked=1':''}` }) }
}, {route:'/pages/tuition-detail/tuition-detail',detail:true}))
