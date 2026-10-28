const childContext = require('../../utils/child-context')
const { request } = require('../../utils/request')

Page(childContext.page({
  data: {
    loading: false,
    loadError: '',
    loadSucceeded: false,
    teacherMode: false,
    cancelingId: '',
    timetable: [],
    groupedTimetable: [],
    timetableCount: 0,
    showEmpty: false
  },

  onLoad(options={}) {
    this.accountId=options.accountId?String(options.accountId):''
    this._unloaded = false
    if (this.redirectTeacher()) return
    this.loadTimetable()
  },

  onShow() {
    if (this.redirectTeacher()) return
    if (this._scheduleDirty || this._loadedToken !== wx.getStorageSync('token')) {
      this._scheduleDirty = false
      this.setData({ timetable: [], groupedTimetable: [], timetableCount: 0 })
      this.loadTimetable()
    }
  },

  redirectTeacher() {
    const roles = wx.getStorageSync('roles') || []
    const teacherMode = roles.includes('teacher') && !roles.includes('admin')
    this.setData({teacherMode})
    if (!teacherMode) return false
    this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({timetable:[],groupedTimetable:[],timetableCount:0,loadSucceeded:false,loading:false})
    if (!this._redirecting) {
      this._redirecting = true
      wx.redirectTo({url:'/pages/teacher-single-transactions/teacher-single-transactions',
        fail:()=>{if (!this._unloaded) this._redirecting=false}})
    }
    return true
  },

  onUnload() { this._unloaded = true; this._loadVersion = (this._loadVersion || 0) + 1 },

  onPullDownRefresh() {
    this.loadTimetable().finally(() => wx.stopPullDownRefresh())
  },

  retryLoad() {
    if (!this.data.loading) return this.loadTimetable()
  },

  async loadTimetable() {
    if (this.redirectTeacher()) return
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    const token = wx.getStorageSync('token')
    const current = () => !this._unloaded && version === this._loadVersion && wx.getStorageSync('token') === token
    this._loadedToken = token
    this.setData({ loading: true, loadError: '', loadSucceeded: false, timetable: [], groupedTimetable: [],
      timetableCount: 0, showEmpty: false })
    try {
      const res = await request({ url: '/miniapp/parent/timetable', ...(this.accountId?{data:{accountId:this.accountId}}:{}), timeout: 15000 })
      if (!current()) return
      const timetable = this.decorateTimetable(res.data || [])
      this.setData({
        loadSucceeded: true,
        timetable,
        groupedTimetable: this.groupByPeriod(timetable),
        timetableCount: timetable.length,
        showEmpty: timetable.length === 0
      })
    } catch (error) {
      if (current()) {
        let message = '课表暂时无法加载，请稍后重试。'
        if (error.network) message = '网络连接失败，请检查网络后重试。'
        if (Number(error.code) === 403) message = '暂时没有查看课表的权限，请联系管理员。'
        this.setData({ loadError: message, loadSucceeded: false, showEmpty: false })
      }
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  decorateTimetable(list) {
    return list.map((item) => {
      const hasDates = item.startDate || item.endDate
      const next = this.computeNextClass(item)
      return {
        ...item,
        cancelRequestPending: !!item.classDate && String(item.cancelRequestStatus) === '1',
        viewKey: String(item.enrollmentId || item.scheduleId),
        timeText: this.buildTimeText(item),
        placeText: item.lessonLocation || item.classroomName || '',
        subjectTag: this.joinText([item.gradeName, item.subjectName]),
        payText: item.payStatus === '已支付' ? '已支付' : '未支付',
        nextClassDate: next ? next.date : '',
        nextClassWeekday: next ? next.weekday : '',
        nextClassEnded: hasDates && !next,   // 有日期才可能"已结课"，否则是"待配置"
        nextClassConfigured: hasDates
      }
    })
  },

  // 计算下一次课的具体日期与周几
  computeNextClass(item) {
    const dates = this.buildDateList(item)
    if (!dates.length) return null
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const future = dates.find((d) => d >= today)
    if (future) return { date: this.fmtDate(future), weekday: this.weekdayText(future) }
    return null
  },

  // 把后端各种格式的 DATE 值统一解析为 Date
  parseDate(val) {
    if (!val) return null
    if (val instanceof Date) return val
    if (typeof val === 'number') {
      const d = new Date(val)
      if (!isNaN(d.getTime())) return d
    }
    const s = String(val)
    if (s.indexOf('T') >= 0 || s.indexOf(' ') >= 0) {
      const d = new Date(s)
      if (!isNaN(d.getTime())) return d
    }
    const m = s.match(/^(\d{4})[-\/.](\d{1,2})[-\/.](\d{1,2})/)
    if (m) {
      const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
      if (!isNaN(d.getTime())) return d
    }
    return null
  },

  // 按上课模式 + 调课表生成全部上课日期
  buildDateList(item) {
    if (item.classDate) { const date = this.parseDate(item.classDate); return date ? [date] : [] }
    const start = this.parseDate(item.startDate)
    const end = this.parseDate(item.endDate)
    if (!start || !end) return []
    if (start.getTime() > end.getTime()) return []

    let pattern = item.classPattern
    if (!pattern) {
      if (item.periodName === '周六' || item.periodName === '周日') {
        pattern = 'WEEKLY'
      } else {
        pattern = 'DAILY_5_1'
      }
    }
    let dates = []

    if (pattern === 'WEEKLY') {
      const targetDay = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'].indexOf(item.periodName)
      if (targetDay < 0) return []
      let cur = new Date(start)
      while (cur <= end && cur.getDay() !== targetDay) {
        cur.setDate(cur.getDate() + 1)
      }
      while (cur <= end) {
        dates.push(new Date(cur.getFullYear(), cur.getMonth(), cur.getDate()))
        cur.setDate(cur.getDate() + 7)
      }
    } else if (pattern === 'DAILY_5_1') {
      let cur = new Date(start.getFullYear(), start.getMonth(), start.getDate())
      let cnt = 0
      const stop = new Date(end.getFullYear(), end.getMonth(), end.getDate())
      while (cur <= stop) {
        if (cnt % 6 < 5) dates.push(new Date(cur.getFullYear(), cur.getMonth(), cur.getDate()))
        cur.setDate(cur.getDate() + 1)
        cnt++
      }
    }

    // 应用调课表
    const adj = item.adjustments || []
    const adjByOrig = {}
    adj.forEach((a) => {
      if (a.originalDate) {
        const d = this.parseDate(a.originalDate)
        if (d) adjByOrig[this.fmtDate(d)] = a
      }
    })
    let result = []
    dates.forEach((d) => {
      const key = this.fmtDate(d)
      const a = adjByOrig[key]
      if (a) {
        if (a.adjustedDate) {
          const ad = this.parseDate(a.adjustedDate)
          if (ad) result.push(ad)
        }
        // adjustedDate 为空 = 停课，跳过
      } else {
        result.push(d)
      }
    })

    // 去重 + 排序
    const seen = {}
    return result.filter((d) => {
      const k = this.fmtDate(d)
      if (seen[k]) return false
      seen[k] = true
      return true
    }).sort((a, b) => a - b)
  },

  fmtDate(d) {
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${y}-${m}-${day}`
  },

  weekdayText(d) {
    const names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    return names[d.getDay()]
  },

  buildTimeText(item) {
    const start = item.startTime ? String(item.startTime).slice(0, 5) : ''
    const end = item.endTime ? String(item.endTime).slice(0, 5) : ''
    if (start && end) return `${start}-${end}`
    return item.timeSlot || ''
  },

  joinText(parts) {
    return parts.filter(Boolean).join(' · ')
  },

  groupByPeriod(list) {
    const groups = []
    list.forEach((item) => {
      const key = this.joinText([item.termName || '课程', item.periodName])
      let group = groups.find((entry) => entry.key === key)
      if (!group) {
        group = { key, items: [] }
        groups.push(group)
      }
      group.items.push(item)
    })
    return groups
  },

  goEnrollment() {
    if (this.data.teacherMode) return
    wx.navigateTo({ url: '/pages/enrollment/enrollment'+(this.accountId?'?accountId='+encodeURIComponent(this.accountId):'') })
  },

  async handleCancel(e) {
    if (this.data.teacherMode || this.data.loading || !this.data.loadSucceeded || this.data.loadError || this._cancelBusy) return
    const { enrollmentId } = e.currentTarget.dataset || {}
    const record = this.data.timetable.find(item => String(item.enrollmentId) === String(enrollmentId))
    if (!enrollmentId || !record) {
      wx.showToast({ title: '报名信息异常', icon: 'none' })
      return
    }
    const single = !!record.classDate
    if (single && String(record.cancelRequestStatus) === '1') {
      wx.showToast({ title: '取消申请待老师处理', icon: 'none' })
      return
    }
    const token = this._loadedToken
    const current = () => !!token && !this._unloaded && token === wx.getStorageSync('token') && !this.data.teacherMode
    if (!current()) return
    this._cancelBusy = true
    this.setData({ cancelingId: String(enrollmentId) })
    let showingLoading = false
    try {
      const confirmed = await new Promise((resolve) => {
        wx.showModal({
          title: single ? '确认申请取消本次课？' : '确认取消报名？',
          content: `确定要${single ? '申请取消' : '取消'}「${record.studentName || '学生'}」的「${record.courseClassName || '该课程'}」吗？\n${single ? '老师同意后才会取消；待处理期间保留报名、名额及课包暂占。' : '取消后将立即从课表中移除。'}`,
          confirmText: single ? '提交申请' : '确认取消',
          cancelText: '再想想',
          confirmColor: '#c84444',
          success(res) { resolve(res.confirm) },
          fail() { resolve(false) }
        })
      })
      if (!confirmed || !current()) return
      wx.showLoading({ title: single ? '提交中...' : '取消中...', mask: true })
      showingLoading = true
      await request(single ? {
        url: `/miniapp/parent/enrollment/${enrollmentId}/cancel-request`, method: 'POST',
        data: { reason: '家长在我的课表确认申请取消本次课' }
      } : { url: `/miniapp/parent/enrollment/${enrollmentId}`, method: 'DELETE' })
      if (!current()) return
      wx.hideLoading(); showingLoading = false
      getCurrentPages().forEach(page => { if (page !== this) page._scheduleDirty = true })
      wx.showToast({ title: single ? '已提交取消申请' : '已取消报名', icon: 'success' })
      await this.loadTimetable()
    } catch (error) {
      if (current()) wx.showToast({ title: error.message || (single ? '申请失败，请重试' : '取消失败'), icon: 'none' })
    } finally {
      if (showingLoading) wx.hideLoading()
      this._cancelBusy = false
      if (!this._unloaded) this.setData({ cancelingId: '' })
    }
  }
}, {route:'/pages/timetable/timetable',detail:false}))
