const { request } = require('./request')
const access = require('./access')
const base = '/miniapp/education/sign'
async function authorize(current) {
  if (!wx.getStorageSync('token')) { wx.reLaunch({ url: '/pages/register/register' }); throw new Error('请先登录') }
  const info = await request({ url: '/getInfo' })
  if (!current()) return false
  Object.assign(getApp().globalData, { roles: info.roles || [], permissions: info.permissions || [] })
  if (!access.staff()) throw new Error('仅教务或管理员可审核出勤')
  return true
}
function images(value) {
  return String(value || '').split(',').filter(Boolean).map(require('./public-file-url'))
}
function confirm(content) { return new Promise((resolve, reject) => wx.showModal({ title: '教务审核确认', content, success: r => resolve(r.confirm), fail: () => reject(new Error('无法打开确认窗口，请重试')) })) }
function isStaff() { return !!wx.getStorageSync('token') && access.staff() }
module.exports = { request, base, authorize, images, confirm, isStaff }
