const { request } = require('../../utils/request')
const calendar = require('../../utils/course-calendar')

const MODE = [{ value: '1', label: '班课' }, { value: '2', label: '一对一' }]
const PATTERN = [{ value: 'WEEKLY', label: '每周一次（周六／周日）' }, { value: 'DAILY_5_1', label: '上5天休1天' }]
const STATUS = [{ value: '0', label: '正常' }, { value: '1', label: '停用' }]
const RECRUIT = [{ value: '0', label: '可报名' }, { value: '1', label: '停招' }, { value: '2', label: '满班' }]

Page({
  data: {
    loading: true, submitting: false, error: '', editing: false, teacherName: '',
    form: {}, pickers: []
  },
  onLoad(options) {
    this._token = wx.getStorageSync('token')
    this._unloaded = false
    const roles = wx.getStorageSync('roles') || []
    if (!roles.includes('teacher')) {
      this.setData({ loading: false, error: '请以教师身份进入排课' })
      return
    }
    this._id = options.id || ''
    if(!this._id) { wx.redirectTo({url:'/pages/teacher-schedule-create/teacher-schedule-create'}); return }
    if (this._id && !/^[1-9]\d*$/.test(this._id)) {
      this.setData({ loading: false, error: '排课编号无效' }); return
    }
    this.loadForm()
  },
  onUnload() { this._unloaded = true },
  onShow() {
    if(!this.current()) { this.setData({form:{},pickers:[],loading:false,error:'登录身份已变化，请返回首页重新进入'}); return }
    if(this._scheduleDirty && this._id) { this._scheduleDirty=false; this.loadForm() }
  },
  current() { const roles=wx.getStorageSync('roles') || []; return !this._unloaded && !!this._token && this._token === wx.getStorageSync('token') && roles.includes('teacher') },
  async loadForm() {
    this.setData({ loading: true, error: '' })
    try {
      const [dimensions, detail, profile] = await Promise.all([
        request({ url: '/system/courseTimetable/dimensions' }),
        this._id ? request({ url: `/miniapp/teacher/course/schedule/${this._id}/edit` }) : Promise.resolve({ data: {} }),
        request({ url: '/miniapp/teacher/course/profile' })
      ])
      if (!this.current()) return
      this._dims = dimensions.data || {}
      const course = detail.data || {}
      // 只提交现有业务字段，不带创建信息、报名人数、删除标志或可伪造的教师归属。
      const form = {
        scheduleId: course.scheduleId || null, scheduleCode: course.scheduleCode || '',
        courseYear: course.courseYear || this._dims.currentYear || new Date().getFullYear(),
        classroomId: course.classroomId || '', lessonLocation: course.lessonLocation || '', termName: course.termName || '', periodName: course.periodName || '',
        timeSlot: course.timeSlot || '', gradeName: course.gradeName || '', subjectName: course.subjectName || (profile.data || {}).teacherSubject || '',
        classType: course.classType || '', classMode: course.classMode || (this._id ? '1' : '2'),
        startTime: course.startTime || (this._id ? '' : '08:00:00'), endTime: course.endTime || '',
        startDate: course.startDate || (this._id ? '' : calendar.formatDate(new Date())), endDate: course.endDate || '',
        classPattern: course.classPattern || (this._id ? '' : 'DAILY_5_1'),
        courseClassName: course.courseClassName || '', recruitStatus: course.recruitStatus || '0',
        status: course.status || '0', remark: course.remark || ''
      }
      this.setData({ form, simpleMode:String(form.classMode) === '2', editing:!!this._id, teacherName:this._dims.currentUserName || this._dims.currentUserNick || '当前教师',teacherLevelText:({elite:'精英教师',senior:'资深教师'})[(profile.data || {}).teacherLevel] || '后台未配置',subjectConfigured:!!(profile.data || {}).teacherSubject,enrolledCount:course.enrolledCount || 0 })
      this.updatePickers()
      this.updatePreview()
    } catch (error) {
      if (this.current()) this.setData({ error: error.message || '加载失败，请重试' })
    } finally { if (this.current()) this.setData({ loading: false }) }
  },
  updatePickers() {
    const dims = this._dims || {}, form = this.data.form
    const rooms = (dims.classroomList || []).map(room => ({ value: room.classroomId, label: `${room.campusName || '未分校区'} · ${room.classroomName}` }))
    const periods = ['春季', '秋季'].includes(form.termName) ? [{ value: '周六', label: '周六' }, { value: '周日', label: '周日' }] : dims.periods
    const definitions = [
      ['classroomId', '教室', rooms], ['termName', '学期', dims.terms], ['periodName', '期次／上课日', periods],
      ['timeSlot', '时段', dims.timeSlots], ['gradeName', '年级', dims.grades], ['subjectName', '学科', dims.subjects],
      ['classType', '班型', dims.classTypes], ['classMode', '授课形式', this._id ? MODE : MODE.filter(item => item.value === '2')], ['classPattern', '上课模式', PATTERN],
      ['recruitStatus', '招生状态', RECRUIT], ['status', '排课状态', STATUS]
    ]
    const visible = this.data.simpleMode ? definitions.filter(item => ['gradeName'].includes(item[0]) || (this._id && ['recruitStatus','status'].includes(item[0]))) : definitions.filter(item => form.classMode !== '2' || !['classroomId','subjectName','classMode'].includes(item[0]))
    const pickers = visible.map(([field, label, source]) => {
      const options = (source || []).map(option => ({ value: option.value, label: option.label }))
      if (form[field] && !options.some(option => String(option.value) === String(form[field]))) options.unshift({ value: form[field], label: String(form[field]) })
      if (field !== 'classMode' || this._id) options.unshift({ value: '', label: `请选择${label}` })
      const index = Math.max(0, options.findIndex(option => String(option.value) === String(form[field])))
      return { field, label, options, index, text: options[index].label }
    })
    this.setData({ pickers })
  },
  input(e) {
    if (!this.current() || this.data.submitting) return
    const field = e.currentTarget.dataset.field
    if (!['courseClassName', 'courseYear', 'remark', 'lessonLocation'].includes(field)) return
    this.setData({ [`form.${field}`]: e.detail.value })
  },
  choose(e) {
    if (!this.current() || this.data.submitting) return
    const picker = this.data.pickers.find(item => item.field === e.currentTarget.dataset.field)
    const option = picker && picker.options[Number(e.detail.value)]
    if (!option) return
    this.setData({ [`form.${picker.field}`]: option.value })
    if (picker.field === 'termName') this.setData({ 'form.periodName': '', 'form.classPattern': ['春季', '秋季'].includes(option.value) ? 'WEEKLY' : 'DAILY_5_1' })
    if (picker.field === 'periodName') this.setData({ 'form.classPattern': ['周一', '周二', '周三', '周四', '周五', '周六', '周日'].includes(option.value) ? 'WEEKLY' : 'DAILY_5_1' })
    if (picker.field === 'timeSlot' && !this.data.editing) {
      const times = String(option.value).match(/(\d{1,2}:\d{2})\D+(\d{1,2}:\d{2})/)
      if (times) this.setData({ 'form.startTime': times[1].padStart(5, '0') + ':00', 'form.endTime': times[2].padStart(5, '0') + ':00' })
    }
    this.updatePickers()
  },
  updatePreview() {
    this.setData({ startPickerTime: String(this.data.form.startTime || '').slice(0, 5), endPickerTime: String(this.data.form.endTime || '').slice(0, 5) })
  },
  chooseDate(e) { this.setField(e, e.detail.value) },
  chooseTime(e) { this.setField(e, e.detail.value + ':00') },
  setField(e, value) {
    if (!this.current() || this.data.submitting) return
    const field = e.currentTarget.dataset.field
    if (['startDate', 'endDate', 'startTime', 'endTime'].includes(field)) { this.setData({ [`form.${field}`]: value }); this.updatePreview() }
  },
  validation() {
    const form = this.data.form
    if (!this.data.editing && form.classMode !== '2') return '教师只能新增一对一课程'
    if (this.data.simpleMode) {
      for (const [field, label] of [['gradeName', '年级'], ['courseClassName', '课名']]) {
        if (!String(form[field] || '').trim()) return `请填写${label}`
      }
      return ''
    }
    for (const [field, label] of [['classroomId', '教室'], ['termName', '学期'], ['periodName', '期次／上课日'], ['timeSlot', '时段'], ['gradeName', '年级'], ['subjectName', '学科'], ['courseClassName', '课程班名称']]) {
      if (field === 'classroomId' && form.classMode === '2') { if (!form.lessonLocation.trim()) return '请填写上课地点'; continue }
      if (!String(form[field] || '').trim()) return `请填写${label}`
    }
    const start = calendar.timeMinutes(form.startTime), end = calendar.timeMinutes(form.endTime)
    if (start === null || end === null || start >= end) return '结束时间必须晚于开始时间'
    if (form.classMode === '2' && end - start !== 120) return '一对一每次固定2小时'
    if (!!form.startDate !== !!form.endDate) return '开课和结课日期需同时填写'
    if (form.startDate && form.startDate > form.endDate) return '结课日期不能早于开课日期'
    if (form.startDate && form.classPattern === 'WEEKLY' && !['周一', '周二', '周三', '周四', '周五', '周六', '周日'].includes(form.periodName)) return '每周排课请选择正确的上课星期'
    if (!/^\d{4}$/.test(String(form.courseYear)) || Number(form.courseYear) < 2000 || Number(form.courseYear) > 2100) return '请填写正确的课程年份'
    return ''
  },
  async save() {
    if (!this._id || !this.current() || this.data.loading || this.data.error || this.data.submitting) return
    const error = this.validation()
    if (error) { wx.showToast({ title: error, icon: 'none' }); return }
    if (!this.data.form.startDate) {
      const confirmed = await this.confirm('保存待排课程？', '未配置日期的课程会列在待排区，不会出现在周网格，也不能作为准确空闲时间的依据。')
      if (!confirmed || !this.current() || this.data.submitting) return
    }
    this.setData({ submitting: true })
    try {
      const form = this.data.form
      let payload = { ...form, classPattern: form.classPattern || null, startDate: form.startDate || null, endDate: form.endDate || null, classroomId: form.classMode === '2' ? null : Number(form.classroomId), courseYear: Number(form.courseYear),
        courseClassName: form.courseClassName.trim(), remark: form.remark.trim() }
      if (this.data.editing && this.data.simpleMode) {
        payload={scheduleId:form.scheduleId,courseClassName:form.courseClassName.trim(),gradeName:form.gradeName,recruitStatus:form.recruitStatus,status:form.status,remark:form.remark.trim()}
      }
      await request({ url: '/miniapp/teacher/course/schedule', method: 'PUT', data: payload })
      if (!this.current()) return
      wx.showToast({ title: '已保存', icon: 'success' })
      this.backToCalendar()
    } catch (error) {
      if (this.current()) wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    } finally { if (this.current()) this.setData({ submitting: false }) }
  },
  reselectTime() { if(this.current() && !this.data.submitting && !this.data.loading) wx.navigateTo({url:`/pages/teacher-schedule-create/teacher-schedule-create?id=${this._id}`}) },
  async remove() {
    if (!this.current() || !this.data.editing || this.data.loading || this.data.error || this.data.submitting) return
    const confirmed = await this.confirm('删除排课？', '仅无报名、考勤、调课或作业引用的排课可删除。有历史记录请把排课状态改为停用。')
    if (!confirmed || !this.current() || this.data.submitting) return
    this.setData({ submitting: true })
    try {
      await request({ url: `/miniapp/teacher/course/schedule/${this._id}`, method: 'DELETE' })
      if (!this.current()) return
      wx.showToast({ title: '已删除', icon: 'success' })
      this.backToCalendar()
    } catch (error) { if (this.current()) wx.showToast({ title: error.message || '删除失败', icon: 'none' }) }
    finally { if (this.current()) this.setData({ submitting: false }) }
  },
  confirm(title, content) { return new Promise(resolve => wx.showModal({ title, content, success: result => resolve(result.confirm), fail: () => resolve(false) })) },
  backToCalendar() {
    const pages = getCurrentPages(), previous = pages[pages.length - 2]
    pages.forEach(page => { if (page !== this) page._scheduleDirty = true })
    wx.navigateBack()
  }
})
