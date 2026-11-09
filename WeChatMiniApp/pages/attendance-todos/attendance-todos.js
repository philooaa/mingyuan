const { request, todoRoute } = require('../../utils/attendance-workflow')
const access = require('../../utils/access')
const filters = require('../../utils/attendance-filters')
Page({
  data: { ...filters.state(), attendanceFilters: {}, filterDate: '', items: [], count: 0, totalCount: 0, overdueCount: 0, error: '', loading: false, role: '' },
  onShow() { this._active = true; return this.load() },
  onHide() { this._active = false },
  onUnload() { this._active = false },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
  async load() {
    const token = wx.getStorageSync('token'), role = access.selected(), version = this._version = (this._version || 0) + 1
    if (this.data.role !== role) this.setData({ attendanceFilters: {}, filterDate: '' })
    this._items = []
    this.setData({ loading: true, items: [], count: 0, totalCount: 0, overdueCount: 0, error: '', role })
    try {
      const r = await request({ url: '/miniapp/attendance/todos', data: { role } })
      if (this._active && token === wx.getStorageSync('token') && version === this._version && role === access.selected()) {
        this._items = ((r.data || {}).items || []).map(item => ({ ...item, rowKey: `${item.scheduleId}:${item.classDate}` }))
        this.applyFilters()
      }
    } catch (e) { if (this._active && version === this._version) this.setData({ error: e.message }) }
    finally { if (version === this._version) this.setData({ loading: false }) }
  },
  applyFilters() {
    const all = this._items || [], items = all.filter(item => filters.matches(item, { ...this.data.attendanceFilters, filterDate: this.data.filterDate }))
    this.setData({ ...filters.state(all, this.data.attendanceFilters), items, count: items.length, totalCount: all.length, overdueCount: items.filter(item => item.overdue).length })
  },
  onAttendanceFilter(e) { this.setData({ attendanceFilters: filters.change(this.data, e) }); this.applyFilters() },
  onDateFilter(e) { this.setData({ filterDate: e.detail.value }); this.applyFilters() },
  resetAttendanceFilters() { this.setData({ attendanceFilters: {}, filterDate: '' }); this.applyFilters() },
  open(e) {
    const item = this.data.items[e.currentTarget.dataset.index]
    if (item && this._active && this.data.role === access.selected()) wx.navigateTo({ url: todoRoute(item, this.data.role) })
  }
})
