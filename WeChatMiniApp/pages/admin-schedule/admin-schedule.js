const { request } = require('../../utils/request')
const root = '/miniapp/staff/schedule'
const staff = roles => (roles || []).some(r => ['admin', 'business_admin', 'education'].includes(r))
const labels = rows => (rows || []).map(r => ({ value: r.value || r.dictLabel, label: r.label || r.dictLabel }))

Page({
  data: { loading: false, ready: false, error: '', list: [], total: 0, keyword: '', pageNum: 1, pageSize: 20,
    formOpen: false, saving: false, recognizing: false, formError: '', form: {}, campusOptions: [], teachers: [], fields: [], periods: [], classTypes: [], timeSlots: [], singleTimeSlots: [],
    modes: [{label:'班课',value:'1'},{label:'一对一',value:'2'}],
    patterns:[{label:'每周一次',value:'WEEKLY'},{label:'连上无假期',value:'DAILY'},{label:'上5休1',value:'DAILY_5_1'},{label:'上6休1',value:'DAILY_6_1'},{label:'上5休2',value:'DAILY_5_2'},{label:'上4休1',value:'DAILY_4_1'},{label:'其他（暂不排课）',value:'OTHER'}],
    patternIndex:0, priceOptions:['220','250','280','手动填写'], priceChoice:'',
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
      const fields=[['termName','学期',d.terms],['periodName','期次或上课日',d.periods],['timeSlot','时段',d.timeSlots],['gradeName','年级',d.grades],['subjectName','学科',d.subjects]]
        .map(([key,label,options])=>({key,label,options:labels(options)}))
      this.setData({periods:labels(d.periods),classTypes:labels(d.classTypes),timeSlots:labels(d.timeSlots),singleTimeSlots:labels(d.singleTimeSlots),campusOptions:Object.keys(d.classrooms || {}).filter(c=>c!=='未分配校区'),teachers,fields,ready:true})
      if(this.data.formOpen)this.setPeriodOptions()
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
    this._automaticCourseName=null
    this._formVersion=(this._formVersion || 0)+1
    this.setSlotOptions('1')
    this.setData({priceChoice:'',form:{campusName:'',courseYear:String(new Date().getFullYear()),courseClassName:'',scheduleCode:'',termName:'',periodName:'',timeSlot:'',startTime:'',endTime:'',gradeName:'',subjectName:'',teacherId:'',teacherLabel:'',classType:'',classMode:'1',classPattern:'WEEKLY',startDate:'',endDate:'',unitPrice:'',enrollLimit:'',recruitStatus:'0',status:'0',remark:''},patternIndex:0,formOpen:true,formError:''})
    this.setPeriodOptions()
  },
  async edit(e) {
    if(!this.current() || !this.data.ready || this.data.saving)return
    const id=String(e.currentTarget.dataset.id),version=this._version
    if(!/^[1-9]\d*$/.test(id))return
    try {
      const res=await request({url:root+'/'+id})
      if(!this.current() || version!==this._version)return
      const f=res.data || {}
      if(!f.classPattern)f.classPattern=/^周[一二三四五六日]$/.test(f.periodName || '')?'WEEKLY':'DAILY_5_1'
      const teacher=this.data.teachers.find(t=>t.value===String(f.teacherId))
      this._automaticCourseName=null
      this._formVersion=(this._formVersion || 0)+1
      this.setSlotOptions(f.classMode)
      this.setData({priceChoice:f.unitPrice == null ? '' : [220,250,280].includes(Number(f.unitPrice)) ? String(Number(f.unitPrice)) : '手动填写',form:{...f,teacherId:String(f.teacherId || ''),teacherLabel:teacher?teacher.label:f.teacherName,startDate:(f.startDate || '').slice(0,10),endDate:(f.endDate || '').slice(0,10)},patternIndex:this.patternIndexOf(f.classPattern),formOpen:true,formError:''})
      this.setPeriodOptions()
    } catch(e) { if(this.current() && version===this._version)wx.showToast({title:e.message || '课程加载失败',icon:'none'}) }
  },
  async editBillingCount() {
    if(!this.current() || this.data.saving || !this.data.form.scheduleId || this.data.form.classMode==='2')return
    const id=this.data.form.scheduleId, token=this._token, version=this._formVersion
    const valid=()=>this.current() && token===this._token && version===this._formVersion
    this.setData({saving:true,formError:''})
    try {
      const r=await request({url:'/system/coursePrice/lesson-count/'+id});if(!valid())return
      const c=r.data, choice=await wx.showActionSheet({itemList:['手动修改计费课次数','恢复系统自动计算']});if(!valid())return
      let count=c.systemLessonCount, reason='', mismatch=false
      const manual=choice.tapIndex===0
      if(manual) {
        const entered=await wx.showModal({title:'修改计费课次数',content:'系统计算：'+(c.systemLessonCount==null?'待配置':c.systemLessonCount+'次')+'；当前计费：'+(c.lessonCount || '待配置')+'次。请输入整数课次数。',editable:true,placeholderText:'请输入计费课次数',confirmText:'下一步'})
        if(!entered.confirm || !valid())return
        const value=String(entered.content || '').trim()
        if(!/^\d+$/.test(value) || Number(value)<1 || Number(value)>3661)throw new Error('课次数须为1至3661的整数')
        count=Number(value);mismatch=count!==c.systemLessonCount
        if(mismatch) {
          const note=await wx.showModal({title:'填写修改原因',editable:true,placeholderText:'与系统计算不一致，需填写原因',confirmText:'下一步'})
          if(!note.confirm || !valid())return
          reason=String(note.content || '').trim();if(!reason || reason.length>255)throw new Error('请填写255字以内的修改原因')
        }
      } else if(count==null)throw new Error('排课日期或规则未配置完整，无法自动计算')
      const confirmed=await wx.showModal({title:mismatch?'课次数不一致提醒':'确认计费课次数',content:(mismatch?'系统计算为'+(c.systemLessonCount==null?'待配置':c.systemLessonCount+'次')+'，手动填写为'+count+'次。':'计费课次数将采用'+count+'次。')+'仅影响后续定价，不改变已有订单金额，也不改变实际排课。',confirmText:'确认保存'})
      if(!confirmed.confirm || !valid())return
      await request({url:'/system/coursePrice/lesson-count/'+id,method:'PUT',data:{mode:manual?'MANUAL':'AUTO',lessonCount:manual?count:null,reason,confirmMismatch:mismatch,version:c.lessonCountVersion}})
      if(valid())wx.showToast({title:'计费课次数已更新',icon:'success'})
    } catch(e) {if(valid() && !String(e.errMsg || '').includes('cancel'))this.setData({formError:e.message || '课次数修改失败，请重试'})}
    finally {if(this._alive && token===this._token)this.setData({saving:false})}
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
  close() { if(!this.data.saving){this._formVersion=(this._formVersion || 0)+1;this.setData({formOpen:false,formError:''})} },
  input(e) { if(this.current() && !this.data.saving)this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value}) },
  async recognizeName() {
    if(!this.current() || !this.data.formOpen || this.data.saving || this.data.recognizing)return
    const name=String(this.data.form.courseClassName || '').trim(), version=this._version, formVersion=this._formVersion, scheduleId=this.data.form.scheduleId || ''
    if(!name){wx.showToast({title:'请先填写课程名称',icon:'none'});return}
    this.setData({recognizing:true})
    const valid=()=>this.current() && this.data.formOpen && version===this._version && formVersion===this._formVersion && String(this.data.form.courseClassName || '').trim()===name && (this.data.form.scheduleId || '')===scheduleId
    try {
      const result=await request({url:root+'/recognize-name',method:'POST',data:{name}});if(!valid())return
      const data=result.data || {}, warnings=(data.warnings || []).slice(), teacher=this.data.teachers.find(t=>String(t.value)===String(data.teacherId))
      const proposed={gradeName:data.gradeName,subjectName:data.subjectName,classType:data.classType,teacherId:teacher && teacher.value}
      if(data.teacherId && !teacher)warnings.push('教师选项已变化，请手动选择')
      if(proposed.classType && !this.data.classTypes.some(t=>t.value===proposed.classType)){delete proposed.classType;warnings.push('识别的班型不在启用选项中，请手动选择')}
      if((data.teacherCandidates || []).length>1)warnings.push('候选教师：'+data.teacherCandidates.map(t=>`${t.name || t.nickName || '教师'}（账号编号 ${t.id}）`).join('、'))
      const labels={gradeName:'年级',subjectName:'学科',classType:'班型',teacherId:'教师'}
      const keys=Object.keys(proposed).filter(k=>proposed[k]!==undefined && proposed[k]!==null && proposed[k]!=='')
      const conflicts=keys.filter(k=>this.data.form[k]!==undefined && this.data.form[k]!==null && this.data.form[k]!=='' && String(this.data.form[k])!==String(proposed[k]))
      const lines=keys.map(k=>`${labels[k]}：${k==='teacherId' ? teacher.label : proposed[k]}`)
      const content=[...(lines.length?lines:['没有可自动填写的字段']),...warnings,...(conflicts.length?['已有字段不同：'+conflicts.map(k=>labels[k]).join('、')]:[])].join('\n')
      const modal=await wx.showModal({title:'识别结果',content,showCancel:!!conflicts.length,confirmText:conflicts.length?'替换已有':'确定',cancelText:'保留已有'})
      if(!valid())return
      const change={};keys.forEach(k=>{const old=this.data.form[k];if((conflicts.length && modal.confirm) || old===undefined || old===null || old===''){change['form.'+k]=proposed[k];if(k==='teacherId')change['form.teacherLabel']=teacher.label}})
      this.setData(change)
    } catch(e) { if(valid())wx.showToast({title:e.message || '识别失败，请重试',icon:'none'}) }
    finally { if(this._alive && this._token===wx.getStorageSync('token'))this.setData({recognizing:false}) }
  },
  chooseCampus(e) { if(!this.data.saving && !this.data.form.classroomId)this.setData({'form.campusName':this.data.campusOptions[Number(e.detail.value)] || ''}) },
  choosePrice(e) { if(this.data.saving)return;const choice=this.data.priceOptions[Number(e.detail.value)];if(choice)this.setData({priceChoice:choice,'form.unitPrice':choice==='手动填写'?'':choice}) },
  chooseTeacher(e) { const t=this.data.teachers[Number(e.detail.value)];if(t && !this.data.saving){this.setData({'form.teacherId':t.value,'form.teacherLabel':t.label});this.autoCourseName()} },
  autoCourseName() {
    const f=this.data.form, teacher=this.data.teachers.find(t=>String(t.value)===String(f.teacherId))
    const name=teacher && (teacher.realName || teacher.userName || teacher.nickName)
    if(!f.gradeName || !f.subjectName || !f.classType || !name) {if(this._automaticCourseName && f.courseClassName===this._automaticCourseName){this._automaticCourseName=null;this.setData({'form.courseClassName':''})}return}
    if(f.courseClassName && f.courseClassName!==this._automaticCourseName)return
    this._automaticCourseName=[f.gradeName,f.subjectName,f.classType,name].join('')
    this.setData({'form.courseClassName':this._automaticCourseName})
  },
  setSlotOptions(mode) {
    const options=mode==='2'?this.data.singleTimeSlots:this.data.timeSlots
    this.setData({fields:this.data.fields.map(f=>f.key==='timeSlot'?{...f,options}:f)})
  },
  setPeriodOptions(clearInvalid=false) {
    const weekly=this.data.form.classPattern==='WEEKLY'
    const options=this.data.periods.filter(p=>/^周[一二三四五六日]$/.test(p.value)===weekly)
    const change={fields:this.data.fields.map(f=>f.key==='periodName'?{...f,label:weekly?'上课星期':'期次',options}:f)}
    if(clearInvalid && !options.some(p=>p.value===this.data.form.periodName))change['form.periodName']=''
    this.setData(change)
  },
  /** 上课模式在 patterns 里的下标，找不到时退回「每周一次」 */
  patternIndexOf(value) {
    const i=this.data.patterns.findIndex(p=>p.value===value)
    return i<0?0:i
  },
  choose(e) {
    if(!this.current() || this.data.saving)return
    const key=e.currentTarget.dataset.key, f=this.data.fields.find(f=>f.key===key)
    const options=f?f.options:({classType:this.data.classTypes,classMode:this.data.modes,classPattern:this.data.patterns,recruitStatus:this.data.recruitOptions,status:this.data.statusOptions})[key]
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
    if(key==='classMode' && row.value==='2') {change['form.classPattern']='WEEKLY';change.patternIndex=0;change['form.periodName']=''}
    if(key==='timeSlot') {
      const times=row.value.split(/[-–—]/).map(t=>t.trim())
      if(times.length===2) {change['form.startTime']=times[0].length===4?'0'+times[0]+':00':times[0]+':00';change['form.endTime']=times[1].length===4?'0'+times[1]+':00':times[1]+':00'}
    }
    this.setData(change)
    if(key==='classPattern' || key==='classMode')this.setPeriodOptions(true)
    if(key==='classMode' && row.value==='2' && this.data.form.startDate)this.date({currentTarget:{dataset:{key:'startDate'}},detail:{value:this.data.form.startDate}})
    if(['gradeName','subjectName','classType'].includes(key))this.autoCourseName()
  },
  date(e) {
    if(this.data.saving)return
    this.setData({['form.'+e.currentTarget.dataset.key]:e.detail.value})
    if(this.data.form.classMode==='2')this.setData({'form.startDate':e.detail.value,'form.endDate':e.detail.value,'form.classPattern':'WEEKLY','patternIndex':0,'form.periodName':['周日','周一','周二','周三','周四','周五','周六'][new Date(e.detail.value.replace(/-/g,'/')+' 12:00:00').getDay()]})
  },
  async save() {
    if(!this.current() || !this.data.ready || this.data.saving)return
    const f=this.data.form
    if (!f.scheduleId && (!/^\d+(\.\d{1,2})?$/.test(String(f.unitPrice).trim()) || Number(f.unitPrice) <= 0)) { this.setData({formError:'课时单价须大于0且最多保留两位小数'}); return }
    if(f.classPattern==='WEEKLY' && !/^周[一二三四五六日]$/.test(f.periodName || '')){this.setData({formError:f.classMode==='2'?'请选择一对一上课日期':'请选择上课星期'});return}
    if(f.classPattern!=='WEEKLY' && /^周[一二三四五六日]$/.test(f.periodName || '')){this.setData({formError:'请选择期次'});return}
    for(const [key,label] of [['campusName','校区'],['termName','学期'],['periodName',f.classPattern==='WEEKLY'?'上课星期':'期次'],['timeSlot','时段'],['gradeName','年级'],['subjectName','学科'],['teacherId','教师'],['courseClassName','课程名称']]) {
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
        const result=await wx.showModal({title:'确认修改课程',content:'即使已有报名或签到记录，也将修改课程信息并影响后续上课安排。原报名、签到和财务记录保留，不会自动调整费用。请先与学员沟通，确认后强制保存。',confirmText:'确认修改'})
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
