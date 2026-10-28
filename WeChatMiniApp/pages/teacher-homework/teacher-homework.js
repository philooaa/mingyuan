const { request } = require('../../utils/request')
function valueOf(item, names, fallback = '') { for (const n of names) if (item[n] !== undefined && item[n] !== null) return item[n]; return fallback }
// 卡片按班级展示本班提交情况（后端 targetCounts 为 scheduleId:提交数:已批阅数）
function classCard(item, scheduleId) {
  if (item.status !== '1' && item.status !== '2') return item
  const scoped = (item.targetCountMap || {})[String(scheduleId)]
  const submitted = scoped ? scoped.submitted : 0
  const reviewed = scoped ? scoped.reviewed : 0
  return { ...item, progress: submitted ? `本班提交 ${submitted} 人，已批阅 ${reviewed} 人` : '本班暂无提交' }
}
const statuses = { 0: '草稿', 1: '已发布', 2: '已关闭', draft: '草稿', published: '已发布', closed: '已关闭' }
const statusTabs = [
  { key: 'all', name: '全部' },
  { key: '0', name: '草稿' },
  { key: '1', name: '已发布' },
  { key: '2', name: '已关闭' },
  { key: 'pending', name: '待批阅' }
]
Page({
  data: { list: [], shown: [], groups: [], classTabs: [], activeClass: '', statusTabs, activeStatus: 'all', loading: false, schedules: [], scheduleNames: {}, publish: false, publishItem: null, publishClasses: [] },
  onLoad(options = {}) { if (options.filter === 'pending') this.setData({ activeStatus: 'pending' }) },
  onShow() { this.loadList() },
  async loadSchedules() {
    if (this.data.schedules.length) return
    try {
      const res = await request({ url: '/miniapp/teacher/sign/list' })
      const schedules = (res.data || []).map(item => ({ scheduleId: String(valueOf(item, ['scheduleId', 'id'], '')), name: valueOf(item, ['courseClassName', 'className'], '未命名班级') }))
      const scheduleNames = {}
      schedules.forEach(row => { scheduleNames[row.scheduleId] = row.name })
      this.setData({ schedules, scheduleNames })
    } catch (e) { /* 班级名称加载失败时列表仍可显示 */ }
  },
  async loadList() {
    this.setData({ loading: true })
    await this.loadSchedules()
    try {
      const r = await request({ url: '/miniapp/teacher/homework/list' })
      const names = this.data.scheduleNames
      const list = (r.data || []).map(item => {
        const status = String(valueOf(item, ['status'], ''))
        const submissionCount = Number(valueOf(item, ['submissionCount'], 0))
        const reviewedCount = Number(valueOf(item, ['reviewedCount'], 0))
        let progress = ''
        if (status === '1' || status === '2') {
          progress = submissionCount ? `提交 ${submissionCount} 人，已批阅 ${reviewedCount} 人` : '暂无提交'
        }
        // 归属班级：已发布/已关闭按发布目标，草稿按占位班级
        const targetIds = String(valueOf(item, ['targetIds'], '')).split(',').filter(Boolean)
        const classIds = status === '0' || !targetIds.length ? [String(valueOf(item, ['scheduleId'], ''))] : targetIds
        const classNames = classIds.map(id => names[id] || valueOf(item, ['scheduleName'], '未知班级'))
        const targetCountMap = {}
        String(valueOf(item, ['targetCounts'], '')).split(',').filter(Boolean).forEach(pair => {
          const parts = pair.split(':')
          if (parts[0]) targetCountMap[parts[0]] = { submitted: Number(parts[1]) || 0, reviewed: Number(parts[2]) || 0 }
        })
        return {
          ...item,
          homeworkId: valueOf(item, ['homeworkId', 'id']),
          status,
          statusText: statuses[status] || valueOf(item, ['statusText'], '未知状态'),
          pendingCount: Math.max(0, submissionCount - reviewedCount),
          progress,
          targetCountMap,
          classIds,
          classNames,
          classText: classNames.join('、')
        }
      })
      // 班级 Tab：只列出有作业的班级；同名班级（同一课程名多个排课）标注 ID 便于区分
      const seen = {}
      const classTabs = [{ key: 'all', name: '全部班级' }]
      list.forEach(item => item.classIds.forEach((id, i) => { if (!seen[id]) { seen[id] = true; classTabs.push({ key: id, name: item.classNames ? item.classNames[i] : names[id] || '未命名班级' }) } }))
      const tabNameCount = {}
      classTabs.forEach(t => { if (t.key !== 'all') tabNameCount[t.name] = (tabNameCount[t.name] || 0) + 1 })
      classTabs.forEach(t => { if (t.key !== 'all' && tabNameCount[t.name] > 1) t.name = `${t.name}（ID:${t.key}）` })
      const activeClass = classTabs.some(t => t.key === this.data.activeClass) ? this.data.activeClass : 'all'
      this.setData({ list, classTabs, activeClass })
      this.applyFilter()
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },
  applyFilter() {
    const { list, activeClass, activeStatus } = this.data
    let shown = activeClass === 'all' ? list : list.filter(item => item.classIds.indexOf(activeClass) >= 0)
    // 默认视图仍隐藏已关闭作业；待批阅包含关闭后尚未批完的提交。
    if (activeStatus === 'all') shown = shown.filter(item => item.status !== '2')
    else if (activeStatus === 'pending') shown = shown.filter(item => item.pendingCount > 0)
    else shown = shown.filter(item => item.status === activeStatus)
    // 按班级分组：同一作业发布到多个班级时，在每个班级下各出现一次
    const names = this.data.scheduleNames
    const groupMap = {}
    const order = []
    shown.forEach(item => {
      const ids = activeClass === 'all' ? item.classIds : [activeClass]
      ids.forEach(id => {
        if (!id || id === 'undefined' || id === 'null') return
        if (!groupMap[id]) { groupMap[id] = { scheduleId: id, name: names[id] || '未命名班级', items: [] }; order.push(id) }
        groupMap[id].items.push(classCard(item, id))
      })
    })
    const groups = order.map(id => groupMap[id])
    const nameCount = {}
    groups.forEach(g => { nameCount[g.name] = (nameCount[g.name] || 0) + 1 })
    groups.forEach(g => { g.displayName = nameCount[g.name] > 1 ? `${g.name}（ID:${g.scheduleId}）` : g.name })
    this.setData({ shown, groups })
  },
  switchClass(e) { this.setData({ activeClass: e.currentTarget.dataset.key }); this.applyFilter() },
  switchStatus(e) { this.setData({ activeStatus: e.currentTarget.dataset.key }); this.applyFilter() },
  create() { wx.navigateTo({ url: '/pages/teacher-homework-create/teacher-homework-create' }) },
  edit(e) { wx.navigateTo({ url: `/pages/teacher-homework-create/teacher-homework-create?homeworkId=${e.currentTarget.dataset.id}` }) },
  async openCard(e) {
    const { id, schedule, sname } = e.currentTarget.dataset
    const item = this.data.list.find(row => String(row.homeworkId) === String(id)) || {}
    if (item.status === '0') {
      wx.navigateTo({ url: `/pages/teacher-homework-create/teacher-homework-create?homeworkId=${id}` })
      return
    }
    const qs = `id=${id}&title=${encodeURIComponent(item.title || item.homeworkTitle || '')}&scheduleId=${schedule || ''}&scheduleName=${encodeURIComponent(sname || '')}`
    wx.navigateTo({ url: `/pages/teacher-homework-review/teacher-homework-review?${qs}` })
  },
  async publish(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find(row => String(row.homeworkId) === String(id)) || {}
    await this.loadSchedules()
    const schedules = this.data.schedules
    if (!schedules.length) return wx.showToast({ title: '暂无可发布的班级', icon: 'none' })
    const publishClasses = schedules.map(row => ({ ...row, checked: String(row.scheduleId) === String(item.scheduleId) }))
    this.setData({ publish: true, publishItem: item, publishClasses })
  },
  closePublish() { this.setData({ publish: false, publishItem: null, publishClasses: [] }) },
  toggleClass(e) {
    const index = Number(e.currentTarget.dataset.index)
    const publishClasses = this.data.publishClasses.slice()
    publishClasses[index] = { ...publishClasses[index], checked: !publishClasses[index].checked }
    this.setData({ publishClasses })
  },
  async confirmPublish() {
    const selected = this.data.publishClasses.filter(item => item.checked).map(item => Number(item.scheduleId))
    if (!selected.length) return wx.showToast({ title: '请至少选择一个班级', icon: 'none' })
    const item = this.data.publishItem || {}
    const result = await new Promise(resolve => wx.showModal({ title: '确认发布作业', content: `发布对象：${selected.length} 个班级\n截止时间：${item.deadline || '未设置'}\n发布后对应班级家长可以查看并提交，是否继续？`, confirmText: '确认发布', success: resolve }))
    if (!result.confirm) return
    try {
      await request({ url: `/miniapp/teacher/homework/${item.homeworkId}/publish`, method: 'POST', data: { scheduleIds: selected } })
      this.closePublish()
      wx.showToast({ title: '已发布', icon: 'success' })
      this.loadList()
    } catch (x) {
      wx.showToast({ title: x.message || '发布失败', icon: 'none' })
    }
  },
  async action(e) {
    const { id, type } = e.currentTarget.dataset
    if (type === 'close') {
      const result = await new Promise(resolve => wx.showModal({ title: '确认关闭作业', content: '关闭后家长不能再提交，已提交的仍可批阅，是否继续？', confirmText: '确认关闭', success: resolve }))
      if (!result.confirm) return
    }
    try { await request({ url: `/miniapp/teacher/homework/${id}/${type}`, method: 'POST' }); wx.showToast({ title: type === 'publish' ? '已发布' : '已关闭', icon: 'success' }); this.loadList() } catch (x) { wx.showToast({ title: x.message || '操作失败', icon: 'none' }) }
  },
  // 调整已发布作业的截止时间
  noop() {}
})
