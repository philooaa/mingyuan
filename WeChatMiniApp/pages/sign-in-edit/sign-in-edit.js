const { request } = require('../../utils/request')

// 各来源可选的签到状态：报名=到课/录播/请假，试听=试听到课/请假，调课=调课到课/请假
const STATUS_OPTIONS = {
  '1': [{ key: '1', label: '到课' }, { key: '2', label: '录播' }, { key: '3', label: '请假' }],
  '2': [{ key: '4', label: '试听到课' }, { key: '3', label: '请假' }],
  '3': [{ key: '5', label: '调课到课' }, { key: '3', label: '请假' }]
}
Object.values(STATUS_OPTIONS).forEach(options => options.push({key:'6',label:'未到课'}))
const STATUS_LABELS = { '1': '到课', '2': '录播', '3': '请假', '4': '试听到课', '5': '调课到课', '6': '未到课' }
const SOURCE_LABELS = { '1': '报名', '2': '试听', '3': '调课' }

function todayStr() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

Page({
  data: {
    scheduleId: null,
    course: '',
    room: '',
    timeText: '',
    classDate: todayStr(),
    rows: [],
    locked: false,
    review: null,
    stats: null,
    loading: false,
    ready: false,
    submitting: false
  },

  onLoad(query) {
    this._unloaded = false
    this.setData({
      scheduleId: String(query.scheduleId || ''),
      classDate: /^\d{4}-\d{2}-\d{2}$/.test(query.classDate || '') ? query.classDate : todayStr(),
      course: decodeURIComponent(query.course || ''),
      room: decodeURIComponent(query.room || ''),
      timeText: decodeURIComponent(query.time || '')
    })
    this.loadDetail()
  },

  async loadDetail() {
    if (this.data.submitting || this._unloaded) return
    const { scheduleId, classDate } = this.data
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    const token = wx.getStorageSync('token')
    const current = () => !this._unloaded && version === this._loadVersion &&
      this.data.classDate === classDate && wx.getStorageSync('token') === token
    this.setData({ loading: true, ready: false, rows: [], stats: null, review: null, locked: false })
    try {
      const detail = await request({
        url: '/miniapp/teacher/sign/detail',
        data: { scheduleId, classDate }
      })
      if (!current()) return
      const data = detail.data || {}
      const review = data.review || {}
      const details = data.details || []
      const stage = data.reviewStage || review.reviewStage
      const reviewed = stage ? !['NEW','RETURNED'].includes(stage) : String(review.reviewStatus) === '1' || !!review.review1AdminId || !!review.review2AdminId
      const approvedLeave = new Set((reviewed ? [] : data.approvedLeaveEnrollmentIds || []).map(String))
      let rows
      if (details.length) {
        rows = details.map((d) => this.decorateRow({
          studentId: d.studentId,
          enrollmentId: d.enrollmentId,
          studentName: d.studentName,
          sourceType: d.sourceType,
          signStatus: approvedLeave.has(String(d.enrollmentId)) ? '3' : d.signStatus,
          remark: d.remark || ''
        }, d.sourceType))
      } else {
        const res = await request({
          url: '/miniapp/teacher/sign/students',
          data: { scheduleId, classDate }
        })
        if (!current()) return
        rows = (res.data || []).map((s) => this.decorateRow({
          studentId: s.studentId || s.student_id,
          enrollmentId: s.enrollmentId || s.enrollment_id,
          studentName: s.studentName || s.student_name,
          sourceType: s.sourceType || ({TRIAL:'2',TRANSFER:'3'}[s.source] || '1'),
          signStatus: approvedLeave.has(String(s.enrollmentId || s.enrollment_id)) ? '3' : s.source === 'TRIAL' ? '4' : s.source === 'TRANSFER' ? '5' : '1',
          remark: ''
        }, s.sourceType || ({TRIAL:'2',TRANSFER:'3'}[s.source] || '1')))
      }
      this._loadedToken = token
      this.setData({
        ready: true,
        submissionVersion: data.submissionVersion || '',
        rows,
        review,
        locked: reviewed,
        stats: this.buildStats(rows)
      })
    } catch (error) {
      if (current()) wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  onUnload() { this._unloaded = true; this._loadVersion = (this._loadVersion || 0) + 1 },

  canEdit() {
    return !this._unloaded && this.data.ready && !this.data.locked && !this.data.loading && !this.data.submitting &&
      wx.getStorageSync('token') === this._loadedToken
  },

  decorateRow(row, sourceType) {
    this._rowSequence = (this._rowSequence || 0) + 1
    sourceType = String(sourceType)
    return {
      ...row,
      rowKey: `student:${this._rowSequence}`,
      signStatus: String(row.signStatus),
      sourceType,
      sourceText: SOURCE_LABELS[sourceType] || '报名',
      statusText: STATUS_LABELS[row.signStatus] || '',
      statusOptions: STATUS_OPTIONS[sourceType] || STATUS_OPTIONS['1'],
      added: sourceType !== '1'
    }
  },

  buildStats(rows) {
    const count = (key) => rows.filter((r) => r.signStatus === key).length
    return {
      attended: count('1'),
      recorded: count('2'),
      leave: count('3'),
      audited: count('4'),
      transferred: count('5'),
      actual: count('1') + count('4') + count('5'),
      total: rows.length
    }
  },

  setStatus(e) {
    if (!this.canEdit()) return
    const { rowKey, key } = e.currentTarget.dataset
    const index = this.data.rows.findIndex((row) => row.rowKey === rowKey)
    const row = this.data.rows[index]
    const status = String(key)
    if (!row || !(STATUS_OPTIONS[row.sourceType] || []).some((option) => option.key === status)) return
    const rows = this.data.rows.map((item, i) => i === index ? { ...item, signStatus: status, statusText: STATUS_LABELS[status] } : item)
    this.setData({ rows, stats: this.buildStats(rows) })
  },

  addAudited() { this.addStudent('2') },

  addTransferred() { this.addStudent('3') },

  addStudent(sourceType) {
    if (!this.canEdit()) return
    wx.showToast({title:'请教务先关联学生档案并设置试听或调课的生效讲次，再刷新点名名单',icon:'none',duration:3000})
  },

  removeRow(e) {
    if (!this.canEdit()) return
    const { rowKey } = e.currentTarget.dataset
    const row = this.data.rows.find((item) => item.rowKey === rowKey)
    if (!row || !row.added) return
    wx.showModal({
      title: '移除学员',
      content: `确定移除「${row.studentName}」吗？`,
      success: (res) => {
        if (!res.confirm || !this.canEdit()) return
        const rows = this.data.rows.filter((item) => item.rowKey !== rowKey)
        this.setData({ rows, stats: this.buildStats(rows) })
      }
    })
  },

  async submit() {
    const { scheduleId, classDate, rows } = this.data
    if (!this.canEdit()) return
    if (!rows.length) {
      wx.showToast({ title: '暂无学员可签到', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      const res = await request({
        url: '/miniapp/teacher/sign/submit',
        method: 'POST',
        data: {
          scheduleId,
          classDate,
          reviewVersion: this.data.review?.reviewVersion || 0,
          submissionVersion: this.data.submissionVersion,
          details: rows.map((r) => ({
            studentId: r.studentId,
            enrollmentId: r.enrollmentId,
            studentName: r.studentName,
            sourceType: r.sourceType,
            signStatus: r.signStatus,
            remark: r.remark || ''
          }))
        }
      })
      if (this._unloaded || wx.getStorageSync('token') !== this._loadedToken) return
      this.setData({ ready: false })
      wx.showToast({ title: `已提交教务审核，实到 ${res.actualCount} 人`, icon: 'none' })
      setTimeout(() => {
        if (!this._unloaded && wx.getStorageSync('token') === this._loadedToken) wx.navigateBack()
      }, 900)
    } catch (error) {
      if (!this._unloaded) wx.showToast({ title: error.message || '提交失败', icon: 'none' })
    } finally {
      if (!this._unloaded) this.setData({ submitting: false })
    }
  }
})
