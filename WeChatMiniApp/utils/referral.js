const { request } = require('./request')
const display = require('./coupon-display')
const labels = { WAITING:'待达标', PENDING:'待奖励审核', REWARDED:'已发奖励', OPEN:'待核查', RESOLVED:'已处理', AVAILABLE:'未使用', LOCKED:'占用中', USED:'已使用', REVOKED:'已撤回', CANCELLED:'达标报名已取消', FULL_REFUND:'达标收款已全额退款', PAYMENT_INVALID:'收款资格异常' }
const badge = n => Number(n)>99?'99+':Number(n)>0?String(n):''
const historyLabels = {BIND:'绑定推荐人',CORRECT:'管理员纠错',GRANT:'发放奖励',REVOKE:'撤回奖励',DETECTED:'发现异常',RESOLVE:'处理异常'}
function history(raw){try{return (typeof raw==='string'?JSON.parse(raw):raw||[]).map(e=>({...e,label:historyLabels[e.action]||e.action,timeText:String(e.time||'').replace('T',' ').slice(0,19)}))}catch(e){return[]}}
const key = () => `REF_${Date.now()}_${Math.random().toString(36).slice(2,12)}`
function remaining(seconds){const n=Math.max(0,Math.ceil(Number(seconds)||0));return n>=86400?`${Math.floor(n/86400)}天${Math.floor(n%86400/3600)}小时`:n>=3600?`${Math.floor(n/3600)}小时${Math.floor(n%3600/60)}分钟`:n>0?'不足1小时':'即将截止'}
function page(admin) {
  const role=admin?'admin':'parent',base=admin?'/system/referral':'/miniapp/parent/referral'
  return {
    data:{admin,home:{},rows:[],total:0,pageNum:1,loading:false,busy:false,error:'',kind:admin?'records':'bindings',state:'',parentPhone:'',filterRole:'',filterRoles:['全部关系角色','新用户','老用户'],filterRoleIndex:0,code:'',pendingBadge:'',anomalyBadge:'',referralBadge:'',selectedIds:[],showGrant:false,templates:[],templateIndex:-1,rewardRoles:['新老双方','新用户','老用户'],rewardRoleIndex:0,reason:'',preview:null,selectedParents:[],uncertain:false},
    onLoad(){this._alive=true;this._visible=true},
    onShow(){this._visible=true;const token=wx.getStorageSync('token');if(token!==this._token){this._frozen=null;this.setData({home:{},rows:[],selectedIds:[],showGrant:false,preview:null,uncertain:false,pendingBadge:'',anomalyBadge:'',referralBadge:'',code:'',parentPhone:'',reason:'',busy:false})}this._token=token;this.load();clearInterval(this._timer);if(admin)this._timer=setInterval(()=>this.loadCounts(),30000)},
    onHide(){this._visible=false;this._version=(this._version||0)+1;clearInterval(this._timer);if(this._frozen)this.setData({uncertain:true});this.setData({busy:false})},
    onUnload(){this.onHide();this._alive=false},
    current(){return this._alive && this._visible && !!this._token && this._token===wx.getStorageSync('token') && (wx.getStorageSync('roles')||[]).includes(role)},
    onPullDownRefresh(){if(this.data.busy){wx.stopPullDownRefresh();return Promise.resolve()}return this.load().finally(()=>wx.stopPullDownRefresh())},
    async load(){
      if(!this.current()){this.setData({home:{},rows:[],error:'请使用对应身份重新进入',pendingBadge:'',anomalyBadge:'',referralBadge:''});return}
      const version=this._version=(this._version||0)+1;this.setData({loading:true,error:''})
      try {
        const params={kind:this.data.kind,state:this.data.state,pageNum:this.data.pageNum,pageSize:20,recipientRole:this.data.filterRole,...(admin&&this.data.parentPhone.trim()?{parentPhone:this.data.parentPhone.trim()}:{})}
        const [list,home]=await Promise.all([request({url:admin?base:base+'/records',data:params}),admin?request({url:base+'/counts'}):request({url:base})])
        if(!this.current()||version!==this._version)return
        const h=home.data||{}
        this.setData({rows:(list.rows||[]).map(r=>({...r,rowId:String(r[this.data.kind==='bindings'?'bindingId':this.data.kind==='records'?'recordId':this.data.kind==='rewards'?'rewardId':'anomalyId']),historyRows:history(r.history),label:labels[r.cause||r.state||r.recommendationStatus||r.reviewStatus||r.couponStatus]||'',couponLabel:labels[r.couponStatus]||'',timeText:r.updateTime||r.qualifiedAt||r.createTime||r.boundAt})),total:list.total||0,home:admin?{}:{...h,deadlineText:String(h.bindDeadline||'').replace('T',' ').slice(0,19),remainingText:remaining(h.remainingSeconds)},pendingBadge:badge(h.pending),anomalyBadge:badge(h.anomalies),referralBadge:badge(Number(h.pending||0)+Number(h.anomalies||0)),selectedIds:[]})
      }catch(e){if(this.current()&&version===this._version)this.setData({error:e.message||'暂时无法加载，请重试',rows:[],home:{},pendingBadge:'',anomalyBadge:'',referralBadge:''})}
      finally{if(this.current()&&version===this._version)this.setData({loading:false})}
    },
    async loadCounts(){if(!admin||!this.current())return;const token=this._token,v=this._version;try{const r=await request({url:base+'/counts'});if(this.current()&&token===this._token&&v===this._version)this.setData({pendingBadge:badge(r.data.pending),anomalyBadge:badge(r.data.anomalies),referralBadge:badge(Number(r.data.pending||0)+Number(r.data.anomalies||0))})}catch(e){}},
    tab(e){if(this.data.busy||this.data.loading)return;this.setData({kind:e.currentTarget.dataset.kind,state:'',pageNum:1,selectedIds:[]});this.load()},
    inputCode(e){if(!this.data.busy)this.setData({code:e.detail.value.trim().toUpperCase()})},
    copyCode(){if(this.current()&&this.data.home.referralCode)wx.setClipboardData({data:this.data.home.referralCode})},
    async bind(){
      if(admin||!this.current()||this.data.busy||!this.data.home.canBind)return
      const code=this.data.code,version=this._version;this.setData({busy:true})
      try {const r=await request({url:base+'/preview',method:'POST',data:{code}});if(!this.current()||version!==this._version)return
        const confirmed=await this.modal({title:'确认推荐人',content:`推荐人：${r.data.referrerName}\n确认后将绑定该家长，你不能自行更换。`});if(!confirmed.confirm||!this.current()||version!==this._version)return
        await request({url:base+'/bind',method:'POST',data:{code}});if(this.current()&&version===this._version){wx.showToast({title:'绑定成功',icon:'success'});this.setData({code:''});this.setData({busy:false});await this.load()}
      }catch(e){if(this.current()&&version===this._version)wx.showToast({title:e.message||'结果未确认，请刷新核对',icon:'none'})}finally{if(this.current()&&version===this._version)this.setData({busy:false})}
    },
    modal(options){return new Promise(resolve=>wx.showModal({...options,success:resolve,fail:()=>resolve({confirm:false})}))},
    inputParent(e){if(!this.data.busy)this.setData({parentPhone:e.detail.value.trim()})},
    chooseFilterRole(e){this.setData({filterRoleIndex:Number(e.detail.value),filterRole:['','NEW','OLD'][Number(e.detail.value)]})},
    search(){if(!this.data.busy&&!this.data.loading){if(this.data.parentPhone&&!/^[0-9]{3,11}$/.test(this.data.parentPhone)){wx.showToast({title:'请输入3至11位家长手机号',icon:'none'});return}this.setData({pageNum:1});this.load()}},
    selectRecords(e){if(!this.data.busy)this.setData({selectedIds:e.detail.value.map(String)})},
    async openGrant(){
      if(!admin||!this.current()||this.data.busy||!this.data.selectedIds.length)return
      this._draftIds=this.data.selectedIds.slice();this.setData({busy:true});const v=this._version
      try {const r=await request({url:'/system/coupon/templates',data:{templateStatus:'ACTIVE',pageSize:100,pageNum:1}});if(this.current()&&v===this._version){this._batchKey=key();this._frozen=null;this.setData({showGrant:true,templates:(r.rows||[]).map(display.template),templateIndex:-1,reason:'',preview:null,selectedParents:[],uncertain:false});this._parentsPicked=false}}catch(e){if(this.current()&&v===this._version)wx.showToast({title:e.message,icon:'none'})}finally{if(this.current()&&v===this._version)this.setData({busy:false})}
    },
    closeGrant(){if(!this.data.busy&&!this.data.uncertain){this.setData({showGrant:false,preview:null});this._frozen=null}},
    chooseTemplate(e){if(!this.data.busy&&!this.data.uncertain){this.setData({templateIndex:Number(e.detail.value),preview:null,selectedParents:[]});this._parentsPicked=false}},
    chooseRewardRole(e){if(!this.data.busy&&!this.data.uncertain){this.setData({rewardRoleIndex:Number(e.detail.value),preview:null,selectedParents:[]});this._parentsPicked=false}},
    inputReason(e){if(!this.data.busy&&!this.data.uncertain)this.setData({reason:e.detail.value})},
    selectParents(e){if(!this.data.busy&&!this.data.uncertain){this._parentsPicked=true;const selected=e.detail.value.map(String);this.setData({selectedParents:selected,preview:{...this.data.preview,previewHash:'',recipients:this.data.preview.recipients.map(p=>({...p,checked:selected.includes(String(p.parentId))}))}})}},
    draft(){const t=this.data.templates[this.data.templateIndex];return{recordIds:this._draftIds,recipientRole:['BOTH','NEW','OLD'][this.data.rewardRoleIndex],templateId:t&&t.templateId,...(this._parentsPicked?{parentIds:this.data.selectedParents.slice()}:{}),reason:this.data.reason.trim(),idempotencyKey:this._batchKey}},
    async previewGrant(){
      if(!admin||!this.current()||this.data.busy||this.data.uncertain)return
      const draft=this.draft();if(!draft.templateId||!draft.reason){wx.showToast({title:'请选择模板并填写发放原因',icon:'none'});return}
      const v=this._version;this.setData({busy:true})
      try{const r=await request({url:base+'/preview',method:'POST',data:draft});if(this.current()&&v===this._version){this.setData({preview:{...r.data,recipients:r.data.recipients.map(p=>({...p,checked:true}))},selectedParents:r.data.recipients.map(p=>String(p.parentId))});this._frozen=null;this._parentsPicked=true}}catch(e){if(this.current()&&v===this._version)wx.showToast({title:e.message,icon:'none'})}finally{if(this.current()&&v===this._version)this.setData({busy:false})}
    },
    async grant(){
      if(!admin||!this.current()||this.data.busy)return
      if(!this._frozen){if(!this.data.preview||!this.data.preview.previewHash)return;this._frozen={...this.draft(),previewHash:this.data.preview.previewHash}}
      const v=this._version;this.setData({busy:true})
      try{if(!this.data.uncertain){const c=await this.modal({title:'确认发放奖励',content:`${this.data.preview.template.couponName}\n${this.data.preview.recipientCount}名家长，共${this.data.preview.couponCount}张券。\n同一家长跨推荐记录的奖励分别计数。`});if(!c.confirm){if(this.current()&&v===this._version)this._frozen=null;return}if(!this.current()||v!==this._version)return}
        await request({url:base+'/grant',method:'POST',data:this._frozen});if(this.current()&&v===this._version){this._frozen=null;this.setData({uncertain:false,showGrant:false,preview:null});wx.showToast({title:'奖励已发放',icon:'success'});this.setData({busy:false});await this.load()}
      }catch(e){if(this.current()&&v===this._version){const uncertain=!e.applicationRejected;this.setData({uncertain,preview:uncertain?this.data.preview:null});if(!uncertain){this._frozen=null;this._batchKey=key()}wx.showToast({title:e.message||'结果未确认，请重试原批次',icon:'none'})}}finally{if(this.current()&&v===this._version)this.setData({busy:false})}
    },
    async act(e){
      if(!admin||!this.current()||this.data.busy||this.data.loading)return
      const {id,action}=e.currentTarget.dataset,v=this._version;const row=this.data.rows.find(r=>String(r[action==='correct'?'bindingId':action==='revoke'?'rewardId':'anomalyId'])===String(id));if(!row)return
      this.setData({busy:true})
      try {
        let body,path
        if(action==='correct'){const code=await this.modal({title:'输入新的推荐码',editable:true,placeholderText:'16位专属推荐码'});if(!code.confirm||!this.current()||v!==this._version)return;const why=await this.modal({title:'填写纠错原因',editable:true,placeholderText:'仅首次收款前可纠错'});if(!why.confirm)return;body={code:(code.content||'').trim(),reason:(why.content||'').trim(),version:row.version};path=`/bindings/${id}/correct`}
        else if(action==='revoke'){const why=await this.modal({title:'撤回未使用奖励券',editable:true,placeholderText:'填写撤回原因；占用或已使用不能撤回'});if(!why.confirm)return;body={reason:(why.content||'').trim()};path=`/rewards/${id}/revoke`}
        else if(action==='resolve'){const why=await this.modal({title:'填写异常核查原因',editable:true});if(!why.confirm||!this.current()||v!==this._version)return;const result=await this.modal({title:'填写处理结果',editable:true,placeholderText:'记录核查结论，不自动追款'});if(!result.confirm)return;body={reason:(why.content||'').trim(),result:(result.content||'').trim()};path=`/anomalies/${id}/resolve`}
        else return
        if(!this.current()||v!==this._version)return;await request({url:base+path,method:'POST',data:body});if(this.current()&&v===this._version){wx.showToast({title:'处理完成',icon:'success'});this.setData({busy:false});await this.load()}
      }catch(e){if(this.current()&&v===this._version)wx.showToast({title:e.message,icon:'none'})}finally{if(this.current()&&v===this._version)this.setData({busy:false})}
    },
    previous(){if(!this.data.busy&&!this.data.loading&&this.data.pageNum>1){this.setData({pageNum:this.data.pageNum-1});this.load()}},
    next(){if(!this.data.busy&&!this.data.loading&&this.data.pageNum*20<this.data.total){this.setData({pageNum:this.data.pageNum+1});this.load()}},
    coupons(){if(this.current())wx.navigateTo({url:'/pages/my-coupons/my-coupons'})}
  }
}
module.exports={page,badge,labels,remaining}
