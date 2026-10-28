App({
  globalData: {
    // 体验版与管理网页共用测试后端；不要使用手机自身的 127.0.0.1。
    baseUrl: 'https://test-admin.mingyuantostars.online/prod-api',
    token: '',
    userInfo: null,
    roles: []
  },

  onLaunch() {
    this.globalData.token = wx.getStorageSync('token') || ''
    this.globalData.userInfo = wx.getStorageSync('userInfo') || null
    this.globalData.roles = wx.getStorageSync('roles') || []
  }
})
