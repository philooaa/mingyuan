const {request,base,range}=require('../../utils/attendance-workflow')
const courseFilters=[{field:'teacherName',label:'全部老师'},{field:'gradeName',label:'全部年级'},{field:'subjectName',label:'全部科目'}]
Page({
 data:{showPending:false,pendingRows:[],filteredPending:[],pendingQuery:'',courses:[],allCourses:[],courseFilters:[],terms:["全部年份 / 学期"],termIndex:0,courseQuery:"",offlineName:'',showExisting:false,lessons:[],students:[],rows:[],courseIndex:-1,lessonIndex:0,studentIndex:-1,keyword:'',form:{scheduleId:'',studentId:'',enrollmentId:'',startLesson:1,endLesson:'',source:'OFFLINE',paymentStatus:'UNPAID',reason:''},sources:['线下/历史插班','正常报名','试听','临时调课'],payments:['未收费','历史收费已核对','沿用报名'],sourceIndex:0,paymentIndex:0,error:'',busy:false},
 onPullDownRefresh(){return this.loadCourses().finally(()=>wx.stopPullDownRefresh())},
 onShow(){this._active=true;this._token=wx.getStorageSync('token');this.loadCourses()},onHide(){this._active=false},onUnload(){this._active=false},current(){return this._active&&this._token===wx.getStorageSync('token')},
 async run(action){if(this.data.busy)return;this.setData({busy:true,error:''});try{await action()}catch(e){if(this.current())this.setData({error:e.message||'操作失败'})}finally{if(this.current())this.setData({busy:false})}},
 loadCourses(){return this.run(async()=>{const r=await request({url:base+'/courses'});if(!this.current())return;const courses=(r.data||[]).map(c=>({...c,label:[c.courseName,this.courseTerm(c)].filter(Boolean).join(' · ')}));this.setData({allCourses:courses,courseFilters:courseFilters.map(filter=>({...filter,index:0,options:[filter.label,...new Set(courses.map(course=>course[filter.field]).filter(Boolean))]})),terms:['全部年份 / 学期',...new Set(courses.map(c=>this.courseTerm(c)).filter(Boolean))],termIndex:0,courseQuery:''});this.filterCourses()})},
 courseTerm(course){return [course.year,course.termName].filter(Boolean).join(' ')},
 filterCourses(){
  const {allCourses,termIndex,terms,courseQuery,courseFilters}=this.data
  const query=courseQuery.trim().toLowerCase()
  const courses=allCourses.filter(course=>(!termIndex||this.courseTerm(course)===terms[termIndex])
    && courseFilters.every(filter=>!filter.index||course[filter.field]===filter.options[filter.index])
    && [course.courseName,this.courseTerm(course)].join(' ').toLowerCase().includes(query))
  const courseIndex=courses.findIndex(course=>String(course.scheduleId)===this.data.form.scheduleId)
  this.setData({courses,courseIndex})
  if(courseIndex<0)this.setData({lessons:[],lessonIndex:0,rows:[],form:{...this.data.form,scheduleId:''}})
 },
 searchCourse(e){this.setData({courseQuery:e.detail.value});this.filterCourses()},
 pickTerm(e){this.setData({termIndex:Number(e.detail.value)});this.filterCourses()},
 pickCourseFilter(e){
  const index=Number(e.currentTarget.dataset.index)
  this.setData({['courseFilters['+index+'].index']:Number(e.detail.value)})
  this.filterCourses()
 },
 toggleExisting(){this.setData({showExisting:!this.data.showExisting})},
 async course(index){const c=this.data.courses[index];this.setData({courseIndex:index,lessons:[],rows:[],lessonIndex:0,form:{...this.data.form,scheduleId:c?String(c.scheduleId):''}});if(!c)return;const r=await request({url:base+'/lessons',data:{scheduleId:c.scheduleId}});if(!this.current())return;this.setData({lessons:(r.data||[]).map(l=>({...l,label:`第${l.lessonNo}讲 · ${l.classDate}${l.locked?'（已锁定）':''}`}))});await this.roster()},
 pickCourse(e){this.run(()=>this.course(Number(e.detail.value)))},pickLesson(e){this.setData({lessonIndex:Number(e.detail.value)});this.run(()=>this.roster())},
 async roster(){const l=this.data.lessons[this.data.lessonIndex];if(!l)return;const r=await request({url:base+'/roster',data:{scheduleId:this.data.form.scheduleId,lessonId:l.lessonId}});if(this.current())this.setData({rows:r.data||[]})},
 input(e){const f=e.currentTarget.dataset.field;if(f==='keyword')this.setData({keyword:e.detail.value});else this.setData({form:{...this.data.form,[f]:e.detail.value}})},
 search(){return this.run(async()=>{const r=await request({url:base+'/students',data:{keyword:this.data.keyword}});if(this.current())this.setData({students:(r.data||[]).map(s=>({...s,label:`${s.studentName} · 档案${s.studentId} · ${s.contactPhone||''}`})),studentIndex:-1,form:{...this.data.form,studentId:''}})})},
 pickStudent(e){const i=Number(e.detail.value),s=this.data.students[i];if(s)this.setData({studentIndex:i,form:{...this.data.form,studentId:String(s.studentId)}})},
 pickSource(e){const i=Number(e.detail.value);this.setData({sourceIndex:i,form:{...this.data.form,source:['OFFLINE','ENROLLED','TRIAL','TRANSFER'][i]}})},pickPayment(e){const i=Number(e.detail.value);this.setData({paymentIndex:i,form:{...this.data.form,paymentStatus:['UNPAID','HISTORICAL','ENROLLMENT'][i]}})},
 add(){return this.run(async()=>{const body=range(this.data.form);const lessons=this.data.lessons;if(!lessons.some(l=>Number(l.lessonNo)===body.startLesson)||body.endLesson!==null&&!lessons.some(l=>Number(l.lessonNo)===body.endLesson))throw new Error('起止讲次须在本季度课次内');await request({url:base+'/roster',method:'POST',data:body});if(this.current()){await this.roster();wx.showToast({title:'已保存名单范围'})}})},
 inputOfflineName(e){this.setData({offlineName:e.detail.value})},
 addOffline(){
  const lesson=this.data.lessons[this.data.lessonIndex]
  const studentName=this.data.offlineName.trim()
  if(!studentName||!lesson||!lesson.classDate||Number(lesson.locked)||Number(lesson.cancelled)||this.data.busy)return
  return this.run(async()=>{
   await request({url:base+'/roster/offline',method:'POST',data:{
    scheduleId:this.data.form.scheduleId,startLesson:Number(lesson.lessonNo),studentName
   }})
   if(!this.current())return
   this.setData({offlineName:''})
   await this.roster()
   if(this.current())wx.showToast({title:'已补录'})
  })
 },
 togglePending(){
  if(this.data.busy)return
  const showPending=!this.data.showPending
  this.setData({showPending,error:''})
  if(showPending)return this.run(()=>this.loadPending())
 },
 async loadPending(){
  const result=await request({url:base+'/students/pending-parent'})
  if(!this.current())return
  this.setData({pendingRows:result.data||[]})
  this.filterPending()
 },
 searchPending(e){this.setData({pendingQuery:e.detail.value});this.filterPending()},
 filterPending(){
  const query=this.data.pendingQuery.trim().toLowerCase()
  this.setData({filteredPending:this.data.pendingRows.filter(row=>
   row.studentName.toLowerCase().includes(query)||row.courses.some(course=>(course.courseName||'').toLowerCase().includes(query)))})
 },
 bindParent(e){
  const pending=this.data.showPending
  const student=(pending?this.data.filteredPending:this.data.rows)[e.currentTarget.dataset.index]
  if(!student||this.data.busy)return
  wx.showModal({title:'绑定家长账号',content:student.studentName,editable:true,
   placeholderText:'输入已核实的家长注册手机号',success:result=>{
    if(!result.confirm||!this.current())return
    this.run(async()=>{
     const phone=result.content.trim()
     if(!/^1[3-9][0-9]{9}$/.test(phone))throw new Error('请输入家长已注册的11位手机号')
     await request({url:base+'/students/'+student.studentId+'/parent',method:'PUT',data:{phone}})
     if(!this.current())return
     if(pending){
      this.setData({pendingRows:this.data.pendingRows.filter(row=>row.studentId!==student.studentId)})
      this.filterPending()
     }
     if(this.data.form.scheduleId)await this.roster()
     if(this.current())wx.showToast({title:'已绑定'})
    })
   }})
 },
 remove(e){const row=this.data.rows[e.currentTarget.dataset.index];if(!row||this.data.busy)return;wx.showModal({title:'从第几讲起移出？',editable:true,placeholderText:'输入生效讲次，历史点名保留',success:r=>{if(!r.confirm||!this.current())return;const n=Number(r.content);if(!Number.isInteger(n)||n<1){this.setData({error:'请输入有效讲次'});return}this.run(async()=>{await request({url:base+'/roster/'+(row.membershipId?row.membershipId:'enrollment/'+row.enrollmentId),method:'PUT',data:{scheduleId:this.data.form.scheduleId,effectiveLesson:n,reason:this.data.form.reason}});if(this.current())await this.roster()})}})}
})
