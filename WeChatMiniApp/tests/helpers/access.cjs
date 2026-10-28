const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
module.exports = wx => {
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../utils/access.js'), 'utf8'), {
    module, wx, getApp: () => ({ globalData: { roles: wx.getStorageSync('roles') || [], userInfo: wx.getStorageSync('userInfo') || {} } })
  })
  return module.exports
}
