App({
  globalData: {
    // 手机预览和体验版仍走服务器；开发者工具在启动时切换为本机后端。
    baseUrl: 'https://test-admin.mingyuantostars.online/prod-api',
    token: '',
    userInfo: null,
    roles: []
  },

  onLaunch() {
    const platform = wx.getDeviceInfo ? wx.getDeviceInfo().platform : ''
    if (platform === 'devtools') this.globalData.baseUrl = 'http://127.0.0.1:8080'
    const previousBackend = wx.getStorageSync('backendBaseUrl')
    if (previousBackend !== this.globalData.baseUrl && (previousBackend || platform === 'devtools')) {
      // 不将服务器会话带入本地库，或将本地会话带回服务器。
      for (const key of ['token', 'userInfo', 'roles']) wx.removeStorageSync(key)
    }
    wx.setStorageSync('backendBaseUrl', this.globalData.baseUrl)
    this.globalData.token = wx.getStorageSync('token') || ''
    this.globalData.userInfo = wx.getStorageSync('userInfo') || null
    this.globalData.roles = wx.getStorageSync('roles') || []
  }
})
