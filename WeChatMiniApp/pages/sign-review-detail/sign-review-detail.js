const s = require('../../utils/sign-review')
const { uploadImage } = require('../../utils/attendance-workflow')
const labels = { '1': '到课', '2': '录播', '3': '请假', '4': '试听到课', '5': '调课到课', '6': '未到课' }
const displayName = value => { const name = String(value || '').trim(); return /^(mp_|wxid_)|[a-f0-9]{24,}|^\d{7,}$/i.test(name) || name.length > 24 ? '' : name }
const displayTime = value => String(value || '').replace('T', ' ').slice(0, 16)
const sources = { '1': '报名', '2': '试听', '3': '调课' }
Page({
  data: { scheduleId: '', classDate: '', course: '', room: '', teacherName: '', timeSlot: '', submittedBy: '', submittedTime: '', rows: [], review: {}, stats: null, submissionVersion: '', loading: false, submitting: false, canReview: false, reviewReady: false, canCorrect: false, reason: '', pendingImages: [], pendingImageUrls: [], uploading: false, canInitialReview: false, canFinalReview: false, stageText: '', pendingCount: 0, error: '' },
  onLoad(query = {}) { this.setData({ scheduleId: String(query.scheduleId || ''), classDate: query.classDate || '' }); this._visit = 0 },
  onShow() { this._active = true; const token = wx.getStorageSync('token'); if (this._mediaOperation && this._mediaOperation.token === token) return; if (this._previewToken === token) { this._previewToken = null; return } this._previewToken = null; this._mediaOperation = null; this.setData({ uploading: false }); this._visit++; this._token = wx.getStorageSync('token'); this.setData({ submitting: false }); return this.loadDetail() },
  onHide() { this._active = false; if (!this._mediaOperation && !this._previewToken) this._visit++ },
  onUnload() { this._unloaded = true; this._mediaOperation = null; this.onHide() },
  current(visit) { return this._active && visit === this._visit && this._token === wx.getStorageSync('token') },
  onPullDownRefresh() { return this.loadDetail().finally(() => wx.stopPullDownRefresh()) },
  async loadDetail() {
    if (this.data.submitting || this.data.uploading) return
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
        return { ...r, signInId: String(r.signInId), options, pendingStatus: '', pendingLabel: '', correctionReason: '', optionIndex: Math.max(0, options.findIndex(o => o.key === r.signStatus)), history: history.filter(h => h.action === 'REJECT' || (h.action === 'CORRECT' && labels[h.beforeStatus] && labels[h.afterStatus])).map((h, index) => ({ ...h, index, time: displayTime(h.time), text: h.action === 'REJECT' ? '标记审核异常' : (labels[h.beforeStatus] || h.beforeStatus) + ' → ' + (labels[h.afterStatus] || h.afterStatus) })), sourceText: sources[r.sourceType] || '报名', statusText: labels[r.signStatus] || '待核查' }
      }), first = rows[0] || {}
      const review = { ...(d.review || {}), review1Images: s.images((d.review || {}).review1Images), review2Images: s.images((d.review || {}).review2Images), history: ((d.review || {}).history || []).map((h,index)=>({...h,index,images:Array.isArray(h.images)?h.images.map(require('../../utils/public-file-url')):s.images(h.images)})) }
      review.review1AdminName = displayName(review.review1AdminName)
      review.review2AdminName = displayName(review.review2AdminName)
      review.review1Time = displayTime(review.review1Time)
      review.review2Time = displayTime(review.review2Time)
      const canInitialReview = (d.canInitialReview === true || review.canInitialReview === true) && s.canReviewStage('initial'), canFinalReview = (d.canFinalReview === true || review.canFinalReview === true) && s.canReviewStage('final')
      const reviewReady = rows.length > 0 && !!d.submissionVersion && (canInitialReview || canFinalReview)
      this.setData({ rows, review, canInitialReview, canFinalReview, stageText: review.reviewStage === 'PENDING_FINAL' ? '教务已提交截图，待管理员终审' : d.stageText || review.stageText || '', pendingImages: [], stats: d.stats || null, submissionVersion: d.submissionVersion || '',
        teacherName: displayName(d.teacherName || first.teacherName), timeSlot: d.timeSlot || first.timeSlot || '', submittedBy: d.submittedBy || '', submittedTime: displayTime(d.submittedTime),
        course: first.courseClassName || [first.gradeName, first.subjectName].filter(Boolean).join(''), room: [first.campusName, first.classroomName].filter(Boolean).join(' · '),
        canCorrect: rows.length > 0 && (d.canCorrect === true || review.canCorrect === true) && !!d.submissionVersion,
        canReview: reviewReady, reviewReady })
      if (rows.length && !d.submissionVersion) this.setData({ error: '后端尚未更新，请重新打包并启动后端后再审核' })
    } catch (error) { if (current()) this.setData({ error: error.message, canReview: false, rows: [] }) }
    finally { if (current()) this.setData({ loading: false }) }
  },
  previewImage(e) { const { urls, current } = e.currentTarget.dataset; if (urls && urls.length) { this._previewToken = wx.getStorageSync('token'); wx.previewImage({ urls, current, fail: () => { this._previewToken = null } }) } },
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
  openReturnDialog() {
    const visit = this._visit
    if (!this.current(visit) || !this.data.canReview || this.data.loading || this.data.submitting || this.data.uploading || this._returnDialogOpen) return
    this._returnDialogOpen = true
    wx.showModal({
      title: '退回点名表', editable: true, placeholderText: '请填写退回原因（必填）', confirmText: '确认退回',
      success: result => {
        if (!result.confirm || !this.current(visit)) return
        this.setData({ reason: String(result.content || '').trim() })
        this.rejectReview()
      },
      complete: () => { this._returnDialogOpen = false }
    })
  },
  rejectReview() {
    if (!this.data.rows.length || this.data.review.reviewStatus === '1') return
    if (!this.data.canReview) return
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
    if (!this.current(visit) || this.data.loading || this.data.submitting || this.data.uploading || !this.data.submissionVersion) return
    reason = String(reason || '').trim()
    if (action === 'reject' && (!reason || reason.length > 500)) { this.setData({ error: '请填写1至500字的原因' }); return }
    const body = { scheduleId: this.data.scheduleId, classDate: this.data.classDate, submissionVersion: this.data.submissionVersion, reviewVersion: this.data.review.reviewVersion || 0, ...(action === 'reject' ? { action: 'RETURN', reason } : {}), ...extra }
    this.setData({ submitting: true, error: '' })
    let refresh = false
    try {
      if (!await s.authorize(() => this.current(visit))) return
      const prompt = action === 'reject' ? '退回本课次？教师更正后需重新进行教务初审和业务管理员终审。' : '保存全部' + body.corrections.length + '位学员的出勤更正？每人的原始点名和更正原因都会保留；任一条不符合规则，整批均不保存。'
      if (!await s.confirm(prompt) || !this.current(visit) || body.submissionVersion !== this.data.submissionVersion) return
      refresh = true
      await s.request({ url: s.base + '/' + (action === 'reject' ? 'review' : action), method: 'POST', data: body })
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
  chooseImages() {
    if (!this._active || !this.data.canInitialReview || this.data.loading || this.data.submitting || this.data.uploading || this.data.pendingImages.length >= 9) return
    const operation = { token: wx.getStorageSync('token'), visit: this._visit }
    this._mediaOperation = operation
    const current = () => !this._unloaded && this._mediaOperation === operation && operation.token === wx.getStorageSync('token') && operation.visit === this._visit
    const finish = () => {
      if (this._mediaOperation !== operation) return
      this._mediaOperation = null
      if (!this._unloaded) this.setData({ uploading: false })
    }
    this.setData({ uploading: true, error: '' })
    const fail = error => {
      if (current() && !/cancel/i.test(error.errMsg || '')) this.setData({ error: '无法选择图片，请检查相册／相机权限后重试' })
      finish()
    }
    try {
      wx.chooseMedia({ count: 9 - this.data.pendingImages.length, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'], fail,
        success: async response => {
          try {
            const files = response.tempFiles || []
            for (const file of files) {
              if (!current()) return
              const image = await uploadImage(file.tempFilePath)
              if (!current()) return
              const pendingImages = this.data.pendingImages.concat(image)
              this.setData({ pendingImages, pendingImageUrls: pendingImages.map(require('../../utils/public-file-url')) })
            }
          } catch (error) { if (current()) this.setData({ error: error.message || '上传失败，请重试' }) }
          finally { finish() }
        }
      })
    } catch (error) { fail(error) }
  },
  removeImage(e) { if(!this.data.submitting&&!this.data.uploading){const pendingImages=this.data.pendingImages.filter((_,i)=>i!==Number(e.currentTarget.dataset.index));this.setData({pendingImages,pendingImageUrls:pendingImages.map(require('../../utils/public-file-url'))})} },
  async confirmReview() {
    const visit = this._visit
    if (!this.current(visit) || !this.data.canReview || this.data.pendingCount || this.data.loading || this.data.submitting) return
    if(this.data.uploading)return
    if(this.data.canInitialReview && !this.data.pendingImages.length){this.setData({error:'初审必须上传至少一张监控截图'});return}
    const body = { scheduleId: this.data.scheduleId, classDate: this.data.classDate, submissionVersion: this.data.submissionVersion, reviewVersion:this.data.review.reviewVersion||0, action:'APPROVE', images: this.data.canInitialReview ? this.data.pendingImages.slice() : [] }
    this.setData({ submitting: true, error: '' })
    let refresh = false
    try {
      if (!await s.authorize(() => this.current(visit))) return
      if (!s.canReviewStage(this.data.canInitialReview ? 'initial' : 'final')) throw new Error(this.data.canInitialReview ? '请切换到教务身份初审' : '请切换到管理员身份终审')
      const stats = this.data.stats
      if (!stats) throw new Error('统计尚未就绪，请刷新后核对')
      if (!await s.confirm(this.data.classDate + ' · ' + this.data.teacherName + '：名单 ' + stats.totalCount + ' 人，实到 ' + stats.actualCount + ' 人。确认当前阶段审核？只有终审通过才进入正式统计和课酬。') || !this.current(visit) || body.submissionVersion !== this.data.submissionVersion) return
      refresh = true
      if (!s.canReviewStage(this.data.canInitialReview ? 'initial' : 'final')) throw new Error('当前身份已切换，请重新进入审核页面')
      const res = await s.request({ url: s.base + '/review', method: 'POST', data: body })
      if (!this.current(visit)) return
      this.setData({ canReview: false }); wx.showToast({ title: this.data.canInitialReview ? '初审通过，待终审' : '终审通过', icon: 'success' })
    } catch (error) { if (this.current(visit)) this.setData({ error: error.uncertain ? '确认结果尚未确定，请刷新核对，勿重复操作' : error.message, canReview: false }) }
    finally {
      if (this.current(visit)) {
        this.setData({ submitting: false })
        if (refresh) { const message = this.data.error; await this.loadDetail(); if (message && this.current(visit)) this.setData({ error: message }) }
      }
    }
  }
})
