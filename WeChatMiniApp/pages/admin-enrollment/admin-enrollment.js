const { request, root, guard, decorateSchedule, compareGrades } = require('../../utils/admin-enrollment')
Page({
  data: { grades: ['全部年级'], gradeIndex: 0, subjects: ['全部科目'], subjectIndex: 0, years: ['全部年份'], yearIndex: 0, terms: ['全部季度'], termIndex: 0, periods:['全部期次'], periodIndex:0, modes: ['全部形式', '班课', '一对一'], modeIndex: 0, teacherKeyword: '', groups: [], keyword: '', loading: false, total: 0, list: [], pageNum: 1, error: '' },
  onLoad() {
    if (!guard()) return
    request({ url: `${root}/grades` }).then(res => this.setData({ grades: ['全部年级', ...(res.data || []).slice().sort(compareGrades)] }))
      .catch(e => wx.showToast({ title: e.message, icon: 'none' }))
    request({ url: `${root}/filter-options` }).then(res => { const rows=res.data || []; const unique=k=>Array.from(new Set(rows.map(r=>r[k]).filter(Boolean))).sort(); this.setData({subjects:['全部科目',...unique('subjectName')],years:['全部年份',...unique('courseYear').map(String)],terms:['全部季度',...unique('termName')],periods:['全部期次',...unique('periodName')]}) }).catch(e=>wx.showToast({title:e.message,icon:'none'}))
  },
  onShow() { if (guard()) this.load(false) },
  onPullDownRefresh() { this.load(false).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (this.data.list.length < this.data.total) this.load(true) },
  changeGrade(e) { this.setData({ gradeIndex: Number(e.detail.value) }); this.load(false) },
  changeFilter(e) { this.setData({[e.currentTarget.dataset.key]:Number(e.detail.value)}); this.load(false) },
  inputTeacher(e) { this.setData({teacherKeyword:e.detail.value}) },
  refreshFilters() { this.load(false) },
  resetFilters() { this.setData({gradeIndex:0,subjectIndex:0,yearIndex:0,termIndex:0,periodIndex:0,modeIndex:0,teacherKeyword:''}); this.load(false) },
  inputKeyword(e) { this.setData({ keyword: e.detail.value }) },
  search() {
    const keyword = this.data.keyword.trim()
    if (!keyword) return wx.showToast({ title: '输入学生姓名或家长手机号', icon: 'none' })
    const d=this.data, filters={gradeName:d.gradeIndex?d.grades[d.gradeIndex]:'',subjectName:d.subjectIndex?d.subjects[d.subjectIndex]:'',courseYear:d.yearIndex?d.years[d.yearIndex]:'',termName:d.termIndex?d.terms[d.termIndex]:'',periodName:d.periodIndex?d.periods[d.periodIndex]:'',classMode:d.modeIndex?String(d.modeIndex):'',teacherKeyword:d.teacherKeyword.trim()}
    wx.navigateTo({ url: `/pages/admin-enrollment-list/admin-enrollment-list?keyword=${encodeURIComponent(keyword)}&filters=${encodeURIComponent(JSON.stringify(filters))}` })
  },
  toggle(e) {
    const index = e.currentTarget.dataset.index
    this.setData({ [`groups[${index}].collapsed`]: !this.data.groups[index].collapsed })
  },
  open(e) { wx.navigateTo({ url: `/pages/admin-enrollment-list/admin-enrollment-list?scheduleId=${e.currentTarget.dataset.id}` }) },
  async load(append) {
    if (append && this.data.loading) return
    const generation = this._generation = (this._generation || 0) + 1
    this.setData({ loading: true, error: '' })
    try {
      const pageNum = append ? this.data.pageNum + 1 : 1
      const res = await request({ url: `${root}/schedules`, data: { pageNum, pageSize: 30,
        gradeName: this.data.gradeIndex ? this.data.grades[this.data.gradeIndex] : '', subjectName:this.data.subjectIndex ? this.data.subjects[this.data.subjectIndex] : '', courseYear:this.data.yearIndex ? this.data.years[this.data.yearIndex] : '', termName:this.data.termIndex ? this.data.terms[this.data.termIndex] : '', periodName:this.data.periodIndex ? this.data.periods[this.data.periodIndex] : '', classMode:this.data.modeIndex ? String(this.data.modeIndex) : '', teacherKeyword:this.data.teacherKeyword.trim() } })
      if (generation !== this._generation) return
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
    } catch (e) { if (generation === this._generation) { this.setData({ error: e.message }); wx.showToast({ title: e.message, icon: 'none' }) } }
    finally { if (generation === this._generation) this.setData({ loading: false }) }
  }
})
