const { request } = require('../../utils/request')
const s = require('../../utils/sign-review')
const overview = require('../../utils/attendance-overview')
Page({
  data: { mode:'overview',courseKeyword:'',courses:[],courseIndex:-1,courseId:'',overviewLoading:false,overviewError:'',overviewReady:false,dates:[],students:[],shownStudents:[],studentKeyword:'',courseStats:{},occurrenceCount:0,from:'',to:'',summaryOpen:false,summaryLoading:false,summaryError:'',summaryScope:'course',summaryStudent:null,summaryStats:{},summaryRows:[],summaryTotal:0,summaryPage:1, loading: false, list: [], showEmpty: false, error: '', filterDate: '', filterIndex: 0, filters: ['待确认', '已确认', '全部课次'], pendingCount: 0, confirmedCount: 0 },
  onShow() { this._active = true; this._visit = (this._visit || 0) + 1; this._token = wx.getStorageSync('token'); this._visible=true; if(this._owner!==this._token){this._owner=this._token;this._rows=[];this.setData({courseId:'',courses:[],students:[],shownStudents:[],dates:[],overviewReady:false,summaryOpen:false,summaryStudent:null,summaryRows:[],list:[]})} const list=this.loadList(); if(this.data.mode==='overview')this.loadCourses(); return list },
  onHide() { this._active=false; this._visit++; this._visible=false; this._version=(this._version||0)+1;this._overviewVersion=(this._overviewVersion||0)+1;this._courseVersion=(this._courseVersion||0)+1;this._summaryVersion=(this._summaryVersion||0)+1;this.setData({summaryOpen:false,summaryLoading:false}) },
  onUnload() { this.onHide(); this._unloaded=true },
  onPullDownRefresh() { return (this.data.mode==='overview'?this.loadCourses():this.loadList()).finally(()=>wx.stopPullDownRefresh()) },
  current(visit) { return this._active && visit === this._visit && this._token === wx.getStorageSync('token') },
  currentOwner(token){return !this._unloaded&&this._visible&&s.isStaff()&&wx.getStorageSync('token')===token},
  chooseMode(e){this.setData({mode:e.currentTarget.dataset.mode});this.closeSummary();if(this.data.mode==='overview')this.loadCourses();else this.loadList()},
  courseInput(e){this.setData({courseKeyword:e.detail.value})},
  studentInput(e){const studentKeyword=e.detail.value;this.setData({studentKeyword,shownStudents:overview.filter(this.data.students,studentKeyword)})},
  async loadCourses(){
    const token=wx.getStorageSync('token'),version=this._courseVersion=(this._courseVersion||0)+1
    this._overviewVersion=(this._overviewVersion||0)+1;this.closeSummary();this.setData({overviewLoading:true,overviewReady:false,overviewError:'',dates:[],students:[],shownStudents:[]})
    if(!s.isStaff()){this.setData({overviewLoading:false,courses:[],overviewError:'仅教务或管理员可查看学生出勤'});return}
    try{const r=await request({url:'/miniapp/admin/sign/courses',data:{keyword:this.data.courseKeyword}});if(!this.currentOwner(token)||version!==this._courseVersion)return
      const courses=overview.courses(r.data);let index=courses.findIndex(c=>String(c.scheduleId)===String(this.data.courseId));if(index<0&&courses.length)index=0
      this.setData({courses,courseIndex:index,courseId:index<0?'':String(courses[index].scheduleId),overviewLoading:false});if(index>=0)await this.loadOverview()
    }catch(e){if(this.currentOwner(token)&&version===this._courseVersion)this.setData({overviewLoading:false,courses:[],courseIndex:-1,courseId:'',overviewError:e.message||'课程加载失败，请重试'})}
  },
  pickCourse(e){const index=Number(e.detail.value),course=this.data.courses[index];if(!course)return;this.setData({courseIndex:index,courseId:String(course.scheduleId),studentKeyword:''});this.loadOverview()},
  changeRange(e){const field=e.currentTarget.dataset.field;if(!['from','to'].includes(field))return;this.setData({[field]:e.detail.value});this.loadOverview()},
  resetRange(){this.setData({from:'',to:''});this.loadOverview()},
  async loadOverview(){
    this.closeSummary();const token=wx.getStorageSync('token'),courseId=this.data.courseId,version=this._overviewVersion=(this._overviewVersion||0)+1
    this.setData({overviewLoading:true,overviewReady:false,overviewError:'',dates:[],students:[],shownStudents:[]});if(!courseId||!s.isStaff()){this.setData({overviewLoading:false});return}
    try{const r=await request({url:'/miniapp/admin/sign/overview',data:{scheduleId:courseId,from:this.data.from,to:this.data.to}})
      if(!this.currentOwner(token)||version!==this._overviewVersion||courseId!==this.data.courseId)return
      const d=r.data||{},students=(d.rows||[]).map(overview.row);this.setData({students,shownStudents:overview.filter(students,this.data.studentKeyword),dates:d.dates||[],courseStats:d.stats||{},occurrenceCount:d.occurrenceCount||0,overviewReady:true})
    }catch(e){if(this.currentOwner(token)&&version===this._overviewVersion)this.setData({overviewError:e.message||'出勤总览加载失败，请重试'})}
    finally{if(this.currentOwner(token)&&version===this._overviewVersion)this.setData({overviewLoading:false})}
  },
  openCell(e){const {key,date}=e.currentTarget.dataset,row=this.data.students.find(r=>r.studentKey===key),index=this.data.dates.indexOf(date);if(!row||index<0||!row.cells[index].recorded)return;this.navigateDetail(this.data.courseId,date)},
  openStudent(e){if(!s.isStaff())return;const student=this.data.students.find(r=>r.studentKey===e.currentTarget.dataset.key);if(!student||!this.data.overviewReady)return;this.setData({summaryOpen:true,summaryStudent:student,summaryScope:'course',summaryRows:[],summaryStats:{},summaryTotal:0,summaryPage:1});this.loadSummary()},
  closeSummary(){this._summaryVersion=(this._summaryVersion||0)+1;this.setData({summaryOpen:false,summaryLoading:false,summaryError:'',summaryStudent:null,summaryRows:[],summaryStats:{}})},
  chooseSummaryScope(e){const scope=e.currentTarget.dataset.scope;if(!this.data.summaryStudent?.linked&&scope==='all')return;this.setData({summaryScope:scope,summaryPage:1,summaryRows:[]});this.loadSummary()},
  moreSummary(){if(this.data.summaryLoading||!this.data.summaryHasMore)return;this.setData({summaryPage:this.data.summaryPage+1});this.loadSummary(true)},
  async loadSummary(append=false){
    if(!this.data.summaryOpen||!this.data.summaryStudent||!s.isStaff())return
    if(!append)this.setData({summaryStats:{},summaryTotal:0,summaryHasMore:false})
    const token=wx.getStorageSync('token'),version=this._summaryVersion=(this._summaryVersion||0)+1,key=this.data.summaryStudent.studentKey,scope=this.data.summaryScope,pageNum=this.data.summaryPage
    this.setData({summaryLoading:true,summaryError:''})
    try{const r=await request({url:'/miniapp/admin/sign/student-summary',data:{scheduleId:this.data.courseId,studentKey:key,allCourses:scope==='all',from:this.data.from,to:this.data.to,pageNum}})
      if(!this.currentOwner(token)||version!==this._summaryVersion||!this.data.summaryOpen||this.data.summaryStudent.studentKey!==key)return
      const d=r.data;this.setData({summaryStats:d.stats,summaryTotal:d.total,summaryHasMore:pageNum*30<d.total,summaryRows:overview.mergeLessons(append?this.data.summaryRows:[],(d.rows||[]).map(overview.summary))})
    }catch(e){if(this.currentOwner(token)&&version===this._summaryVersion){this.setData({summaryError:e.message||'统计加载失败，请重试'});if(append)this.setData({summaryPage:Math.max(1,pageNum-1)})}}
    finally{if(this.currentOwner(token)&&version===this._summaryVersion)this.setData({summaryLoading:false})}
  },
  retrySummary(){this.setData({summaryPage:1,summaryRows:[]});this.loadSummary()},
  noop(){},
  onDateFilter(e) { this.setData({ filterDate: e.detail.value }); return this.loadList() },
  resetFilter() { this.setData({ filterDate: '' }); return this.loadList() },
  changeFilter(e) { this.setData({ filterIndex: Number(e.currentTarget.dataset.index) }); this.applyFilter() },
  applyFilter() {
    const all = this._rows || [], list = all.filter(r => this.data.filterIndex === 2 || (this.data.filterIndex === 1 ? r.reviewStatus === '1' : r.reviewStatus !== '1'))
    this.setData({ list, showEmpty: !list.length, pendingCount: all.filter(r => r.reviewStatus !== '1').length, confirmedCount: all.filter(r => r.reviewStatus === '1').length })
  },
  async loadList() {
    const visit = this._visit, sequence = this._sequence = (this._sequence || 0) + 1
    const current = () => this.current(visit) && sequence === this._sequence
    this._rows = []; this.setData({ loading: true, error: '', list: [], pendingCount: 0, confirmedCount: 0 })
    try {
      if (!await s.authorize(current)) return
      const res = await s.request({ url: s.base + '/list', data: this.data.filterDate ? { classDate: this.data.filterDate } : {} })
      if (!current()) return
      this._rows = (res.data || []).map(item => this.decorate(item)); this.applyFilter()
    } catch (error) { if (current()) this.setData({ error: error.message, list: [], showEmpty: false }) }
    finally { if (current()) this.setData({ loading: false }) }
  },
  decorate(item) {
    const dateText = String(item.classDate || '').slice(0, 10), done = item.reviewStatus === '1'
    return { ...item, scheduleId: String(item.scheduleId), rowKey: String(item.scheduleId) + ':' + dateText, dateText,
      course: item.courseClassName || [item.gradeName, item.subjectName, item.classType].filter(Boolean).join(''),
      reviewText: done ? '教务已确认' : (item.reviewStatus === '2' ? '审核异常 · 待更正' : '待教务确认'), reviewClass: done ? 'done' : 'pending',
      room: [item.campusName, item.classroomName].filter(Boolean).join(' · ') }
  },
  openDetail(e) {
    const id = String(e.currentTarget.dataset.scheduleId), date = e.currentTarget.dataset.date
    if (!s.isStaff() || !this.currentOwner(wx.getStorageSync('token'))) return
    this.navigateDetail(id,date)
  },
  navigateDetail(id,date) {
    if (!this.currentOwner(wx.getStorageSync('token')) || !/^[1-9]\d*$/.test(String(id)) || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return
    wx.navigateTo({url:'/pages/sign-review-detail/sign-review-detail?scheduleId='+encodeURIComponent(id)+'&classDate='+encodeURIComponent(date)})
  }
})
