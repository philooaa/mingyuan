const childContext = require('../../utils/child-context')
const { request } = require('../../utils/request')
const {requestKey}=require('../../utils/personal-account')

Page(childContext.page({
  data: {
    loading: false,
    scopeAccountId:'',
    submitting: false,
    schedules: [],
    teacherGroups: [], teacherOptions: [], selectedTeacherKeys: [], selectedTeachers: [],
    teacherKeyword: '', showTeacherPicker: false, allVisibleTeachersSelected: false,
    enrollments: [],
    showScheduleEmpty: false,
    showEnrollmentEmpty: false,
    showSheet: false,
    subjects: [],
    grades: [],
    subjectOptions: ['全部科目'],
    gradeOptions: ['全部年级'],
    courseTypeOptions: ['班课', '一对一'],
    courseTypeIndex: 0,
    expandedCourseIds: [],
    subjectIndex: 0,
    gradeIndex: 0,
    selectedSchedule: null,
    lessonAccounts: [], creditAccount:null, lessonAccountOptions: [], lessonAccountIndex:0, selectedChildId:null, newChild:true, childMissing:false, settlementMethod:'C', accountsError:'', accountsLoading:false,
    form: {
      studentName: '',
      contactPhone: ''
    }
  },

  onLoad(options = {}) {
    if(options.accountId)this.setData({scopeAccountId:String(options.accountId),selectedChildId:String(options.accountId),newChild:false})
    this._token = wx.getStorageSync('token')
    this._unloaded = false
    if (options.courseType === '2') this.setData({ courseTypeIndex: 1 })
    this.loadDict().then(() => this.loadPage())
  },

  onShow() {
    if (this._scheduleDirty && this.current()) {
      this._scheduleDirty = false
      return this.loadPage()
    }
    if (this._hasLoadedAccounts && this.current()) return this.loadLessonAccounts()
  },

  onUnload() { this._unloaded = true },

  current() { return !this._unloaded && !!this._token && this._token === wx.getStorageSync('token') && (wx.getStorageSync('roles') || []).includes('parent') },

  onPullDownRefresh() {
    Promise.all([this.loadDict(), this.loadPage()])
      .finally(() => wx.stopPullDownRefresh())
  },

  async loadDict() {
    try {
      const res = await request({ url: '/miniapp/material/dict' })
      const data = res.data || {}
      const subjects = data.subjects || []
      const grades = data.grades || []
      this.setData({
        subjects,
        grades,
        subjectOptions: ['全部科目'].concat(subjects.map((i) => i.dictLabel)),
        gradeOptions: ['全部年级'].concat(grades.map((i) => i.dictLabel))
      })
    } catch (error) {
      // 字典加载失败不阻断主流程
    }
  },

  async loadPage() {
    this.setData({ loading: true })
    try {
      await Promise.all([this.loadSchedules(), this.loadEnrollments(),this.loadLessonAccounts()])
    } catch (error) {
      wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadLessonAccounts() {
    if(!this.current()) return
    const version=this._accountVersion=(this._accountVersion || 0)+1
    this.setData({accountsLoading:true,accountsError:'',creditAccount:null})
    try {
      const res=await request({url:'/miniapp/parent/lesson-accounts'})
      if(!this.current() || version!==this._accountVersion) return
      const accounts=res.data || [],index=accounts.findIndex(a=>String(a.accountId)===String(this.data.selectedChildId))
      const selected=this.data.scopeAccountId || this.data.selectedChildId
      this.setData({creditAccount:selected?accounts.find(a=>String(a.accountId)===String(selected)) || null:accounts[0] || null})
      this.setData({lessonAccounts:accounts,lessonAccountOptions:accounts.map(a=>`${a.studentName} · ${a.gradeName} · 可用额度 ${a.availableCredits} · #${a.accountId}`).concat('＋ 添加孩子信息'),lessonAccountIndex:index>=0?index:accounts.length,childMissing:!!this.data.selectedChildId && index<0})
      if(index>=0)this.setData({'form.studentName':accounts[index].studentName})
    } catch(error) { if(this.current() && version===this._accountVersion) this.setData({accountsError:'孩子资料暂时无法查询，请重试'}) }
    finally { if(this.current() && version===this._accountVersion) {this._hasLoadedAccounts=true;this.setData({accountsLoading:false})} }
  },
  purchasePackage() {
    if(!this.current() || this.data.submitting || this.data.showSheet || this.data.accountsLoading || this.data.accountsError) return
    const account=this.data.creditAccount
    if(!account || !/^[1-9]\d*$/.test(String(account.accountId)) || this.data.scopeAccountId && String(account.accountId)!==this.data.scopeAccountId) return
    wx.navigateTo({url:'/pages/lesson-packages/lesson-packages?accountId='+encodeURIComponent(account.accountId)})
  },
  chooseSettlement(e) {
    if(!this.current() || this.data.submitting || !this.data.selectedSchedule || !this.data.selectedSchedule.classDate) return
    const method=e.currentTarget.dataset.method
    if(!['C','P'].includes(method)) return
    this.setData({settlementMethod:method})
  },
  chooseLessonAccount(e) {
    if(this.data.submitting || this.data.scopeAccountId) return
    const index=Number(e.detail.value),account=this.data.lessonAccounts[index]
    if(index<0 || index>this.data.lessonAccounts.length)return
    this.setData({lessonAccountIndex:index,selectedChildId:account?account.accountId:null,newChild:!account,childMissing:false,'form.studentName':account?account.studentName:'','form.studentRequestKey':requestKey()})
  },
  manageChildren() {if(this.current() && !this.data.submitting)wx.navigateTo({url:'/pages/parent-children/parent-children'})},
  async creditHistory(e) {
    if(!this.current()) return
    try { const res=await request({url:`/miniapp/parent/lesson-accounts/${e.currentTarget.dataset.id}/history`}); if(this.current()) wx.showModal({title:'课次操作记录',showCancel:false,content:(res.data || []).map(r=>`${r.createTime} ${({GRANT:'发放',WITHDRAW:'收回',HOLD:'预约暂占',RELEASE:'释放暂占',CONSUME:'课后消耗'})[r.action] || r.action}${r.quantity}次，剩余${r.remainingAfter}，暂占${r.reservedAfter}\n${r.reason || ''}`).join('\n') || '暂无记录'}) }
    catch(error) { if(this.current()) wx.showToast({title:'记录加载失败',icon:'none'}) }
  },

  async loadSchedules() {
    const version = this._scheduleVersion = (this._scheduleVersion || 0) + 1
    // 现有接口返回全部可报名课程，年级／学科／多位教师统一在同一数据集筛选。
    const [res, single] = await Promise.all([request({ url: '/miniapp/parent/schedules' }), request({ url: '/miniapp/parent/single/lessons' })])
    if (!this.current() || version !== this._scheduleVersion) return
    this._allSchedules = this.decorateSchedules((res.data || []).concat((single.data || []).flatMap(course => (course.lessons || []).map(lesson => ({ ...course, ...lesson, classMode: '2', remainingCount: lesson.canEnroll ? 1 : 0, capacity: 1 })))) ).map(course => ({
      ...course,
      teacherKey: course.teacherId ? `teacher:${course.teacherId}` : `unbound:${course.scheduleId}`
    }))
    const teachers = new Map()
    this._allSchedules.forEach(course => {
      if (!teachers.has(course.teacherKey)) teachers.set(course.teacherKey, {
        key: course.teacherKey, name: course.teacherName || '教师待配置',
        label: course.teacherName || '教师待配置'
      })
    })
    this._teachers = Array.from(teachers.values()).sort((a,b) => a.name.localeCompare(b.name, 'zh-CN') || a.key.localeCompare(b.key))
    this.setData({ selectedTeacherKeys: this.data.selectedTeacherKeys.filter(key => teachers.has(key)) })
    this.applyFilters()
  },

  applyFilters() {
    const subject = this.data.subjectIndex > 0 && this.data.subjects[this.data.subjectIndex - 1]
    const grade = this.data.gradeIndex > 0 && this.data.grades[this.data.gradeIndex - 1]
    const eligible = (this._allSchedules || []).filter(course =>
      this.matchesCourseType(course) &&
      (!subject || course.subjectName === subject.dictLabel) &&
      (!grade || course.gradeName === grade.dictLabel))
    const eligibleKeys = new Set(eligible.map(course => course.teacherKey))
    this._eligibleTeachers = (this._teachers || []).filter(t => eligibleKeys.has(t.key))
    const keys = this.data.selectedTeacherKeys.filter(key => eligibleKeys.has(key))
    this.setData({ selectedTeacherKeys: keys })
    const schedules = eligible.filter(course => !keys.length || keys.includes(course.teacherKey))
    const groups = new Map()
    schedules.forEach(course => {
      if (!groups.has(course.teacherKey)) groups.set(course.teacherKey, { key: course.teacherKey, label: (this._teachers || []).find(t => t.key === course.teacherKey).label, courses: [] })
      groups.get(course.teacherKey).courses.push(course)
    })
    if (this.data.courseTypeIndex === 1) groups.forEach(group => {
      const courses = new Map()
      group.courses.forEach(lesson => {
        const id = String(lesson.scheduleId)
        if (!courses.has(id)) courses.set(id, { ...lesson, viewKey: `course:${id}`, lessons: [], expanded: this.data.expandedCourseIds.includes(id),
          clockText: lesson.startTime && lesson.endTime ? `${String(lesson.startTime).slice(0,5)}–${String(lesson.endTime).slice(0,5)}` : lesson.timeSlot || '',
          dateRangeText: lesson.startDate && lesson.endDate ? `${String(lesson.startDate).slice(0,10)} 至 ${String(lesson.endDate).slice(0,10)}` : '' })
        courses.get(id).lessons.push(lesson)
      })
      group.courses = Array.from(courses.values()).map(course => ({ ...course, lessonCount: course.lessons.length, availableLessonCount: course.lessons.filter(lesson => lesson.canEnroll).length }))
    })
    this.setData({ schedules, teacherGroups: Array.from(groups.values()).sort((a,b) => a.label.localeCompare(b.label, 'zh-CN')), showScheduleEmpty: schedules.length === 0 })
    this.updateTeacherOptions()
  },

  updateTeacherOptions() {
    const keyword = this.data.teacherKeyword.trim().toLowerCase(), keys = this.data.selectedTeacherKeys
    const teachers = this._eligibleTeachers || []
    const teacherOptions = teachers.filter(t => !keyword || t.name.toLowerCase().includes(keyword)).map(t => ({ ...t, selected: keys.includes(t.key) }))
    this.setData({
      teacherOptions,
      allVisibleTeachersSelected: teacherOptions.length > 0 && teacherOptions.every(t => t.selected),
      selectedTeachers: teachers.filter(t => keys.includes(t.key))
    })
  },
  toggleTeacherPicker() { this.setData({ showTeacherPicker: !this.data.showTeacherPicker }) },
  toggleCourseDates(e) {
    if (!this.current() || this.data.submitting || this.data.showSheet) return
    const id = String(e.currentTarget.dataset.id)
    if (!this.data.schedules.some(course => String(course.scheduleId) === id && String(course.classMode) === '2')) return
    const expanded = this.data.expandedCourseIds
    this.setData({ expandedCourseIds: expanded.includes(id) ? expanded.filter(value => value !== id) : expanded.concat(id) })
    this.applyFilters()
  },
  searchTeacher(e) {
    if (!this.current() || this.data.loading || this.data.submitting || this.data.showSheet) return
    this.setData({ teacherKeyword: e.detail.value }); this.updateTeacherOptions()
  },
  chooseTeachers(e) {
    if (!this.current() || this.data.loading || this.data.submitting || this.data.showSheet || !Array.isArray(e.detail.value)) return
    const visible = new Set(this.data.teacherOptions.map(t => t.key))
    const selected = e.detail.value.filter(key => visible.has(key))
    // A checkbox-group reports only rendered checkboxes; retain selections hidden by search.
    const hidden = this.data.selectedTeacherKeys.filter(key => !visible.has(key))
    this.setData({ selectedTeacherKeys: Array.from(new Set(hidden.concat(selected))) })
    this.applyFilters()
  },
  selectAllTeachers() {
    if (!this.current() || this.data.loading || this.data.submitting || this.data.showSheet) return
    this.setData({ selectedTeacherKeys: Array.from(new Set(this.data.selectedTeacherKeys.concat(this.data.teacherOptions.map(t => t.key)))) })
    this.applyFilters()
  },
  toggleTeacher(e) {
    if (!this.current() || this.data.loading || this.data.submitting || this.data.showSheet) return
    const key = e.currentTarget.dataset.key
    const keys = this.data.selectedTeacherKeys
    if (!keys.includes(key)) return
    this.setData({ selectedTeacherKeys: keys.filter(k => k !== key) })
    this.applyFilters()
  },
  clearTeachers() {
    if (!this.current() || this.data.loading || this.data.submitting || this.data.showSheet) return
    this.setData({ selectedTeacherKeys: [], teacherKeyword: '' }); this.applyFilters()
  },

  async loadEnrollments() {
    const res = await request({ url: '/miniapp/parent/enrollments',...(this.data.scopeAccountId?{data:{accountId:this.data.scopeAccountId}}:{}) })
    if (!this.current()) return
    this._allEnrollments = this.decorateEnrollments(res.data || [])
    this.applyEnrollmentFilter()
  },

  matchesCourseType(item) {
    const classMode = String(item.classMode || (item.classDate ? '2' : '1'))
    return classMode === String(this.data.courseTypeIndex + 1)
  },

  applyEnrollmentFilter() {
    const enrollments = (this._allEnrollments || []).filter(item => this.matchesCourseType(item))
    this.setData({
      enrollments,
      showEnrollmentEmpty: enrollments.length === 0
    })
  },

  decorateSchedules(list) {
    return list.map((item) => {
      const enrolled = Number(item.enrolled) === 1
      // 班课不按教室容量限制名额（一对一仍看单节课自身的 canEnroll），因此不再出现「已满」
      const canEnroll = String(item.classMode) === '2' ? !!item.canEnroll : !enrolled
      return {
        ...item, viewKey: `${item.scheduleId}:${item.classDate || 'class'}`,
        subjectText: this.labelOf(this.data.subjects, item.subjectName) || item.subjectName,
        gradeText: this.labelOf(this.data.grades, item.gradeName) || item.gradeName,
        timeText: this.buildTimeText(item),
        placeText: this.buildPlaceText(item),
        countText: `已报名 ${item.enrolledCount || 0} 人`,
        canEnroll,
        statusClass: canEnroll ? 'ok' : 'disabled',
        statusText: item.classDate ? item.statusText : enrolled ? '已报名' : '报名'
      }
    })
  },

  decorateEnrollments(list) {
    return list.map((item) => ({
      ...item,
      settlementText:item.classDate ? (item.settlementMethod==='P' ? `课包 · ${item.lessonCredits || '待核对'}次额度` : '单次付费') : '',
      paymentText:({'0':'未支付','1':'已支付','2':'已退款'})[String(item.payStatus)] || item.payStatus || '未支付',
      subjectText: this.labelOf(this.data.subjects, item.subjectName) || item.subjectName,
      gradeText: this.labelOf(this.data.grades, item.gradeName) || item.gradeName,
      timeText: this.buildTimeText(item),
      placeText: this.buildPlaceText(item),
      statusText: item.classDate ? ({ '0': '待老师确认', '1': '已确认', '2': '已取消', '3': '老师已拒绝' })[String(item.enrollmentStatus)] || '待核对' : ['2','已取消'].includes(String(item.enrollmentStatus)) ? '已取消' : '报名成功',
      canRequestCancel: !!item.classDate && new Date(`${String(item.classDate).slice(0,10)}T${item.startTime}`) > new Date() && ['0','1'].includes(String(item.enrollmentStatus)) && String(item.cancelRequestStatus) !== '1'
    }))
  },

  labelOf(list, value) {
    const hit = list.find((i) => i.dictValue === value)
    return hit ? hit.dictLabel : ''
  },

  openTuition(e) { wx.navigateTo({url:`/pages/tuition-detail/tuition-detail?id=${encodeURIComponent(String(e.currentTarget.dataset.id))}${this.data.scopeAccountId?'&accountId='+encodeURIComponent(this.data.scopeAccountId):''}`}) },

  buildPlaceText(item) {
    if (item.lessonLocation) return item.lessonLocation
    return `${item.campusName || ''}${item.classroomName ? ' · ' + item.classroomName : ''}`
  },

  buildTimeText(item) {
    const start = item.startTime ? String(item.startTime).slice(0, 5) : ''
    const end = item.endTime ? String(item.endTime).slice(0, 5) : ''
    const clock = start && end ? `${start}-${end}` : (item.timeSlot || '')
    if (item.classDate) return `${String(item.classDate).slice(0,10)} ${clock}`
    return `${item.termName || ''} ${item.periodName || ''} ${clock}`.trim()
  },

  onSubjectChange(e) {
    this.setData({ subjectIndex: Number(e.detail.value) }); this.applyFilters()
  },
  onGradeChange(e) {
    this.setData({ gradeIndex: Number(e.detail.value) }); this.applyFilters()
  },
  chooseCourseType(e) {
    this.onCourseTypeChange({ detail: { value: e.currentTarget.dataset.index } })
  },
  onCourseTypeChange(e) {
    if (!this.current() || this.data.submitting || this.data.showSheet) return
    const index = Number(e.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.courseTypeOptions.length || index === this.data.courseTypeIndex) return
    this.setData({ courseTypeIndex: index })
    this.applyFilters()
    this.applyEnrollmentFilter()
  },
  resetFilters() {
    this.setData({ subjectIndex: 0, gradeIndex: 0, selectedTeacherKeys: [], teacherKeyword: '' }); this.applyFilters()
  },

  openEnroll(e) {
    const schedule = this.data.schedules.find((item) => item.viewKey === e.currentTarget.dataset.key)
    if (!schedule) return
    if (!schedule.canEnroll) {
      wx.showToast({ title: schedule.statusText, icon: 'none' })
      return
    }
    const userInfo = wx.getStorageSync('userInfo') || {}
    const selected=this.data.scopeAccountId?this.data.lessonAccounts.find(a=>String(a.accountId)===this.data.scopeAccountId):this.data.lessonAccounts[0]
    this.setData({
      selectedSchedule: schedule,
      settlementMethod:'C',lessonAccountIndex:selected?this.data.lessonAccounts.indexOf(selected):this.data.lessonAccounts.length,selectedChildId:this.data.scopeAccountId||(selected?selected.accountId:null),newChild:!this.data.scopeAccountId&&!selected,childMissing:!!this.data.scopeAccountId&&!selected,
      showSheet: true,
      form: {
        studentName:selected?selected.studentName:'',
        studentRequestKey:requestKey(),
        contactPhone: userInfo.phonenumber || ''
      }
    })
  },

  closeEnroll() {
    if (this.data.submitting) return
    this.setData({
      selectedSchedule: null,
      showSheet: false
    })
  },

  handleFormInput(e) {
    const field = e.currentTarget.dataset.field
    if(this.data.submitting || !['studentName','contactPhone'].includes(field) || (field==='studentName' && !this.data.newChild))return
    this.setData({ [`form.${field}`]: e.detail.value })
  },

  async requestCancel(e) {
    const id = e.currentTarget.dataset.id
    if (!this.current() || this.data.submitting) return
    const result = await new Promise(resolve => wx.showModal({ title: '申请取消本次课', content: '申请待处理期间保留报名与名额，老师同意后取消。', editable: true, placeholderText: '请输入取消原因', success: resolve, fail: () => resolve({confirm:false}) }))
    if (!result.confirm || !this.current() || this.data.submitting) return
    if (!String(result.content || '').trim()) { wx.showToast({title:'请填写取消原因',icon:'none'}); return }
    this.setData({submitting:true})
    try { await request({url:`/miniapp/parent/enrollment/${id}/cancel-request`,method:'POST',data:{reason:result.content}}); if (this.current()) await this.loadPage() }
    catch(error) { if (this.current()) wx.showToast({title:error.message || '申请失败',icon:'none'}) }
    finally { if (this.current()) this.setData({submitting:false}) }
  },
  async viewHistory(e) {
    try { const res = await request({url:`/miniapp/parent/enrollment/${e.currentTarget.dataset.id}/history`}); if (!this.current()) return; wx.showModal({title:'报名操作历史',content:(res.data || []).map(row=>`${row.createTime} ${({SUBMIT:'提交报名',CONFIRM:'确认接课',REJECT:'拒绝报名',REQUEST_CANCEL:'申请取消',APPROVE_CANCEL:'同意取消',REJECT_CANCEL:'拒绝取消'})[row.action] || row.action} ${row.reason || ''}`).join('\n') || '暂无操作记录',showCancel:false}) }
    catch(error) { if(this.current()) wx.showToast({title:error.message || '查询失败',icon:'none'}) }
  },
  async submitEnroll() {
    if (!this.current() || this.data.submitting || !this.data.selectedSchedule) return
    const { selectedSchedule, form } = this.data
    if(this.data.accountsLoading || this.data.accountsError || this.data.childMissing){wx.showToast({title:'请重新加载并选择孩子',icon:'none'});return}
    if (!form.studentName.trim()) {
      wx.showToast({ title: '请输入学生姓名', icon: 'none' })
      return
    }
    const packageMode=!!selectedSchedule.classDate && this.data.settlementMethod==='P',account=this.data.lessonAccounts.find(a=>String(a.accountId)===String(this.data.selectedChildId))
    if(!this.data.newChild && !account){wx.showToast({title:'请重新选择孩子',icon:'none'});return}
    if(packageMode && (this.data.accountsError || this.data.accountsLoading || !account)) { wx.showToast({title:'请先选择有效的学生课包账户',icon:'none'}); return }
    if(packageMode && Number(account.availableCredits)<=0) { wx.showToast({title:'可用课次不足，请联系管理员或选择单次付费',icon:'none'}); return }
    this.setData({ submitting: true })
    try {
      await request({
        url: '/miniapp/parent/enroll',
        method: 'POST',
        data: {
          scheduleId: selectedSchedule.scheduleId,
          ...(selectedSchedule.classDate ? {classDate:selectedSchedule.classDate} : {}),
          studentName: form.studentName.trim(),
          contactPhone: form.contactPhone.trim()
          ,settlementMethod:selectedSchedule.classDate ? this.data.settlementMethod : 'C',lessonAccountId:account?account.accountId:null,studentRequestKey:this.data.newChild?form.studentRequestKey:null
        }
      })
      if (!this.current()) return
      if(typeof getCurrentPages==='function')getCurrentPages().forEach(p=>{p._scheduleDirty=true})
      wx.showToast({ title: selectedSchedule.classDate ? '已提交，待老师确认' : '报名成功', icon: 'success' })
      this.setData({
        selectedSchedule: null,
        showSheet: false
      })
      await this.loadPage()
    } catch (error) {
      if(this.current())wx.showToast({ title: error.message || '报名失败', icon: 'none' })
    } finally {
      if(this.current())this.setData({ submitting: false })
    }
  }
}, {route:'/pages/enrollment/enrollment',detail:false}))
