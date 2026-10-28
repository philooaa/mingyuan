const { request } = require('../../utils/request')

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function pad(n) { return String(n).padStart(2, '0') }
function fmt(d) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` }
function parse(s) {
  const m = String(s || '').match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (!m) return new Date()
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
}

Page({
  data: {
    date: '',
    weekday: '',
    loading: false,
    campuses: [],
    slots: [],
    unplaced: [],
    unplacedWidth: 0,
    courseCount: 0,
    // 选中待调整的课程：{ scheduleId, timeSlot, courseClassName }
    sel: null
  },

  onLoad() {
    const today = new Date()
    this.setData({ date: fmt(today), weekday: WEEK[today.getDay()] })
    this.load()
  },

  onShow() {
    // 从别的页面回来时刷新一次（教室/排课可能已变），首次进入不重复请求
    if (this._loadedOnce) this.load()
    this._loadedOnce = true
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const res = await request({ url: '/system/roomAssign/day', data: { date: this.data.date }, timeout: 20000 })
      const d = res.data || {}
      const slots = (d.slots || []).map((v) => ({ value: v }))
      const cells = d.cells || {}
      // 后端按「教室ID@时段」给数据，这里摊平成 教室 → 每个时段一格，WXML 里不用做查找
      const campuses = (d.campuses || []).map((cp) => ({
        campusName: cp.campusName,
        // scroll-view 需要内部有明确宽度才能横向滚动，按教室数算出来
        roomsWidth: (cp.rooms || []).length * 180,
        rooms: (cp.rooms || []).map((r) => ({
          classroomId: String(r.classroomId),
          classroomName: r.classroomName,
          cells: slots.map((s) => {
            const list = cells[`${r.classroomId}@${s.value}`] || []
            // 一个格子最多画两张卡（行高固定才能和左侧时段列对齐），多出来的用 +N 角标提示
            return { slot: s.value, list, shown: list.slice(0, 2), more: Math.max(0, list.length - 2) }
          })
        }))
      }))
      const unplaced = d.unplaced || []
      this.setData({
        loading: false,
        campuses,
        slots,
        unplaced,
        unplacedWidth: unplaced.length * 236,
        courseCount: d.courseCount || 0,
        weekday: d.weekday || this.data.weekday,
        sel: null
      })
    } catch (e) {
      this.setData({ loading: false, campuses: [], slots: [], unplaced: [], unplacedWidth: 0, courseCount: 0 })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onDateChange(e) {
    const date = e.detail.value
    this.setData({ date, weekday: WEEK[parse(date).getDay()], sel: null })
    this.load()
  },

  prevDay() {
    const d = parse(this.data.date)
    d.setDate(d.getDate() - 1)
    this.setData({ date: fmt(d), weekday: WEEK[d.getDay()], sel: null })
    this.load()
  },

  nextDay() {
    const d = parse(this.data.date)
    d.setDate(d.getDate() + 1)
    this.setData({ date: fmt(d), weekday: WEEK[d.getDay()], sel: null })
    this.load()
  },

  goToday() {
    const d = new Date()
    this.setData({ date: fmt(d), weekday: WEEK[d.getDay()], sel: null })
    this.load()
  },

  cancelSel() {
    this.setData({ sel: null })
  },

  /** 点课程卡片：选中；已选中时点同格另一门课，视为把已选课程调进对方教室（互换） */
  selectCourse(e) {
    const ds = e.currentTarget.dataset
    const id = String(ds.id)
    const slot = ds.slot
    const sel = this.data.sel
    if (sel) {
      if (sel.scheduleId === id) { this.setData({ sel: null }); return }
      if (sel.timeSlot !== slot) {
        wx.showToast({ title: '只能在同一时段内调整教室', icon: 'none' })
        return
      }
      // 卡片在某个教室里 → 直接对那间教室发起调整（占用了就互换）
      if (ds.room) { this.confirmMove(String(ds.room), slot, ds.roomName); return }
    }
    this.setData({ sel: { scheduleId: id, timeSlot: slot, courseClassName: ds.name || '' } })
  },

  /** 点格子空白处：有选中课程时，把课调到这个教室 */
  onCellTap(e) {
    const ds = e.currentTarget.dataset
    const sel = this.data.sel
    if (!sel) return
    if (sel.timeSlot !== ds.slot) {
      wx.showToast({ title: '只能在同一时段内调整教室', icon: 'none' })
      return
    }
    this.confirmMove(String(ds.room), ds.slot, ds.roomName)
  },

  confirmMove(roomId, slot, roomName) {
    const sel = this.data.sel
    if (!sel) return
    const occupants = this.findCell(roomId, slot)
    const occupied = occupants.filter((c) => c.scheduleId !== sel.scheduleId)
    const date = this.data.date
    const content = occupied.length
      ? `「${occupied[0].courseClassName}」同时段已在这间教室，确认两门课互换教室？从 ${date} 起生效。`
      : `确认把「${sel.courseClassName}」调到「${roomName || '该教室'}」？从 ${date} 起生效，之后这门课都在该教室。`
    wx.showModal({
      title: '调整教室',
      content,
      confirmText: '确认调整',
      success: (r) => { if (r.confirm) this.doMove(roomId) }
    })
  },

  async doMove(roomId) {
    const sel = this.data.sel
    if (!sel || this._moving) return
    this._moving = true
    wx.showLoading({ title: '调整中…', mask: true })
    try {
      const res = await request({
        url: '/system/roomAssign/move',
        method: 'POST',
        data: { scheduleId: sel.scheduleId, date: this.data.date, targetClassroomId: roomId }
      })
      wx.hideLoading()
      const msg = (res.data && res.data.message) || '已调整'
      wx.showToast({ title: msg.length > 18 ? '调整成功' : msg, icon: 'none' })
      this.setData({ sel: null })
      this.load()
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '调整失败', icon: 'none' })
    } finally {
      this._moving = false
    }
  },

  /** 从已摊平的页面数据里取某格课程，避免再存一份索引 */
  findCell(roomId, slot) {
    const rooms = []
    ;(this.data.campuses || []).forEach((cp) => { cp.rooms.forEach((r) => rooms.push(r)) })
    const room = rooms.find((r) => r.classroomId === String(roomId))
    if (!room) return []
    const cell = room.cells.find((c) => c.slot === slot)
    return (cell && cell.list) || []
  },

  noop() {}
})
