const {request}=require('./request')
const personal=require('./personal-account')
const context=require('./child-context')
const amount=value=>{if(value==null)return '待核价';const s=String(value);if(!/^\d+(\.\d{1,2})?$/.test(s))return '待核对';const [a,b='']=s.split('.');return a+'.'+b.padEnd(2,'0')}
function child(row){
  const remaining=row.remainingCredits,reserved=row.reservedCredits
  const available=remaining!=null && reserved!=null?Number(remaining)-Number(reserved):row.availableCredits
  return {...row,accountId:String(row.accountId),initial:String(row.studentName||'孩').slice(0,1),availableText:available!=null&&Number.isFinite(Number(available))?String(available):'—',pendingBadge:personal.badge(row.actionPendingCount),paymentPendingCount:Number(row.paymentPendingCount)||0,homeworkPendingCount:Number(row.homeworkPendingCount)||0,courseCount:Number(row.courseCount)||0}
}
function record(row,kind){
  if(kind==='attendance')return {...row,recordKey:String(row.recordKey),timeText:row.classDate||'',statusText:({'1':'到课','2':'录播','3':'请假','4':'试听到课','5':'调课到课','6':'未到课'})[row.signStatus]||'待核对',reviewText:row.source==='SINGLE'?'教师已确认':String(row.reviewStatus)==='1'?'已复核':String(row.reviewStatus)==='2'?'审核异常，待更正':'待教务复核'}
  if(kind==='history')return personal.decorate(row,kind)
  let progress=personal.payText(row.payStatus)
  if(row.settlementMethod==='P')progress='课包结算'
  else if(Number(row.paymentUnknownCount)>0)progress='收款待核对'
  else if(Number(row.paymentPendingCount)>0)progress='待管理员确认收款'
  else if(Number(row.refundProgressCount)>0)progress='退款处理中'
  else if(row.billingStatus==='PENDING_PRICE')progress='待核价'
  else if(row.billingStatus==='HISTORY_PENDING')progress='历史待核验'
  const actionable=row.actionable===true||Number(row.actionable)===1
  const timeText=row.billType==='CREDIT_PACKAGE'?'一对一课包 · '+(row.packageCreditedTime?'已入账':row.billingStatus==='CLOSED'?'已取消':'线下确认收款后入账'):row.classDate?`${row.classDate} ${String(row.startTime||'').slice(0,5)}–${String(row.endTime||'').slice(0,5)}`:`班课 · ${String(row.startTime||'').slice(0,5)}–${String(row.endTime||'').slice(0,5)}`
  return {...personal.decorate(row,'enrollments'),enrollmentId:String(row.enrollmentId),actionable,progressText:progress,amountText:amount(row.payableAmount),receivedText:amount(row.receivedAmount),refundedText:amount(row.refundedAmount),receivedLabel:row.billingStatus==='HISTORY_PENDING'?'待核验':'¥'+amount(row.receivedAmount),refundedLabel:row.billingStatus==='HISTORY_PENDING'?'待核验':'¥'+amount(row.refundedAmount),timeText}
}
function page(view='list'){
  const base=personal.page(false,'children')
  return {...base,
    data:{...base.data,view,child:null,accountId:'',section:'courses',recordKind:'enrollments',legacy:false,childCount:0,unlinkedCount:0,unlinkedPendingBadge:'',sections:[{key:'courses',name:'课程'},{key:'learning',name:'学习记录'},{key:'fees',name:'课包与费用'}]},
    onLoad(options={}){
      this._visible=true
      this._createOnLoad=options.create==='1';this._returnHome=options.returnHome==='1'
      const legacy=view==='list'&&options.legacy==='1',accountId=view==='detail'?String(options.accountId||''):''
      this.setData({legacy,accountId,view,section:'courses',recordKind:'enrollments'})
      if(view==='detail'&&!/^[1-9]\d*$/.test(accountId)){this._alive=true;this._token=wx.getStorageSync('token');this.setData({error:'请从孩子列表选择孩子'});return}
      base.onLoad.call(this,options)
      if(wx.setNavigationBarTitle)wx.setNavigationBarTitle({title:legacy?'未关联历史记录':view==='detail'?'孩子详情':'我的账户'})
    },
    current(){return this._visible!==false&&base.current.call(this)},
    onShow(){this._visible=true;if(view==='detail'&&context.owner()&&context.id()&&context.id()!==this.data.accountId){this.setData({child:null,records:[],accounts:[],accountId:context.id(),showForm:false,showHistory:false,pageNum:1});this._seen=true;}if(!this.current())this.setData({child:null,form:{},pendingBadge:'',unlinkedPendingBadge:''});base.onShow.call(this)},
    onHide(){this._visible=false;this._version=(this._version||0)+1;this._operationVersion=(this._operationVersion||0)+1;this._historyVersion=(this._historyVersion||0)+1;this.setData({submitting:false})},
    onUnload(){this.onHide();base.onUnload.call(this)},
    async load(){
      if(!this.current())return
      const version=this._version=(this._version||0)+1,detailView=view==='detail',legacy=this.data.legacy
      this.setData({loading:true,error:''})
      try{
        const snapshot=request({url:detailView?`/miniapp/parent/account/children/${this.data.accountId}`:'/miniapp/parent/account'})
        const rows=detailView||legacy?request({url:'/miniapp/parent/account/records',data:{kind:this.data.recordKind,pageNum:this.data.pageNum,pendingOnly:this.data.pendingOnly,...(this.data.section==='fees'?{includePackages:true}:{}),...(detailView?{accountId:this.data.accountId}:{unlinkedOnly:true})}}):Promise.resolve({data:{rows:[],total:0}})
        const [a,b]=await Promise.all([snapshot,rows]);if(!this.current()||version!==this._version)return
        const d=a.data||{},account=detailView?child(d.child):null,accounts=detailView?[account]:(d.children||[]).map(child)
        this.setData({detail:d,child:account,accounts,childCount:accounts.length,records:((b.data||{}).rows||[]).map(r=>record(r,this.data.recordKind)),total:(b.data||{}).total||0,pendingCount:detailView?d.pendingCount:d.actionPendingCount||0,pendingBadge:personal.badge(detailView?d.pendingCount:d.actionPendingCount),unlinkedCount:d.unlinkedCount||0,unlinkedPendingBadge:personal.badge(d.unlinkedPendingCount)})
        if(account)context.select(account)
        else if(!legacy){const preferred=this._lastCreatedChildId!==this._appliedCreatedChildId?this._lastCreatedChildId:null;context.reconcile(accounts,preferred);if(preferred)this._appliedCreatedChildId=preferred}
        if(this._createOnLoad&&!detailView&&!legacy){this._createOnLoad=false;base.openCreate.call(this)}
        if(account&&wx.setNavigationBarTitle)wx.setNavigationBarTitle({title:account.studentName})
      }catch(e){if(this.current()&&version===this._version)this.setData({error:e.message||'暂时无法加载，请重试',records:[],accounts:[],child:null,detail:null,pendingBadge:'',unlinkedPendingBadge:''})}
      finally{if(this.current()&&version===this._version)this.setData({loading:false})}
    },
    openChild(e){if(!this.current()||this.data.loading)return;const a=this.data.accounts.find(a=>a.accountId===String(e.currentTarget.dataset.id));if(a){context.select(a);wx.navigateTo({url:`/pages/parent-child-detail/parent-child-detail?accountId=${encodeURIComponent(a.accountId)}`})}},
    openCreate(){if(view==='list'&&!this.data.legacy)return base.openCreate.call(this)},
    async save(){await base.save.call(this);if(this._returnHome&&this._lastCreatedChildId&&!this.data.showForm&&this.current()){const created=this.data.accounts.find(a=>a.accountId===this._lastCreatedChildId);if(created){context.select(created);wx.navigateBack({delta:1})}}},
    editProfile(){if(this.data.child)base.openEdit.call(this,{currentTarget:{dataset:{id:this.data.child.accountId}}})},
    async removeChild(e){await base.removeChild.call(this,e);if(view==='detail'&&this.current()&&!this.data.child&&wx.navigateBack)wx.navigateBack({delta:1})},
    openLegacy(){if(this.current())wx.navigateTo({url:'/pages/parent-account/parent-account?legacy=1'})},
    openService(e){if(!this.current())return;const routes={orders:'/pages/material-orders/material-orders',coupons:'/pages/my-coupons/my-coupons',referral:'/pages/parent-referral/parent-referral'};const url=routes[e.currentTarget.dataset.key];if(url)wx.navigateTo({url})},
    scoped(url,extra=''){if(this.current()&&this.data.child)wx.navigateTo({url:`${url}?accountId=${encodeURIComponent(this.data.accountId)}${extra}`})},
    enroll(){this.scoped('/pages/enrollment/enrollment')},purchasePackage(){if(!this.data.loading)this.scoped('/pages/lesson-packages/lesson-packages')},timetable(){this.scoped('/pages/timetable/timetable')},homework(e){this.scoped('/pages/homework/homework',e&&e.currentTarget.dataset.todo?'&status=todo':'')},leave(){this.scoped('/pages/parent-leave/parent-leave')},
    section(e){if(!this.current()||this.data.submitting||this.data.loading)return;const section=e.currentTarget.dataset.section;if(!['courses','learning','fees'].includes(section))return;this.setData({section,recordKind:section==='learning'?'attendance':'enrollments',pageNum:1,pendingOnly:false,records:[]});this.load()},
    transactions(){if(!this.current()||this.data.loading)return;this.setData({section:'fees',recordKind:this.data.recordKind==='history'?'enrollments':'history',pageNum:1,pendingOnly:false,records:[]});this.load()},
    handlePayment(){if(!this.current()||this.data.loading)return;this.setData({section:'fees',recordKind:'enrollments',pendingOnly:true,pageNum:1,records:[]});this.load()},
    allPayments(){if(!this.current()||this.data.loading)return;this.setData({pendingOnly:false,pageNum:1});this.load()},
    creditHistory(){if(this.data.child)return base.accountHistory.call(this,{currentTarget:{dataset:{id:this.data.accountId}}})},
    openFinance(e){if(!this.current())return;const id=String(e.currentTarget.dataset.id);if(!this.data.records.some(r=>r.enrollmentId===id))return;wx.navigateTo({url:`/pages/tuition-detail/tuition-detail?id=${encodeURIComponent(id)}${this.data.child?'&accountId='+encodeURIComponent(this.data.accountId):'&unlinked=1'}`})}
  }
}
module.exports={page,child,record,amount}
