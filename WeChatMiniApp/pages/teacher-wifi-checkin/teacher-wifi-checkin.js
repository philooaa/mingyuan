const { request } = require('../../utils/request')

Page({
  data: {
    loading: false,
    connectedSsid: '',
    connectedBssid: '',
    wifiReady: false,
    wifiError: '',
    manualSsid: '',
    inRecord: null,
    outRecord: null,
    date: ''
  },

  onShow() {
    this.refreshWifi()
    this.loadStatus()
  },

  onPullDownRefresh() {
    Promise.all([this.refreshWifi(), this.loadStatus()]).finally(() => wx.stopPullDownRefresh())
  },

  fmtTime(text) { return String(text || '').slice(11, 16) },

  async loadStatus() {
    this.setData({ loading: true })
    try {
      const res = await request({ url: '/miniapp/teacher/checkin/status' })
      const d = res.data || {}
      this.setData({
        date: d.date || '',
        inRecord: d.in ? { ...d.in, timeText: this.fmtTime(d.in.checkInTime) } : null,
        outRecord: d.out ? { ...d.out, timeText: this.fmtTime(d.out.checkInTime) } : null
      })
    } catch (error) {
      wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 读取当前连接的WiFi（需先 startWifi；开发者工具模拟器不支持，走手动输入兜底）
  refreshWifi() {
    return new Promise((resolve) => {
      const fail = (msg) => {
        this.setData({ wifiReady: false, connectedSsid: '', connectedBssid: '', wifiError: msg })
        resolve()
      }
      wx.startWifi({
        success: () => {
          wx.getConnectedWifi({
            success: (res) => {
              const wifi = res.wifi || {}
              this.setData({ wifiReady: true, connectedSsid: wifi.SSID || '', connectedBssid: wifi.BSSID || '', wifiError: '' })
              resolve()
            },
            fail: (err) => {
              const e = String((err && err.errMsg) || '')
              fail(/not connected|no connected/i.test(e) ? '已开启WiFi但未连接到网络' : '未获取到已连接的WiFi')
            }
          })
        },
        fail: () => {
          try {
            const platform = (wx.getSystemInfoSync().platform || '').toLowerCase()
            if (platform === 'devtools') { fail('开发者工具不支持读取WiFi，请在下方手动输入WiFi名称测试（真机可用自动读取）'); return }
          } catch (x) { /* ignore */ }
          fail('未开启WiFi，请打开手机WiFi开关')
        }
      })
    })
  },

  inputManualSsid(e) { this.setData({ manualSsid: e.detail.value }) },

  async checkIn(e) {
    const type = e.currentTarget.dataset.type
    if (type === 'in' && this.data.inRecord) return
    if (type === 'out' && this.data.outRecord) return
    if (type === 'out' && !this.data.inRecord) {
      wx.showToast({ title: '请先打上班卡', icon: 'none' })
      return
    }
    await this.refreshWifi()
    const ssid = this.data.connectedSsid || this.data.manualSsid.trim()
    if (!ssid) {
      wx.showToast({ title: this.data.wifiError || '请先连接校区WiFi或手动输入WiFi名称', icon: 'none' })
      return
    }
    try {
      wx.showLoading({ title: '打卡中' })
      await request({ url: '/miniapp/teacher/checkin', method: 'POST', data: { type, ssid, bssid: this.data.connectedBssid || '' } })
      wx.hideLoading()
      wx.showToast({ title: type === 'in' ? '上班打卡成功' : '下班打卡成功', icon: 'success' })
      this.loadStatus()
    } catch (error) {
      wx.hideLoading()
      wx.showToast({ title: error.message || '打卡失败', icon: 'none' })
    }
  }
})
