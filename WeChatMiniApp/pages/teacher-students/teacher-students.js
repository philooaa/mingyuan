const { request, decorateEnrollment, decorateSchedule, label } = require('../../utils/admin-enrollment')
const ROOT = '/miniapp/teacher/course'

Page({
  data: { mode: 'courses', scheduleId: '', id: '', keyword: '', submittedKeyword: '', courses: [], students: [],
    schedule: null, enrollment: null, history: [], page: 1, total: 0, loading: false, error: '' },
  onLoad(options) {
    this._unloaded = false
    this.setData({ scheduleId: options.scheduleId || '', id: options.id || '',
      mode: options.id ? 'detail' : (options.scheduleId ? 'students' : 'courses') })
  },
  onShow() {
    if (!(wx.getStorageSync('roles') || []).includes('teacher')) {
      wx.reLaunch({ url: '/pages/index/index' })
      return
    }
    this.load(true)
  },
  onUnload() { this._unloaded = true; this._version = (this._version || 0) + 1 },
  onPullDownRefresh() { this.load(true).finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (!this.data.loading && this.data.students.length < this.data.total) this.load(false) },
  inputKeyword(e) { this.setData({ keyword: e.detail.value }) },
  search() {
    const keyword = this.data.keyword.trim()
    this.setData({ mode: keyword ? 'search' : 'courses', scheduleId: '', id: '', submittedKeyword: keyword,
      students: [], courses: [], enrollment: null, schedule: null, history: [], total: 0 })
    this.load(true)
  },
  showCourses() { this.setData({ keyword: '' }); this.search() },
  async load(reset) {
    const version = this._version = (this._version || 0) + 1
    const token = wx.getStorageSync('token')
    const current = () => !this._unloaded && this._version === version && wx.getStorageSync('token') === token
    const { mode, id, scheduleId, submittedKeyword } = this.data
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: true, error: '' })
    try {
      if (mode === 'detail') {
        const result = await request({ url: `${ROOT}/student/${encodeURIComponent(id)}`, timeout: 15000 })
        if (!current()) return
        const info = result.data
        this.setData({ enrollment: decorateEnrollment(info.enrollment), schedule: decorateSchedule(info.schedule),
          history: (info.history || []).map((row, key) => ({ ...row, key, date: String(row.classDate || '').slice(0, 10),
            signText: label(row.signStatus, { 0: '未到课', 1: '到课', 2: '录播', 3: '请假', 4: '试听到课', 5: '调课到课' }),
            sourceText: label(row.sourceType, { 1: '报名', 2: '试听', 3: '调课' }),
            reviewText: String(row.reviewStatus) === '1' ? '已复核' : '待复核' })) })
      } else if (mode === 'courses') {
        const result = await request({ url: `${ROOT}/schedules`, timeout: 15000 })
        if (current()) this.setData({ courses: (result.data || []).map(decorateSchedule) })
      } else {
        const [result, course] = await Promise.all([
          request({ url: `${ROOT}/students`, timeout: 15000,
            data: { ...(scheduleId ? { scheduleId } : {}), keyword: submittedKeyword, pageNum: page, pageSize: 20 } }),
          reset && scheduleId ? request({ url: `${ROOT}/schedule/${encodeURIComponent(scheduleId)}`, timeout: 15000 }) : Promise.resolve(null)
        ])
        if (!current()) return
        const students = (result.rows || []).map(decorateEnrollment)
        this.setData({ students: reset ? students : this.data.students.concat(students), page, total: result.total || 0,
          ...(course ? { schedule: decorateSchedule(course.data) } : {}) })
      }
    } catch (error) {
      if (current()) {
        this.setData({ error: error.message, ...(reset ? { courses: [], students: [], enrollment: null, total: 0 } : {}) })
      }
    } finally { if (current()) this.setData({ loading: false }) }
  },
  openCourse(e) {
    const scheduleId = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/teacher-students/teacher-students?scheduleId=${encodeURIComponent(scheduleId)}` })
  },
  openStudent(e) {
    wx.navigateTo({ url: `/pages/teacher-students/teacher-students?id=${encodeURIComponent(e.currentTarget.dataset.id)}` })
  },
  openStudentCourse() {
    if (this.data.enrollment) this.openCourse({ currentTarget: { dataset: { id: this.data.enrollment.scheduleId } } })
  }
})
