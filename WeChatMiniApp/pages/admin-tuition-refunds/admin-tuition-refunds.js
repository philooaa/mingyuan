const t = require('../../utils/tuition')
const base = t.adminUrl + '/refunds'
function display(row) {
  return { ...t.refund(row), approvedText: row.approvedAmount == null ? '待教务核定' : t.money(row.approvedAmount), receivedText: t.money(row.receivedAmount == null ? row.paymentAmount : row.receivedAmount), availableText: t.money(row.availableForThisRefund), offline: row.refundWorkflow === 'OFFLINE_TWO_STAGE' }
}
Page({
  data: { loading: false, submitting: false, error: '', rows: [], total: 0, pageNum: 1, searchText: '', filterIndex: 0, filters: ['全部退费', '待教务确认', '待管理员退款', '已退款', '已驳回'], detail: null, allowed: {}, approvedAmount: '', reviewRemark: '' },
  onLoad(options = {}) { this.id = String(options.id || ''); this.enrollmentId = String(options.enrollmentId || ''); this._visit = 0; this.setData({ isDetail: !!this.id }) },
  onShow() {
    this._active = true; this._visit++
    this._token = wx.getStorageSync('token'); this._user = String((getApp().globalData.userInfo || {}).userId || '')
    this.setData({ submitting: false, approvedAmount: '', reviewRemark: '' })
    return this.load()
  },
  onHide() { this._active = false; this._visit++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (!this.id && !this.data.loading && this.data.rows.length < this.data.total) return this.load(false) },
  current(visit) { return this._active && visit === this._visit && this._token === wx.getStorageSync('token') && this._user === String((getApp().globalData.userInfo || {}).userId || '') },
  permitted(action, adminOnly = false) {
    return (getApp().globalData.roles || []).some(r => r === 'admin' || !adminOnly && r === 'education') && t.can('system:tuitionRefund:' + action)
  },
  async permissions(visit) {
    const info = await t.request({ url: '/getInfo' })
    if (!this.current(visit)) return false
    Object.assign(getApp().globalData, { roles: info.roles || [], permissions: info.permissions || [] })
    const allowed = {}; ['list', 'query', 'review'].forEach(p => { allowed[p] = this.permitted(p) }); allowed.markRefunded = this.permitted('markRefunded', true); allowed.adminReject = this.permitted('review', true)
    this.setData({ allowed })
    return true
  },
  async load(reset = true) {
    if (this.data.submitting) return
    if (!t.auth('staff')) { this.setData({ error: '仅授权教务或管理员可处理退费', allowed: {}, detail: null, rows: [] }); return }
    const visit = this._visit, sequence = this._sequence = (this._sequence || 0) + 1, pageNum = reset ? 1 : this.data.pageNum + 1
    this.setData({ loading: true, error: '', allowed: {}, ...(reset ? { rows: [], detail: null } : {}) })
    try {
      if (!await this.permissions(visit) || sequence !== this._sequence) return
      if (!this.permitted(this.id ? 'query' : 'list')) throw new Error('当前账号没有退费' + (this.id ? '详情' : '列表') + '权限，请在网页角色管理中授权')
      const res = await t.request({ url: this.id ? base + '/' + encodeURIComponent(this.id) : base + '/list', ...(this.id ? {} : { data: { pageNum, pageSize: 20, studentName: this.data.searchText.trim(), refundStatus: ['', 'WAIT_EDU', 'WAIT_ADMIN', 'SUCCESS', 'REJECTED'][this.data.filterIndex], ...(this.enrollmentId ? { enrollmentId: this.enrollmentId } : {}) } }) })
      if (!this.current(visit) || sequence !== this._sequence) return
      if (this.id) {
        if (!res.data || String(res.data.refundId) !== this.id) throw new Error('退费记录未找到，请刷新后重试')
        this.setData({ detail: display(res.data) })
      } else this.setData({ rows: (reset ? [] : this.data.rows).concat((res.rows || []).map(display)), total: res.total || 0, pageNum })
    } catch (error) { if (this.current(visit) && sequence === this._sequence) this.setData({ error: error.message }) }
    finally { if (this.current(visit) && sequence === this._sequence) this.setData({ loading: false }) }
  },
  input(e) { const field = e.currentTarget.dataset.field; if (!this.data.submitting && ['searchText', 'approvedAmount', 'reviewRemark'].includes(field)) this.setData({ [field]: e.detail.value }) },
  filter(e) { if (!this.data.submitting) { this.setData({ filterIndex: Number(e.detail.value) }); return this.load() } },
  search() { return this.load() },
  openDetail(e) {
    if (!this.data.submitting && this.permitted('query')) wx.navigateTo({ url: '/pages/admin-tuition-refunds/admin-tuition-refunds?id=' + encodeURIComponent(String(e.currentTarget.dataset.id)) })
  },
  review(e) { const action = e.currentTarget.dataset.action; if (['approve', 'reject'].includes(action)) return this.submit(action) },
  markRefunded() { return this.submit('mark-refunded') },
  async submit(action) {
    const detail = this.data.detail, visit = this._visit
    if (!this.current(visit) || this.data.submitting || this.data.loading || !detail) return
    this.setData({ submitting: true, error: '' })
    let refresh = false
    try {
      if (!await this.permissions(visit)) return
      if (!detail.offline) throw new Error('历史退费请在管理网页核对，不可直接转入新流程')
      if (detail.version == null || !/^\d+$/.test(String(detail.version))) throw new Error('记录版本缺失，请刷新后重试')
      const marking = action === 'mark-refunded', adminReject = action === 'reject' && detail.refundStatus === 'WAIT_ADMIN'
      const permission = marking ? 'markRefunded' : 'review', adminOnly = marking || adminReject
      if (!this.permitted(permission, adminOnly)) throw new Error('当前账号没有此步骤的退费处理权限')
      if (marking ? detail.refundStatus !== 'WAIT_ADMIN' : adminReject ? false : detail.refundStatus !== 'WAIT_EDU') throw new Error('当前阶段不能执行此操作，请刷新退费状态')
      let body, content
      if (marking) {
        if (t.cents(detail.approvedAmount) <= 0) throw new Error('教务核定金额无效，请核对')
        body = { version: detail.version }
        content = '确认已在线下实际退还 ' + t.money(detail.approvedAmount) + '？登记后流程结束，无需家长再确认。请勿在未退款时点击。'
      } else {
        const approved = action === 'approve', remark = this.data.reviewRemark.trim()
        if (remark.length > 500 || !approved && !remark) throw new Error(remark.length > 500 ? '审核说明不能超过500字' : '请填写驳回原因')
        const approvedAmount = approved ? t.amount(this.data.approvedAmount) : null
        if (approved && (t.cents(approvedAmount) <= 0 || detail.availableForThisRefund != null && t.cents(approvedAmount) > t.cents(detail.availableForThisRefund))) throw new Error('核定金额须大于0且不能超过本申请可退上限')
        body = { approved, approvedAmount, reviewRemark: remark, version: detail.version }
        content = approved ? '确认核定退款金额 ' + t.money(approvedAmount) + ' 并提交管理员？本步骤不会登记已退款。' : '确认驳回本申请？原因：' + remark
      }
      if (!await t.confirm(marking ? '登记已退款' : action === 'approve' ? '教务确认金额并已阅' : '驳回退费申请', content) || !this.current(visit) || !this.permitted(permission, adminOnly)) return
      refresh = true
      const res = await t.request({ url: base + '/' + encodeURIComponent(this.id) + (marking ? '/mark-refunded' : '/review'), method: 'POST', data: body })
      if (!res.data || String(res.data.refundId) !== this.id) throw new Error('处理结果尚未确定，请刷新核对，勿重复操作')
      if (this.current(visit)) {
        this.setData({ detail: display(res.data), approvedAmount: '', reviewRemark: '' })
        wx.showToast({ title: marking ? '已登记退款' : action === 'approve' ? '已提交管理员' : '已驳回', icon: 'none' })
      }
    } catch (error) {
      if (this.current(visit)) this.setData({ error: error.uncertain ? '处理结果尚未确定，请刷新核对最新状态，勿重复操作。' : error.message })
    } finally {
      if (this.current(visit)) {
        this.setData({ submitting: false })
        if (refresh) {
          const message = this.data.error
          await this.load()
          if (message && this.current(visit)) this.setData({ error: message })
        }
      }
    }
  }
})
