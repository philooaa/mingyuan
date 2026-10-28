const { request } = require('../../utils/request')

Page({
  data: {
    loading: false,
    groups: [],
    total: 0,
    // 新增/编辑弹层
    editVisible: false,
    editForm: null,
    campusOptions: ['万达', '百家湖'],
    // 当前连接的WiFi（自动识别）
    wifiReading: false,
    wifiReady: false,
    connectedSsid: '',
    connectedBssid: '',
    wifiError: ''
  },

  onShow() { this.load() },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },

  // 读取手机当前连接的WiFi（需先 startWifi；开发者工具模拟器不支持，走手动输入兜底）
  refreshWifi() {
    return new Promise((resolve) => {
      const finish = (patch) => {
        if (this._unloaded) { resolve(); return }
        this.setData({ wifiReading: false, ...patch })
        resolve()
      }
      if (!wx.startWifi || !wx.getConnectedWifi) {
        finish({ wifiReady: false, connectedSsid: '', connectedBssid: '', wifiError: '当前微信版本不支持读取WiFi，请手动输入' })
        return
      }
      this.setData({ wifiReading: true })
      wx.startWifi({
        success: () => {
          wx.getConnectedWifi({
            success: (res) => {
              const wifi = res.wifi || {}
              // 部分安卓机型返回的 SSID 带双引号，需要去掉
              const ssid = String(wifi.SSID || '').replace(/^"|"$/g, '').trim()
              const bssid = String(wifi.BSSID || '').trim()
              finish({
                wifiReady: !!ssid,
                connectedSsid: ssid,
                connectedBssid: bssid,
                wifiError: ssid ? '' : '已连接WiFi但系统未返回名称，请手动输入'
              })
            },
            fail: (err) => {
              const msg = String((err && err.errMsg) || '')
              let hint = '未读取到当前WiFi，请手动输入'
              if (/12005|wifi not turned on/i.test(msg)) hint = '手机未打开WiFi开关，请打开后重试'
              else if (/12006|gps not turned on/i.test(msg)) hint = '请打开手机定位开关后重试'
              else if (/12001|not support/i.test(msg)) hint = '当前系统不支持读取WiFi，请手动输入'
              else if (/not connected|no connected/i.test(msg)) hint = '手机当前未连接任何WiFi'
              finish({ wifiReady: false, connectedSsid: '', connectedBssid: '', wifiError: hint })
            }
          })
        },
        fail: () => {
          let devtools = false
          try { devtools = String((wx.getSystemInfoSync().platform || '')).toLowerCase() === 'devtools' } catch (x) { devtools = false }
          finish({
            wifiReady: false,
            connectedSsid: '',
            connectedBssid: '',
            wifiError: devtools ? '开发者工具不支持读取WiFi（真机可用），请手动输入' : '手机未打开WiFi开关，请打开后重试'
          })
        }
      })
    })
  },

  // 手动点击「读取当前WiFi」：读取成功直接覆盖填入
  async readWifi() {
    if (this.data.wifiReading) return
    await this.refreshWifi()
    if (!this.data.editForm) return
    const patch = {}
    if (this.data.connectedSsid) patch['editForm.ssid'] = this.data.connectedSsid
    if (this.data.connectedBssid) patch['editForm.bssid'] = this.data.connectedBssid
    if (Object.keys(patch).length) {
      this.setData(patch)
      wx.showToast({ title: '已填入当前WiFi', icon: 'none' })
    } else {
      wx.showToast({ title: this.data.wifiError || '未读取到当前WiFi，请手动输入', icon: 'none' })
    }
  },

  async load() {
    this.setData({ loading: true })
    try {
      const res = await request({ url: '/system/wificonfig/list' })
      const items = (res.data || []).map((w) => ({
        wifiId: w.wifiId,
        campusName: w.campusName || '未分配',
        ssid: w.ssid || '—',
        bssid: w.bssid || '',
        status: w.status,
        remark: w.remark || ''
      }))
      const byCampus = {}
      items.forEach((w) => {
        (byCampus[w.campusName] || (byCampus[w.campusName] = [])).push(w)
      })
      const order = ['万达', '百家湖']
      const keys = Object.keys(byCampus).sort((a, b) => {
        const ia = order.indexOf(a); const ib = order.indexOf(b)
        return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
      })
      const groups = keys.map((k) => ({
        campus: k,
        wifis: byCampus[k].sort((a, b) => a.ssid.localeCompare(b.ssid, 'zh'))
      }))
      this.setData({ groups, total: items.length, loading: false })
    } catch (e) {
      this.setData({ loading: false, groups: [] })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onUnload() { this._unloaded = true },

  async openAdd() {
    this.setData({
      editVisible: true,
      editForm: { wifiId: null, ssid: '', bssid: '', campusIndex: 0, remark: '' }
    })
    // 新增时自动识别并填入手机当前连接的WiFi，识别失败则保留手动输入
    await this.refreshWifi()
    if (this._unloaded || !this.data.editVisible || !this.data.editForm) return
    const f = this.data.editForm
    const patch = {}
    if (!f.ssid && this.data.connectedSsid) patch['editForm.ssid'] = this.data.connectedSsid
    if (!f.bssid && this.data.connectedBssid) patch['editForm.bssid'] = this.data.connectedBssid
    if (Object.keys(patch).length) this.setData(patch)
  },
  openEdit(e) {
    const id = e.currentTarget.dataset.id
    let wifi = null
    this.data.groups.forEach((g) => { g.wifis.forEach((w) => { if (w.wifiId === id) wifi = w }) })
    if (!wifi) return
    const idx = this.data.campusOptions.indexOf(wifi.campusName)
    this.setData({
      editVisible: true,
      editForm: {
        wifiId: wifi.wifiId,
        ssid: wifi.ssid === '—' ? '' : wifi.ssid,
        bssid: wifi.bssid,
        campusIndex: idx < 0 ? 0 : idx,
        remark: wifi.remark
      }
    })
  },
  closeEdit() { this.setData({ editVisible: false, editForm: null }) },
  onField(e) { this.setData({ [`editForm.${e.currentTarget.dataset.field}`]: e.detail.value }) },
  onCampus(e) { this.setData({ 'editForm.campusIndex': Number(e.detail.value) }) },

  async save() {
    const f = this.data.editForm
    if (!f) return
    if (!f.ssid.trim()) { wx.showToast({ title: '请填写WiFi名称', icon: 'none' }); return }
    const payload = {
      campusName: this.data.campusOptions[f.campusIndex],
      ssid: f.ssid.trim(),
      bssid: f.bssid.trim() || null,
      status: '0'
    }
    if (f.remark.trim()) payload.remark = f.remark.trim()
    try {
      wx.showLoading({ title: '保存中' })
      if (f.wifiId) {
        payload.wifiId = f.wifiId
        await request({ url: '/system/wificonfig', method: 'PUT', data: payload })
      } else {
        await request({ url: '/system/wificonfig', method: 'POST', data: payload })
      }
      wx.hideLoading()
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ editVisible: false, editForm: null })
      this.load()
    } catch (e) {
      wx.hideLoading()
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    }
  },

  remove(e) {
    const id = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name
    wx.showModal({
      title: '删除WiFi',
      content: `确定删除「${name}」吗？`,
      confirmColor: '#d94c4c',
      success: async (res) => {
        if (!res.confirm) return
        try {
          wx.showLoading({ title: '删除中' })
          await request({ url: `/system/wificonfig/${id}`, method: 'DELETE' })
          wx.hideLoading()
          wx.showToast({ title: '已删除', icon: 'success' })
          this.load()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '删除失败', icon: 'none' })
        }
      }
    })
  },

  noop() {}
})
