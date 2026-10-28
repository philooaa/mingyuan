const { request } = require('../../utils/request')
const calendar = require('../../utils/course-calendar')

Page({
  data: { compactCreation:true,loading:false,loadSucceeded:false,loadError:'',submitting:false,weekStart:'',weekDays:[],hours:[],calendarTopAnchor:'',
    pendingCourses:[],stoppedCourses:[],weekLessonCount:0,overlapCount:0,showSheet:false,selectedSlot:null,
    courseClassName:'',gradeIndex:0,gradeOptions:[],roomIndex:0,roomOptions:[],subject:'',teacherLevelText:'',pendingScheduleCount:0 },
  onLoad(options={}) { this._token=wx.getStorageSync('token'); this._alive=true; this._id=options.id || ''; if(this._id && !/^[1-9]\d*$/.test(this._id)) { this.clear('课程编号无效'); return }; this.setData({weekStart:calendar.formatDate(new Date())}); this.load() },
  onShow() { if (this._alive && !this.current()) this.clear('登录身份已变化，请返回首页重新进入。') },
  onUnload() { this._alive=false; this._version=(this._version || 0)+1 },
  onPullDownRefresh() { this.load().finally(()=>wx.stopPullDownRefresh()) },
  current() { const roles=wx.getStorageSync('roles') || []; return this._alive && !!this._token && this._token===wx.getStorageSync('token') && roles.includes('teacher') },
  clear(message) { this._cells=[]; this._courses=[]; this.setData({loadSucceeded:false,loading:false,loadError:message,weekDays:[],showSheet:false,selectedSlot:null}) },
  async load() {
    if (!this.current() || this.data.submitting) return
    const version=this._version=(this._version || 0)+1, date=this.data.weekStart
    this.setData({loading:true,loadSucceeded:false,loadError:'',showSheet:false})
    try {
      const availabilityQuery={date}
      if(this._id) availabilityQuery.excludeId=this._id
      const [courses,available,profile,dict]=await Promise.all([
        request({url:'/miniapp/teacher/course/schedules'}), request({url:'/miniapp/teacher/course/availability',data:availabilityQuery}),
        request({url:'/miniapp/teacher/course/profile'}),request({url:'/miniapp/material/dict'})
      ])
      if (!this.current() || version!==this._version) return
      this._courses=courses.data || []; this._cells=(available.data || {}).cells || []
      this._editing=this._id ? this._courses.find(c=>String(c.scheduleId)===this._id && String(c.classMode)==='2') : null
      if(this._id && !this._editing) throw new Error('课程不存在或不属于本人')
      const week=calendar.buildWeek(this._courses.filter(c=>String(c.scheduleId)!==this._id),date,this._cells)
      const axisKey=JSON.stringify(week.calendarTimeline)
      if(this._axisKey!==axisKey) { this._axisKey=axisKey; this._axisRevision=(this._axisRevision || 0)+1; week.calendarTopAnchor='calendar-top-'+this._axisRevision }
      week.weekDays=week.weekDays.map(day=>({...day,publishSlots:this._cells.filter(slot=>slot.date===day.date && slot.canPublish).map(slot=>({...slot,style:calendar.rangeStyle(week.calendarTimeline,slot.startTime,slot.endTime)}))}))
      const grades=(dict.data || {}).grades || [], p=profile.data || {}
      this.setData({...week,loading:false,loadSucceeded:true,gradeOptions:grades.map(g=>g.dictLabel),subject:p.teacherSubject || '',teacherLevelText:({elite:'精英教师',senior:'资深教师'})[p.teacherLevel] || '等级待后台配置',pendingScheduleCount:(available.data || {}).pendingScheduleCount || 0})
    } catch(error) { if(this.current() && version===this._version) this.clear(error.applicationRejected ? error.message : '课表或空闲教室加载失败，请重试。') }
    finally { if(this.current() && version===this._version) this.setData({loading:false}) }
  },
  retryLoad() { this.load() },
  shiftWeek(days) { if(this.data.submitting) return; this.setData({weekStart:calendar.formatDate(calendar.addDays(calendar.weekStart(this.data.weekStart),days))}); this.load() },
  previousWeek() { this.shiftWeek(-7) }, nextWeek() { this.shiftWeek(7) },
  currentWeek() { if(!this.data.submitting) { this.setData({weekStart:calendar.formatDate(new Date())}); this.load() } },
  chooseDate(e) { if(!this.data.submitting) { this.setData({weekStart:e.detail.value}); this.load() } },
  selectSlot(e) {
    if(!this.current() || this.data.loading || !this.data.loadSucceeded || this.data.submitting) return
    const slot=this._cells.find(s=>s.date===e.currentTarget.dataset.date && s.timeSlot===e.currentTarget.dataset.slot && s.canPublish)
    if(!slot) return
    if(!this.data.subject) { wx.showToast({title:'请联系管理员配置授课学科',icon:'none'}); return }
    this.setData({showSheet:true,selectedSlot:slot,courseClassName:this._editing ? this._editing.courseClassName : '',gradeIndex:this._editing ? Math.max(0,this.data.gradeOptions.indexOf(this._editing.gradeName)) : 0,roomIndex:0,roomOptions:slot.classrooms.map(room=>({...room,label:`${room.campusName} · ${room.classroomName}`}))})
  },
  closeSheet() { if(!this.data.submitting) this.setData({showSheet:false,selectedSlot:null}) },
  inputName(e) { if(!this.data.submitting) this.setData({courseClassName:e.detail.value}) },
  chooseGrade(e) { if(!this.data.submitting) this.setData({gradeIndex:Number(e.detail.value)}) },
  chooseRoom(e) { if(!this.data.submitting) this.setData({roomIndex:Number(e.detail.value)}) },
  async showCourse(e) {
    if(!this.current() || this.data.loading || !this.data.loadSucceeded || this.data.loadError || this.data.submitting || this.data.showSheet || this._courseDialogOpen) return
    const c=(this._courses || []).find(c=>String(c.scheduleId)===String(e.currentTarget.dataset.id))
    if(!c) return
    const canRemove=String(c.classMode)==='2' && String(c.status)==='0' && c.enrolledCount != null && Number(c.enrolledCount)===0 && Array.isArray(c.singleBookings) && !c.singleBookings.length
    const detail=`${e.currentTarget.dataset.date || '日期待排'} ${String(c.startTime || '').slice(0,5)}–${String(c.endTime || '').slice(0,5)}\n${c.lessonLocation || [c.campusName,c.classroomName].filter(Boolean).join(' · ') || '地点待配置'}`
    if(!canRemove) { wx.showModal({title:c.courseClassName || '课程详情',showCancel:false,content:detail}); return }
    const version=this._version, count=calendar.expandCourse(c).dates.length
    this._courseDialogOpen=true
    try {
      const confirmed=await new Promise(resolve=>wx.showModal({
        title:c.courseClassName || '取消一对一课程',
        content:`${detail}\n\n确认取消${count>1 ? `整门课程（包含全部${count}个课次）` : '这节一对一课程'}？课程将停用并释放教师与教室时段，已取消的报名和上课历史保留。仍有有效报名时不能取消课程。`,
        confirmText:'取消课程',cancelText:'保留课程',confirmColor:'#c84444',
        success:result=>resolve(result.confirm),fail:()=>resolve(false)
      }))
      if(!confirmed || !this.current() || version!==this._version || this.data.loading || this.data.submitting) return
      this.setData({submitting:true})
      // 复用排课停用事务，保留已取消的报名和考勤引用；后台锁内复核有效报名。
      await request({url:'/miniapp/teacher/course/schedule',method:'PUT',data:{scheduleId:c.scheduleId,status:'1'}})
      if(!this.current()) return
      getCurrentPages().forEach(p=>{if(p!==this)p._scheduleDirty=true})
      this.setData({submitting:false})
      wx.showToast({title:'已取消课程',icon:'success'})
      await this.load()
    } catch(error) {
      if(this.current()) {
        this.setData({submitting:false})
        wx.showToast({title:error.applicationRejected ? error.message : '取消结果未确认，请刷新课表核对',icon:'none'})
        await this.load()
      }
    } finally { this._courseDialogOpen=false; if(this.current())this.setData({submitting:false}) }
  },
  async save() {
    if(!this.current() || this.data.submitting || !this.data.selectedSlot) return
    const name=this.data.courseClassName.trim(),grade=this.data.gradeOptions[this.data.gradeIndex],room=this.data.roomOptions[this.data.roomIndex],slot=this.data.selectedSlot
    if(!name || !grade || !room) { wx.showToast({title:'请填写课名并选择年级、教室',icon:'none'}); return }
    this.setData({submitting:true})
    try {
      let payload={courseClassName:name,gradeName:grade,classroomId:room.classroomId,startDate:slot.date,timeSlot:slot.timeSlot}
      if(this._id) payload={...payload,scheduleId:this._id,endDate:slot.date,startTime:slot.startTime,endTime:slot.endTime,lessonLocation:'',termName:`一对一${slot.date}`,courseYear:Number(slot.date.slice(0,4)),periodName:['周日','周一','周二','周三','周四','周五','周六'][calendar.parseDate(slot.date).getDay()],classPattern:'WEEKLY'}
      await request({url:'/miniapp/teacher/course/schedule',method:this._id ? 'PUT' : 'POST',data:payload})
      if(!this.current()) return
      getCurrentPages().forEach(p=>{p._scheduleDirty=true})
      this.setData({showSheet:false,selectedSlot:null,submitting:false})
      wx.showToast({title:'已发布一节课程',icon:'success'}); await this.load()
    } catch(error) { if(this.current()) { this.setData({submitting:false}); wx.showToast({title:error.applicationRejected ? error.message : '发布失败，请刷新空闲教室后重试。',icon:'none'}); await this.load() } }
    finally { if(this.current()) this.setData({submitting:false}) }
  }
})
