const { request } = require('../../utils/request')
const calendar = require('../../utils/course-calendar')
const courseState = require('../../utils/teacher-course-state')
const ACTIONS = { SUBMIT: '提交报名', CONFIRM: '确认接课', REJECT: '拒绝报名', REQUEST_CANCEL: '申请取消', APPROVE_CANCEL: '同意取消', REJECT_CANCEL: '拒绝取消',COMPLETE:'确认完成',PAYMENT_STATUS:'付款登记' }
const TAB_KEYS = ['calendar', 'courses', 'bookings', 'records']

Page({
  data: {
    activeTab: 'calendar', tabs: [{key:'calendar',label:'周课表'},{key:'courses',label:'课程列表'},{key:'bookings',label:'报名处理'},{key:'records',label:'课次记录'}],
    loading: false, submitting: false, loadSucceeded: false, loadError: '', refreshError: '',
    timetable: [], visibleCourses: [], rows: [], courseCount: 0, plannedLessonCount: 0, pendingCount: 0, pendingBadge: '',
    calendarFilterIndex: 0, calendarFilterOptions: ['全部课程', '班课', '一对一'],
    weekStart: '', weekLabel: '', weekDays: [], hours: [], pendingCourses: [], stoppedCourses: [],
    weekLessonCount: 0, overlapCount: 0, calendarTopAnchor: '',
    focusedId: '', focusedScheduleId: '', focusedDate: '',
    showCourseSheet: false, selectedCourse: null, selectedDate: '', selectedLessons: []
  },
  onLoad(options = {}) {
    this._token = wx.getStorageSync('token')
    this._unloaded = false
    this._visible = false
    this._courses = []; this._rows = []
    this.setData({focusedId:options.id || '',activeTab:options.id ? 'records' : TAB_KEYS.includes(options.tab) ? options.tab : 'calendar'})
    this.refreshWeek(calendar.formatDate(new Date()))
    this.load()
  },
  onShow() {
    this._visible = true
    if (!this.current()) { this.clearView('登录身份已变化，请返回首页重新进入。'); return }
    if (this._scheduleDirty || this._wasHidden) {
      const background = !this._scheduleDirty && this.data.loadSucceeded
      this._scheduleDirty = false; this._wasHidden = false; this.load({background})
    }
    clearInterval(this._refreshTimer)
    this._refreshTimer = setInterval(()=>{
      if (this._visible && this.current() && this.data.loadSucceeded && !this.data.loading && !this.data.submitting && !this._decisionOpen) this.load({background:true})
    },30000)
  },
  onHide() { this._visible=false; this._wasHidden=true; clearInterval(this._refreshTimer); this._version=(this._version || 0)+1; this._backgroundLoading=false },
  onUnload() { this._unloaded = true; this._visible=false; clearInterval(this._refreshTimer); this._version = (this._version || 0) + 1 },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },
  current() {
    const roles = wx.getStorageSync('roles') || []
    return !this._unloaded && !!this._token && this._token === wx.getStorageSync('token') && roles.includes('teacher')
  },
  ready() { return this.current() && this.data.loadSucceeded && !this.data.loading && !this.data.submitting && !this.data.loadError && !this.data.refreshError },
  clearView(message = '') {
    if (this._unloaded) return
    this._courses = []; this._rows = []
    this.setData({loading:false,loadSucceeded:false,loadError:message,refreshError:'',timetable:[],visibleCourses:[],rows:[],courseCount:0,plannedLessonCount:0,pendingCount:0,pendingBadge:'',showCourseSheet:false,selectedCourse:null,selectedLessons:[]})
    this.refreshWeek(this.data.weekStart)
  },
  retryLoad() { if (!this.data.loading && !this.data.submitting) return this.load() },
  async load({background=false} = {}) {
    if (!this.current()) { this.clearView('请以教师身份进入我的课程。'); return }
    if (background && (this._backgroundLoading || this.data.loading || this.data.submitting || this._decisionOpen)) return
    const version = this._version = (this._version || 0) + 1
    this._backgroundLoading = background
    if (!background) { this.clearView(); this.setData({loading:true}) }
    try {
      const [courses, transactions, dimensions] = await Promise.all([
        request({url:'/miniapp/teacher/course/schedules',timeout:15000}),
        request({url:'/miniapp/teacher/course/single/transactions',timeout:15000}),
        request({url:'/system/courseTimetable/dimensions',timeout:15000})
      ])
      if (!this.current() || version !== this._version) return
      this._timeSlots=(dimensions.data || {}).timeSlots || []
      this._courses = (courses.data || []).map(course => {
        const expanded = calendar.expandCourse(course)
        return {...course,classMode:String(course.classMode),modeText:String(course.classMode)==='2' ? '一对一' : '班课',timeText:this.clockText(course),placeText:course.lessonLocation || course.classroomName || '地点待配置',
          subjectTag:[course.gradeName,course.subjectName].filter(Boolean).join(' · '),lessonCount:expanded.dates.length,pendingReason:expanded.reason,
          dateRangeText:course.startDate && course.endDate ? `${String(course.startDate).slice(0,10)} 至 ${String(course.endDate).slice(0,10)}` : '日期待配置',
          courseStatusText:String(course.status)==='1' ? '已停用' : String(course.recruitStatus || '0')==='0' ? '开放报名' : '已停招'}
      })
      this._rows = (transactions.data || []).map(row => {
        const classDate = String(row.classDate || '').slice(0,10)
        const state = calendar.singleLessonState({},row,classDate)
        return {...row,...state,...courseState.bookingFlags(row),classDate,timeText:this.clockText(row),canComplete:[true,1,'1'].includes(row.canComplete),settlementText:row.settlementMethod==='P' ? `课包 · ${row.lessonCredits || '待核对'}次额度` : `单次付费 · ${({0:'未支付',1:'已支付',2:'已退款'})[row.payStatus] || row.payStatus || '未支付'}`}
      })
      const pendingCount = courseState.pendingCount(this._rows)
      this.setData({loadSucceeded:true,refreshError:'',timetable:this._courses,courseCount:this._courses.length,
        plannedLessonCount:this._courses.reduce((sum,course)=>sum+course.lessonCount,0),pendingCount,pendingBadge:courseState.badgeText(pendingCount)})
      this.refreshWeek(this.data.weekStart)
      this.refreshRecords()
      if (background && this.data.showCourseSheet) {
        const id = this.data.selectedCourse.scheduleId
        if (this._courses.some(course=>String(course.scheduleId)===String(id))) this.showCourse({currentTarget:{dataset:{id,date:this.data.selectedDate}}})
        else this.closeCourse()
      }
    } catch (error) {
      if (this.current() && version===this._version) {
        const message = Number(error.code)===403 ? '暂无查看权限，请联系管理员。' : error.network ? '网络连接失败，请重试。' : '课程暂时无法加载，请重试。'
        if (background) this.setData({refreshError:'课程状态更新失败，请重新加载后再处理或判断空闲时间。',pendingCount:null,pendingBadge:''})
        else this.clearView(message)
      }
    } finally { if (version===this._version) { this._backgroundLoading=false; if (this.current()) this.setData({loading:false}) } }
  },
  clockText(course) { return course.startTime && course.endTime ? `${String(course.startTime).slice(0,5)}–${String(course.endTime).slice(0,5)}` : course.timeSlot || '时间待配置' },
  changeTab(e) {
    const key = e.currentTarget.dataset.key
    if (!this.current() || this.data.submitting || !TAB_KEYS.includes(key)) return
    this.setData({activeTab:key}); this.refreshRecords()
  },
  refreshRecords() {
    let rows = this._rows || []
    if (this.data.focusedId) rows = rows.filter(row=>String(row.enrollmentId)===String(this.data.focusedId))
    if (this.data.focusedScheduleId) rows = rows.filter(row=>String(row.scheduleId)===String(this.data.focusedScheduleId))
    if (this.data.focusedDate) rows = rows.filter(row=>row.classDate===this.data.focusedDate)
    if (this.data.activeTab==='bookings') rows = rows.filter(row=>row.needsHandling)
    this.setData({rows})
  },
  showAll() { this.setData({focusedId:'',focusedScheduleId:'',focusedDate:''}); this.refreshRecords() },
  openPending() { if (!this.ready()) return; this.setData({activeTab:'bookings',focusedId:'',focusedScheduleId:'',focusedDate:''}); this.refreshRecords() },
  refreshWeek(date) {
    const filter = this.data.calendarFilterIndex
    const courses = (this._courses || []).filter(course=>filter===0 || course.classMode===String(filter))
    const week=calendar.buildWeek(courses,date,this._timeSlots),axisKey=JSON.stringify(week.calendarTimeline)
    if(this._axisKey!==axisKey) { this._axisKey=axisKey; this._axisRevision=(this._axisRevision || 0)+1; week.calendarTopAnchor='calendar-top-'+this._axisRevision }
    this.setData({...week,visibleCourses:courses})
  },
  onCalendarFilterChange(e) {
    const index = Number(e.detail.value)
    if (!this.current() || this.data.submitting || !Number.isInteger(index) || index<0 || index>2) return
    this.setData({calendarFilterIndex:index}); this.refreshWeek(this.data.weekStart)
  },
  previousWeek() { this.shiftWeek(-7) },
  nextWeek() { this.shiftWeek(7) },
  shiftWeek(days) { this.refreshWeek(calendar.formatDate(calendar.addDays(calendar.weekStart(this.data.weekStart),days))) },
  currentWeek() { this.refreshWeek(calendar.formatDate(new Date())) },
  chooseDate(e) { this.refreshWeek(e.detail.value) },
  openStudents(e) {
    if (!this.ready()) return
    const id = e.currentTarget.dataset.id
    if (id != null && !(this._courses || []).some(course=>String(course.scheduleId)===String(id))) return
    wx.navigateTo({url:'/pages/teacher-students/teacher-students'+(id == null ? '' : `?scheduleId=${encodeURIComponent(id)}`)})
  },
  showCourse(e) {
    if (!this.ready()) return
    const course = (this._courses || []).find(item=>String(item.scheduleId)===String(e.currentTarget.dataset.id))
    if (!course) return
    const date = e.currentTarget.dataset.date || ''
    const lessons = calendar.expandCourse(course).dates.filter(value=>!date || value===date).map(classDate=>{
      const booking = (course.singleBookings || []).find(row=>String(row.classDate).slice(0,10)===classDate)
      const state = course.classMode==='2' ? calendar.singleLessonState(course,booking,classDate) : {statusText:'班课',lessonState:'class-course'}
      return {classDate,...state,studentName:booking ? booking.studentName : ''}
    })
    this.setData({showCourseSheet:true,selectedCourse:course,selectedDate:date,selectedLessons:lessons})
  },
  closeCourse() { this.setData({showCourseSheet:false,selectedCourse:null,selectedLessons:[]}) },
  openCourseRecords(e) {
    if (!this.ready() || !this.data.selectedCourse || this.data.selectedCourse.classMode!=='2') return
    const date = e.currentTarget.dataset.date || this.data.selectedDate || ''
    this.setData({activeTab:'records',focusedId:'',focusedScheduleId:String(this.data.selectedCourse.scheduleId),focusedDate:date})
    this.closeCourse(); this.refreshRecords()
  },
  async decide(e) {
    if (!this.ready()) return
    const {id,action} = e.currentTarget.dataset
    const row = (this._rows || []).find(item=>String(item.enrollmentId)===String(id))
    const label = ({CONFIRM:'确认接课',REJECT:'拒绝报名',APPROVE_CANCEL:'同意取消',REJECT_CANCEL:'拒绝取消'})[action]
    if (!row || !row.canHandle || !label || (action.endsWith('CANCEL') ? !row.isCancel : !row.isPending || row.isCancel)) return
    const version = this._version
    this._decisionOpen=true
    let result
    try { result = await new Promise(resolve=>wx.showModal({title:label,content:action==='CONFIRM'?`确认接下 ${row.classDate} 的这节2小时课程？`:'请填写处理原因，原始报名和历史记录将保留。',editable:action!=='CONFIRM',placeholderText:'处理原因',success:resolve,fail:()=>resolve({confirm:false})})) }
    finally { this._decisionOpen=false }
    if (!result.confirm || !this.ready() || version!==this._version) return
    if (action!=='CONFIRM' && !String(result.content || '').trim()) { wx.showToast({title:'请填写处理原因',icon:'none'}); return }
    this.setData({submitting:true})
    try {
      await request({url:`/miniapp/teacher/course/single/${id}/decision`,method:'POST',data:{action,reason:result.content || ''}})
      if (!this.current()) return
      getCurrentPages().forEach(page=>{page._scheduleDirty=true})
      this._scheduleDirty=false
      await this.load()
    } catch (error) { if (this.current()) wx.showToast({title:error.applicationRejected ? error.message : '处理失败，请刷新后重试。',icon:'none'}) }
    finally { if (this.current()) this.setData({submitting:false}) }
  },
  async history(e) {
    if (!this.ready()) return
    const id = e.currentTarget.dataset.id
    if (!(this._rows || []).some(row=>String(row.enrollmentId)===String(id))) return
    try {
      const res = await request({url:`/miniapp/teacher/course/single/${id}/history`})
      if (!this.current()) return
      wx.showModal({title:'报名处理历史',showCancel:false,content:(res.data || []).map(row=>`${row.createTime} ${ACTIONS[row.action] || row.action} ${row.actorName || ''} ${row.reason || ''}`).join('\n') || '暂无处理历史'})
    } catch (error) { if (this.current()) wx.showToast({title:'历史记录暂时无法加载。',icon:'none'}) }
  },
  async complete(e) {
    if(!this.ready())return
    const id=e.currentTarget.dataset.id,row=this._rows.find(r=>String(r.enrollmentId)===String(id))
    if(!row || !row.canComplete)return
    const result=await new Promise(resolve=>wx.showModal({title:'确认本次课程完成？',content:row.settlementMethod==='P' ? `将正式消耗暂占的${row.lessonCredits}次课包额度，保留签到和消耗记录。` : '记录本次课程完成，不扣课包额度。',success:resolve,fail:()=>resolve({confirm:false})}))
    if(!result.confirm || !this.ready())return
    this.setData({submitting:true})
    try{await request({url:`/miniapp/teacher/course/single/${id}/complete`,method:'POST'});if(this.current()){getCurrentPages().forEach(p=>{p._scheduleDirty=true});await this.load()}}
    catch(error){if(this.current())wx.showToast({title:error.applicationRejected?error.message:'完成状态未确认，请刷新核对',icon:'none'})}
    finally{if(this.current())this.setData({submitting:false})}
  },
  async attendance(e) {
    if (!this.ready()) return
    const id = e.currentTarget.dataset.id
    const selected = (this._rows || []).find(row=>String(row.enrollmentId)===String(id))
    if (!selected) return
    try {
      const res = await request({url:`/miniapp/teacher/course/student/${id}`})
      if (!this.current()) return
      const history = ((res.data || {}).history || []).filter(row=>String(row.classDate).slice(0,10)===selected.classDate)
      wx.showModal({title:'上课记录',showCancel:false,content:history.map(row=>`${String(row.classDate).slice(0,10)} ${({0:'未签到',1:'到课',2:'录播',3:'请假',4:'试听到课',5:'调课到课'})[String(row.signStatus)] || '待核对'} · ${String(row.reviewStatus)==='1' ? '已复核' : '待复核'}`).join('\n') || '暂无该课次的上课记录'})
    } catch (error) { if (this.current()) wx.showToast({title:'上课记录暂时无法加载。',icon:'none'}) }
  }
})
