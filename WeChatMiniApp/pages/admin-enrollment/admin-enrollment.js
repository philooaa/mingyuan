const { request, root, guard, decorateSchedule, compareGrades } = require('../../utils/admin-enrollment')
Page({
  data: { grades: ['全部年级'], gradeIndex: 0, groups: [], keyword: '', loading: false, total: 0, list: [], pageNum: 1, error: '' },
  onLoad() {
    if (!guard()) return
    request({ url: `${root}/grades` }).then(res => this.setData({ grades: ['全部年级', ...(res.data || []).slice().sort(compareGrades)] }))
      .catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  onShow() { if (guard()) this.load(false) },
  onPullDownRefresh() { this.load(false).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (this.data.list.length < this.data.total) this.load(true) },
  changeGrade(e) { this.setData({ gradeIndex: Number(e.detail.value) }); this.load(false) },
  inputKeyword(e) { this.setData({ keyword: e.detail.value }) },
  search() {
    const keyword = this.data.keyword.trim()
    if (!keyword) return wx.showToast({ title: '输入学生姓名或家长手机号', icon: 'none' })
    wx.navigateTo({ url: `/pages/admin-enrollment-list/admin-enrollment-list?keyword=${encodeURIComponent(keyword)}` })
  },
  toggle(e) {
    const index = e.currentTarget.dataset.index
    this.setData({ [`groups[${index}].collapsed`]: !this.data.groups[index].collapsed })
  },
  open(e) { wx.navigateTo({ url: `/pages/admin-enrollment-list/admin-enrollment-list?scheduleId=${e.currentTarget.dataset.id}` }) },
  async load(append) {
    if (this.data.loading) return
    this.setData({ loading: true, error: '' })
    try {
      const pageNum = append ? this.data.pageNum + 1 : 1
      const res = await request({ url: `${root}/schedules`, data: { pageNum, pageSize: 30,
        gradeName: this.data.gradeIndex ? this.data.grades[this.data.gradeIndex] : '' } })
      const list = (append ? this.data.list : []).concat((res.rows || []).map(decorateSchedule))
      const grouped = new Map()
      list.forEach(item => {
        const grade = item.gradeName || '未分年级'
        if (!grouped.has(grade)) grouped.set(grade, [])
        grouped.get(grade).push(item)
      })
      const collapsed = new Map(this.data.groups.map(g => [g.grade, g.collapsed]))
      const groups = Array.from(grouped, ([grade, items]) => ({ grade, items, collapsed: collapsed.get(grade) || false }))
        .sort((a, b) => compareGrades(a.grade, b.grade))
      this.setData({ list, groups, pageNum, total: res.total || 0 })
    } catch (e) { this.setData({ error: e.message }); wx.showToast({ title: e.message, icon: 'none' }) }
    finally { this.setData({ loading: false }) }
  }
})
