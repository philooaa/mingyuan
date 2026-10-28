const { request } = require('../../utils/request')

function fmtDate(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
function fmtTime(text) { return String(text || '').slice(11, 16) }

Page({
  data: {
    tab: 'review', // review=打卡复核 stats=统计表
    loading: false,
    date: fmtDate(new Date()),
    rows: [],
    okCount: 0,
    missCount: 0,
    // 修改弹层
    editVisible: false,
    editForm: null,
    // 补卡弹层
    addVisible: false,
    addForm: null,
    teacherNames: [],
    typeLabels: ['上班', '下班'],
    campusLabels: ['万达校区', '百家湖校区'],
    // 统计表
    statsLoading: false,
    statsMonth: '',
    statsRequiredDays: 0,
    statsRows: [],
    // 教师打卡明细弹层
    detailVisible: false,
    detailLoading: false,
    detailTeacherName: '',
    detailDays: []
  },

  onShow() {
    if (!this.data.statsMonth) {
      const now = new Date()
      this.setData({ statsMonth: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}` })
    }
    this.load()
  },

  onPullDownRefresh() {
    (this.data.tab === 'stats' ? this.loadStats() : this.load()).finally(() => wx.stopPullDownRefresh())
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    this.setData({ tab })
    if (tab === 'stats' && !this._statsLoaded) this.loadStats()
  },

  onDate(e) {
    this.setData({ date: e.detail.value })
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const res = await request({ url: '/miniapp/teacher/checkin/admin/summary', data: { date: this.data.date } })
      const rows = (res.data || []).map((r) => ({
        teacherId: r.teacherId,
        teacherName: r.teacherName || '—',
        inId: r.inId,
        outId: r.outId,
        inTime: fmtTime(r.inTime),
        outTime: fmtTime(r.outTime),
        inCampus: r.inCampus || '',
        outCampus: r.outCampus || '',
        inSsid: r.inSsid || '',
        outSsid: r.outSsid || '',
        inRemark: r.inRemark || '',
        outRemark: r.outRemark || '',
        status: r.status
      }))
      this.setData({
        rows,
        okCount: rows.filter((r) => r.status === 'normal').length,
        missCount: rows.filter((r) => r.status !== 'normal').length,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false, rows: [] })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  // ============ 修改打卡时间 ============
  openEdit(e) {
    const id = Number(e.currentTarget.dataset.id)
    const row = this.data.rows.find((r) => r.inId === id || r.outId === id)
    if (!row) return
    const isIn = row.inId === id
    const full = isIn ? (this.data.date + ' ' + (row.inTime || '08:00') + ':00') : (this.data.date + ' ' + (row.outTime || '17:00') + ':00')
    this.setData({
      editVisible: true,
      editForm: {
        checkInId: id,
        typeText: isIn ? '上班' : '下班',
        teacherName: row.teacherName,
        d: this.data.date,
        time: full.slice(11, 16),
        remark: (isIn ? row.inRemark : row.outRemark) || ''
      }
    })
  },
  closeEdit() { this.setData({ editVisible: false, editForm: null }) },
  onField(e) { this.setData({ [`editForm.${e.currentTarget.dataset.field}`]: e.detail.value }) },
  onEditDate(e) { this.setData({ 'editForm.d': e.detail.value }) },
  onEditTime(e) { this.setData({ 'editForm.time': e.detail.value }) },

  async saveEdit() {
    const f = this.data.editForm
    if (!f) return
    try {
      wx.showLoading({ title: '保存中' })
      await request({ url: '/miniapp/teacher/checkin/admin', method: 'PUT', data: { checkInId: f.checkInId, checkInTime: `${f.d} ${f.time}:00`, remark: (f.remark || '').trim() } })
      wx.hideLoading()
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ editVisible: false, editForm: null })
      this.load()
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    }
  },

  // ============ 删除打卡（转缺卡） ============
  markMissing(e) {
    const id = Number(e.currentTarget.dataset.id)
    const name = e.currentTarget.dataset.name
    const type = e.currentTarget.dataset.type
    wx.showModal({
      title: '转为缺卡',
      content: `确定删除「${name}」的${type}打卡记录吗？删除后计为缺卡。`,
      confirmColor: '#d94c4c',
      success: async (res) => {
        if (!res.confirm) return
        try {
          wx.showLoading({ title: '处理中' })
          await request({ url: `/miniapp/teacher/checkin/admin/${id}`, method: 'DELETE' })
          wx.hideLoading()
          wx.showToast({ title: '已转为缺卡', icon: 'success' })
          this.load()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  // ============ 缺卡补为正常（无需WiFi） ============
  markNormal(e) {
    const teacherId = Number(e.currentTarget.dataset.teacherId)
    const type = e.currentTarget.dataset.type
    const name = e.currentTarget.dataset.name
    const defTime = type === 'in' ? '08:00:00' : '17:30:00'
    wx.showModal({
      title: `补${type === 'in' ? '上班' : '下班'}卡`,
      content: `将「${name}」按 ${this.data.date} ${defTime.slice(0, 5)} 记为正常打卡？`,
      confirmColor: '#1b9a68',
      success: async (res) => {
        if (!res.confirm) return
        try {
          wx.showLoading({ title: '处理中' })
          await request({ url: '/miniapp/teacher/checkin/admin', method: 'POST', data: { teacherId, classDate: this.data.date, type, checkInTime: `${this.data.date} ${defTime}`, remark: '教务补卡' } })
          wx.hideLoading()
          wx.showToast({ title: '已补卡', icon: 'success' })
          this.load()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  // ============ 手动补卡弹层 ============
  async openAdd() {
    this.setData({
      addVisible: true,
      addForm: { teacherIndex: 0, typeIndex: 0, campusIndex: -1, d: this.data.date, time: '', remark: '' }
    })
    if (!this._teachersLoaded) await this.loadTeachers()
  },
  closeAdd() { this.setData({ addVisible: false, addForm: null }) },
  onAddField(e) { this.setData({ [`addForm.${e.currentTarget.dataset.field}`]: e.detail.value }) },
  onAddDate(e) { this.setData({ 'addForm.d': e.detail.value }) },
  onAddTime(e) { this.setData({ 'addForm.time': e.detail.value }) },
  onAddType(e) { this.setData({ 'addForm.typeIndex': Number(e.detail.value) }) },
  onAddCampus(e) { this.setData({ 'addForm.campusIndex': Number(e.detail.value) }) },
  onTeacher(e) { this.setData({ 'addForm.teacherIndex': Number(e.detail.value) }) },

  async loadTeachers() {
    try {
      const res = await request({ url: '/system/salaryConfig/teachers' })
      this._teachers = res.data || []
      this._teachersLoaded = true
      this.setData({ teacherNames: this._teachers.map((t) => t.teacherName) })
    } catch (e) {
      wx.showToast({ title: e.message || '教师加载失败', icon: 'none' })
    }
  },

  async submitAdd() {
    const f = this.data.addForm
    if (!f) return
    const teacher = (this._teachers || [])[f.teacherIndex]
    if (!teacher) { wx.showToast({ title: '请选择教师', icon: 'none' }); return }
    if (!f.time) { wx.showToast({ title: '请选择打卡时间', icon: 'none' }); return }
    const payload = {
      teacherId: teacher.teacherId,
      classDate: f.d,
      type: f.typeIndex === 0 ? 'in' : 'out',
      checkInTime: `${f.d} ${f.time}:00`,
      campusName: f.campusIndex >= 0 ? this.data.campusLabels[f.campusIndex] : '',
      remark: (f.remark || '').trim() || '教务补卡'
    }
    try {
      wx.showLoading({ title: '提交中' })
      await request({ url: '/miniapp/teacher/checkin/admin', method: 'POST', data: payload })
      wx.hideLoading()
      wx.showToast({ title: '补卡成功', icon: 'success' })
      this.setData({ addVisible: false, addForm: null })
      this.load()
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '补卡失败', icon: 'none' })
    }
  },

  // ============ 统计表（按月，周一至周五应打卡） ============
  onStatsMonth(e) { this.setData({ statsMonth: e.detail.value }); this.loadStats() },

  async loadStats() {
    this.setData({ statsLoading: true })
    try {
      const res = await request({ url: '/miniapp/teacher/checkin/admin/statistics', data: { month: this.data.statsMonth } })
      this._statsLoaded = true
      const d = res.data || {}
      const rows = d.rows || []
      const totals = rows.reduce((t, r) => ({ workDays: t.workDays + r.workDays, inCount: t.inCount + r.inCount, outCount: t.outCount + r.outCount }), { workDays: 0, inCount: 0, outCount: 0 })
      this.setData({ statsRows: rows, statsTotals: totals, statsRequiredDays: d.requiredDays || 0, statsLoading: false })
    } catch (e) {
      this.setData({ statsLoading: false, statsRows: [] })
      wx.showToast({ title: e.message || '统计加载失败', icon: 'none' })
    }
  },

  // ============ 教师打卡明细 ============
  async openTeacherDetail(e) {
    const teacherId = Number(e.currentTarget.dataset.teacherId)
    const teacherName = e.currentTarget.dataset.teacherName || ''
    this.setData({ detailVisible: true, detailLoading: true, detailTeacherName: teacherName, detailDays: [] })
    try {
      const res = await request({ url: '/miniapp/teacher/checkin/admin/records', data: { teacherId, month: this.data.statsMonth } })
      const byDate = {}
      const order = []
      for (const r of res.data || []) {
        const d = String(r.classDate || '').slice(0, 10)
        if (!byDate[d]) { byDate[d] = { date: d, inTime: '', outTime: '', inCampus: '', outCampus: '', inRemark: '', outRemark: '' }; order.push(d) }
        const t = String(r.checkInTime || '').slice(11, 16)
        if (r.checkType === 'out') {
          byDate[d].outTime = t
          byDate[d].outCampus = r.campusName || ''
          byDate[d].outRemark = r.remark || ''
        } else {
          byDate[d].inTime = t
          byDate[d].inCampus = r.campusName || ''
          byDate[d].inRemark = r.remark || ''
        }
      }
      order.sort((a, b) => b.localeCompare(a))
      const days = order.map((d) => {
        const x = byDate[d]
        x.status = x.inTime && x.outTime ? 'normal' : (x.inTime ? 'missing_out' : 'missing_in')
        return x
      })
      this.setData({ detailDays: days, detailLoading: false })
    } catch (err) {
      this.setData({ detailLoading: false })
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    }
  },

  closeTeacherDetail() { this.setData({ detailVisible: false }) },

  noop() {}
})
