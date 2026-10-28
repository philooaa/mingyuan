const { request } = require('../../utils/request')
const devSession = require('../../utils/dev-session')
const courseState = require('../../utils/teacher-course-state')
const childContext = require('../../utils/child-context')
const access = require('../../utils/access')

const roleProfiles = {
  parent: {
    name: '家长',
    tag: '学生家长',
    eyebrow: '家长端',
    title: '课程报名、课表与上课记录',
    stats: [
      { label: '可用额度', value: '—', suffix: '' },
      { label: '已报课程', value: '—', suffix: '门' },
      { label: '待办事项', value: '—', suffix: '项' }
    ],
    actions: [
      { title: '课程报名', icon: '报', tone: 'blue', route: '/pages/enrollment/enrollment' },
      { title: '我的课表', icon: '课', tone: 'orange', route: '/pages/timetable/timetable' },
      { title: '考勤记录', icon: '勤', tone: 'cyan', route: '/pages/attendance/attendance' },
      { title: '请假申请', icon: '假', tone: 'violet', route: '/pages/parent-leave/parent-leave' },
      { title: '资料商城', icon: '资', tone: 'indigo', route: '/pages/material/material' },
      { title: '我的资料', icon: '料', tone: 'green', route: '/pages/material-orders/material-orders' },
      { title: '我的作业', icon: '作', tone: 'violet', route: '/pages/homework/homework' },
      { title: '我的缴费', icon: '费', tone: 'blue', route: '/pages/tuition-list/tuition-list' },
      { title: '我的收据', icon: '据', tone: 'cyan', route: '/pages/tuition-receipt/tuition-receipt' },
      { title: '退费记录', icon: '退', tone: 'orange', route: '/pages/tuition-refund/tuition-refund' },
      { title: '我的优惠券', icon: '券', tone: 'indigo', route: '/pages/my-coupons/my-coupons' },
      { title: '邀请好友', icon: '邀', tone: 'violet', route: '/pages/parent-referral/parent-referral' },
      { key:'personalAccount', title:'我的账户', icon:'账', tone:'gold', route:'/pages/parent-account/parent-account' }
    ],
    agendaTitle: '我的课程',
    schedule: []
  },
  teacher: {
    name: '教师',
    tag: '教师工作台',
    eyebrow: '教师端',
    title: '上课、点名与班级管理',
    stats: [
      { key: 'teacherClasses', label: '负责班级', value: '—', suffix: '班', linkText: '查看学员', route: '/pages/teacher-students/teacher-students' },
      { key: 'teacherMyCourses', label: '我的课程', value: '—', suffix: '班', linkText: '查看课程', route: '/pages/teacher-single-transactions/teacher-single-transactions' },
      { key: 'teacherHomework', label: '我的作业', value: '—', suffix: '项', linkText: '查看作业', route: '/pages/teacher-homework/teacher-homework' },
      { key: 'teacherSinglePending', label: '一对一事务', value: '—', suffix: '项', linkText: '去处理', route: '/pages/teacher-single-transactions/teacher-single-transactions?tab=bookings' }
    ],
    actions: [
      { key:'teacherAttendance', title: '学生出勤', icon: '签', tone: 'blue', route: '/pages/sign-in/sign-in' },
      { key: 'teacherCourses', title: '我的课程', icon: '课', tone: 'orange', route: '/pages/teacher-single-transactions/teacher-single-transactions' },
      { key: 'teacherLeave', title: '请假审核', icon: '假', tone: 'violet', route: '/pages/teacher-leave/teacher-leave' },
      { title: '班级学员', icon: '班', tone: 'green', route: '/pages/teacher-students/teacher-students' },
      { title: 'WiFi打卡', icon: '卡', tone: 'red', route: '/pages/teacher-wifi-checkin/teacher-wifi-checkin' },
      { title: '我的资料', icon: '料', tone: 'green', route: '/pages/material-upload/material-upload' },
      { title: '作业管理', icon: '作', tone: 'violet', route: '/pages/teacher-homework/teacher-homework' }
    ],
    agendaTitle: '我的班级',
    schedule: []
  },
  admin: {
    name: '管理员',
    tag: '校区运营管理',
    eyebrow: '管理员端',
    title: '招生、排课、教室与运营管理',
    stats: [
      { label: '在读学员', value: '386', suffix: '人' },
      { label: '本月收入', value: '28.4', suffix: '万' },
      { label: '续费率', value: '83', suffix: '%' }
    ],
    actions: [
      { key:'studentSignReview', title:'学生出勤', icon:'勤', tone:'cyan', route:'/pages/sign-review/sign-review' },
      { title: '打卡复核', icon: '核', tone: 'blue', route: '/pages/admin-checkin-review/admin-checkin-review' },
      { title: '课程报名', icon: '招', tone: 'blue', route: '/pages/admin-enrollment/admin-enrollment' },
      { title: '排课管理', icon: '排', tone: 'orange' },
      { title: '教室管理', icon: '室', tone: 'cyan', route: '/pages/admin-classrooms/admin-classrooms' },
      { title: '学生档案', icon: '档', tone: 'green', route: '/pages/admin-students/admin-students' },
      { key:'personalAccount', title: '交易管理', icon: '交', tone: 'gold', route: '/pages/admin-transactions/admin-transactions' },
      { key:'referral', title: '优惠券管理', icon: '券', tone: 'violet', route: '/pages/admin-referral/admin-referral' },
      { title: '教师管理', icon: '师', tone: 'indigo', route: '/pages/admin-teachers/admin-teachers' },
      { title: '收费财务', icon: '财', tone: 'gold', route: '/pages/admin-tuition/admin-tuition' },
      { title: '退费管理', icon: '退', tone: 'orange', route: '/pages/admin-tuition-refunds/admin-tuition-refunds' },
      { title: '薪资核算', icon: '薪', tone: 'red' },
      { title: '运营分析', icon: '析', tone: 'violet' },
      { title: '资料商城', icon: '资', tone: 'indigo', route: '/pages/material/material' },
      { title: '资料管理', icon: '料', tone: 'green', route: '/pages/material-upload/material-upload' },
      { title: 'WiFi配置', icon: '网', tone: 'red', route: '/pages/admin-wifi-config/admin-wifi-config' }
    ],
    agendaTitle: '管理待办',
    schedule: [
      { time: '09:20', name: '五年级数学提高班', room: '教师时间冲突', teacher: '徐老师', status: '需处理' },
      { time: '14:00', name: '新初一暑期班', room: '待分配教室', teacher: '教务组', status: '待排课' }
    ]
  }
}

// Education is a real authenticated role, never a development login option.
roleProfiles.education = {
  name: '教务', tag: '全校区教务管理', eyebrow: '教务端', title: '课程出勤、收费与退费初审',
  stats: [], actions: roleProfiles.admin.actions.filter(a => ['教室管理', '学生档案', '收费财务', '退费管理', '学生出勤'].includes(a.title)), agendaTitle: '教务工作说明', schedule: []
}

function normalizeRole(roleKey) {
  return roleProfiles[roleKey] ? roleKey : 'parent'
}

// 日期解析（兼容 SQL DATE/时间戳/字符串多种格式）
function parseDate(val) {
  if (!val) return null
  if (val instanceof Date) return val
  if (typeof val === 'number') {
    const d = new Date(val)
    if (!isNaN(d.getTime())) return d
  }
  const s = String(val)
  if (s.indexOf('T') >= 0 || s.indexOf(' ') >= 0) {
    const d = new Date(s)
    if (!isNaN(d.getTime())) return d
  }
  const m = s.match(/^(\d{4})[-\/.](\d{1,2})[-\/.](\d{1,2})/)
  if (m) {
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
    if (!isNaN(d.getTime())) return d
  }
  return null
}
function fmtDate(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
function weekdayShort(d) {
  const names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return names[d.getDay()]
}

// 按排课信息计算下一次上课日期（与 timetable 页面算法一致）
function computeNextDate(item) {
  if(item.classDate){const d=parseDate(item.classDate);if(!d)return null;const clock=String(item.startTime||'').split(':');d.setHours(Number(clock[0])||0,Number(clock[1])||0,0,0);return d>new Date()?{date:fmtDate(d),weekday:weekdayShort(d)}:{date:'已结课',weekday:''}}
  const start = parseDate(item.startDate)
  const end = parseDate(item.endDate)
  if (!start || !end) return null
  if (start.getTime() > end.getTime()) return null

  let pattern = item.classPattern
  if (!pattern) {
    pattern = (item.periodName === '周六' || item.periodName === '周日') ? 'WEEKLY' : 'DAILY_5_1'
  }
  let dates = []
  if (pattern === 'WEEKLY') {
    const targetDay = item.periodName === '周日' ? 0 : 6
    let cur = new Date(start)
    while (cur <= end && cur.getDay() !== targetDay) {
      cur.setDate(cur.getDate() + 1)
    }
    while (cur <= end) {
      dates.push(new Date(cur.getFullYear(), cur.getMonth(), cur.getDate()))
      cur.setDate(cur.getDate() + 7)
    }
  } else if (pattern === 'DAILY_5_1') {
    let cur = new Date(start.getFullYear(), start.getMonth(), start.getDate())
    let cnt = 0
    const stop = new Date(end.getFullYear(), end.getMonth(), end.getDate())
    while (cur <= stop) {
      if (cnt % 6 < 5) dates.push(new Date(cur.getFullYear(), cur.getMonth(), cur.getDate()))
      cur.setDate(cur.getDate() + 1)
      cnt++
    }
  }

  // 应用调课表
  const adj = item.adjustments || []
  const adjByOrig = {}
  adj.forEach((a) => {
    if (a.originalDate) {
      const d = parseDate(a.originalDate)
      if (d) adjByOrig[fmtDate(d)] = a
    }
  })
  let result = []
  dates.forEach((d) => {
    const key = fmtDate(d)
    const a = adjByOrig[key]
    if (a) {
      if (a.adjustedDate) {
        const ad = parseDate(a.adjustedDate)
        if (ad) result.push(ad)
      }
    } else {
      result.push(d)
    }
  })

  const seen = {}
  result = result.filter((d) => {
    const k = fmtDate(d)
    if (seen[k]) return false
    seen[k] = true
    return true
  }).sort((a, b) => a - b)

  if (!result.length) return null
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const future = result.find(d=>{const clock=String(item.startTime||'').split(':');const start=new Date(d);start.setHours(Number(clock[0])||0,Number(clock[1])||0,0,0);return start>new Date()})
  if (future) return { date: fmtDate(future), weekday: weekdayShort(future) } // 只显示 MM-DD
  return { date: '已结课', weekday: '' }
}

function buildRoleState(roleKey, visibleRoles) {
  const normalizedRole = normalizeRole(roleKey)
  const profile = roleProfiles[normalizedRole]
  const roles = visibleRoles || []
  return {
    currentRole: normalizedRole,
    currentRoleName: profile.name,
    tag: profile.tag,
    eyebrow: profile.eyebrow,
    title: profile.title,
    stats: profile.stats,
    quickActions: profile.actions,
    agendaTitle: profile.agendaTitle,
    schedule: profile.schedule,
    roles: roles.map((role) => ({
      ...role,
      className: role.key === normalizedRole ? 'active' : ''
    }))
  }
}

function getRoleFromStorage(roles = wx.getStorageSync('roles') || []) { return access.selected(roles) }

Page({
  data: {
    logoUrl: '/images/logo.png',
    roleLocked: true,
    developmentLoginEnabled: false,
    roleSwitching: false,
    teacherDataMessage: '',
    coursePendingCount: null,
    coursePendingError: '',
    parentChildren: [], currentChild: null, childLoading:false, childError:'', showChildPicker:false, parentScheduleError:'',
    roles: [],
    ...buildRoleState('parent', []),
    quickActions: [], stats: [], schedule: []
  },

  onLoad() {
    this._unloaded = false
    this._visible = false
  },

  onShow() {
    this._visible = true
    this.applyLoginRole()
  },

  onHide() {
    this._visible = false
    this.stopCoursePendingRefresh()
    this.stopAccountRefresh()
  },

  onUnload() {
    this._unloaded = true
    this._visible = false
    this.stopCoursePendingRefresh()
    this.stopAccountRefresh()
    this._teacherLoadVersion = (this._teacherLoadVersion || 0) + 1
    this._roleLoadVersion = (this._roleLoadVersion || 0) + 1
  },

  async applyLoginRole() {
    this.stopCoursePendingRefresh()
    this.stopAccountRefresh()
    this.setCoursePending(null)
    const loadVersion = this._roleLoadVersion = (this._roleLoadVersion || 0) + 1
    const token = wx.getStorageSync('token')
    if (!token) {
      wx.reLaunch({ url: '/pages/register/register' })
      return
    }

    if (wx.getStorageSync('devSessionVersion')) {
      try {
        const checked = await devSession.checkBackendSession()
        if (this._unloaded || loadVersion !== this._roleLoadVersion) return
        if (checked.restarted || wx.getStorageSync('token') !== token) {
          if (!wx.getStorageSync('token')) wx.reLaunch({ url: '/pages/register/register' })
          return
        }
      } catch (error) {
        if (!this._unloaded && loadVersion === this._roleLoadVersion) {
          wx.showToast({ title: error.message || '后端暂时无法连接', icon: 'none' })
        }
        return
      }
    }
    // Only getInfo grants the finance entry; cached roles and role tabs cannot do so.
    this.setData({ quickActions: [], stats: [], schedule: [], roles: [] })
    let info
    try {
      info = await request({ url: '/getInfo' })
      if (this._unloaded || loadVersion !== this._roleLoadVersion || wx.getStorageSync('token') !== token) return
      getApp().globalData.roles = info.roles || []
      getApp().globalData.permissions = info.permissions || []
      if (info.user) getApp().globalData.userInfo = info.user
    } catch (error) {
      if (!this._unloaded && loadVersion === this._roleLoadVersion) wx.showToast({ title: error.message || '身份核对失败，请重新登录', icon: 'none' })
      return
    }
    wx.setStorageSync('roles', info.roles || [])
    if (info.user) wx.setStorageSync('userInfo', info.user)
    const workspaces = access.options(info.roles || [])
    if (!workspaces.length) {
      this.setData({ roles: [], quickActions: [], stats: [], schedule: [], roleLocked: true, currentRole: '', currentRoleName: '', title: '此账号尚未分配小程序角色', tag: '请联系管理员授权' })
      return
    }
    const role = getRoleFromStorage(info.roles || [])
    const financeAllowed = access.staff() && access.can('system:tuition:list')
    this._teacherLoadVersion = (this._teacherLoadVersion || 0) + 1
    this.setData({ teacherDataMessage: '',currentChild:null,parentChildren:[],childLoading:false,childError:'',showChildPicker:false,parentScheduleError:'', roleLocked: workspaces.length < 2, developmentLoginEnabled: access.developmentTool() && !!wx.getStorageSync('devSessionVersion'), ...buildRoleState(role, workspaces) })
    if(role==='education')this.loadSignReviewPending()
    if(role==='parent' || role==='admin') { this.loadAccountPending();if(role==='admin'){this.loadReferralPending();this.loadSignReviewPending()};this._accountTimer=setInterval(()=>{this.loadAccountPending();if(this.data.currentRole==='admin'){this.loadReferralPending();this.loadSignReviewPending()}},30000) }

    const actions = this.data.quickActions.filter(a => a.route !== '/pages/admin-tuition/admin-tuition')
    if (financeAllowed && ['admin', 'education'].includes(role)) actions.push({ title: '收费财务', icon: '财', tone: 'gold', route: '/pages/admin-tuition/admin-tuition' })
    this.setData({ quickActions: access.actions(actions, role) })

    if (role === 'parent') {
      await this.loadParentTag()
    } else if (role === 'teacher') {
      const user = wx.getStorageSync('userInfo') || {}
      this.setData({ tag: user.nickName || user.userName || '教师工作台' })
      this.loadTeacherData()
      this.loadLeavePending()
      this._coursePendingOwnerToken = token
      this.loadCoursePending()
      this.startCoursePendingRefresh()
    }
  },

  async loadLeavePending() {
    if(this._leavePendingLoading || this._unloaded || !this._visible || this.data.currentRole!=='teacher' || getRoleFromStorage()!=='teacher')return
    const token=wx.getStorageSync('token'),version=this._roleLoadVersion
    const requestVersion=this._leavePendingVersion=(this._leavePendingVersion || 0)+1
    this._leavePendingLoading=true
    try {
      const r=await request({url:'/miniapp/teacher/leave',data:{state:'PENDING'},timeout:15000})
      if(this._unloaded || !this._visible || requestVersion!==this._leavePendingVersion || version!==this._roleLoadVersion || this.data.currentRole!=='teacher' || getRoleFromStorage()!=='teacher' || wx.getStorageSync('token')!==token)return
      this.setData({quickActions:this.data.quickActions.map(a=>a.key==='teacherLeave'?{...a,badgeText:courseState.badgeText(Number((r.data || {}).pendingCount))}:a)})
    } catch(e) { /* 审核页提供失败提示及重试，首页不把加载失败当作0。 */ }
    finally {if(requestVersion===this._leavePendingVersion)this._leavePendingLoading=false}
  },

  async loadSignReviewPending() {
    const role=this.data.currentRole
    if(this._signReviewLoading||this._unloaded||!this._visible||!['admin','education'].includes(role)||getRoleFromStorage()!==role)return
    const token=wx.getStorageSync('token'),roleVersion=this._roleLoadVersion,version=this._signReviewVersion=(this._signReviewVersion||0)+1
    this._signReviewLoading=true
    try {
      const r=await request({url:'/miniapp/education/sign/list',timeout:15000})
      if(this._unloaded||!this._visible||version!==this._signReviewVersion||roleVersion!==this._roleLoadVersion||this.data.currentRole!==role||getRoleFromStorage()!==role||wx.getStorageSync('token')!==token)return
      const count=new Set((r.data||[]).filter(x=>String(x.reviewStatus||'0')!=='1').map(x=>`${x.scheduleId}:${String(x.classDate).slice(0,10)}`)).size
      this.setData({quickActions:this.data.quickActions.map(a=>a.key==='studentSignReview'?{...a,badgeText:courseState.badgeText(count)}:a)})
    }catch(e){}finally{if(version===this._signReviewVersion)this._signReviewLoading=false}
  },

  async loadReferralPending() {
    if(this._referralPendingLoading || this._unloaded || !this._visible || this.data.currentRole!=='admin' || getRoleFromStorage()!=='admin')return
    const token=wx.getStorageSync('token'),version=this._roleLoadVersion,requestVersion=this._referralPendingVersion=(this._referralPendingVersion||0)+1
    this._referralPendingLoading=true
    try {
      const r=await request({url:'/system/referral/counts',timeout:15000})
      if(this._unloaded || !this._visible || requestVersion!==this._referralPendingVersion || version!==this._roleLoadVersion || this.data.currentRole!=='admin' || getRoleFromStorage()!=='admin' || wx.getStorageSync('token')!==token)return
      this.setData({quickActions:this.data.quickActions.map(a=>a.key==='referral'?{...a,badgeText:courseState.badgeText(Number(r.data.pending)+Number(r.data.anomalies))}:a)})
    }catch(e){}finally{if(requestVersion===this._referralPendingVersion)this._referralPendingLoading=false}
  },

  setCoursePending(count, error = '') {
    if (this._unloaded) return
    this.setData({coursePendingCount:count,coursePendingError:error,
      stats:this.data.stats.map(stat=>stat.key==='teacherSinglePending' ? {...stat,value:count == null ? '—' : String(count)} : stat),
      quickActions:this.data.quickActions.map(action=>action.key==='teacherCourses' ? {...action,badgeText:courseState.badgeText(count)} : action)})
  },
  startCoursePendingRefresh() {
    if (!this._visible || this._unloaded || this.data.currentRole !== 'teacher') return
    clearInterval(this._coursePendingTimer)
    this._coursePendingTimer = setInterval(()=>{this.loadCoursePending();this.loadLeavePending();this.loadTeacherData()},30000)
  },
  stopCoursePendingRefresh() {
    clearInterval(this._coursePendingTimer)
    this._coursePendingTimer = null
    this._coursePendingVersion = (this._coursePendingVersion || 0) + 1
    this._coursePendingLoading = false
    this._coursePendingOwnerToken = null
    this._leavePendingVersion = (this._leavePendingVersion || 0) + 1
    this._leavePendingLoading = false
    this._signReviewVersion=(this._signReviewVersion||0)+1
    this._signReviewLoading=false
    this._referralPendingVersion = (this._referralPendingVersion || 0) + 1
    this._referralPendingLoading = false
  },
  async loadCoursePending() {
    const token = wx.getStorageSync('token')
    if (this._coursePendingLoading || !this._visible || this._unloaded || this.data.currentRole !== 'teacher' ||
      getRoleFromStorage() !== 'teacher' || !token || token !== this._coursePendingOwnerToken) return
    const version = this._coursePendingVersion = (this._coursePendingVersion || 0) + 1
    const current = () => !this._unloaded && this._visible && version===this._coursePendingVersion &&
      this.data.currentRole==='teacher' && getRoleFromStorage()==='teacher' && wx.getStorageSync('token')===token
    this._coursePendingLoading = true
    try {
      const result = await request({url:'/miniapp/teacher/course/single/transactions',timeout:15000})
      if (current()) this.setCoursePending(courseState.pendingCount(result.data || []))
    } catch (error) {
      if (current()) this.setCoursePending(null,'课程待处理数量暂时无法加载，请重试。')
    } finally { if (version===this._coursePendingVersion) this._coursePendingLoading=false }
  },

  async loadTeacherData() {
    const token = wx.getStorageSync('token')
    const version = this._teacherLoadVersion
    const results = await Promise.allSettled([
      request({ url: '/miniapp/teacher/sign/list', timeout: 15000 }),
      request({ url: '/miniapp/teacher/homework/list', timeout: 15000 })
    ])
    if (version !== this._teacherLoadVersion || this.data.currentRole !== 'teacher' || wx.getStorageSync('token') !== token) return
    const classes = results[0].status === 'fulfilled' ? (results[0].value.data || []) : null
    const homework = results[1].status === 'fulfilled' ? (results[1].value.data || []) : null
    const openCount = homework ? homework.filter(item => String(item.status) === '1').length : 0
    const statValues = {
      teacherClasses: classes ? String(classes.length) : '—',
      teacherMyCourses: classes ? String(classes.length) : '—',
      teacherHomework: homework ? String(openCount) : '—',
      teacherSinglePending: this.data.coursePendingCount == null ? '—' : String(this.data.coursePendingCount)
    }
    this.setData({
      stats: roleProfiles.teacher.stats.map(stat => ({ ...stat, value: statValues[stat.key] })),
      quickActions: this.data.quickActions.map(a => a.key==='teacherAttendance' && classes ? {...a,badgeText:courseState.badgeText(classes.reduce((n,c)=>n+(c.corrections||[]).length,0))} : a),
      schedule: (classes || []).slice(0, 3).map((item) => ({
        time: item.timeSlot || '—',
        name: item.courseClassName || '未命名班级',
        room: item.classroomName || '',
        teacher: item.teacherName || ''
      })),
      teacherDataMessage: classes === null ? '班级加载失败，请重新进入或查看学生出勤' : (classes.length ? '' : '当前教师尚未绑定班级'),
    })
  },

  async loadParentSchedule() {
    const token = wx.getStorageSync('token'),ticket=childContext.snapshot(),version=this._parentScheduleVersion=(this._parentScheduleVersion||0)+1
    this.setData({schedule:[],parentScheduleError:''});if(!ticket.id)return
    try {
      const res = await request({ url: '/miniapp/parent/timetable',data:{accountId:ticket.id} })
      if (this._unloaded || !this._visible || version!==this._parentScheduleVersion || !childContext.current(ticket) || this.data.currentRole !== 'parent' || wx.getStorageSync('token') !== token) return
      const list = res.data || []
      // 取前 3 条，取最近 3 次课，按下次上课日期排序
      const decorated = list.map((item) => {
        const next = computeNextDate(item)
        const start = item.startTime ? String(item.startTime).slice(0, 5) : ''
        const end = item.endTime ? String(item.endTime).slice(0, 5) : ''
        return {
          time: (start && end) ? `${start}-${end}` : (item.timeSlot || '—'),
          date: next ? next.date : '',
          weekday: next ? next.weekday : '',
          dateReady: !!next,
          name: item.courseClassName || '未命名课程',
          room: item.classroomName || '',
          teacher: item.teacherName || '',
          status: (item.payStatus === '未支付') ? '待支付' : '待上课'
        }
      })
      decorated.sort((a, b) => {
        if (!a.date) return 1
        if (!b.date) return -1
        if (a.date === '已结课') return 1
        if (b.date === '已结课') return -1
        return a.date.localeCompare(b.date)
      })
      const schedule = decorated.filter(r=>r.dateReady&&r.date!=='已结课').slice(0, 3)
      this.setData({ schedule, parentScheduleError:decorated.some(r=>!r.dateReady)?'部分课程尚未配置日期，可在我的课表查看':'' })
    } catch (error) {
      if(!this._unloaded&&this._visible&&version===this._parentScheduleVersion&&childContext.current(ticket))this.setData({schedule:[],parentScheduleError:'课程暂时加载失败，请重试'})
    }
  },

  openChildren() { if(this.data.currentRole==='parent' && getRoleFromStorage()==='parent') wx.navigateTo({url:'/pages/parent-children/parent-children'}) },
  stopAccountRefresh() {clearInterval(this._accountTimer);this._accountTimer=null;this._accountVersion=(this._accountVersion || 0)+1;this._accountBusy=false;if(!this._unloaded)this.setData({accountPendingError:'',quickActions:this.data.quickActions.map(a=>a.key==='personalAccount'?{...a,badgeText:''}:a)})},
  async loadAccountPending() {
    const role=this.data.currentRole,token=wx.getStorageSync('token');if(this._accountBusy || !this._visible || this._unloaded || !['parent','admin'].includes(role) || getRoleFromStorage()!==role || !token)return
    const v=this._accountVersion=(this._accountVersion || 0)+1,current=()=>!this._unloaded && this._visible && v===this._accountVersion && this.data.currentRole===role && getRoleFromStorage()===role && wx.getStorageSync('token')===token
    this._accountBusy=true
    try{const r=await request({url:role==='admin'?'/system/eduTransaction/pending-count':'/miniapp/parent/account/pending-count',data:role==='parent'?{actionable:true}:{},timeout:15000});if(current())this.setData({accountPendingError:'',quickActions:this.data.quickActions.map(a=>a.key==='personalAccount'?{...a,badgeText:courseState.badgeText(Number(r.data))}:a)})}
    catch(e){if(current())this.setData({accountPendingError:'账户待处理数量暂时无法加载',quickActions:this.data.quickActions.map(a=>a.key==='personalAccount'?{...a,badgeText:''}:a)})}
    finally{if(v===this._accountVersion)this._accountBusy=false}
  },
  async loadParentTag() {
    const token=wx.getStorageSync('token'),roleVersion=this._roleLoadVersion,version=this._parentChildrenVersion=(this._parentChildrenVersion||0)+1
    this.setData({childLoading:true,childError:'',schedule:[],stats:roleProfiles.parent.stats})
    try {
      const r=await request({url:'/miniapp/parent/account'})
      if(this._unloaded||!this._visible||version!==this._parentChildrenVersion||roleVersion!==this._roleLoadVersion||this.data.currentRole!=='parent'||wx.getStorageSync('token')!==token)return
      const children=((r.data||{}).children||[]).map(c=>({...c,accountId:String(c.accountId),badgeText:courseState.badgeText(c.actionPendingCount)}))
      const selected=childContext.reconcile(children)
      this.setData({parentChildren:children,tag:'管理孩子信息'})
      this.displayChild(selected);await this.loadParentSchedule()
    } catch(e) {
      if(!this._unloaded&&this._visible&&version===this._parentChildrenVersion&&this.data.currentRole==='parent'&&wx.getStorageSync('token')===token)this.setData({currentChild:null,parentChildren:[],schedule:[],childError:'孩子资料加载失败，请重试',stats:roleProfiles.parent.stats})
    } finally {if(version===this._parentChildrenVersion&&!this._unloaded)this.setData({childLoading:false})}
  },
  displayChild(child) {
    const stats=child?[{label:'可用额度',value:String(Number(child.remainingCredits)-Number(child.reservedCredits)),suffix:''},{label:'已报课程',value:String(child.courseCount||0),suffix:'门'},{label:'待办事项',value:String(child.actionPendingCount||0),suffix:'项'}]:roleProfiles.parent.stats
    this.setData({currentChild:child,stats,schedule:[],showChildPicker:false,parentScheduleError:''})
    this._parentScheduleVersion=(this._parentScheduleVersion||0)+1
  },
  toggleChildPicker(){if(this.data.currentRole==='parent'&&!this.data.roleSwitching&&!this.data.childLoading){if(this.data.childError){this.loadParentTag();return}this.setData({showChildPicker:!this.data.showChildPicker})}},
  chooseChild(e){
    if(this.data.currentRole!=='parent'||this.data.roleSwitching||this.data.childLoading)return
    const child=this.data.parentChildren.find(c=>c.accountId===String(e.currentTarget.dataset.id));if(!child)return
    childContext.select(child);this.displayChild(child);this.loadParentSchedule()
  },
  addChild(){if(this.data.currentRole==='parent'&&!this.data.roleSwitching&&!this.data.childLoading){this.setData({showChildPicker:false});wx.navigateTo({url:'/pages/parent-account/parent-account?create=1&returnHome=1'})}},

  async openRoleMenu() {
    if (this._unloaded || this._visible === false || this.data.roleSwitching || this._roleMenuOpen || this.data.roles.length < 2) return
    const roles = this.data.roles.slice()
    const token = wx.getStorageSync('token')
    const version = this._roleLoadVersion
    this._roleMenuOpen = true
    try {
      const choice = await new Promise(resolve => wx.showActionSheet({
        itemList: roles.map(role => role.name + (role.key === this.data.currentRole ? '（当前）' : '')),
        success: resolve,
        fail: () => resolve(null)
      }))
      if (!choice || this._unloaded || this._visible === false || !token || token !== wx.getStorageSync('token') || version !== this._roleLoadVersion) return
      const role = roles[choice.tapIndex]
      if (role && role.key !== this.data.currentRole) await this.switchRole({ currentTarget: { dataset: { role: role.key } } })
    } finally { this._roleMenuOpen = false }
  },

  async switchRole(e) {
    if (this._unloaded || this.data.roleSwitching) return
    const role = e.currentTarget.dataset.role
    if (!access.options().some(item => item.key === role)) {
      wx.showToast({ title: '当前账号未获此身份授权', icon: 'none' }); return
    }
    this.setData({ roleSwitching: true, quickActions: [], stats: [], schedule: [] })
    try {
      await this.applyLoginRole()
      if (!this._unloaded && this.data.roles.some(item => item.key === role) && access.select(role)) await this.applyLoginRole()
    } finally { if (!this._unloaded) this.setData({ roleSwitching: false }) }
  },
  logout() {
    this.stopCoursePendingRefresh(); this.stopAccountRefresh()
    ;['token', 'roles', 'userInfo', 'devSessionVersion'].forEach(k => wx.removeStorageSync(k))
    Object.assign(getApp().globalData, { token: '', roles: [], permissions: [], userInfo: null })
    wx.reLaunch({ url: '/pages/register/register' })
  },

  switchTeacher() {
    if (!this._unloaded && this.data.developmentLoginEnabled) wx.navigateTo({ url: '/pages/register/register?selectTeacher=1' })
  },

  switchParent() {
    if(!this._unloaded && this.data.developmentLoginEnabled && !this.data.roleSwitching && this.data.currentRole==='parent')wx.navigateTo({url:'/pages/register/register?selectParent=1'})
  },

  getWxLoginCode() {
    return new Promise((resolve) => {
      wx.login({
        success(res) {
          resolve(res.code || 'mock')
        },
        fail() {
          resolve('mock')
        }
      })
    })
  },

  handleStat(e) {
    if (this._unloaded || this.data.roleSwitching || this.data.currentRole !== 'teacher' || getRoleFromStorage() !== 'teacher') return
    const stat = this.data.stats.find(item => item.key === e.currentTarget.dataset.key)
    if (stat && stat.route) wx.navigateTo({ url: stat.route })
  },

  handleAction(e) {
    const route = e.currentTarget.dataset.route
    const title = e.currentTarget.dataset.title
    const disabled = e.currentTarget.dataset.disabled
    if (this._unloaded || this.data.roleSwitching || !access.actions(this.data.quickActions, this.data.currentRole).some(a => a.route === route && a.title === title)) return
    if (disabled) {
      wx.showToast({
        title: `${title}已暂时关闭`,
        icon: 'none'
      })
      return
    }
    if (route) {
      if(this.data.currentRole==='parent'&&['课程报名','我的课表','考勤记录','请假申请','我的作业','我的缴费','我的收据','退费记录'].includes(title)){if(this.data.childLoading||this.data.childError){wx.showToast({title:'请先加载孩子资料',icon:'none'});return}if(!this.data.currentChild){this.addChild();return}wx.navigateTo({url:childContext.url(route)})}else wx.navigateTo({ url: route })
      return
    }

    wx.showToast({
      title: `${title}暂未开放`,
      icon: 'none'
    })
  }
})
