const { request } = require('../../utils/request')
const root = '/miniapp/staff/schedule'
const staff = roles => (roles || []).some(r => ['admin', 'business_admin', 'education'].includes(r))
const labels = rows => (rows || []).map(r => ({ value: r.value || r.dictLabel, label: r.label || r.dictLabel }))

Page({
  data: { loading: false, ready: false, error: '', list: [], total: 0, keyword: '', pageNum: 1, pageSize: 20,
    formOpen: false, saving: false, formError: '', form: {}, campusOptions: [], teachers: [], fields: [], timeSlots: [], singleTimeSlots: [],
    modes: [{label:'班课',value:'1'},{label:'一对一',value:'2'}],
    patterns:[{label:'每周一次',value:'WEEKLY'},{label:'连上无假期',value:'DAILY'},{label:'上5休1',value:'DAILY_5_1'},{label:'上6休1',value:'DAILY_6_1'},{label:'上5休2',value:'DAILY_5_2'},{label:'上4休1',value:'DAILY_4_1'},{label:'其他（暂不排课）',value:'OTHER'}],
    patternIndex:0,
    recruitOptions:[{label:'开放报名',value:'0'},{label:'停止招生',value:'1'},{label:'已满',value:'2'}], statusOptions:[{label:'正常',value:'0'},{label:'停用',value:'1'}] },
  onLoad(options={}) { this._token=wx.getStorageSync('token');this._alive=true;this._editId=options.id || '';this._editOnLoad=!!this._editId },
  onShow() { this._visible=true;this.load() },
  onHide() { this._visible=false;this._version=(this._version || 0)+1 },
  onUnload() { this.onHide();this._alive=false },
  current() { return this._alive && this._visible && this._token && this._token===wx.getStorageSync('token') && staff(wx.getStorageSync('roles')) },
  onPullDownRefresh() { this.load().finally(()=>wx.stopPullDownRefresh()) },
  async load() {
    if(!this.current()) { this.setData({list:[],ready:false,formOpen:false,error:'仅教务和管理员可安排课程，请返回首页切换已授权身份。'});return }
    if(this.data.saving)return
    const version=this._version=(this._version || 0)+1
    this.setData({loading:true,ready:false,error:''})
    try {
      const [info,res]=await Promise.all([request({url:'/getInfo'}),request({url:root+'/options'})])
      if(!this.current() || version!==this._version)return
      if(!staff(info.roles || (info.data || {}).roles))throw new Error('教务或管理员权限已被撤销')
      const d=res.data || {}, teachers=(d.teachers || []).map(t=>({...t,label:t.realName || t.nickName || t.userName,value:String(t.userId)}))
      const fields=[['termName','学期',d.terms],['periodName','期次或上课日',d.periods],['timeSlot','时段',d.timeSlots],['gradeName','年级',d.grades],['subjectName','学科',d.subjects],['classType','班型',d.classTypes]]
        .map(([key,label,options])=>({key,label,options:labels(options)}))
      this.setData({timeSlots:labels(d.timeSlots),singleTimeSlots:labels(d.singleTimeSlots),campusOptions:Object.keys(d.classrooms || {}).filter(c=>c!=='未分配校区'),teachers,fields,ready:true})
      await this.loadList(version)
      if(this._editOnLoad && this.current() && version===this._version) { this._editOnLoad=false;await this.edit({currentTarget:{dataset:{id:this._editId}}}) }
    } catch(e) { if(this.current() && version===this._version)this.setData({ready:false,list:[],error:e.message || '排课选项加载失败，请重试'}) }
    finally { if(this.current() && version===this._version)this.setData({loading:false}) }
  },
  async loadList(version=this._version) {
    const res=await request({url:root+'/list',data:{pageNum:this.data.pageNum,pageSize:this.data.pageSize,courseClassName:this.data.keyword}})
    if(this.current() && version===this._version)this.setData({list:(res.rows || []).map(r=>({...r,locationText:[r.campusName,r.classroomId?'已分配教室':'待分配教室'].filter(Boolean).join(' · ')})),total:Number(res.total || 0)})
  },
  inputSearch(e) { if(!this.data.saving)this.setData({keyword:e.detail.value}) },
  search() { if(!this.data.saving) {this.setData({pageNum:1});this.load()} },
  previous() { if(this.data.pageNum>1 && !this.data.loading && !this.data.saving) {this.setData({pageNum:this.data.pageNum-1});this.load()} },
  next() { if(this.data.pageNum*this.data.pageSize<this.data.total && !this.data.loading && !this.data.saving) {this.setData({pageNum:this.data.pageNum+1});this.load()} },
  add() {
    if(!this.current() || !this.data.ready || this.data.loading || this.data.saving)return
    this.setSlotOptions('1')
    this.setData({form:{campusName:'',courseYear:String(new Date().getFullYear()),courseClassName:'',scheduleCode:'',termName:'',periodName:'',timeSlot:'',startTime:'',endTime:'',gradeName:'',subjectName:'',teacherId:'',teacherLabel:'',classType:'',classMode:'1',classPattern:'WEEKLY',startDate:'',endDate:'',unitPrice:'',enrollLimit:'',recruitStatus:'0',status:'0',remark:''},patternIndex:0,formOpen:true,formError:''})
  },
  async edit(e) {
    if(!this.current() || !this.data.ready || this.data.saving)return
    const id=String(e.currentTarget.dataset.id),version=this._version
    if(!/^[1-9]\d*$/.test(id))return
    try {
      const res=await request({url:root+'/'+id})
      if(!this.current() || version!==this._version)return
      const f=res.data || {}, teacher=this.data.teachers.find(t=>t.value===String(f.teacherId))
      this.setSlotOptions(f.classMode)
      this.setData({form:{...f,teacherId:String(f.teacherId || ''),teacherLabel:teacher?teacher.label:f.teacherName,startDate:(f.startDate || '').slice(0,10),endDate:(f.endDate || '').slice(0,10)},patternIndex:this.patternIndexOf(f.classPattern),formOpen:true,formError:''})
    } catch(e) { if(this.current() && version===this._version)wx.showToast({title:e.message || '课程加载失败',icon:'none'}) }
  },
  async remove(e) {
    if(!this.current() || !this.data.ready || this.data.loading || this.data.saving)return
    const id=String(e.currentTarget.dataset.id),version=this._version
    if(!/^[1-9]\d*$/.test(id))return
    let removed=false
    this.setData({saving:true,error:''})
    try {
      const result=await wx.showModal({title:'确认删除课程',content:'即使已有学员报名，也将删除该课程并停止招生。报名、收款及收据历史保留，不会自动退款，请先与学员沟通。',confirmText:'确认删除',confirmColor:'#d93025'})
      if(!result.confirm || !this.current() || version!==this._version)return
      await request({url:root+'/'+id+'?force=true',method:'DELETE'})
      removed=true
      if(this.current() && version===this._version)wx.showToast({title:'课程已删除',icon:'success'})
    } catch(e) { if(this.current() && version===this._version)this.setData({error:e.applicationRejected?e.message:'删除结果未确认，请刷新列表核对。'}) }
    finally { if(this._alive && this._token===wx.getStorageSync('token'))this.setData({saving:false});if(removed && this.current())await this.load() }
  },
  close() { if(!this.data.saving)this.setData({formOpen:false,formError:''}) },
  input(e) { if(this.current() && !this.data.saving)this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value}) },
  chooseCampus(e) { if(!this.data.saving && !this.data.form.classroomId)this.setData({'form.campusName':this.data.campusOptions[Number(e.detail.value)] || ''}) },
  chooseTeacher(e) { const t=this.data.teachers[Number(e.detail.value)];if(t && !this.data.saving)this.setData({'form.teacherId':t.value,'form.teacherLabel':t.label}) },
  setSlotOptions(mode) {
    const options=mode==='2'?this.data.singleTimeSlots:this.data.timeSlots
    this.setData({fields:this.data.fields.map(f=>f.key==='timeSlot'?{...f,options}:f)})
  },
  /** 上课模式在 patterns 里的下标，找不到时退回「每周一次」 */
  patternIndexOf(value) {
    const i=this.data.patterns.findIndex(p=>p.value===value)
    return i<0?0:i
  },
  choose(e) {
    if(!this.current() || this.data.saving)return
    const key=e.currentTarget.dataset.key, f=this.data.fields.find(f=>f.key===key)
    const options=f?f.options:({classMode:this.data.modes,classPattern:this.data.patterns,recruitStatus:this.data.recruitOptions,status:this.data.statusOptions})[key]
    const row=options && options[Number(e.detail.value)];if(!row)return
    const change={['form.'+key]:row.value}
    if(key==='classMode') {
      this.setSlotOptions(row.value)
      const slots=row.value==='2'?this.data.singleTimeSlots:this.data.timeSlots
      if(!slots.some(s=>s.value===this.data.form.timeSlot)) {
        change['form.timeSlot']='';change['form.startTime']='';change['form.endTime']=''
      }
    }
    if(key==='classPattern') change.patternIndex=Number(e.detail.value)
    if(key==='timeSlot') {
      const times=row.value.split(/[-–—]/).map(t=>t.trim())
      if(times.length===2) {change['form.startTime']=times[0].length===4?'0'+times[0]+':00':times[0]+':00';change['form.endTime']=times[1].length===4?'0'+times[1]+':00':times[1]+':00'}
    }
    this.setData(change)
  },
  date(e) {
    if(this.data.saving)return
    this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value})
    if(this.data.form.classMode==='2')this.setData({'form.startDate':e.detail.value,'form.endDate':e.detail.value,'form.classPattern':'WEEKLY','patternIndex':0,'form.periodName':['周日','周一','周二','周三','周四','周五','周六'][new Date(e.detail.value.replace(/-/g,'/')+' 12:00:00').getDay()]})
  },
  async save() {
    if(!this.current() || !this.data.ready || this.data.saving)return
    const f=this.data.form
    for(const [key,label] of [['campusName','校区'],['termName','学期'],['periodName','期次或上课日'],['timeSlot','时段'],['gradeName','年级'],['subjectName','学科'],['teacherId','教师'],['courseClassName','课程名称']]) {
      if(!String(f[key] || '').trim()) {this.setData({formError:'请填写或选择'+label});return}
    }
    const scheduleCode=f.scheduleCode || ('SCH'+Date.now().toString(36)+Math.random().toString(36).slice(2,12))
    this.setData({'form.scheduleCode':scheduleCode})
    const payload={...f,scheduleCode,teacherId:Number(f.teacherId),courseYear:Number(f.courseYear),unitPrice:f.unitPrice===''?null:f.unitPrice,enrollLimit:f.enrollLimit===''?null:Number(f.enrollLimit),startDate:f.startDate || null,endDate:f.endDate || null}
    delete payload.classroomId;delete payload.teacherLabel
    const version=this._version
    let saved=false
    this.setData({saving:true,formError:''})
    try {
      if(f.scheduleId) {
        const result=await wx.showModal({title:'确认修改课程',content:'若已有学员报名，修改后将影响该课程的报名安排。请先与学员沟通；确认后仍可保存。',confirmText:'确认修改'})
        if(!result.confirm || !this.current() || version!==this._version)return
      }
      await request({url:root+(f.scheduleId?'?force=true':''),method:f.scheduleId?'PUT':'POST',data:payload})
      saved=true
      if(!this.current() || version!==this._version)return
      this.setData({formOpen:false});wx.showToast({title:'排课已保存',icon:'success'})
    } catch(e) { if(this.current() && version===this._version)this.setData({formError:e.applicationRejected?e.message:'保存结果未确认，请先关闭表单并刷新列表核对，避免重复新增。'}) }
    finally { if(this._alive && this._token===wx.getStorageSync('token'))this.setData({saving:false});if(saved && this.current())await this.load() }
  }
})
