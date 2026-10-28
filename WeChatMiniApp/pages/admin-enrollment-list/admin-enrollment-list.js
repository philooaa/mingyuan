const { request, root, guard, decorateEnrollment, decorateSchedule } = require('../../utils/admin-enrollment')
Page({
  data: { scheduleId: '', schedule: null, keyword: '', queryKeyword: '', list: [], loading: false, total: 0, pageNum: 1, error: '', scheduleError: '' },
  onLoad(options) {
    let keyword = options.keyword || ''
    try { keyword = decodeURIComponent(keyword) } catch (e) { /* 保留已解码或含百分号的搜索词 */ }
    this.setData({ scheduleId: options.scheduleId || '', keyword })
  },
  onShow() { if (guard()) this.load(false) },
  onPullDownRefresh() { this.load(false).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (this.data.list.length < this.data.total) this.load(true) },
  input(e) { this.setData({ keyword: e.detail.value }) },
  search() { this.load(false) },
  open(e) { wx.navigateTo({ url: `/pages/admin-enrollment-detail/admin-enrollment-detail?id=${e.currentTarget.dataset.id}` }) },
  async load(append) {
    if (append && this.data.loading) return
    const generation = this._generation = (this._generation || 0) + 1
    const keyword = append ? this.data.queryKeyword : this.data.keyword.trim()
    const pageNum = append ? this.data.pageNum + 1 : 1
    this.setData({ loading: true, error: '',
      ...(append ? {} : { list: [], total: 0, pageNum: 1, queryKeyword: keyword, scheduleError: '' }) })
    // 班次资料与名单独立加载，班次资料失败不能阻断名单查询。
    if (!append && this.data.scheduleId) {
      request({ url: `${root}/schedule/${this.data.scheduleId}`, timeout: 15000 })
        .then(res => {
          if (generation === this._generation) this.setData({ schedule: decorateSchedule(res.data) })
        }).catch(e => {
          if (generation === this._generation) this.setData({ scheduleError: e.message })
        })
    }
    try {
      const res = await request({ url: `${root}/list`, timeout: 15000, data: { pageNum, pageSize: 30,
        ...(this.data.scheduleId ? { scheduleId: this.data.scheduleId } : {}), keyword } })
      if (generation !== this._generation) return
      this.setData({ list: (append ? this.data.list : []).concat((res.rows || []).map(decorateEnrollment)), total: res.total || 0, pageNum })
    } catch (e) {
      if (generation !== this._generation) return
      this.setData({ error: e.message }); wx.showToast({ title: e.message, icon: 'none' })
    } finally { if (generation === this._generation) this.setData({ loading: false }) }
  }
})
