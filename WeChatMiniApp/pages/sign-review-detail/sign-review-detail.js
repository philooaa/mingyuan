const s = require('../../utils/sign-review')
const labels = { '1': '到课', '2': '录播', '3': '请假', '4': '试听到课', '5': '调课到课', '6': '未到课' }
const sources = { '1': '报名', '2': '试听', '3': '调课' }
Page({
  data: { scheduleId: '', classDate: '', course: '', room: '', teacherName: '', timeSlot: '', submittedBy: '', submittedTime: '', rows: [], review: {}, stats: null, submissionVersion: '', loading: false, submitting: false, canReview: false, reviewReady: false, canCorrect: false, reason: '', pendingCount: 0, error: '' },
  onLoad(query = {}) { this.setData({ scheduleId: String(query.scheduleId || ''), classDate: query.classDate || '' }); this._visit = 0 },
  onShow() { this._active = true; this._visit++; this._token = wx.getStorageSync('token'); this.setData({ submitting: false }); return this.loadDetail() },
  onHide() { this._active = false; this._visit++ },
  onUnload() { this.onHide() },
  current(visit) { return this._active && visit === this._visit && this._token === wx.getStorageSync('token') },
  onPullDownRefresh() { return this.loadDetail().finally(() => wx.stopPullDownRefresh()) },
  async loadDetail() {
    if (this.data.submitting) return
    const visit = this._visit, sequence = this._sequence = (this._sequence || 0) + 1
    const current = () => this.current(visit) && sequence === this._sequence
    this.setData({ loading: true, canReview: false, reviewReady: false, canCorrect: false, pendingCount: 0, reason: '', error: '', rows: [], review: {}, stats: null, submissionVersion: '', teacherName: '', timeSlot: '', submittedBy: '', submittedTime: '', course: '', room: '' })
    try {
      if (!/^[1-9]\d*$/.test(this.data.scheduleId) || !/^\d{4}-\d{2}-\d{2}$/.test(this.data.classDate)) throw new Error('课次标识或日期无效，请从出勤审核列表进入')
      if (!await s.authorize(current)) return
      const res = await s.request({ url: s.base + '/detail', data: { scheduleId: this.data.scheduleId, classDate: this.data.classDate } })
      if (!current()) return
      const d = res.data || {}, rows = (d.details || []).map(r => {
        const history = typeof r.reviewHistory === 'string' ? JSON.parse(r.reviewHistory) : r.reviewHistory || []
        const options = (r.sourceType === '2' ? ['4', '3', '6'] : r.sourceType === '3' ? ['5', '3', '6'] : ['1', '2', '3', '6']).map(key => ({ key, label: labels[key] }))
        return { ...r, signInId: String(r.signInId), options, pendingStatus: '', pendingLabel: '', correctionReason: '', optionIndex: Math.max(0, options.findIndex(o => o.key === r.signStatus)), history: history.map((h, index) => ({ ...h, index, text: h.action === 'REJECT' ? '标记审核异常' : (labels[h.beforeStatus] || h.beforeStatus) + ' → ' + (labels[h.afterStatus] || h.afterStatus) })), sourceText: sources[r.sourceType] || '报名', statusText: labels[r.signStatus] || '待核查' }
      }), first = rows[0] || {}
      const review = { ...(d.review || {}), review1Images: s.images((d.review || {}).review1Images), review2Images: s.images((d.review || {}).review2Images) }
      const reviewReady = rows.length > 0 && review.reviewStatus !== '1' && !!d.submissionVersion && (review.reviewStatus !== '2' || rows.some(r => r.history.length && r.history[r.history.length - 1].action === 'CORRECT'))
      this.setData({ rows, review, stats: d.stats || null, submissionVersion: d.submissionVersion || '',
        teacherName: d.teacherName || first.teacherName || '', timeSlot: d.timeSlot || first.timeSlot || '', submittedBy: d.submittedBy || '', submittedTime: d.submittedTime || '',
        course: first.courseClassName || [first.gradeName, first.subjectName].filter(Boolean).join(''), room: [first.campusName, first.classroomName].filter(Boolean).join(' · '),
        canCorrect: rows.length > 0 && review.reviewStatus === '2' && !!d.submissionVersion,
        canReview: reviewReady, reviewReady })
      if (rows.length && !d.submissionVersion) this.setData({ error: '后端尚未更新，请重新打包并启动后端后再审核' })
    } catch (error) { if (current()) this.setData({ error: error.message, canReview: false, rows: [] }) }
    finally { if (current()) this.setData({ loading: false }) }
  },
  previewImage(e) { const { urls, current } = e.currentTarget.dataset; if (urls && urls.length) wx.previewImage({ urls, current }) },
  onReason(e) { this.setData({ reason: e.detail.value }) },
  onCorrectionReason(e) {
    if (!this.data.canCorrect || this.data.loading || this.data.submitting) return
    const id = String(e.currentTarget.dataset.id)
    this.setData({ rows: this.data.rows.map(r => r.signInId === id && r.pendingStatus ? { ...r, correctionReason: e.detail.value } : r) })
  },
  chooseCorrection(e) {
    if (!this.data.canCorrect || this.data.loading || this.data.submitting) return
    const row = this.data.rows.find(r => r.signInId === String(e.currentTarget.dataset.id))
    const option = row && row.options[Number(e.detail.value)]
    if (!option) return
    const rows = this.data.rows.map(r => r.signInId === row.signInId ? { ...r, optionIndex: Number(e.detail.value), pendingStatus: option.key === r.signStatus ? '' : option.key, pendingLabel: option.label, correctionReason: option.key === r.signStatus ? '' : r.correctionReason } : r)
    const pendingCount = rows.filter(r => r.pendingStatus).length
    this.setData({ rows, pendingCount, canReview: this.data.reviewReady && pendingCount === 0 })
  },
  rejectReview() {
    if (!this.data.rows.length || this.data.review.reviewStatus === '1') return
    return this.submitChange('reject', this.data.reason)
  },
  correctAttendance() {
    if (!this.data.canCorrect || !this.data.pendingCount || this.data.loading || this.data.submitting) return
    const rows = this.data.rows.filter(r => r.pendingStatus)
    const invalid = rows.find(r => !String(r.correctionReason || '').trim() || String(r.correctionReason).trim().length > 500)
    if (invalid) { this.setData({ error: '请为' + invalid.studentName + '填写1至500字的更正原因' }); return }
    return this.submitChange('correct', '', { corrections: rows.map(r => ({ signInId: r.signInId, signStatus: r.pendingStatus, reason: String(r.correctionReason).trim() })) })
  },
  async submitChange(action, reason, extra = {}) {
    const visit = this._visit
    if (!this.current(visit) || this.data.loading || this.data.submitting || !this.data.submissionVersion) return
    reason = String(reason || '').trim()
    if (action === 'reject' && (!reason || reason.length > 500)) { this.setData({ error: '请填写1至500字的原因' }); return }
    const body = { scheduleId: this.data.scheduleId, classDate: this.data.classDate, submissionVersion: this.data.submissionVersion, ...(action === 'reject' ? { reason } : {}), ...extra }
    this.setData({ submitting: true, error: '' })
    let refresh = false
    try {
      if (!await s.authorize(() => this.current(visit))) return
      const prompt = action === 'reject' ? '标记本课次审核异常？教师会看到异常原因，之后由教务或管理员更正出勤。' : '保存全部' + body.corrections.length + '位学员的出勤更正？每人的原始点名和更正原因都会保留；任一条不符合规则，整批均不保存。'
      if (!await s.confirm(prompt) || !this.current(visit) || body.submissionVersion !== this.data.submissionVersion) return
      refresh = true
      await s.request({ url: s.base + '/' + action, method: 'POST', data: body })
      if (this.current(visit)) wx.showToast({ title: action === 'reject' ? '已标记异常' : '已保存更正', icon: 'success' })
    } catch (error) {
      if (action === 'correct' && !error.uncertain) refresh = false
      if (this.current(visit)) this.setData({ error: error.uncertain ? '操作结果尚未确定，请刷新核对' : error.message })
    }
    finally {
      if (this.current(visit)) {
        this.setData({ submitting: false })
        if (refresh) { const message = this.data.error; await this.loadDetail(); if (message && this.current(visit)) this.setData({ error: message }) }
      }
    }
  },
  async confirmReview() {
    const visit = this._visit
    if (!this.current(visit) || !this.data.canReview || this.data.pendingCount || this.data.loading || this.data.submitting) return
    const body = { scheduleId: this.data.scheduleId, classDate: this.data.classDate, submissionVersion: this.data.submissionVersion, images: [] }
    this.setData({ submitting: true, error: '' })
    let refresh = false
    try {
      if (!await s.authorize(() => this.current(visit))) return
      const stats = this.data.stats
      if (!stats) throw new Error('统计尚未就绪，请刷新后核对')
      if (!await s.confirm(this.data.classDate + ' · ' + this.data.teacherName + '：名单 ' + stats.totalCount + ' 人，实到 ' + stats.actualCount + ' 人。确认后本课次点名锁定，是否确认？') || !this.current(visit) || body.submissionVersion !== this.data.submissionVersion) return
      refresh = true
      const res = await s.request({ url: s.base + '/review', method: 'POST', data: body })
      if (!this.current(visit)) return
      if (res.reviewStatus !== '1') throw new Error('后台未完成单人确认，请刷新核对后端版本')
      this.setData({ canReview: false }); wx.showToast({ title: '教务审核已确认', icon: 'success' })
    } catch (error) { if (this.current(visit)) this.setData({ error: error.uncertain ? '确认结果尚未确定，请刷新核对，勿重复操作' : error.message, canReview: false }) }
    finally {
      if (this.current(visit)) {
        this.setData({ submitting: false })
        if (refresh) { const message = this.data.error; await this.loadDetail(); if (message && this.current(visit)) this.setData({ error: message }) }
      }
    }
  }
})
