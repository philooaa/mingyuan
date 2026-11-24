const { request, todoRoute } = require('../../utils/attendance-workflow')
const access = require('../../utils/access')
const filters = require('../../utils/attendance-filters')
const submittedLabels = { FINAL: '已确认', PENDING_FINAL: '已签到', PENDING_FIRST: '已签到' }

function teacherLesson(item) {
  const submittedLabel = submittedLabels[item.stage]
  return {
    ...item,
    shortClassDate: (item.classDate || '').slice(5),
    attendanceLabel: submittedLabel || '待签到',
    attendanceTone: item.stage === 'FINAL' ? 'lesson-confirmed' : submittedLabel ? 'lesson-signed' : 'lesson-pending'
  }
}
Page({
  data: { ...filters.state(), reviewByLesson:true, courseId:'', matchedCourses:[], queried:false, appliedFilters:{}, attendanceFilters: {}, filterDate: '', items: [], count: 0, totalCount: 0, overdueCount: 0,pendingSignCount:0,signedCount:0,confirmedCount:0, error: '', loading: false, role: '' },
  onShow() { this._active = true; return this.load() },
  onHide() { this._active = false },
  onUnload() { this._active = false },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
  async load() {
    const token = wx.getStorageSync('token'), role = access.selected(), version = this._version = (this._version || 0) + 1
    if (this.data.role !== role) this.setData({ attendanceFilters: {}, filterDate: '', courseId:'', queried:false, appliedFilters:{} })
    this._items = []
    this.setData({ loading: true, items: [], count: 0, totalCount: 0, overdueCount: 0, error: '', role })
    try {
      const r = await request({ url: '/miniapp/attendance/todos', data: role === 'teacher' ? {role,allLessons:true} : {role} })
      if (this._active && token === wx.getStorageSync('token') && version === this._version && role === access.selected()) {
        this._items = ((r.data || {}).items || []).map(item => ({ ...item, rowKey: `${item.scheduleId}:${item.classDate}` }))
        this._courses = (r.data || {}).courses || []
        this.applyFilters()
      }
    } catch (e) { if (this._active && version === this._version) this.setData({ error: e.message }) }
    finally { if (version === this._version) this.setData({ loading: false }) }
  },
  applyFilters() {
    const all = this._items || [], teacher = this.data.role === 'teacher'
    const selected=this.data.attendanceFilters
    const matchedCourses=teacher&&this.data.queried
      ? (this._courses||[]).filter(course=>filters.matches(course,this.data.appliedFilters,true))
      : []
    const courseId=this.data.courseId
    const items=teacher
      ? all.filter(item => courseId && String(item.scheduleId) === courseId)
        .sort((a, b) => (a.lessonNo || Infinity) - (b.lessonNo || Infinity))
        .map(teacherLesson)
      : all.filter(item=>filters.matches(item,selected,false,true))
    this.setData({...filters.state(teacher ? this._courses||[] : all,selected,teacher,true),
      matchedCourses,items,count:items.length,totalCount:teacher?items.length:all.length,
      overdueCount:items.filter(item=>item.overdue).length,
      pendingSignCount:teacher?items.filter(item=>item.attendanceLabel==='待签到').length:0,
      signedCount:teacher?items.filter(item=>item.attendanceLabel==='已签到').length:0,
      confirmedCount:teacher?items.filter(item=>item.attendanceLabel==='已确认').length:0})
  },
  queryCourses() {
    if(this.data.loading)return
    this.setData({appliedFilters:{...this.data.attendanceFilters},queried:true,courseId:''})
    this.applyFilters()
  },
  chooseCourse(e) {
    const course=this.data.matchedCourses[Number(e.currentTarget.dataset.index)]
    if(!course)return
    this.setData({courseId:String(course.scheduleId),courseTitle:course.courseName,coursePeriod:course.periodName||'',courseTime:course.timeSlot||''})
    this.applyFilters()
  },
  backToCourses() {
    this.setData({courseId:''})
    this.applyFilters()
  },
  onAttendanceFilter(e) { this.setData({ attendanceFilters: filters.change(this.data, e) }); this.applyFilters() },
  onDateFilter(e) { this.setData({ filterDate: e.detail.value }); this.applyFilters() },
  resetAttendanceFilters() { this.setData({ attendanceFilters: {}, filterDate: '',courseId:'',queried:false,appliedFilters:{} }); this.applyFilters() },
  open(e) {
    const item = this.data.items[e.currentTarget.dataset.index]
    if (item && this._active && this.data.role === access.selected()) wx.navigateTo({ url: todoRoute(item, this.data.role) })
  }
})
