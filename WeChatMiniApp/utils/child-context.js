// Only the selected ID is persisted. The server remains the authority for ownership.
let revision = 0
function owner() {
  const roles = wx.getStorageSync('roles') || []
  if (!roles.includes('parent')) return ''
  const app = typeof getApp === 'function' ? getApp() : null
  const user = app && app.globalData && app.globalData.userInfo || wx.getStorageSync('userInfo') || {}
  const id = user.userId
  if (roles.some(r => ['admin','teacher','education'].includes(r)) && wx.getStorageSync('miniappWorkspace:'+id) !== 'parent') return ''
  return id == null ? '' : `${app && app.globalData && app.globalData.baseUrl || ''}:${id}`
}
function id() { const key=owner();const value=key && wx.getStorageSync('parentChild:'+key);return value && /^[1-9]\d*$/.test(String(value.accountId)) ? String(value.accountId) : '' }
function select(row) {
  const key=owner(),next=row && row.accountId!=null?String(row.accountId):''
  if(!key || (next && !/^[1-9]\d*$/.test(next)))return false
  if(id()!==next)revision++
  wx.setStorageSync('parentChild:'+key,{accountId:next});return true
}
function reconcile(children,preferred) {
  const next=(children||[]).find(c=>String(c.accountId)===String(preferred||id()))||(children||[])[0]||null
  select(next);return next
}
function snapshot(){return {owner:owner(),token:wx.getStorageSync('token'),id:id(),revision}}
function current(ticket){return !ticket || ticket.owner===owner() && ticket.token===wx.getStorageSync('token') && ticket.id===id() && ticket.revision===revision}
function sameParent(ticket){return !ticket || ticket.owner===owner() && ticket.token===wx.getStorageSync('token')}
function url(path){const value=id();return value?path+(path.includes('?')?'&':'?')+'accountId='+encodeURIComponent(value):path}
function isChildRequest(options){
  const path=options.url.split('?')[0]
  return /^\/miniapp\/parent\/(?:enrollments|timetable|attendance(?:\/details)?|enroll|enrollment\/[^/]+(?:\/history|\/cancel-request)?|homework(?:\/.*)?|leave(?:\/.*)?|tuition(?:\/.*)?|lesson-packages\/(?:quote|orders(?:\/[^/]+\/cancel)?))$/.test(path)
}
function prepare(options){
  if(!owner() || options.parentScope || !isChildRequest(options))return {options,ticket:null}
  const ticket=snapshot(),data={...(options.data||{})},query=options.url.match(/[?&]accountId=([^&]+)/),specified=query?decodeURIComponent(query[1]):data.accountId
  if(!ticket.id)throw new Error('请先选择或添加孩子信息')
  if(specified!=null && String(specified)!==ticket.id)throw new Error('当前孩子已切换，请重新进入')
  const scopedUrl=String(options.method||'GET').toUpperCase()==='DELETE'&&!query?options.url+(options.url.includes('?')?'&':'?')+'accountId='+encodeURIComponent(ticket.id):options.url
  return {options:{...options,url:scopedUrl,data:{...data,accountId:ticket.id}},ticket}
}
// Reopen a stale list in the new scope; old record details return to the new child.
function page(definition,settings={}) {
  const result={...definition}
  const load=definition.onLoad,show=definition.onShow
  result.onLoad=function(options={}){
    this._parentScope=options.unlinked==='1';this._childManaged=!!owner()&&!this._parentScope
    if(this._childManaged || this._parentScope&&owner()){
      this._childTicket=snapshot();const original=this.setData.bind(this)
      if(this._parentScope)this._parentScopeTicket=this._childTicket
      this._childSetData=original;this.setData=updates=>{if(this._childClearing || (this._parentScope?sameParent(this._parentScopeTicket):current(this._childTicket)))original(updates)}
    }
    if(this._childManaged){
      if(!this._childTicket.id){this._childBlocked=true;this._childClearing=true;this._childSetData({error:'请先选择或添加孩子信息',loading:false});this._childClearing=false;wx.redirectTo({url:'/pages/parent-account/parent-account?create=1'});return}
      options={...options,accountId:this._childTicket.id}
    }
    return load && load.call(this,options)
  }
  result.onShow=function(){
    if(this._childBlocked)return
    if(this._childManaged&&!current(this._childTicket) || !sameParent(this._parentScopeTicket)){
      this._childClearing=true;this.setData(JSON.parse(JSON.stringify(definition.data||{})));this._childClearing=false
      const target=id()?settings.detail?'/pages/parent-child-detail/parent-child-detail':settings.route:'/pages/parent-account/parent-account'
      wx.redirectTo({url:url(target)});return
    }
    return show && show.call(this)
  }
  for(const [key,value] of Object.entries(definition))if(typeof value==='function'&&!['onLoad','onShow','onHide','onUnload'].includes(key))result[key]=function(...args){
    if(this._childBlocked || this._childManaged&&!current(this._childTicket) || !sameParent(this._parentScopeTicket))return key==='current'?false:undefined
    return value.apply(this,args)
  }
  return result
}
module.exports={owner,id,select,reconcile,snapshot,current,url,prepare,page}
