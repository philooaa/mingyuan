const childContext = require('../../utils/child-context')
const t = require('../../utils/tuition')
Page(childContext.page({
  data: { loading: false, error: '', rows: [], total: 0, pageNum: 1, filterIndex: 0, filters: ['全部账单', '待核价', '待缴费', '已缴费', '历史待核验'] },
  onLoad(options={}) {this.accountId=options.accountId?String(options.accountId):''; this._visit = 0 },
  onShow() { this._active = true; this._visit++; return this.load(true) },
  onHide() { this._active = false; this._visit++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { return this.load(true).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (!this.data.loading && this.data.rows.length < this.data.total) return this.load(false) },
  async load(reset = true) {
    if (!t.auth('parent')) return
    const visit = this._visit, sequence = this._loadSequence = (this._loadSequence || 0) + 1, pageNum = reset ? 1 : this.data.pageNum + 1
    this.setData({ loading: true, error: '' })
    const filters = [{}, { billingStatus: 'PENDING_PRICE' }, { billingStatus: 'OPEN', payStatus: '未支付' }, { payStatus: '已支付' }, { billingStatus: 'HISTORY_PENDING' }]
    try {
      const res = await t.request({parentScope:this._parentScope, url: t.parentUrl, data: { pageNum, pageSize: 20,...(this.accountId?{accountId:this.accountId}:{}), ...filters[this.data.filterIndex] } })
      if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ rows: (reset ? [] : this.data.rows).concat((res.rows || []).map(t.bill)), total: res.total || 0, pageNum })
    } catch (error) { if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ error: error.message }) }
    finally { if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ loading: false }) }
  },
  filter(e) { this._visit++; this.setData({ filterIndex: Number(e.detail.value), rows: [] }); return this.load(true) },
  detail(e) { wx.navigateTo({ url: `/pages/tuition-detail/tuition-detail?id=${encodeURIComponent(String(e.currentTarget.dataset.id))}${this.accountId?'&accountId='+encodeURIComponent(this.accountId):this._parentScope?'&unlinked=1':''}` }) },
  receipts() { wx.navigateTo({ url: '/pages/tuition-receipt/tuition-receipt'+(this.accountId?'?accountId='+encodeURIComponent(this.accountId):this._parentScope?'?unlinked=1':'') }) },
  refunds() { wx.navigateTo({ url: '/pages/tuition-refund/tuition-refund'+(this.accountId?'?accountId='+encodeURIComponent(this.accountId):this._parentScope?'?unlinked=1':'') }) },
  coupons() { wx.navigateTo({ url: '/pages/my-coupons/my-coupons' }) }
}, {route:'/pages/tuition-list/tuition-list',detail:false}))
