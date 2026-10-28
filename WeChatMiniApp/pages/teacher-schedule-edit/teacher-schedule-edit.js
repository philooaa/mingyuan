Page({
  data: { message: '课程由教务和管理员统一安排，教师不再发布或修改排课。' },
  onLoad(options={}) {
    const roles=wx.getStorageSync('roles') || []
    if(!wx.getStorageSync('token') || !roles.some(r=>['admin','business_admin','education'].includes(r)))return
    const id=String(options.id || '')
    if(id && !/^[1-9]\d*$/.test(id))return
    wx.redirectTo({url:'/pages/admin-schedule/admin-schedule'+(id?'?id='+id:'')})
  },
  current() { return false },
  onUnload() {}
})
