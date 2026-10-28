const childContext = require('../../utils/child-context')
const t = require('../../utils/tuition')
const kinds = ['PARTIAL', 'WITHDRAWAL', 'SESSION']
Page(childContext.page({
  data: { loading: false, submitting: false, error: '', detail: null, rows: [], total: 0, pageNum: 1, reason: '', kindIndex: 0, kindOptions: ['费用调整', '退出此课程', '单次请假'], retrying: false, legacyIntent: false, targets: [], targetOptions: [], targetIndex: 0, targetsLoading: false, targetError: '' },
  onLoad(options={}) { this.accountId=options.accountId?String(options.accountId):''; this.id = String(options.id || ''); this._visit = 0 },
  onShow() { this._active = true; this._visit++; this.setData({ submitting: false }); return this.load(true) },
  onHide() { this._active = false; this._visit++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { return this.load(true).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (!this.data.loading && this.data.rows.length < this.data.total) return this.load(false) },
  input(e) { if (!this.data.submitting && !this.data.retrying && e.currentTarget.dataset.field === 'reason') this.setData({ reason: e.detail.value }) },
  async kind(e) {
    if (this.data.submitting || this.data.retrying) return
    const index = Number(e.detail.value)
    if (!kinds[index]) return
    this._targetSequence = (this._targetSequence || 0) + 1
    this.setData({ targetsLoading: false, kindIndex: index, targets: [], targetOptions: [], targetIndex: 0, targetError: '' })
    if (index === 2) await this.loadTargets()
  },
  target(e) { if (!this.data.submitting && !this.data.retrying) this.setData({ targetIndex: Number(e.detail.value) }) },
  async loadTargets() {
    if (!this._active || !this.id || !t.auth('parent')) return
    const visit = this._visit, sequence = this._targetSequence = (this._targetSequence || 0) + 1
    this.setData({ targetsLoading: true, targets: [], targetOptions: [], targetError: '' })
    try {
      const res = await t.request({parentScope:this._parentScope, url: `${t.parentUrl}/${encodeURIComponent(this.id)}/refund-targets`,...(this.accountId?{data:{accountId:this.accountId}}:{}) })
      if (!this._active || visit !== this._visit || sequence !== this._targetSequence) return
      const targets = ((res.data || {}).rows || []).filter(r => typeof r.targetId === 'string' && ['CLASS_SIGN_IN', 'LESSON_BOOKING'].includes(r.targetType) && r.label)
      this.setData({ targets, targetOptions: targets.map(r => r.label), targetIndex: 0, targetError: targets.length ? '' : '暂无可申请的已审核请假或已取消返次记录。' })
    } catch (error) { if (this._active && visit === this._visit && sequence === this._targetSequence) this.setData({ targetError: error.message }) }
    finally { if (this._active && visit === this._visit && sequence === this._targetSequence) this.setData({ targetsLoading: false }) }
  },
  async load(reset = true) {
    if (!t.auth('parent')) return
    const visit = this._visit, sequence = this._loadSequence = (this._loadSequence || 0) + 1, pageNum = reset ? 1 : this.data.pageNum + 1
    this.setData({ loading: true, error: '', ...(reset ? { detail: null, rows: [] } : {}) })
    try {
      if (this.id && reset) {
        const res = await t.request({parentScope:this._parentScope, url: `${t.parentUrl}/${encodeURIComponent(this.id)}`,...(this.accountId?{data:{accountId:this.accountId}}:{}) })
        if (!this._active || visit !== this._visit || sequence !== this._loadSequence) return
        const pending = t.savedIntent('refund', this.id)
        this.setData({ detail: t.bill(res.data), retrying: !!pending, legacyIntent: !!pending && Object.prototype.hasOwnProperty.call(pending, 'requestedAmount'), ...(pending ? { reason: pending.reason, kindIndex: Math.max(0, kinds.indexOf(pending.refundKind)) } : {}) })
        if (this.data.kindIndex === 2 && !pending) await this.loadTargets()
      }
      const res = await t.request({parentScope:this._parentScope, url: `${t.parentUrl}/refunds`, data: { pageNum, pageSize: 20,...(this.accountId?{accountId:this.accountId}:{}), ...(this.id ? { enrollmentId: this.id } : {}) } })
      if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ rows: (reset ? [] : this.data.rows).concat((res.rows || []).map(t.refund)), total: res.total || 0, pageNum })
    } catch (error) { if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ error: error.message }) }
    finally { if (this._active && visit === this._visit && sequence === this._loadSequence) this.setData({ loading: false }) }
  },
  async apply() {
    if (!this._active || this.data.submitting || this.data.targetsLoading || !t.auth('parent') || !this.id) return
    const visit = this._visit, detail = this.data.detail
    this.setData({ submitting: true, error: '' })
    try {
      const saved = t.savedIntent('refund', this.id)
      if (saved && Object.prototype.hasOwnProperty.call(saved, 'requestedAmount')) throw new Error('历史申请结果尚未确定，请联系教务核对原申请；原请求标识已保留。')
      if (!saved && (!detail || !detail.canRefund)) throw new Error('当前不能申请退费，或已有申请正在处理')
      const payment = detail && detail.payments.find(p => p.paymentStatus === 'SUCCESS' && p.channel !== 'FREE')
      if (!saved && !payment) throw new Error('没有可退款的成功收款')
      const refundKind = saved ? saved.refundKind : kinds[this.data.kindIndex]
      if (!refundKind) throw new Error('请选择退费范围')
      const reason = saved ? saved.reason : this.data.reason.trim()
      if (!reason) throw new Error('请填写退费原因')
      const target = !saved && refundKind === 'SESSION' ? this.data.targets[this.data.targetIndex] : null
      if (!saved && refundKind === 'SESSION' && (!target || this.data.targetError || typeof target.targetId !== 'string')) throw new Error('请查询并选择可申请的单次记录')
      if (!await t.confirm('提交退费申请', `${this.data.kindOptions[kinds.indexOf(refundKind)]}。教务确认金额，管理员登记线下实际退款后完成；成功后显示最终金额。${target ? '\n' + target.label : ''}`)) return
      if (!this._active || visit !== this._visit) return
      const payload = t.intent('refund', this.id, saved || { paymentId: String(payment.paymentId), refundKind, reason, ...(target ? { [target.targetType === 'CLASS_SIGN_IN' ? 'classSignInId' : 'lessonBookingId']: target.targetId } : {}) })
      this.setData({ retrying: true })
      const res = await t.request({parentScope:this._parentScope, url: `${t.parentUrl}/refunds`, method: 'POST', data: {...payload,...(this.accountId?{accountId:this.accountId}:{})} })
      if (!res.data || typeof res.data.refundId !== 'string' || !res.data.refundId.trim()) { const failure = new Error('申请结果尚未确定'); failure.uncertain = true; throw failure }
      t.clearIntent('refund', this.id)
      if (this._active && visit === this._visit) { this.setData({ reason: '', retrying: false, legacyIntent: false }); await this.load(true) }
    } catch (error) { if (this._active && visit === this._visit) {
      // Never replace an unresolved request key, including after a conflicting retry.
      this.setData({ error: error.uncertain ? '申请结果尚未确定，请重试原申请，或联系教务核对。' : error.message, retrying: !!t.savedIntent('refund', this.id) })
    } }
    finally { if (this._active && visit === this._visit) this.setData({ submitting: false }) }
  }
}, {route:'/pages/tuition-refund/tuition-refund',detail:false}))
