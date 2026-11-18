const { request } = require('../../utils/request')
const access = require('../../utils/access')
const childContext = require('../../utils/child-context')
const homeworkShare = require('../../utils/homework-share')
const loginUrl = '/pages/register/register?next=homework'

Page({
  data: { loading: true, error: '', children: [], noChildren: false, wrongRole: false },
  onShow() { this._visible = true; this.load() },
  onHide() { this._visible = false; this._version = (this._version || 0) + 1 },
  onUnload() { this.onHide() },
  onShareAppMessage() { return homeworkShare.card() },
  current(version, token) { return this._visible && version === this._version && token === wx.getStorageSync('token') },
  async load() {
    const version = this._version = (this._version || 0) + 1
    const token = this._token = wx.getStorageSync('token')
    this.setData({ loading: true, error: '', children: [], noChildren: false, wrongRole: false })
    if (!token) { wx.redirectTo({ url: loginUrl }); return }
    try {
      const info = await request({ url: '/getInfo', loginRedirect: loginUrl })
      if (!this.current(version, token)) return
      if (!(info.roles || []).includes('parent') || !info.user) {
        this.setData({ wrongRole: true }); return
      }
      // Resolve the recipient's identity on the server, including dual-role parents.
      wx.setStorageSync('roles', info.roles)
      wx.setStorageSync('userInfo', info.user)
      Object.assign(getApp().globalData, { userInfo: info.user, roles: info.roles, permissions: info.permissions || [] })
      access.select('parent')
      const res = await request({ url: '/miniapp/parent/account', loginRedirect: loginUrl })
      if (!this.current(version, token)) return
      const children = ((res.data || {}).children || []).map(c => ({ accountId: String(c.accountId), studentName: c.studentName, gradeName: c.gradeName }))
      this.setData({ children, noChildren: !children.length })
      if (children.length === 1) this.openChild(children[0])
    } catch (e) {
      if (this.current(version, token)) this.setData({ error: e.message || '作业入口加载失败，请重试' })
    } finally {
      if (this.current(version, token)) this.setData({ loading: false })
    }
  },
  chooseChild(e) {
    if (!this.current(this._version, this._token)) return
    const child = this.data.children.find(c => c.accountId === String(e.currentTarget.dataset.id))
    if (child) this.openChild(child)
  },
  openChild(child) {
    if (!this.current(this._version, this._token) || !childContext.select(child)) return
    wx.redirectTo({ url: childContext.url('/pages/homework/homework'), fail: () => this.setData({ error: '页面打开失败，请重试' }) })
  },
  addChild() { wx.navigateTo({ url: '/pages/parent-account/parent-account?create=1' }) },
  goHome() { wx.reLaunch({ url: '/pages/index/index' }) }
})
