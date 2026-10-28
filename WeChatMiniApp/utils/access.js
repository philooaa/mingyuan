const labels = { parent: '家长', teacher: '教师', education: '教务', admin: '管理员' }
function roles() { return getApp().globalData.roles || wx.getStorageSync('roles') || [] }
function has(role, assigned = roles()) {
  return ['parent', 'teacher', 'education', 'admin'].includes(role) && assigned.includes(role)
}
function staff() { return has('admin') || has('education') }
function can(permission) {
  return (getApp().globalData.permissions || []).some(p => p === '*:*:*' || p === permission)
}
function options(assigned = roles()) {
  return ['admin', 'education', 'teacher', 'parent'].filter(r => has(r, assigned)).map(key => ({ key, name: labels[key] }))
}
function key() { const user = getApp().globalData.userInfo || wx.getStorageSync('userInfo') || {}; return `miniappWorkspace:${user.userId || ''}` }
function selected(assigned = roles()) {
  const saved = wx.getStorageSync(key())
  return has(saved, assigned) ? saved : (options(assigned)[0] || {}).key || ''
}
function select(role) { if (!has(role)) return false; wx.setStorageSync(key(), role); return true }
function allowed(role) { return role === 'staff' ? staff() : has(role) }
const routePermissions = {
  '/pages/admin-enrollment/admin-enrollment': 'system:enrollment:list',
  '/pages/admin-students/admin-students': 'system:enrollment:list',
  '/pages/admin-classrooms/admin-classrooms': 'system:classroom:list',
  '/pages/admin-transactions/admin-transactions': 'edu:transaction:manage',
  '/pages/admin-tuition/admin-tuition': 'system:tuition:list',
  '/pages/admin-tuition-refunds/admin-tuition-refunds': 'system:tuitionRefund:list',
  '/pages/admin-course-price/admin-course-price': 'system:coursePrice:list',
  '/pages/admin-referral/admin-referral': 'system:coupon:list',
  '/pages/admin-teachers/admin-teachers': 'system:salaryConfig:list',
  '/pages/material-upload/material-upload': 'system:material:list'
}
function actions(items, role) {
  return items.filter(item => item.route && (!['admin', 'education'].includes(role) || !routePermissions[item.route] || can(routePermissions[item.route])))
}
function developmentTool() {
  try {
    if (wx.getDeviceInfo) return wx.getDeviceInfo().platform === 'devtools'
    return wx.getSystemInfoSync().platform === 'devtools'
  } catch (_) { return false }
}
function developmentBuild() {
  if (developmentTool()) return true
  try { return wx.getAccountInfoSync().miniProgram.envVersion === 'develop' } catch (_) { return false }
}
module.exports = { roles, has, staff, can, options, selected, select, allowed, actions, developmentTool, developmentBuild }
