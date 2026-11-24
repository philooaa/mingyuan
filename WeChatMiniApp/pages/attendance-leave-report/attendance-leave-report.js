const { request } = require('../../utils/request')
const access = require('../../utils/access')
const auth = require('../../utils/sign-review')
const filters = require('../../utils/attendance-filters')
const initial = () => ({keyword:'',year:'',termName:'',teacherName:'',gradeName:'',from:'',to:'',state:'pending'})
Page({
  data:{readOnly:true,query:initial(),...filters.state([],{},false,true),fields:[{key:'year',label:'年份'},{key:'termName',label:'季度'},{key:'teacherName',label:'教师'},{key:'gradeName',label:'年级'}],stateOptions:['未完成（含待补课）','待补课','已补课','全部'],stateIndex:0,rows:[],report:{},pageNum:1,loading:false,error:'',more:false,makeupOpen:false,makeupMode:'arrange',makeupType:'CLASS',makeupToday:'',makeupDate:'',makeupStartTime:'',makeupEndTime:'',makeupLocation:'',makeupSource:{},makeupCourses:[],makeupLessons:[],makeupHistory:[],makeupCourseIndex:-1,makeupLessonIndex:-1,makeupKeyword:'',makeupRemark:'',makeupReason:'',makeupLoading:false,makeupWriting:false,makeupError:'',makeupUncertain:false},
  async onShow(){
    this.closeMakeup();this._active=true;this._token=wx.getStorageSync('token');this._role=access.selected()
    this.setData({readOnly:this._role!=='teacher',fields:[{key:'year',label:'年份'},{key:'termName',label:'季度'},...(this._role==='teacher'?[]:[{key:'teacherName',label:'教师'}]),{key:'gradeName',label:'年级'}],rows:[],report:{},error:''})
    const visit=this._visit=(this._visit||0)+1
    const current=()=>this.current()&&visit===this._visit
    try {if(!['admin','education','teacher'].includes(this._role)){this.setData({error:'仅教师、教务或管理员可查看请假课时表',loading:false});return}if(!await auth.authorize(current,true)||!current())return;await Promise.all([this.loadOptions(),this.search()])}
    catch(e){if(current())this.setData({error:e.message,loading:false})}
  },
  onHide(){this.closeMakeup();this._active=false;this._visit=(this._visit||0)+1;this._version=(this._version||0)+1},
  onUnload(){this._unloaded=true;this.onHide()},
  current(){return !this._unloaded&&this._active&&['admin','education','teacher'].includes(this._role)&&this._token===wx.getStorageSync('token')&&this._role===access.selected()},
  async loadOptions(){const visit=this._visit;try{const teacher=this._role==='teacher';const r=await request({url:teacher?'/miniapp/attendance/todos':'/miniapp/admin/sign/courses',...(teacher?{data:{role:'teacher',allLessons:true}}:{})});if(this.current()&&visit===this._visit)this.setData(filters.state((teacher?(r.data||{}).courses||[]:r.data||[]).map(c=>({...c,year:c.year||c.courseYear})),this.data.query,false,true))}catch(_){}},
  keywordInput(e){this.setData({query:{...this.data.query,keyword:e.detail.value}})},
  chooseFilter(e){const field=e.currentTarget.dataset.field,index=Number(e.detail.value);if(!this.data.fields.some(f=>f.key===field))return;this.setData({query:{...this.data.query,[field]:index?this.data.filterOptions[field][index]:''},filterIndexes:{...this.data.filterIndexes,[field]:index}})},
  chooseDate(e){const field=e.currentTarget.dataset.field;if(['from','to'].includes(field))this.setData({query:{...this.data.query,[field]:e.detail.value}})},
  chooseState(e){const index=Number(e.detail.value);this.setData({stateIndex:index,query:{...this.data.query,state:['pending','scheduled','completed',''][index]}})},
  reset(){this.setData({query:initial(),stateIndex:0,filterIndexes:{year:0,termName:0,teacherName:0,gradeName:0}});return this.search()},
  search(){this._applied={...this.data.query};return this.load(1)},
  async load(pageNum){
    if(!this.current())return
    const version=this._version=(this._version||0)+1
    this.setData({loading:true,error:'',...(pageNum===1?{rows:[],report:{},more:false}:{})})
    try{const r=await request({url:'/miniapp/attendance/leave-report',data:{...this._applied,...(this._role==='teacher'?{scope:'teacher'}:{}),pageNum,pageSize:20}});if(!this.current()||version!==this._version)return
      const data=r.data||{},rows=(data.rows||[]).map(row=>({...row,expanded:false,lessons:(row.lessons||[]).map(l=>({...l,stateText:{pending:'待处理',scheduled:'待补课',failed:'补课未完成',completed:'已补课',conflict:'待核对'}[l.state]}))}))
      if(pageNum===1)this._makeupNeedsRefresh=false
      this.setData({readOnly:this._role!=='teacher'||data.canManageMakeup!==true||data.readOnly!==false,report:data,rows:pageNum===1?rows:[...this.data.rows,...rows],pageNum,more:pageNum*20<data.total})
    }catch(e){if(this.current()&&version===this._version)this.setData({error:e.message||'请假课时表加载失败'})}
    finally{if(this.current()&&version===this._version)this.setData({loading:false})}
  },
  more(){if(!this.data.loading&&this.data.more)return this.load(this.data.pageNum+1)},
  closeMakeup(){
    this._makeupVersion=(this._makeupVersion||0)+1
    this.setData({makeupOpen:false,makeupLoading:false,makeupWriting:false})
  },
  stopTap(){},
  refreshMakeupReport(){if(this._writing)return;this.closeMakeup();return this.load(1)},
  findLesson(signInId){return this.data.rows.flatMap(r=>r.lessons||[]).find(l=>String(l.signInId)===String(signInId))},
  async openMakeup(e){
    if(!this.current()||this._writing)return
    if(this._makeupNeedsRefresh){wx.showToast({title:'请刷新记录后核对操作结果',icon:'none'});return this.refreshMakeupReport()}
    const {signInId,mode='arrange'}=e.currentTarget.dataset,lesson=this.findLesson(signInId)
    if(!lesson||(mode!=='history'&&(this._role!=='teacher'||this.data.readOnly))||!['arrange','cancel','history'].includes(mode)||mode==='arrange'&&!lesson.canArrange||mode==='cancel'&&(!lesson.canCancel||!lesson.makeup?.planId))return
    this.closeMakeup()
    this.setData({makeupOpen:true,makeupMode:mode,makeupType:'CLASS',makeupToday:new Date(Date.now()+8*60*60*1000).toISOString().slice(0,10),makeupDate:'',makeupStartTime:'',makeupEndTime:'',makeupLocation:'',makeupSource:{...lesson},makeupCourses:[],makeupLessons:[],makeupHistory:[],makeupCourseIndex:-1,makeupLessonIndex:-1,makeupKeyword:'',makeupRemark:'',makeupReason:'',makeupError:'',makeupUncertain:false})
    return this.loadMakeupOptions()
  },
  makeupCurrent(version){return this.current()&&this.data.makeupOpen&&version===this._makeupVersion},
  async loadMakeupOptions(scheduleId){
    if(!this.current()||!this.data.makeupOpen||this._writing)return
    const version=this._makeupVersion=(this._makeupVersion||0)+1
    this.setData({makeupLoading:true,makeupLessons:[],makeupLessonIndex:-1,makeupError:''})
    try{
      const data={signInId:this.data.makeupSource.signInId,keyword:this.data.makeupKeyword.trim()}
      if(scheduleId!=null)data.scheduleId=scheduleId
      const r=await request({url:'/miniapp/attendance/leave-report/makeup/options',data})
      if(!this.makeupCurrent(version))return
      const result=r.data||{},update={makeupLessons:result.lessons||[]}
      if(result.canManageMakeup===false)update.readOnly=true
      if(scheduleId==null)update.makeupCourses=(result.courses||[]).map(c=>({...c,label:[c.courseName,c.teacherName,c.year,c.termName,c.timeSlot].filter(Boolean).join(' · ')}))
      if(result.history)update.makeupHistory=result.history
      if(result.source)update.makeupSource={...this.data.makeupSource,...result.source,signInId:data.signInId}
      this.setData(update)
    }catch(e){if(this.makeupCurrent(version))this.setData({makeupError:e.message||'补课选项加载失败'})}
    finally{if(this.makeupCurrent(version))this.setData({makeupLoading:false})}
  },
  makeupKeywordInput(e){
    if(this._writing)return
    this._makeupVersion=(this._makeupVersion||0)+1
    this.setData({makeupKeyword:e.detail.value,makeupCourses:[],makeupLessons:[],makeupCourseIndex:-1,makeupLessonIndex:-1,makeupLoading:false})
  },
  searchMakeupCourses(){if(this._writing)return;this.setData({makeupCourseIndex:-1});return this.loadMakeupOptions()},
  chooseMakeupCourse(e){
    if(this._writing)return
    const index=Number(e.detail.value),course=this.data.makeupCourses[index]
    if(!course)return
    this.setData({makeupCourseIndex:index});return this.loadMakeupOptions(course.scheduleId)
  },
  chooseMakeupLesson(e){const index=Number(e.detail.value);if(!this._writing&&!this.data.makeupLoading&&this.data.makeupLessons[index])this.setData({makeupLessonIndex:index})},
  chooseMakeupType(e){
    const type=e.currentTarget.dataset.type
    if(this._writing||!this.current()||this.data.readOnly||!this.data.makeupOpen||this.data.makeupMode!=='arrange'||!['CLASS','CUSTOM'].includes(type)||type===this.data.makeupType)return
    this._makeupVersion=(this._makeupVersion||0)+1
    this.setData({makeupType:type,makeupCourseIndex:-1,makeupLessonIndex:-1,makeupLessons:[],makeupKeyword:'',makeupDate:'',makeupStartTime:'',makeupEndTime:'',makeupLocation:'',makeupRemark:'',makeupLoading:false,makeupError:''})
  },
  makeupCustomInput(e){
    const field=e.currentTarget.dataset.field
    if(!this._writing&&['makeupDate','makeupStartTime','makeupEndTime','makeupLocation'].includes(field))this.setData({[field]:e.detail.value})
  },
  makeupRemarkInput(e){if(!this._writing)this.setData({makeupRemark:e.detail.value.slice(0,500)})},
  makeupReasonInput(e){if(!this._writing)this.setData({makeupReason:e.detail.value.slice(0,500)})},
  async submitMakeup(){
    if(!this.current()||this._role!=='teacher'||this.data.readOnly||!this.data.makeupOpen||this.data.makeupLoading||this._writing||this._makeupNeedsRefresh)return
    const source=this.data.makeupSource,lesson=this.findLesson(source.signInId),cancel=this.data.makeupMode==='cancel'
    if(!lesson||this.data.makeupMode==='history'||cancel&&!lesson.canCancel||!cancel&&!lesson.canArrange)return
    const target=this.data.makeupLessons[this.data.makeupLessonIndex],reason=this.data.makeupReason.trim(),custom=this.data.makeupType==='CUSTOM',remark=this.data.makeupRemark.trim()
    const {makeupDate:classDate,makeupStartTime:startTime,makeupEndTime:endTime}=this.data,location=this.data.makeupLocation.trim()
    if(cancel&&!reason){wx.showToast({title:'请填写取消原因',icon:'none'});return}
    if(!cancel&&!custom&&(!target||this.data.makeupCourseIndex<0)){wx.showToast({title:'请选择补课班级和讲次',icon:'none'});return}
    if(!cancel&&custom){
      const parsed=new Date(classDate+'T00:00:00Z')
      if(!/^\d{4}-\d{2}-\d{2}$/.test(classDate)||!Number.isFinite(parsed.getTime())||parsed.getUTCFullYear()!==Number(classDate.slice(0,4))||parsed.getUTCMonth()+1!==Number(classDate.slice(5,7))||parsed.getUTCDate()!==Number(classDate.slice(8,10))){wx.showToast({title:'请选择有效补课日期',icon:'none'});return}
      if(!/^([01]\d|2[0-3]):[0-5]\d$/.test(startTime)||!/^([01]\d|2[0-3]):[0-5]\d$/.test(endTime)||endTime<=startTime){wx.showToast({title:'结束时间须晚于开始时间',icon:'none'});return}
      if(new Date(classDate+'T'+startTime+':00+08:00').getTime()<=Date.now()){wx.showToast({title:'补课开始时间须晚于当前时间',icon:'none'});return}
      if(!location||location.length>200){wx.showToast({title:'请填写补课地点，最多200字',icon:'none'});return}
    }
    if(!cancel&&remark.length>500){wx.showToast({title:'安排备注最多500字',icon:'none'});return}
    const payload=cancel?{planId:lesson.makeup.planId,reason}:custom?{signInId:source.signInId,mode:'CUSTOM',classDate,startTime,endTime,location,remark}:{signInId:source.signInId,mode:'CLASS',targetLessonId:target.lessonId,remark}
    const description=custom?`${classDate} ${startTime}—${endTime}，地点：${location}`:`${this.data.makeupCourses[this.data.makeupCourseIndex]?.courseName} · ${target?.label||'第 '+target?.lessonNo+' 讲'}`
    const version=this._makeupVersion,token=this._token,role=this._role
    const sameOwner=()=>token===wx.getStorageSync('token')&&role===access.selected()
    this._writing=true;this.setData({makeupWriting:true,makeupError:''})
    try{
      const confirmation=await new Promise(resolve=>wx.showModal({title:cancel?'确认取消补课安排':'确认安排补课',content:cancel?'取消后可重新选择补课班级和讲次。':`安排至${description}。立即加入课表及点名名单；课后教师点名、教务初审、管理员终审。不新增学费。`,success:resolve,fail:()=>resolve({confirm:false})}))
      if(!confirmation.confirm||!this.makeupCurrent(version)||this.data.readOnly||this._role!=='teacher'||this._makeupNeedsRefresh)return
      const latest=this.findLesson(source.signInId)
      if(cancel?!latest?.canCancel||String(latest.makeup?.planId)!==String(payload.planId):!latest?.canArrange)return
      await request({url:'/miniapp/attendance/leave-report/makeup'+(cancel?'/cancel':''),method:'POST',data:payload})
      if(!sameOwner())return
      this._makeupNeedsRefresh=true;this._version=(this._version||0)+1
      if(!this.current())return
      this.closeMakeup();wx.showToast({title:cancel?'已取消补课安排':'已安排补课',icon:'success'});await this.load(1)
    }catch(e){
      const uncertain=!e.applicationRejected
      if(sameOwner()&&uncertain){
        this._makeupNeedsRefresh=true;this._version=(this._version||0)+1
        if(this.current())this.setData({loading:false})
      }
      if(this.makeupCurrent(version))this.setData({makeupUncertain:uncertain,makeupError:(e.message||'操作失败')+(uncertain?'；操作结果未确认，请刷新记录后核对再操作。':'')})
    }
    finally{this._writing=false;if(this.makeupCurrent(version))this.setData({makeupWriting:false})}
  },
  toggle(e){const key=e.currentTarget.dataset.key;this.setData({rows:this.data.rows.map(r=>r.rowKey===key?{...r,expanded:!r.expanded}:r)})}
})
