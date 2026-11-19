const { request } = require('../../utils/request')

// 教师统一登记请假；兼容历史未到课记录的展示和计数。
const STATUS_OPTIONS = {
  '1': [{ key: '1', label: '到课' }, { key: '2', label: '录播' }, { key: '3', label: '请假' }],
  '2': [{ key: '4', label: '试听到课' }, { key: '3', label: '请假' }],
  '3': [{ key: '5', label: '调课到课' }, { key: '3', label: '请假' }]
}
const STATUS_LABELS = { '1': '到课', '2': '录播', '3': '请假', '4': '试听到课', '5': '调课到课', '6': '请假' }
const GUEST_API = '/miniapp/teacher/attendance/guests'
const SOURCE_LABELS = { '1': '报名', '2': '试听', '3': '调课' }

function todayStr() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

Page({
  data: {
    scheduleId: null, lessonId: null, lessonNo: null, upcoming: false,
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
    submitting: false,
    guestOpen: false, guestSource: 'TRIAL', guestKeyword: '', guestCandidates: [],
    guestStudent: null, guestSources: [], guestSourceIndex: -1, guestSearched: false,
    guestBusy: false, guestError: '', outgoing: []
  },

  onLoad(query) {
    this._unloaded = false
    this.setData({
      scheduleId: String(query.scheduleId || ''),
      lessonId: query.lessonId || null,
      classDate: /^\d{4}-\d{2}-\d{2}$/.test(query.classDate || '') ? query.classDate : todayStr(),
      course: decodeURIComponent(query.course || ''),
      room: decodeURIComponent(query.room || ''),
      timeText: decodeURIComponent(query.time || '')
    })
    this.loadDetail()
  },

  async loadDetail() {
    if (this.data.submitting || this.data.guestBusy || this._unloaded) return
    const { scheduleId, lessonId } = this.data
    let { classDate } = this.data
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    const token = wx.getStorageSync('token')
    const current = () => !this._unloaded && version === this._loadVersion &&
      this.data.classDate === classDate && wx.getStorageSync('token') === token
    this.closeGuest()
    this.setData({ outgoing: [], loading: true, ready: false, rows: [], stats: null, review: null, locked: false })
    try {
      if (lessonId) {
        const {data:lesson}=await request({url:'/miniapp/attendance/teacher-lesson',data:{scheduleId,lessonId}})
        if (!current()) return
        classDate=lesson.classDate
        this.setData({classDate,lessonNo:lesson.lessonNo,course:lesson.courseName || this.data.course,
          upcoming:lesson.upcoming,timeText:[lesson.startTime,lesson.endTime].filter(Boolean).map(value=>String(value).slice(0,5)).join('–')})
      }
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
          ...s,
          studentId: s.studentId || s.student_id,
          enrollmentId: s.enrollmentId || s.enrollment_id,
          studentName: s.studentName || s.student_name,
          sourceType: s.sourceType || ({TRIAL:'2',TRANSFER:'3'}[s.source] || '1'),
          signStatus: approvedLeave.has(String(s.enrollmentId || s.enrollment_id)) ? '3' : '',
          remark: ''
        }, s.sourceType || ({TRIAL:'2',TRANSFER:'3'}[s.source] || '1')))
      }
      let outgoing = []
      if (lessonId) {
        const response = await request({url:GUEST_API+'/outgoing',data:{scheduleId,classDate}})
        if (!current()) return
        outgoing = response.data || []
      }
      this._loadedToken = token
      this.setData({
        ready: true, outgoing,
        submissionVersion: data.submissionVersion || '',
        rows,
        review: { ...review, reviewStage: stage },
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
    return !this._unloaded && this.data.ready && !this.data.upcoming && !this.data.locked && !this.data.loading && !this.data.submitting &&
      wx.getStorageSync('token') === this._loadedToken
  },

  decorateRow(row, sourceType) {
    this._rowSequence = (this._rowSequence || 0) + 1
    sourceType = String(sourceType)
    return {
      ...row,
      rowKey: `student:${this._rowSequence}`,
      signStatus: String(row.signStatus || ''),
      sourceType,
      sourceText: SOURCE_LABELS[sourceType] || '报名',
      statusText: STATUS_LABELS[row.signStatus] || '未点名',
      statusOptions: STATUS_OPTIONS[sourceType] || STATUS_OPTIONS['1'],
      added: !!row.guestId
    }
  },

  buildStats(rows) {
    const count = (key) => rows.filter((r) => r.signStatus === key).length
    return {
      attended: count('1'),
      recorded: count('2'),
      leave: count('3') + count('6'),
      audited: count('4'),
      transferred: count('5'),
      actual: count('1') + count('4') + count('5'),
      unmarked: rows.filter(row => !row.signStatus).length,
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
    if (!this.canEdit() || this.data.guestBusy) return
    this._guestVersion = (this._guestVersion || 0) + 1
    this.setData({guestOpen:true,guestSource:sourceType==='2'?'TRIAL':'TRANSFER',guestKeyword:'',
      guestCandidates:[],guestStudent:null,guestSources:[],guestSourceIndex:-1,guestSearched:false,guestBusy:false,guestError:''})
  },

  closeGuest() {
    if (this.data.guestBusy) return
    this._guestVersion = (this._guestVersion || 0) + 1
    this.setData({guestOpen:false})
  },
  stopTap() {},
  guestKeywordInput(e) {
    if(this.data.guestBusy) return
    this._guestVersion = (this._guestVersion || 0) + 1
    this.setData({guestKeyword:e.detail.value,guestSearched:false,guestCandidates:[],guestStudent:null,guestSources:[],guestSourceIndex:-1,guestError:''})
  },
  async searchGuests() {
    if(!this.canEdit()||this.data.guestBusy) return
    const keyword=this.data.guestKeyword.trim()
    if(!keyword) {this.setData({guestError:'请输入学生姓名'});return}
    const version=this._guestVersion
    this.setData({guestBusy:true,guestError:'',guestStudent:null,guestSources:[],guestSourceIndex:-1})
    try {
      const res=await request({url:GUEST_API+'/students',data:{scheduleId:this.data.scheduleId,classDate:this.data.classDate,keyword}})
      if(!this.canEdit()||version!==this._guestVersion) return
      this.setData({guestCandidates:res.data||[],guestSearched:true})
    } catch(e) {if(this.canEdit()&&version===this._guestVersion)this.setData({guestError:e.message||'查询失败'})}
    finally {if(!this._unloaded&&version===this._guestVersion)this.setData({guestBusy:false})}
  },
  async pickGuest(e) {
    if(!this.canEdit()||this.data.guestBusy) return
    const student=this.data.guestCandidates[Number(e.currentTarget.dataset.index)]
    if(!student) return
    this.setData({guestStudent:student,guestSources:[],guestSourceIndex:-1,guestError:''})
    if(this.data.guestSource!=='TRANSFER') return
    const version=this._guestVersion
    this.setData({guestBusy:true})
    try {
      const res=await request({url:GUEST_API+'/sources',data:{scheduleId:this.data.scheduleId,classDate:this.data.classDate,studentId:student.studentId}})
      if(!this.canEdit()||version!==this._guestVersion) return
      const sources=(res.data||[]).map(row=>({...row,label:`${row.courseName} · ${row.year||''}${row.termName||''} · 第${row.lessonNo}讲 · ${row.classDate}`}))
      this.setData({guestSources:sources,guestError:sources.length?'':'没有可调出的未提交讲次；已提交课次请联系教务处理'})
    }catch(e){if(this.canEdit()&&version===this._guestVersion)this.setData({guestError:e.message||'原课次查询失败'})}
    finally{if(!this._unloaded&&version===this._guestVersion)this.setData({guestBusy:false})}
  },
  pickGuestSource(e) { if(!this.data.guestBusy)this.setData({guestSourceIndex:Number(e.detail.value),guestError:''}) },
  createTrial() {
    if(!this.data.guestSearched||this.data.guestCandidates.length||this.data.guestSource!=='TRIAL')return
    return this.saveGuest(true)
  },
  async saveGuest(create=false) {
    if(!this.canEdit()||this.data.guestBusy||!this.data.guestOpen) return
    create=create===true
    const student=this.data.guestStudent,origin=this.data.guestSources[this.data.guestSourceIndex]
    if(!create&&!student){this.setData({guestError:'请先选择学生'});return}
    if(this.data.guestSource==='TRANSFER'&&!origin){this.setData({guestError:'请选择原课程和讲次'});return}
    const version=this._guestVersion
    this.setData({guestBusy:true,guestError:''})
    try {
      const {data:row}=await request({url:GUEST_API,method:'POST',data:{scheduleId:this.data.scheduleId,classDate:this.data.classDate,
        source:this.data.guestSource,...(create?{studentName:this.data.guestKeyword.trim()}:{studentId:student.studentId}),
        ...(origin?{sourceLessonId:origin.lessonId}:{})}})
      if(!this.canEdit()||version!==this._guestVersion)return
      const type=row.source==='TRANSFER'?'3':'2'
      const rows=[...this.data.rows,this.decorateRow({...row,signStatus:type==='3'?'5':'4'},type)]
      this.setData({rows,stats:this.buildStats(rows),guestOpen:false})
    }catch(e){if(this.canEdit()&&version===this._guestVersion)this.setData({guestError:e.message||'添加失败'})}
    finally{if(!this._unloaded&&version===this._guestVersion)this.setData({guestBusy:false})}
  },
  async removeGuest(row) {
    if(!this.canEdit()||this.data.guestBusy||!row.guestId)return
    this.setData({guestBusy:true})
    try{
      await request({url:GUEST_API+'/'+row.guestId+'?scheduleId='+encodeURIComponent(this.data.scheduleId)+'&classDate='+encodeURIComponent(this.data.classDate),method:'DELETE'})
      if(!this.canEdit())return
      const rows=this.data.rows.filter(item=>item.rowKey!==row.rowKey)
      this.setData({rows,stats:this.buildStats(rows)})
    }catch(e){if(!this._unloaded)wx.showToast({title:e.message||'移除失败',icon:'none'})}
    finally{if(!this._unloaded)this.setData({guestBusy:false})}
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
        this.removeGuest(row)
      }
    })
  },

  async submit() {
    const { scheduleId, classDate, rows } = this.data
    if (!this.canEdit() || this.data.guestBusy || this.data.guestOpen) return
    if (!rows.length) {
      wx.showToast({ title: '暂无学员可签到', icon: 'none' })
      return
    }
    if (rows.some(row => !row.signStatus)) {
      wx.showToast({ title: '请完成所有学员点名后再提交', icon: 'none' })
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
          ...(this.data.lessonId ? {lessonId:this.data.lessonId} : {}),
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
