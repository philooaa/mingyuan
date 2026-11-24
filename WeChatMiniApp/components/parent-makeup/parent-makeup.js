const {request}=require('../../utils/request')
const childContext=require('../../utils/child-context')
Component({
  properties:{accountId:{type:String,value:'',observer(){if(this._active)this.refresh()}},mode:{type:String,value:'attendance',observer(){if(this._active)this.refresh()}}},
  data:{rows:[],loading:false,error:'',pendingCount:0},
  lifetimes:{attached(){this._active=true;this.refresh()},detached(){this._active=false;this._version=(this._version||0)+1;this.setData({rows:[],error:'',pendingCount:0})}},
  methods:{
    toggleHistory(e){
      if(!this._active||!this._ticket||!childContext.current(this._ticket)||String(this.data.accountId)!==this._ticket.id)return
      const key=String(e.currentTarget.dataset.key)
      this.setData({rows:this.data.rows.map(row=>String(row.rowKey)===key?{...row,historyExpanded:!row.historyExpanded}:row)})
    },
    async refresh(){
      const version=this._version=(this._version||0)+1,ticket=childContext.snapshot(),accountId=String(this.data.accountId||'')
      this.setData({rows:[],error:'',pendingCount:0,loading:false})
      if(!this._active||!accountId||accountId!==ticket.id||!ticket.token)return
      const current=()=>this._active&&version===this._version&&childContext.current(ticket)&&String(this.data.accountId)===accountId
      this.setData({loading:true})
      try{
        const r=await request({url:'/miniapp/parent/attendance/makeup',data:{accountId}})
        if(!current())return
        const data=r.data||{},rows=(data.rows||[]).filter(row=>this.data.mode!=='timetable'||row.state==='scheduled'&&row.makeup&&!row.makeup.cancelled).map(row=>({
          ...row,historyExpanded:false,
          statusLabel:row.stateText==='补课出勤确认中'?'确认中':row.state==='failed'||String(row.stateText||'').includes('重新安排')?'待重排':({pending:'待安排',scheduled:'待补课',completed:'已补课',conflict:'核对中'})[row.state]||'待确认',
          statusHint:row.stateText==='补课出勤确认中'?'老师已登记，正在确认补课出勤':row.state==='failed'?'这次补课尚未完成，等待老师重新安排':row.state==='completed'?'补课出勤已确认，本次请假已完成补课':row.state==='conflict'?'出勤记录正在核对，请等待老师确认':row.state==='pending'?'老师安排后，可在这里查看补课时间与地点':''
        }))
        this._ticket=ticket
        this.setData({rows,pendingCount:data.pendingCount||0})
      }catch(e){if(current())this.setData({error:e.message||'补课记录加载失败'})}
      finally{if(current())this.setData({loading:false})}
    }
  }
})
