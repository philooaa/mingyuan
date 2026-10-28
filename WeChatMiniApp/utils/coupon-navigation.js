const { request } = require('./request')
const referral = require('./referral')
const coupons = require('./coupon-management')
const display = require('./coupon-display')

const sections = {
  issue: ['发放优惠券', '选择已启用模板，再选择接收家长'],
  templates: ['券模板', '设置金额、适用学科与有效期'],
  grants: ['发放记录', '普通券与推荐奖励券的发放和撤回记录'],
  records: ['推荐审核', '勾选达标推荐，进入独立页面发放奖励'],
  bindings: ['绑定记录', '查看推荐关系和纠错历史'],
  anomalies: ['异常处理', '核查取消、退款等异常并记录处理结果']
}

function goTemplates() {
  if (this.current() && !this.data.busy && !this.data.uncertain) {
    wx.navigateTo({ url: '/pages/admin-coupon-section/admin-coupon-section?section=templates' })
  }
}

function homePage() {
  const base = referral.page(true)
  return {
    ...base,
    async load() {
      if (!this.current()) {
        this.setData({ error: '请使用管理员身份重新进入', pendingBadge: '', anomalyBadge: '' })
        return
      }
      const token = this._token, version = this._version = (this._version || 0) + 1
      this.setData({ error: '', loading: true })
      try {
        const r = await request({ url: '/system/referral/counts' })
        if (this.current() && token === this._token && version === this._version) {
          this.setData({ pendingBadge: referral.badge(r.data.pending), anomalyBadge: referral.badge(r.data.anomalies) })
        }
      } catch (e) {
        if (this.current() && version === this._version) this.setData({ error: e.message || '待处理数量加载失败' })
      } finally {
        if (this.current() && version === this._version) this.setData({ loading: false })
      }
    },
    openSection(e) {
      const section = e.currentTarget.dataset.section
      if (this.current() && sections[section]) {
        wx.navigateTo({ url: '/pages/admin-coupon-section/admin-coupon-section?section=' + section })
      }
    }
  }
}

function sectionPage() {
  const base = coupons.page(referral.page(true))
  return {
    ...base,
    data: { ...base.data, sectionTitle: '', sectionHint: '' },
    onLoad(options = {}) {
      base.onLoad.call(this)
      const section = sections[options.section] ? options.section : 'templates'
      this.setData({
        sectionTitle: sections[section][0], sectionHint: sections[section][1],
        couponTab: ['issue', 'templates', 'grants'].includes(section) ? section : 'referrals',
        kind: ['records', 'bindings', 'anomalies'].includes(section) ? section : 'records'
      })
      wx.setNavigationBarTitle({ title: sections[section][0] })
    },
    async load() {
      if (this.data.couponTab !== 'referrals' || this.data.kind !== 'records') return base.load.call(this)
      const token = this._token
      const filter = JSON.stringify([this.data.kind, this.data.parentPhone, this.data.filterRole, this.data.state])
      const selected = this._selectionToken === token && this._selectionFilter === filter ? this.data.selectedIds.slice() : []
      const work = base.load.call(this), version = this._version
      await work
      if (!this.current() || token !== this._token || version !== this._version) return
      this._selectionToken = token
      this._selectionFilter = filter
      this.syncRecordSelection(this.data.error ? [] : selected)
    },
    syncRecordSelection(ids) {
      const selected = [...new Set(ids.map(String))]
      this.setData({ selectedIds: selected, rows: this.data.rows.map(row => ({ ...row, checked: selected.includes(String(row.recordId)) })) })
    },
    selectRecords(e) {
      if (!this.current() || this.data.busy || this.data.loading) return
      const visible = new Set(this.data.rows.map(row => String(row.recordId)))
      const selected = [...new Set([
        ...this.data.selectedIds.filter(id => !visible.has(id)),
        ...e.detail.value.map(String).filter(id => visible.has(id))
      ])]
      if (selected.length > 100) {
        wx.showToast({ title: '每批最多选择100条推荐', icon: 'none' })
        this.syncRecordSelection(this.data.selectedIds)
        return
      }
      this.syncRecordSelection(selected)
    },
    goTemplates,
    openRewardPage() {
      if (!this.current() || this.data.busy || this.data.loading || !this.data.selectedIds.length) return
      const ids = [...new Set(this.data.selectedIds.map(String))]
      if (ids.length > 100) { wx.showToast({ title: '每批最多选择100条推荐', icon: 'none' }); return }
      wx.navigateTo({ url: '/pages/admin-referral-grant/admin-referral-grant?records=' + encodeURIComponent(ids.join(',')) })
    }
  }
}

function rewardPage() {
  const base = referral.page(true)
  return {
    ...base,
    data: { ...base.data, recordCount: 0, identityChanged: false, confirmingReward: false, confirmationTemplate: {} },
    onLoad(options = {}) {
      base.onLoad.call(this)
      const ids = [...new Set(String(options.records || '').split(',').filter(Boolean))]
      this._recordIds = ids.length > 0 && ids.length <= 100 && ids.every(id => /^[1-9]\d*$/.test(id)) ? ids : []
      this.setData({ recordCount: this._recordIds.length })
    },
    onShow() {
      const token = wx.getStorageSync('token')
      if (this._entryToken && token !== this._entryToken) {
        this.setData({ identityChanged: true, templates: [], preview: null, reason: '', recordCount: 0 })
        this._recordIds = []
      }
      this._entryToken = token
      base.onShow.call(this)
    },
    onHide() {
      this.cancelRewardConfirmation()
      base.onHide.call(this)
    },
    async load() {
      if (!this.current() || this.data.identityChanged || !this._recordIds.length) {
        this.setData({ error: '请选择推荐记录，并使用原管理员身份重新进入' })
        return
      }
      // Returning from template creation must preserve the selected records and draft.
      // Never replace an uncertain batch or allow a refresh during confirmation.
      if (this.data.busy || this.data.uncertain || this._frozen) return
      if (!this._formLoaded) {
        this.setData({ selectedIds: this._recordIds.slice(), loading: true, error: '' })
        await base.openGrant.call(this)
        if (this.current()) {
          this._formLoaded = this.data.showGrant
          this.setData({ loading: false })
        }
        return
      }
      const version = this._version = (this._version || 0) + 1
      const selected = this.data.templates[this.data.templateIndex]
      this.setData({ loading: true, error: '' })
      try {
        const r = await request({ url: '/system/coupon/templates', data: { templateStatus: 'ACTIVE', pageSize: 100, pageNum: 1 } })
        if (!this.current() || version !== this._version) return
        const templates = (r.rows || []).map(display.template)
        const index = selected ? templates.findIndex(t => t.templateId === selected.templateId) : -1
        this.setData({ templates, templateIndex: index, preview: null })
      } catch (e) {
        if (this.current() && version === this._version) this.setData({ error: e.message || '券模板加载失败' })
      } finally {
        if (this.current() && version === this._version) this.setData({ loading: false })
      }
    },
    goTemplates,
    back() { if (!this.data.busy && !this.data.uncertain) wx.navigateBack() },
    modal(options) {
      if (options.title !== '确认发放奖励') return base.modal.call(this, options)
      this.setData({ confirmingReward: true, confirmationTemplate: display.template(this.data.preview.template) })
      return new Promise(resolve => { this._rewardConfirmation = resolve })
    },
    confirmRewardGrant() {
      const resolve = this._rewardConfirmation
      if (!resolve || !this.current()) return
      this._rewardConfirmation = null
      this.setData({ confirmingReward: false })
      resolve({ confirm: true })
    },
    cancelRewardConfirmation() {
      const resolve = this._rewardConfirmation
      if (!resolve) return
      this._rewardConfirmation = null
      this._frozen = null // No grant request has been sent while this confirmation is open.
      this.setData({ confirmingReward: false })
      resolve({ confirm: false })
    },
    async grant() {
      if (!this.current() || this.data.identityChanged || this.data.busy || this.data.loading || this._issuing) return
      this._issuing = true
      const token = this._token, version = this._version
      try {
        // Always obtain a current server preview before a new batch; unknown outcomes
        // retry the original frozen request without changing its recipient snapshot.
        if (!this._frozen) {
          this.setData({ preview: null })
          await base.previewGrant.call(this)
          if (!this.current() || token !== this._token || version !== this._version || this.data.identityChanged || !this.data.preview || !this.data.preview.previewHash) return
        }
        await base.grant.call(this)
        if (this.current() && this._formLoaded && !this.data.showGrant && !this.data.uncertain && !this._frozen) wx.navigateBack()
      } finally {
        this._issuing = false
      }
    }
  }
}

module.exports = { homePage, sectionPage, rewardPage }
