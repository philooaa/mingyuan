const { request } = require('../../utils/request')
const { paymentStatus } = require('../../utils/enrollment-payment-status')
const access = require('../../utils/access')

// 报名状态里出现「取消」即视为已取消；其余（报名成功 / 1 / 待确认…）视为在读
function isActiveStatus(s) {
  const v = String(s == null ? '' : s).trim()
  if (!v) return false
  return v !== '2' && v !== '3' && v.indexOf('取消') < 0 && v.indexOf('拒绝') < 0
}

function dateOnly(v) { return (v || '').slice(0, 10) }
async function allEnrollments() {
  const rows=[]; let page=1, total=0
  do {
    const result=await request({url:'/system/enrollment/list',data:{pageNum:page,pageSize:500}})
    const batch=result.rows || []; total=Number(result.total || 0); rows.push(...batch); page++
    if(!batch.length && rows.length<total) throw new Error('报名数据未能完整加载，请重试')
  } while(rows.length<total)
  return rows
}

// 真名优先；真名是纯英文账号名（如 teacher_demo）而昵称是中文时，用昵称更可读
function pickRealName(nick, real) {
  const n = (nick || '').trim()
  const r = (real || '').trim()
  if (!r) return n
  if (!n) return r
  if (/^[\x00-\x7F]+$/.test(r) && /[\u4e00-\u9fa5]/.test(n)) return n
  return r
}

Page({
  data: {
    loading: false,
    keyword: '',
    filterStatus: '',
    quarterIndex: 0,
    quarterOptions: [{label:'全部季度',value:''}],
    list: [],
    shown: [],
    pageSize: 30,
    shownCount: 0,
    hasMore: true,
    total: 0,
    showEmpty: false,
    detail: null,
    canManageAccounts: false,
    statusOptions: [
      { label: '全部', value: '' },
      { label: '已支付', value: '已支付' },
      { label: '未支付', value: '未支付' },
      { label: '待核价', value: '待核价' },
      { label: '未报名', value: '未报名' }
    ]
  },

  onShow() { this.setData({canManageAccounts:access.selected()==='admin' && access.has('admin') && access.can('edu:transaction:manage')});this.load() },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { this.showMore() },

  async load() {
    this.setData({ loading: true })
    try {
      const [enrollRows, teacherRes, profilesRes, optionRes] = await Promise.all([
        allEnrollments(),
        // 教师花名册：排课里存的是昵称（如「张老师」），这里换成真名展示
        request({ url: '/system/salaryConfig/teachers' }).catch(() => ({ data: [] })),
        request({url:'/miniapp/admin/enrollment/student-profiles'}),
        request({url:'/miniapp/admin/enrollment/filter-options'})
      ])
      const nameByNick = {}
      ;(teacherRes.data || []).forEach((t) => {
        const nick = (t.teacherName || '').trim()
        const real = pickRealName(nick, t.realName)
        // 昵称与真名都建映射：新数据 enrollment.teacherName 已是真名，旧数据可能还是昵称
        if (nick && !nameByNick[nick]) nameByNick[nick] = real
        if (real && !nameByNick[real]) nameByNick[real] = real
      })
      const realName = (n) => {
        const v = (n || '').trim()
        if (!v) return '—'
        return nameByNick[v] || v
      }

      const rows = enrollRows.filter((r) => r.delFlag !== '2')
      // 按「家长 + 学生姓名」聚合：同一家长下的同名视为同一名学生，不同家长的同名分开
      const map = {}
      const order = []
      const profileNames = {}
      ;(profilesRes.data || []).forEach(st=>{
        const parentId=String(st.parentId || ''), studentName=String(st.studentName || '').trim()
        if(!studentName || !st.studentId)return
        const key='student:'+st.studentId, nameKey=parentId+'|'+studentName
        ;(profileNames[nameKey] ||= []).push(key)
        map[key]={key,parentId,studentName,studentPhone:st.studentPhone || '',contactPhone:'',parentPhone:st.parentPhone || '',courses:[],activeCount:0,cancelledCount:0,latestTime:''}
        order.push(key)
      })
      rows.forEach((r) => {
        const studentName = (r.studentName || '').trim()
        if (!studentName) return
        const parentId = r.parentId == null ? '' : String(r.parentId)
        const nameKey = `${parentId}|${studentName}`
        const idKey = r.studentId ? 'student:'+r.studentId : ''
        if(!idKey && (profileNames[nameKey] || []).length>1) profileNames[nameKey].forEach(k=>{map[k].ambiguousEnrollment=true})
        const key = idKey && map[idKey] ? idKey : (!idKey && (profileNames[nameKey] || []).length===1 ? profileNames[nameKey][0] : idKey || 'legacy:'+nameKey)
        let s = map[key]
        if (!s) {
          s = map[key] = {
            key,
            parentId,
            studentName,
            studentPhone: '',
            contactPhone: '',
            parentPhone: '',
            courses: [],
            activeCount: 0,
            cancelledCount: 0,
            latestTime: ''
          }
          order.push(key)
        }
        if (!s.studentPhone && r.studentPhone) s.studentPhone = r.studentPhone
        if (!s.contactPhone && r.contactPhone) s.contactPhone = r.contactPhone
        if (!s.parentPhone && r.parentPhone) s.parentPhone = r.parentPhone

        const active = isActiveStatus(r.enrollmentStatus)
        if (active) s.activeCount += 1
        else s.cancelledCount += 1

        const time = r.createTime || ''
        if (time > s.latestTime) s.latestTime = time

        s.courses.push({
          enrollmentId: r.enrollmentId,
          enrollmentCode: r.enrollmentCode || '—',
          courseClassName: r.courseClassName || '—',
          gradeName: r.gradeName || '—',
          subjectName: r.subjectName || '—',
          classType: r.classType || '—',
          termText: [r.courseYear, r.termName, r.periodName].filter(Boolean).join(' · ') || '季度待核对',
          quarter: r.courseYear && r.termName ? String(r.courseYear)+'|'+r.termName : '',
          teacherName: realName(r.teacherName),
          enrollmentStatus: r.enrollmentStatus || '—',
          paymentStatusText: paymentStatus(r),
          payStatus: r.payStatus || '—',
          active,
          createTime: dateOnly(r.createTime),
          remark: r.remark || ''
        })
      })

      const all = order.map((k) => {
        const s = map[k]
        s.courses.sort((a, b) => (b.createTime || '').localeCompare(a.createTime || ''))
        s.courseCount = s.courses.length
        s.latestDate = dateOnly(s.latestTime)
        s.statusText = s.activeCount > 0 ? `在读 ${s.activeCount} 门` : s.courseCount ? '已全部取消' : '未报名'
        s.searchText = [
          s.studentName, s.studentPhone, s.contactPhone, s.parentPhone,
          ...s.courses.map((c) => `${c.courseClassName} ${c.subjectName} ${c.teacherName}`)
        ].join(' ')
        return s
      }).sort((a, b) => (b.latestTime || '').localeCompare(a.latestTime || '') || a.studentName.localeCompare(b.studentName))

      this._all = all
      const quarterMap={}
      ;(optionRes.data || []).forEach(o=>{if(o.courseYear && o.termName) quarterMap[String(o.courseYear)+'|'+o.termName]=String(o.courseYear)+' · '+o.termName})
      rows.forEach(o=>{if(o.courseYear && o.termName) quarterMap[String(o.courseYear)+'|'+o.termName]=String(o.courseYear)+' · '+o.termName})
      const previous=(this.data.quarterOptions[this.data.quarterIndex] || {}).value
      const quarterOptions=[{label:'全部季度',value:''},...Object.keys(quarterMap).sort().reverse().map(value=>({label:quarterMap[value],value}))]
      this.setData({ loading: false,quarterOptions,quarterIndex:Math.max(0,quarterOptions.findIndex(o=>o.value===previous)) })
      this.applyFilter(true)
    } catch (e) {
      this.setData({ loading: false, list: [], shown: [], showEmpty: true })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  onKeyword(e) { this.setData({ keyword: e.detail.value }); this.applyFilter(true) },
  onStatus(e) { this.setData({ filterStatus: e.currentTarget.dataset.value }); this.applyFilter(true) },
  onQuarter(e) { this.setData({quarterIndex:Number(e.detail.value)});this.applyFilter(true) },

  applyFilter(reset) {
    const kw = (this.data.keyword || '').trim()
    const st = this.data.filterStatus
    const quarter=(this.data.quarterOptions[this.data.quarterIndex] || {}).value || ''
    const list = (this._all || []).filter((s) => {
      const active=s.courses.filter(c=>c.active)
      const inQuarter=active.filter(c=>!quarter || c.quarter===quarter)
      if(st==='未报名') { if(s.ambiguousEnrollment || inQuarter.length || (quarter && active.some(c=>!c.quarter)))return false }
      else if(st) { if(!inQuarter.some(c=>c.paymentStatusText===st))return false }
      else if(quarter && !inQuarter.length)return false
      if (!kw) return true
      return (s.searchText || '').indexOf(kw) >= 0
    })
    const pageSize = this.data.pageSize
    const shownCount = reset ? Math.min(pageSize, list.length) : this.data.shownCount
    this.setData({
      list,
      total: list.length,
      shown: list.slice(0, shownCount),
      shownCount,
      hasMore: shownCount < list.length,
      showEmpty: list.length === 0
    })
  },

  showMore() {
    if (!this.data.hasMore) return
    this.setData({ shownCount: this.data.shownCount + this.data.pageSize })
    this.applyFilter(false)
  },

  openDetail(e) {
    const key = e.currentTarget.dataset.key
    const item = (this._all || []).find((s) => s.key === key)
    if (item) this.setData({ detail: item })
  },
  closeDetail() { this.setData({ detail: null }) },

  openAccounts() {
    if(access.selected()!=='admin' || !access.has('admin') || !access.can('edu:transaction:manage'))return
    wx.navigateTo({url:'/pages/admin-transactions/admin-transactions'})
  },
  openStudentAccount() {
    if(access.selected()!=='admin' || !access.has('admin') || !access.can('edu:transaction:manage'))return
    const parentId=this.data.detail && this.data.detail.parentId
    if(!parentId) {wx.showToast({title:'家长账户待核对',icon:'none'});return}
    wx.navigateTo({url:'/pages/admin-transactions/admin-transactions?parentId='+encodeURIComponent(parentId)})
  },

  openEnrollment(e) { wx.navigateTo({url: '/pages/admin-enrollment-detail/admin-enrollment-detail?id='+e.currentTarget.dataset.id}) },
  noop() {}
})
