const { request } = require('../../utils/request')
const devSession = require('../../utils/dev-session')
const access = require('../../utils/access')

const devRoles = [
  { key: 'parent', name: '家长', className: 'active' },
  { key: 'teacher', name: '教师', className: '' },
  { key: 'admin', name: '管理员', className: '' }
]

Page({
  data: {
    submitting: false,
    localPreviewEnabled: false,
    autoPreview: true,
    debugEnabled: false,
    backendLabel: '',
    phone: '', verificationCode: '', codeSending: false, cooldown: 0,
    configLoading: true, phoneLoginEnabled: false, developmentCodeMode: false, developmentCode: '', codePhone: '',
    developmentLoginEnabled: false, showDevelopment: false,
    username: '', password: '', captchaEnabled: false, captchaImage: '', code: '', uuid: '', loginError: '',
    devRole: 'parent',
    teachers: [],
    teacherOptions: [{ label: '请选择教师' }],
    teachersLoading: false,
    teacherError: '',
    teacherIndex: 0,
    teacherLabel: '',
    devTeacherId: '',
    parents: [],
    parentOptions: [{label:'上次使用的／默认家长'}],
    parentIndex: 0,
    parentLabel: '',
    devParentId: '',
    parentsLoading: false,
    parentError: '',
    devRoles
  },

  async onLoad(options = {}) {
    this._unloaded = false
    const pickRole = options.pickRole === '1'
    if (pickRole) this.setData({ autoPreview: false })
    this.applyBackendOverride()
    if (!pickRole && wx.getStorageSync('token') && options.selectTeacher !== '1' && options.selectParent !== '1') {
      wx.reLaunch({ url: '/pages/index/index' })
      return
    }
    // 环境判定不可靠时（如基础库缺 getDeviceInfo），主动探测本机后端并切过去。
    await this.resolveBackend()
    if (this._unloaded) return
    this.loadPhoneConfig()
    await this.loadLocalPreview()
    if (pickRole && !this._unloaded && this.data.localPreviewEnabled) this.choosePreviewRole()
  },

  // 真机调试时由长按 logo 写入的本机后端地址，需在页面加载时套用。
  applyBackendOverride() {
    if (typeof getApp !== 'function') return
    const debugEnabled = access.developmentBuild()
    const override = debugEnabled && wx.getStorageSync('backendOverride')
    if (override) getApp().globalData.baseUrl = String(override).replace(/\/+$/, '')
    this.setData({ debugEnabled, backendLabel: debugEnabled ? getApp().globalData.baseUrl : '' })
  },

  async probeBackend(url) {
    if (typeof getApp !== 'function') return false
    const app = getApp(), previous = app.globalData.baseUrl
    app.globalData.baseUrl = url
    try {
      const res = await request({ url: '/miniapp/dev/session', timeout: 3000 })
      if (res && res.data && res.data.version) return true
    } catch (_) { /* 该地址没有开发会话，继续尝试下一个。 */ }
    app.globalData.baseUrl = previous
    return false
  },

  async resolveBackend() {
    if (typeof getApp !== 'function') return
    if (!access.developmentBuild()) return
    // 仅在开发者工具或显式配置了本机后端时才探测，真机保持线上地址。
    if (!access.developmentTool() && !wx.getStorageSync('backendOverride')) return
    const app = getApp()
    const candidates = []
    const override = wx.getStorageSync('backendOverride')
    if (override) candidates.push(override)
    candidates.push(app.globalData.baseUrl, 'http://127.0.0.1:8080')
    const seen = new Set()
    for (const raw of candidates) {
      const url = String(raw || '').replace(/\/+$/, '')
      if (!url || seen.has(url)) continue
      seen.add(url)
      if (await this.probeBackend(url)) {
        app.globalData.baseUrl = url
        this.setData({ backendLabel: url })
        return
      }
    }
    this.setData({ backendLabel: app.globalData.baseUrl })
  },

  isLocalPreviewEnvironment() {
    if (typeof getApp !== 'function') return false
    if (!access.developmentBuild()) return false
    if (!access.developmentTool() && !wx.getStorageSync('backendOverride')) return false
    const base = getApp().globalData.baseUrl || ''
    return /^http:\/\/(127\.0\.0\.1|localhost|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(base)
  },

  // 长按 logo：切换后端地址（仅用于本地/内网调试）。
  async openBackendSetting() {
    if (!access.developmentBuild()) return
    const res = await new Promise(resolve => wx.showModal({
      title: '开发设置 · 后端地址',
      editable: true,
      placeholderText: 'http://10.201.27.23:8080',
      content: getApp().globalData.baseUrl,
      confirmText: '保存',
      success: resolve,
      fail: () => resolve({})
    }))
    if (!res.confirm) return
    const url = String(res.content || '').trim().replace(/\/+$/, '')
    if (!url) wx.removeStorageSync('backendOverride')
    else wx.setStorageSync('backendOverride', url)
    for (const key of ['token', 'userInfo', 'roles', 'devSessionVersion']) wx.removeStorageSync(key)
    wx.showToast({ title: '已保存，正在重新加载', icon: 'none' })
    setTimeout(() => wx.reLaunch({ url: '/pages/register/register' }), 500)
  },
  async loadLocalPreview() {
    if (!this.isLocalPreviewEnvironment()) return
    try {
      const res = await request({ url: '/miniapp/dev/session', timeout: 10000 })
      if (!this._unloaded) {
        this.setData({ localPreviewEnabled: !!(res.data && res.data.version) })
        if (this.data.localPreviewEnabled && this.data.autoPreview !== false) await this.handleLocalPreview()
      }
    } catch (_) { /* 本地后端未启用预览时仍使用正常授权。 */ }
  },
  async handleLocalPreview(role, identityId) {
    if (this._unloaded || this.data.submitting || !this.data.localPreviewEnabled || !this.isLocalPreviewEnvironment()) return
    const devRole = typeof role === 'string' ? role : 'admin'
    const previousToken = wx.getStorageSync('token')
    this.setData({ submitting: true, loginError: '' })
    try {
      const payload = { loginCode: 'mock', phoneCode: 'mock', devRole }
      if (devRole === 'parent' && identityId) payload.devParentId = String(identityId)
      if (devRole === 'teacher' && identityId) payload.devTeacherId = String(identityId)
      const login = await request({ url: '/miniapp/login', method: 'POST', data: payload })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      const info = await request({ url: '/getInfo', header: { Authorization: 'Bearer ' + login.token } })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      this.activateAccount(login.token, info)
      // 预览教务时强制切到教务工作台，避免多角色账号默认落回管理员视角。
      if (devRole === 'education') access.select('education')
      wx.reLaunch({ url: '/pages/index/index' })
    } catch (e) { if (!this._unloaded) this.setData({ loginError: e.message || '本地预览登录失败' }) }
    finally { if (!this._unloaded) this.setData({ submitting: false }) }
  },

  // 预览时可切换身份，便于查看家长端 / 教务端 / 教师端功能。
  choosePreviewRole() {
    if (this.data.submitting || !this.data.localPreviewEnabled) return
    wx.showActionSheet({
      itemList: ['管理员', '教务', '家长', '教师'],
      success: res => {
        const role = ['admin', 'education', 'parent', 'teacher'][res.tapIndex]
        if (!role) return
        if (role === 'parent' || role === 'teacher') this.previewIdentity(role)
        else this.handleLocalPreview(role)
      }
    })
  },

  async previewIdentity(role) {
    const url = role === 'parent' ? '/miniapp/dev/parents' : '/miniapp/dev/teachers'
    try {
      const res = await request({ url, timeout: 15000 })
      const list = res.data || []
      if (!list.length) return this.handleLocalPreview(role)
      const items = list.slice(0, 6).map(item => String(item.label || item.name || item.userId))
      wx.showActionSheet({
        itemList: items,
        success: pick => this.handleLocalPreview(role, list[pick.tapIndex].userId)
      })
    } catch (e) { wx.showToast({ title: e.message || '身份列表加载失败', icon: 'none' }) }
  },

  async loadPhoneConfig() {
    this.setData({ configLoading: true })
    try {
      const res = await request({ url: '/miniapp/auth/config', timeout: 10000 })
      if (!this._unloaded) this.setData({ phoneLoginEnabled: res.data.phoneLoginEnabled === true, developmentCodeMode: res.data.developmentCodeMode === true, loginError: '' })
    } catch (e) { if (!this._unloaded) this.setData({ phoneLoginEnabled: false, loginError: `登录服务未就绪（${getApp().globalData.baseUrl}），请检查后端是否已启动` }) }
    finally { if (!this._unloaded) this.setData({ configLoading: false }) }
  },
  phoneInput(e) {
    if (this.data.submitting || this.data.codeSending) return
    const phone = String(e.detail.value || '').trim()
    if (phone !== this.data.phone) { clearInterval(this._codeTimer); this.setData({ phone, verificationCode: '', developmentCode: '', codePhone: '', cooldown: 0 }) }
  },
  verificationInput(e) { if (!this.data.submitting) this.setData({ verificationCode: e.detail.value }) },
  async sendCode() {
    if (this._unloaded || this.data.codeSending || this.data.submitting || this.data.cooldown || !this.data.phoneLoginEnabled) return
    const phone = this.data.phone.trim()
    if (!/^1[3-9][0-9]{9}$/.test(phone)) { this.setData({ loginError: '请输入有效的11位手机号' }); return }
    this.setData({ codeSending: true, loginError: '', developmentCode: '' })
    try {
      const res = await request({ url: '/miniapp/auth/code', method: 'POST', data: { phone } })
      if (this._unloaded || this.data.phone !== phone) return
      this.setData({ codePhone: phone, cooldown: Number(res.data.resendAfter) || 60, developmentCode: this.data.developmentCodeMode ? res.data.developmentCode || '' : '' })
      clearInterval(this._codeTimer)
      this._codeTimer = setInterval(() => {
        if (this._unloaded) { clearInterval(this._codeTimer); return }
        const cooldown = Math.max(0, this.data.cooldown - 1); this.setData({ cooldown })
        if (!cooldown) clearInterval(this._codeTimer)
      }, 1000)
    } catch (e) { if (!this._unloaded && this.data.phone === phone) this.setData({ loginError: e.message || '验证码获取失败，请重试' }) }
    finally { if (!this._unloaded) this.setData({ codeSending: false }) }
  },
  async handlePhoneCodeLogin() {
    if (this._unloaded || this.data.submitting || this.data.codeSending || !this.data.phoneLoginEnabled) return
    const phone = this.data.phone.trim(), code = this.data.verificationCode.trim(), previousToken = wx.getStorageSync('token')
    if (!/^1[3-9][0-9]{9}$/.test(phone) || !/^[0-9]{6}$/.test(code)) { this.setData({ loginError: '请填写手机号和6位验证码' }); return }
    this.setData({ submitting: true, loginError: '' })
    try {
      const login = await request({ url: '/miniapp/auth/login', method: 'POST', data: { phone, code } })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      const info = await request({ url: '/getInfo', header: { Authorization: 'Bearer ' + login.token } })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      this.activateAccount(login.token, info)
      this.setData({ verificationCode: '', developmentCode: '' }); wx.reLaunch({ url: '/pages/index/index' })
    } catch (e) { if (!this._unloaded) this.setData({ loginError: e.message || '登录失败，请重新获取验证码' }) }
    finally { if (!this._unloaded) this.setData({ submitting: false }) }
  },

  async loadLoginConfig(role, options = {}) {
    if (!access.developmentTool()) return
    try {
      const res = await request({ url: '/miniapp/dev/session', timeout: 10000 })
      if (!this._unloaded && res.data && res.data.version) {
        this.setData({ developmentLoginEnabled: true, showDevelopment: options.selectTeacher === '1' || options.selectParent === '1' })
        this.setDevRole(role)
      }
    } catch (_) { /* Normal deployments do not expose development login. */ }
  },
  toggleDevelopment() { if (!this.data.submitting && this.data.developmentLoginEnabled) this.setData({ showDevelopment: !this.data.showDevelopment }) },
  accountInput(e) { const key = e.currentTarget.dataset.field; if (!this.data.submitting && ['username', 'password', 'code'].includes(key)) this.setData({ [key]: e.detail.value }) },
  async loadCaptcha() {
    try { const res = await request({ url: '/captchaImage' }); if (!this._unloaded) this.setData({ captchaEnabled: res.captchaEnabled !== false, captchaImage: res.img ? 'data:image/gif;base64,' + res.img : '', uuid: res.uuid || '', code: '' }) }
    catch (_) { if (!this._unloaded) this.setData({ loginError: '登录服务连接失败，请检查后端后重试' }) }
  },
  activateAccount(token, info) {
    if (!info.user || !access.options(info.roles || []).length) throw new Error('账号尚未分配小程序角色，请联系管理员')
    wx.setStorageSync('token', token); wx.setStorageSync('roles', info.roles || []); wx.setStorageSync('userInfo', info.user)
    wx.removeStorageSync('devSessionVersion')
    Object.assign(getApp().globalData, { token, roles: info.roles || [], permissions: info.permissions || [], userInfo: info.user })
  },
  async handleAccountLogin() {
    if (this._unloaded || this.data.submitting) return
    if (!this.data.username.trim() || !this.data.password) { this.setData({ loginError: '请填写账号和密码' }); return }
    this.setData({ submitting: true, loginError: '' })
    const previousToken = wx.getStorageSync('token')
    try {
      const login = await request({ url: '/login', method: 'POST', data: { username: this.data.username.trim(), password: this.data.password, code: this.data.code, uuid: this.data.uuid } })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      const info = await request({ url: '/getInfo', header: { Authorization: 'Bearer ' + login.token } })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      this.activateAccount(login.token, info)
      this.setData({ password: '', code: '' }); wx.reLaunch({ url: '/pages/index/index' })
    } catch (e) { if (!this._unloaded) { this.setData({ loginError: e.message || '登录失败' }); this.loadCaptcha() } }
    finally { if (!this._unloaded) this.setData({ submitting: false }) }
  },

  onUnload() {
    this._unloaded = true
    clearInterval(this._codeTimer)
  },

  chooseDevRole(e) {
    if (this.data.submitting) return
    this.setDevRole(e.currentTarget.dataset.role)
  },

  setDevRole(role) {
    const devRole = ['parent', 'teacher', 'admin'].includes(role) ? role : 'parent'
    this.setData({
      devRole,
      devRoles: devRoles.map((item) => ({
        ...item,
        className: item.key === devRole ? 'active' : ''
      }))
    })
    wx.setStorageSync('devRole', devRole)
    if (devRole === 'teacher') this.loadTeachers()
    if (devRole === 'parent') this.loadParents()
  },

  async loadParents() {
    if(this.data.parentsLoading || this.data.submitting)return
    this.setData({parentsLoading:true,parentError:''})
    try {
      const result=await request({url:'/miniapp/dev/parents',timeout:15000})
      if(this._unloaded)return
      const parents=(result.data||[]).map(p=>({...p,label:`${p.name}（ID ${p.userId}）`}))
      const selected=parents.find(p=>String(p.userId)===this.data.devParentId)
      this.setData({parents,parentOptions:[{label:'上次使用的／默认家长'},...parents],parentIndex:selected?parents.indexOf(selected)+1:0,parentLabel:selected?selected.label:'',devParentId:selected?String(selected.userId):'',parentError:parents.length?'':'暂无其他家长，可先使用默认家长'})
    }catch(error){if(!this._unloaded)this.setData({parents:[],parentOptions:[{label:'上次使用的／默认家长'}],parentIndex:0,parentLabel:'',devParentId:'',parentError:error.message||'家长列表加载失败'})}
    finally{if(!this._unloaded)this.setData({parentsLoading:false})}
  },

  chooseParent(e) {
    if(this.data.submitting || this.data.parentsLoading)return
    const parentIndex=Number(e.detail.value),parent=this.data.parents[parentIndex-1]
    if(parentIndex!==0 && !parent)return
    this.setData({parentIndex,parentLabel:parent?parent.label:'',devParentId:parent?String(parent.userId):''})
  },

  async loadTeachers() {
    if (this.data.teachersLoading || this.data.submitting) return
    this.setData({ teachersLoading: true, teacherError: '' })
    try {
      const result = await request({ url: '/miniapp/dev/teachers', timeout: 15000 })
      if (this._unloaded) return
      const teachers = (result.data || []).map((teacher) => ({
        ...teacher,
        label: `${teacher.name}（${teacher.userName}）`
      }))
      const selected = teachers.find((teacher) => String(teacher.userId) === this.data.devTeacherId)
      const teacherIndex = selected ? teachers.indexOf(selected) + 1 : 0
      this.setData({
        teachers,
        teacherOptions: [{ label: '请选择教师' }, ...teachers],
        teacherIndex,
        teacherLabel: selected ? selected.label : '',
        devTeacherId: selected ? String(selected.userId) : '',
        teacherError: teachers.length ? '' : '暂无可用教师，请在若依中配置独立教师账号及教师角色'
      })
    } catch (error) {
      if (this._unloaded) return
      this.setData({ teachers: [], teacherOptions: [{ label: '请选择教师' }], teacherIndex: 0, teacherLabel: '', devTeacherId: '', teacherError: error.message || '教师列表加载失败' })
    } finally {
      if (!this._unloaded) this.setData({ teachersLoading: false })
    }
  },

  chooseTeacher(e) {
    if (this.data.submitting || this.data.teachersLoading) return
    const teacherIndex = Number(e.detail.value)
    if (teacherIndex === 0) {
      this.setData({ teacherIndex: 0, teacherLabel: '', devTeacherId: '' })
      return
    }
    const teacher = this.data.teachers[teacherIndex - 1]
    if (!teacher) return
    this.setData({ teacherIndex, teacherLabel: teacher.label, devTeacherId: String(teacher.userId) })
  },

  handlePhoneLogin(e) {
    if (this.data.submitting) return
    const phoneCode = e.detail && e.detail.code
    if (!phoneCode) { this.setData({ loginError: '手机号授权未完成，请重新点击并同意授权；如提示无权限，请联系管理员核对微信配置。' }); return }
    return this.loginWithWechat(phoneCode)
  },

  openPrivacy() {
    wx.openPrivacyContract({ fail: () => wx.showToast({ title: '隐私指引暂不可用，请联系管理员配置', icon: 'none' }) })
  },

  handleDevLogin() {
    if (this.data.submitting || !this.data.developmentLoginEnabled || !this.data.showDevelopment) return
    this.loginWithWechat('mock', this.data.devRole)
  },

  async loginWithWechat(phoneCode, devRole) {
    if (this._unloaded || this.data.submitting) return
    if (phoneCode === 'mock' && !this.data.developmentLoginEnabled) return
    if (devRole === 'teacher' && (this.data.teachersLoading || !this.data.devTeacherId)) {
      wx.showToast({ title: '请先选择教师', icon: 'none' })
      return
    }
    const devTeacherId = devRole === 'teacher' ? this.data.devTeacherId : undefined
    const devParentId = devRole === 'parent' ? this.data.devParentId : undefined
    if(devRole==='parent' && this.data.parentsLoading){wx.showToast({title:'家长列表加载中',icon:'none'});return}
    let previousToken = wx.getStorageSync('token')
    this.setData({ submitting: true, loginError: '' })
    try {
      if (phoneCode === 'mock') {
        const cached = await devSession.restore(devRole, devTeacherId || devParentId, () =>
          !this._unloaded && wx.getStorageSync('token') === previousToken)
        if (this._unloaded) return
        if (cached.restored) {
          wx.reLaunch({ url: '/pages/index/index' })
          return
        }
        if (!cached.restarted && wx.getStorageSync('token') !== previousToken) return
        previousToken = wx.getStorageSync('token')
      }
      const loginCode = await this.getWxLoginCode()
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      const loginRes = await request({
        url: '/miniapp/login',
        method: 'POST',
        data: {
          loginCode,
          phoneCode,
          ...(phoneCode === 'mock' ? { devRole } : {}),
          ...(devTeacherId ? { devTeacherId } : {}),
          ...(devParentId ? { devParentId } : {}),
          nickName: devRole ? `MiniApp ${devRole}` : '微信用户'
        }
      })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      // Resolve the new identity before replacing the current session.
      const infoRes = await request({ url: '/getInfo', header: { Authorization: `Bearer ${loginRes.token}` } })
      if (this._unloaded || wx.getStorageSync('token') !== previousToken) return
      if (devRole === 'teacher' && String(infoRes.user && infoRes.user.userId) !== devTeacherId) {
        throw new Error('教师身份不匹配，请重新选择')
      }
      if(devParentId && String(infoRes.user && infoRes.user.userId)!==devParentId)throw new Error('家长身份不匹配，请重新选择')
      if (phoneCode === 'mock') devSession.activate(devRole, loginRes.token, infoRes, loginRes.devSessionVersion)
      else this.activateAccount(loginRes.token, infoRes)

      wx.reLaunch({ url: '/pages/index/index' })
    } catch (error) {
      if (!this._unloaded) this.setData({ loginError: error.message || '登录失败，请重试' })
    } finally {
      if (!this._unloaded) this.setData({ submitting: false })
    }
  },

  getWxLoginCode() {
    return new Promise((resolve, reject) => {
      wx.login({
        success(res) {
          if (res.code) resolve(res.code)
          else reject(new Error('未取得微信登录凭据，请重试'))
        },
        fail() {
          reject(new Error('微信登录失败，请检查网络后重试'))
        }
      })
    })
  }
})
