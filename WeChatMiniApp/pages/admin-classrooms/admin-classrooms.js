const { request } = require('../../utils/request')

Page({
  data: {
    loading: false,
    groups: [],
    total: 0,
    // 新增/编辑弹层
    editVisible: false,
    editForm: null,
    campusOptions: ['万达', '百家湖']
  },

  onShow() { this.load() },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },

  async load() {
    this.setData({ loading: true })
    try {
      const [roomRes, scheduleRes] = await Promise.all([
        request({ url: '/system/classroom/list', data: { pageNum: 1, pageSize: 500 } }),
        request({ url: '/system/schedule/list', data: { pageNum: 1, pageSize: 2000 } })
      ])
      const usedByRoom = {}
      ;(scheduleRes.rows || []).forEach((s) => {
        if (s.classroomId) usedByRoom[s.classroomId] = (usedByRoom[s.classroomId] || 0) + 1
      })
      const rooms = (roomRes.rows || []).map((r) => ({
        classroomId: r.classroomId,
        classroomCode: r.classroomCode || '—',
        classroomName: r.classroomName || '—',
        campusName: r.campusName || '未分配',
        used: usedByRoom[r.classroomId] || 0
      }))
      const byCampus = {}
      rooms.forEach((r) => {
        (byCampus[r.campusName] || (byCampus[r.campusName] = [])).push(r)
      })
      const order = ['万达', '百家湖']
      const keys = Object.keys(byCampus).sort((a, b) => {
        const ia = order.indexOf(a); const ib = order.indexOf(b)
        return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
      })
      const groups = keys.map((k) => ({
        campus: k,
        rooms: byCampus[k].sort((a, b) => a.classroomName.localeCompare(b.classroomName, 'zh')),
        realCount: byCampus[k].filter((r) => r.classroomName.indexOf('待重排') !== 0 && r.classroomName !== '缺教室').length
      }))
      this.setData({ groups, total: rooms.length, loading: false })
    } catch (e) {
      this.setData({ loading: false, groups: [] })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  openAdd() {
    this.setData({
      editVisible: true,
      editForm: { classroomId: null, classroomCode: '', classroomName: '', campusIndex: 0 }
    })
  },
  openEdit(e) {
    const id = e.currentTarget.dataset.id
    let room = null
    this.data.groups.forEach((g) => { g.rooms.forEach((r) => { if (r.classroomId === id) room = r }) })
    if (!room) return
    const idx = this.data.campusOptions.indexOf(room.campusName)
    this.setData({
      editVisible: true,
      editForm: {
        classroomId: room.classroomId,
        classroomCode: room.classroomCode === '—' ? '' : room.classroomCode,
        classroomName: room.classroomName,
        campusIndex: idx < 0 ? 0 : idx
      }
    })
  },
  closeEdit() { this.setData({ editVisible: false, editForm: null }) },
  onField(e) { this.setData({ [`editForm.${e.currentTarget.dataset.field}`]: e.detail.value }) },
  onCampus(e) { this.setData({ 'editForm.campusIndex': Number(e.detail.value) }) },

  async save() {
    const f = this.data.editForm
    if (!f) return
    if (!f.classroomName.trim()) { wx.showToast({ title: '请填写教室名称', icon: 'none' }); return }
    const payload = {
      classroomName: f.classroomName.trim(),
      campusName: this.data.campusOptions[f.campusIndex],
      status: '0'
    }
    if (f.classroomCode.trim()) payload.classroomCode = f.classroomCode.trim()
    try {
      wx.showLoading({ title: '保存中' })
      if (f.classroomId) {
        payload.classroomId = f.classroomId
        await request({ url: '/system/classroom', method: 'PUT', data: payload })
      } else {
        await request({ url: '/system/classroom', method: 'POST', data: payload })
      }
      wx.hideLoading()
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ editVisible: false, editForm: null })
      this.load()
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    }
  },

  remove(e) {
    const id = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name
    const used = e.currentTarget.dataset.used
    if (used > 0) { wx.showToast({ title: '该教室已有排课，不能删除', icon: 'none' }); return }
    wx.showModal({
      title: '删除教室',
      content: `确定删除「${name}」吗？`,
      confirmColor: '#d94c4c',
      success: async (res) => {
        if (!res.confirm) return
        try {
          wx.showLoading({ title: '删除中' })
          await request({ url: `/system/classroom/${id}`, method: 'DELETE' })
          wx.hideLoading()
          wx.showToast({ title: '已删除', icon: 'success' })
          this.load()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '删除失败', icon: 'none' })
        }
      }
    })
  },

  noop() {}
})
