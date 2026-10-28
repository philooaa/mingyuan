const { request } = require('./request')
const childContext=require('./child-context')
const STATES = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }
const ACTIONS = { SUBMIT: '提交申请', WITHDRAW: '撤回申请', APPROVE: '同意请假', REJECT: '拒绝请假' }
const plain = value => value == null ? '' : String(value)
function page(teacher) {
  const role = teacher ? 'teacher' : 'parent'
  const base = `/miniapp/${role}/leave`
  const definition = {
    data: { teacher, loading: false, submitting: false, error: '', rows: [], total: 0, pageNum: 1,
      state: teacher ? 'PENDING' : '', pendingCount: 0, pendingBadge: '', showForm: false, reason: '', options: [],
      children: [], courses: [], lessons: [], childIndex: -1, courseIndex: -1, lessonIndex: -1,
      lessonsLoading: false, lessonError: '', historyId: '', history: [], historyLoading: false },
    onLoad(options={}) {this.accountId=!teacher&&options.accountId?String(options.accountId):''; this._alive = true; this._visible = true; this._token = wx.getStorageSync('token'); this.load() },
    onShow() {
      this._visible = true
      if (!this.current()) { this.clearIdentity(); return }
      if (this._shown) this.load()
      this._shown = true
    },
    onHide() { this._visible = false; this._version = (this._version || 0) + 1; this._lessonVersion = (this._lessonVersion || 0) + 1; this._historyVersion = (this._historyVersion || 0) + 1 },
    onUnload() { this.onHide(); this._alive = false },
    current() { return this._alive && this._visible && !!this._token && this._token === wx.getStorageSync('token') && (wx.getStorageSync('roles') || []).includes(role) },
    clearIdentity() { this.setData({ rows: [], options: [], children: [], courses: [], lessons: [], history: [], showForm: false, historyId: '', loading: false, submitting: false, pendingCount: 0, pendingBadge: '', error: '登录身份已变化，请重新进入' }) },
    onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
    async load() {
      if (!this.current()) { this.clearIdentity(); return }
      const v = this._version = (this._version || 0) + 1
      this.setData({ loading: true, error: '' })
      try {
        const [a,b] = await Promise.all([request({ url: base, data: { state: this.data.state, pageNum: this.data.pageNum,...(this.accountId?{accountId:this.accountId}:{}) } }),
          teacher ? Promise.resolve({ data: [] }) : request({ url: base + '/options',...(this.accountId?{data:{accountId:this.accountId}}:{}) })])
        if (!this.current() || v !== this._version) return
        const d = a.data || {}, options = b.data || [], seen = new Set(), children = []
        options.forEach(o => { if (!seen.has(o.childKey)) { seen.add(o.childKey); children.push({ key: o.childKey, label: o.childName + (o.childKey.startsWith('E:') ? `（旧报名 #${o.enrollmentId}）` : '') }) } })
        this.setData({ rows: (d.rows || []).map(r => ({ ...r, leaveId: plain(r.leaveId), stateText: STATES[r.state] || r.state,
          timeText: `${plain(r.startAt).slice(0,16)} — ${plain(r.endAt).slice(11,16)}` })), total: d.total || 0,
          pendingCount: d.pendingCount || 0, pendingBadge: Number(d.pendingCount)>99?'99+':Number(d.pendingCount)>0?String(d.pendingCount):'', options, children, childIndex: -1, courseIndex: -1, lessonIndex: -1,
          courses: [], lessons: [], lessonsLoading: false, historyId: '', history: [], historyLoading: false })
        this._lessonVersion = (this._lessonVersion || 0) + 1
        this._historyVersion = (this._historyVersion || 0) + 1
      } catch (e) { if (this.current() && v === this._version) this.setData({ error: e.message || '加载失败，请重试', rows: [], options: [], children: [] }) }
      finally { if (this.current() && v === this._version) this.setData({ loading: false }) }
    },
    openForm() { if (!teacher && this.current() && !this.data.loading && !this.data.submitting) {this.setData({ showForm: true, reason: '', childIndex: -1, courseIndex: -1, lessonIndex: -1, courses: [], lessons: [], lessonError: '' });if(this.accountId&&this.data.children.length===1)this.chooseChild({detail:{value:0}})} },
    closeForm() { if (!this.data.submitting) { this._lessonVersion = (this._lessonVersion || 0) + 1; this.setData({ showForm: false, lessonsLoading: false }) } },
    chooseChild(e) {
      if (!this.current() || this.data.submitting || this.data.loading) return
      const index = Number(e.detail.value), child = this.data.children[index]
      if (!child) return
      this._lessonVersion = (this._lessonVersion || 0) + 1
      this.setData({ childIndex: index, courses: this.data.options.filter(o => o.childKey === child.key).map(o => ({ ...o, label: `${o.courseClassName} · ${o.teacherName || '待配置教师'}` })), courseIndex: -1, lessonIndex: -1, lessons: [], lessonError: '', lessonsLoading: false })
    },
    async chooseCourse(e) {
      if (!this.current() || this.data.submitting || this.data.loading) return
      const index = Number(e.detail.value), course = this.data.courses[index]
      if (!course) return
      const v = this._lessonVersion = (this._lessonVersion || 0) + 1
      this.setData({ courseIndex: index, lessonIndex: -1, lessons: [], lessonsLoading: true, lessonError: '' })
      try {
        const r = await request({ url: base + '/lessons', data: { enrollmentId: course.enrollmentId } })
        if (this.current() && v === this._lessonVersion) this.setData({ lessons: (r.data || []).map(l => ({ ...l, label: `${l.classDate} ${l.startTime.slice(0,5)}–${l.endTime.slice(0,5)}` })) })
      } catch (e) { if (this.current() && v === this._lessonVersion) this.setData({ lessonError: e.message || '课次查询失败，请重试' }) }
      finally { if (this.current() && v === this._lessonVersion) this.setData({ lessonsLoading: false }) }
    },
    chooseLesson(e) { if (this.current() && !this.data.submitting) this.setData({ lessonIndex: Number(e.detail.value) }) },
    inputReason(e) { if (this.current() && !this.data.submitting) this.setData({ reason: e.detail.value }) },
    async submit() {
      if (teacher || !this.current() || this.data.submitting || this.data.loading || this.data.lessonsLoading) return
      const course = this.data.courses[this.data.courseIndex], lesson = this.data.lessons[this.data.lessonIndex], reason = this.data.reason.trim()
      if (!course || !lesson || !reason || reason.length > 255) { wx.showToast({ title: '请选择孩子、班课及课次，填写原因', icon: 'none' }); return }
      this.setData({ submitting: true })
      try {
        await request({ url: base, method: 'POST', data: { enrollmentId: course.enrollmentId, classDate: lesson.classDate, reason, version: lesson.version,...(this.accountId?{accountId:this.accountId}:{}) } })
        if (!this.current()) return
        this.setData({ showForm: false }); wx.showToast({ title: '请假申请已提交', icon: 'success' }); await this.load()
      } catch (e) { if (this.current()) wx.showToast({ title: e.message || '提交结果未确认，请保留表单重试', icon: 'none' }) }
      finally { if (this.current()) this.setData({ submitting: false }) }
    },
    async act(e) {
      if (!this.current() || this.data.loading || this.data.submitting) return
      const { id, action } = e.currentTarget.dataset, row = this.data.rows.find(r => plain(r.leaveId) === plain(id))
      if (!row || row.state !== 'PENDING' || !(teacher ? ['APPROVE','REJECT'] : ['WITHDRAW']).includes(action)) return
      this.setData({ submitting: true })
      const version = this._version
      try {
        const r = await new Promise(resolve => wx.showModal({ title: ACTIONS[action], content: action === 'REJECT' ? '' : `${row.studentName} · ${row.courseName}\n${row.timeText}\n${action === 'APPROVE' ? '通过后点名自动带入请假，后续由家长联系老师处理。' : '确认撤回本次请假申请？'}`,
          editable: action === 'REJECT', placeholderText: '请填写拒绝原因（最多255字）', success: resolve, fail: () => resolve({ confirm: false }) }))
        if (!r.confirm || !this.current() || version !== this._version) return
        const reason = action === 'REJECT' ? (r.content || '').trim() : action === 'APPROVE' ? '同意请假' : '家长撤回申请'
        if (!reason || reason.length > 255) { wx.showToast({ title: '请填写255字以内的原因', icon: 'none' }); return }
        await request({ url: `${base}/${row.leaveId}/${teacher ? 'review' : 'withdraw'}`, method: 'POST', data: { action, reason, version: row.version } })
        if (this.current()) { wx.showToast({ title: '处理完成', icon: 'success' }); await this.load() }
      } catch (e) { if (this.current()) { wx.showToast({ title: e.message || '结果未确认，请刷新核对', icon: 'none' }); await this.load() } }
      finally { if (this.current()) this.setData({ submitting: false }) }
    },
    async showHistory(e) {
      if (!this.current() || this.data.submitting) return
      const id = plain(e.currentTarget.dataset.id), v = this._historyVersion = (this._historyVersion || 0) + 1
      this.setData({ historyId: id, history: [], historyLoading: true })
      try {
        const r = await request({ url: base + '/' + encodeURIComponent(id) }), raw = (r.data || {}).history
        const history = typeof raw === 'string' ? JSON.parse(raw) : raw || []
        if (this.current() && v === this._historyVersion) this.setData({ history: history.map(h => ({ ...h, label: ACTIONS[h.action] || h.action, timeText: plain(h.time).replace('T',' ').slice(0,19) })) })
      } catch (e) { if (this.current() && v === this._historyVersion) wx.showToast({ title: e.message || '历史加载失败', icon: 'none' }) }
      finally { if (this.current() && v === this._historyVersion) this.setData({ historyLoading: false }) }
    },
    filter(e) { if (!this.data.loading && !this.data.submitting) { this.setData({ state: e.currentTarget.dataset.state, pageNum: 1 }); this.load() } },
    previous() { if (this.data.pageNum > 1 && !this.data.loading && !this.data.submitting) { this.setData({ pageNum: this.data.pageNum-1 }); this.load() } },
    next() { if (this.data.pageNum*30 < this.data.total && !this.data.loading && !this.data.submitting) { this.setData({ pageNum: this.data.pageNum+1 }); this.load() } }
  }
  return teacher?definition:childContext.page(definition,{route:'/pages/parent-leave/parent-leave'})
}
module.exports = { page }
