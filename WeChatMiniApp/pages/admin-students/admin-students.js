const { request } = require('../../utils/request')

// 报名状态里出现「取消」即视为已取消；其余（报名成功 / 1 / 待确认…）视为在读
function isActiveStatus(s) {
  const v = (s || '').trim()
  if (!v) return false
  return v.indexOf('取消') < 0
}

function dateOnly(v) { return (v || '').slice(0, 10) }

// 真名优先；真名是纯英文账号名（如 teacher_demo）而昵称是中文时，用昵称更可读
function pickRealName(nick, real) {
  const n = (nick || '').trim()
  const r = (real || '').trim()
  if (!r) return n
  if (!n) return r
  if (/^[\x00-\x7F]+$/.test(r) && /[\u4e00-\u9fa5]/.test(n)) return n
  return r
}

Page({
  data: {
    loading: false,
    keyword: '',
    filterStatus: '',
    list: [],
    shown: [],
    pageSize: 30,
    shownCount: 0,
    hasMore: true,
    total: 0,
    showEmpty: false,
    detail: null,
    statusOptions: [
      { label: '全部', value: '' },
      { label: '有在读课程', value: 'active' },
      { label: '已全部取消', value: 'cancelled' }
    ]
  },

  onShow() { this.load() },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { this.showMore() },

  async load() {
    this.setData({ loading: true })
    try {
      const [enrollRes, teacherRes] = await Promise.all([
        request({ url: '/system/enrollment/list', data: { pageNum: 1, pageSize: 3000 } }),
        // 教师花名册：排课里存的是昵称（如「张老师」），这里换成真名展示
        request({ url: '/system/salaryConfig/teachers' }).catch(() => ({ data: [] }))
      ])
      const nameByNick = {}
      ;(teacherRes.data || []).forEach((t) => {
        const nick = (t.teacherName || '').trim()
        const real = pickRealName(nick, t.realName)
        // 昵称与真名都建映射：新数据 enrollment.teacherName 已是真名，旧数据可能还是昵称
        if (nick && !nameByNick[nick]) nameByNick[nick] = real
        if (real && !nameByNick[real]) nameByNick[real] = real
      })
      const realName = (n) => {
        const v = (n || '').trim()
        if (!v) return '—'
        return nameByNick[v] || v
      }

      const rows = (enrollRes.rows || []).filter((r) => r.delFlag !== '2')
      // 按「家长 + 学生姓名」聚合：同一家长下的同名视为同一名学生，不同家长的同名分开
      const map = {}
      const order = []
      rows.forEach((r) => {
        const studentName = (r.studentName || '').trim()
        if (!studentName) return
        const parentId = r.parentId == null ? '' : String(r.parentId)
        const key = `${parentId}|${studentName}`
        let s = map[key]
        if (!s) {
          s = map[key] = {
            key,
            parentId,
            studentName,
            studentPhone: '',
            contactPhone: '',
            parentPhone: '',
            courses: [],
            activeCount: 0,
            cancelledCount: 0,
            latestTime: ''
          }
          order.push(key)
        }
        if (!s.studentPhone && r.studentPhone) s.studentPhone = r.studentPhone
        if (!s.contactPhone && r.contactPhone) s.contactPhone = r.contactPhone
        if (!s.parentPhone && r.parentPhone) s.parentPhone = r.parentPhone

        const active = isActiveStatus(r.enrollmentStatus)
        if (active) s.activeCount += 1
        else s.cancelledCount += 1

        const time = r.createTime || ''
        if (time > s.latestTime) s.latestTime = time

        s.courses.push({
          enrollmentId: r.enrollmentId,
          enrollmentCode: r.enrollmentCode || '—',
          courseClassName: r.courseClassName || '—',
          gradeName: r.gradeName || '—',
          subjectName: r.subjectName || '—',
          classType: r.classType || '—',
          teacherName: realName(r.teacherName),
          enrollmentStatus: r.enrollmentStatus || '—',
          payStatus: r.payStatus || '—',
          active,
          createTime: dateOnly(r.createTime),
          remark: r.remark || ''
        })
      })

      const all = order.map((k) => {
        const s = map[k]
        s.courses.sort((a, b) => (b.createTime || '').localeCompare(a.createTime || ''))
        s.courseCount = s.courses.length
        s.latestDate = dateOnly(s.latestTime)
        s.statusText = s.activeCount > 0 ? `在读 ${s.activeCount} 门` : '已全部取消'
        s.searchText = [
          s.studentName, s.studentPhone, s.contactPhone,
          ...s.courses.map((c) => `${c.courseClassName} ${c.subjectName} ${c.teacherName}`)
        ].join(' ')
        return s
      }).sort((a, b) => (b.latestTime || '').localeCompare(a.latestTime || '') || a.studentName.localeCompare(b.studentName))

      this._all = all
      this.setData({ loading: false })
      this.applyFilter(true)
    } catch (e) {
      this.setData({ loading: false, list: [], shown: [], showEmpty: true })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onKeyword(e) { this.setData({ keyword: e.detail.value }); this.applyFilter(true) },
  onStatus(e) { this.setData({ filterStatus: e.currentTarget.dataset.value }); this.applyFilter(true) },

  applyFilter(reset) {
    const kw = (this.data.keyword || '').trim()
    const st = this.data.filterStatus
    const list = (this._all || []).filter((s) => {
      if (st === 'active' && s.activeCount === 0) return false
      if (st === 'cancelled' && !(s.activeCount === 0 && s.courseCount > 0)) return false
      if (!kw) return true
      return (s.searchText || '').indexOf(kw) >= 0
    })
    const pageSize = this.data.pageSize
    const shownCount = reset ? Math.min(pageSize, list.length) : this.data.shownCount
    this.setData({
      list,
      total: list.length,
      shown: list.slice(0, shownCount),
      shownCount,
      hasMore: shownCount < list.length,
      showEmpty: list.length === 0
    })
  },

  showMore() {
    if (!this.data.hasMore) return
    this.setData({ shownCount: this.data.shownCount + this.data.pageSize })
    this.applyFilter(false)
  },

  openDetail(e) {
    const key = e.currentTarget.dataset.key
    const item = (this._all || []).find((s) => s.key === key)
    if (item) this.setData({ detail: item })
  },
  closeDetail() { this.setData({ detail: null }) },

  noop() {}
})
