const { request } = require('./request')
const base = '/miniapp/staff/attendance'
function todoRoute(item, role) {
 const page = role === 'teacher' ? 'sign-in-edit' : 'sign-review-detail'
 return `/pages/${page}/${page}?scheduleId=${encodeURIComponent(item.scheduleId)}&classDate=${encodeURIComponent(item.classDate)}`
}
function range(form) {
 const start = Number(form.startLesson), end = form.endLesson === '' || form.endLesson == null ? null : Number(form.endLesson)
 if (!form.scheduleId || !form.studentId || !Number.isInteger(start) || start < 1 || (end !== null && (!Number.isInteger(end) || end < start))) throw new Error('请选择课程、学生和有效的起止讲次')
 if (!['ENROLLED','OFFLINE','TRIAL','TRANSFER'].includes(form.source) || !['ENROLLMENT','UNPAID','HISTORICAL'].includes(form.paymentStatus)) throw new Error('请选择名单来源与收费状态')
 if (form.source === 'ENROLLED' && !form.enrollmentId) throw new Error('正常报名须填写关联报名编号')
 if (!String(form.reason || '').trim()) throw new Error('请填写调整原因')
 return {...form,startLesson:start,endLesson:end}
}
function uploadImage(filePath) {
 const token=wx.getStorageSync('token')
 return new Promise((resolve,reject)=>wx.uploadFile({url:getApp().globalData.baseUrl+'/common/upload',filePath,name:'file',timeout:30000,header:{Authorization:'Bearer '+token},success:r=>{try{const d=JSON.parse(r.data);if(d.code!==200)throw new Error(d.msg||'上传失败');const path=d.fileName||d.filePath||d.url;if(!path)throw new Error('上传未返回图片地址');if(wx.getStorageSync('token')!==token)throw new Error('登录已切换，请重新上传');resolve(path)}catch(e){reject(e)}},fail:error=>reject(new Error(/timeout/i.test(error.errMsg||'')?'上传超时，请检查网络后重试':'上传失败，请检查网络后重试'))}))
}
module.exports={request,base,todoRoute,range,uploadImage}
