const { request, root, guard, decorateEnrollment, decorateSchedule, label } = require('../../utils/admin-enrollment')
Page({
  data: { id: '', enrollment: null, schedule: null, history: [], loading: false, cancelling: false, canCancel: false, transfers: [], transferOpen: false, transferRows: [], transferKeyword: '', transferQuery: '', transferPage: 1, transferTotal: 0, transferLoading: false, transferSaving: false, transferError: '', transferSelected: null, transferTargetId: '', transferReason: '', error: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { if (guard()) { this.setData({canCancel: (wx.getStorageSync('roles') || []).includes('admin')}); this.load() } },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await request({ url: `${root}/${this.data.id}` })
      const data = res.data
      this.setData({ transfers: data.transfers || [], enrollment: decorateEnrollment(data.enrollment), schedule: data.schedule ? decorateSchedule(data.schedule) : null,
        history: (data.history || []).map((h, index) => ({ ...h, key: index,
          date: String(h.classDate || '').slice(0, 10),
          signText: label(h.signStatus, { 0: '未到课', 1: '到课', 2: '录播', 3: '请假', 4: '试听到课', 5: '调课到课' }),
          reviewText: String(h.reviewStatus) === '1' ? '已复核' : '待复核' })) })
    } catch (e) { this.setData({ enrollment: null, error: e.message }); wx.showToast({ title: e.message, icon: 'none' }) }
    finally { this.setData({ loading: false }) }
  },
  async openTransfer() {
    if (!this.data.enrollment || this.data.enrollment.cancelled || this.data.enrollment.classDate) return
    this.setData({transferOpen: true, transferKeyword: '', transferQuery: '', transferSelected: null, transferTargetId: '', transferReason: '', transferRows: [], transferError: ''})
    await this.loadTransfer(1)
  },
  closeTransfer() { if (!this.data.transferSaving) { this._transferVersion=(this._transferVersion || 0)+1; this.setData({transferOpen: false, transferLoading: false}) } },
  transferKeyword(e) { this.setData({transferKeyword: e.detail.value}) },
  transferReason(e) { this.setData({transferReason: e.detail.value}) },
  searchTransfer() { if(this.data.transferSaving)return; this.setData({transferQuery: this.data.transferKeyword, transferSelected: null, transferTargetId: ''}); return this.loadTransfer(1) },
  moreTransfer() { return this.loadTransfer(this.data.transferPage+1) },
  async loadTransfer(page) {
    const version=this._transferVersion=(this._transferVersion || 0)+1
    this.setData({transferLoading: true, transferError: ''})
    try {
      const res=await request({url: `${root}/${this.data.id}/transfer-options`,data:{keyword:this.data.transferQuery,page}})
      if(version!==this._transferVersion || !this.data.transferOpen)return
      const rows=(res.data.rows || []).map(decorateSchedule)
      this.setData({transferRows: page===1 ? rows : this.data.transferRows.concat(rows), transferPage:page, transferTotal:res.data.total, transfers:res.data.history || []})
    } catch(e) {if(version===this._transferVersion)this.setData({transferError:e.message || '课程加载失败'})}
    finally {if(version===this._transferVersion)this.setData({transferLoading:false})}
  },
  chooseTransfer(e) { if(this.data.transferSaving)return; const selected=this.data.transferRows.find(r=>String(r.scheduleId)===String(e.currentTarget.dataset.id)); this.setData({transferSelected:selected || null, transferTargetId:selected ? selected.scheduleId : ''}) },
  async submitTransfer() {
    if(this.data.transferSaving)return
    const target=this.data.transferSelected,reason=this.data.transferReason.trim(),enrollment=this.data.enrollment
    if(!target || !reason) {wx.showToast({title:'请选择课程并填写换课原因',icon:'none'});return}
    this.setData({transferSaving:true})
    try {
      const confirmed=await new Promise(resolve=>wx.showModal({title:'确认学生换课',content:`${enrollment.studentName}换至${target.course || target.courseClassName}。原缴费、收据及历史考勤保留，差价需另行处理。`,success:resolve,fail:()=>resolve({confirm:false})}))
      if(!confirmed.confirm)return
      await request({url:`${root}/${this.data.id}/transfer`,method:'PUT',data:{fromScheduleId:enrollment.scheduleId,targetScheduleId:target.scheduleId,reason}})
      this.setData({transferOpen:false});wx.showToast({title:'换课成功',icon:'success'});await this.load()
    } catch(e) {this.setData({transferError:e.message || '换课失败'});wx.showToast({title:e.message || '换课失败',icon:'none'})}
    finally {this.setData({transferSaving:false})}
  },
  openClass() { wx.navigateTo({ url: `/pages/admin-enrollment-list/admin-enrollment-list?scheduleId=${this.data.enrollment.scheduleId}` }) },
  call() { const phone = this.data.enrollment.contactPhone; if (phone) wx.makePhoneCall({ phoneNumber: phone }) },
  cancel() {
    const item = this.data.enrollment
    if (!item || item.cancelled || this.data.cancelling) return
    wx.showModal({ title: '确认取消报名',
      content: `${item.studentName}：已复核到课 ${item.attendedLessonCount} 次，待复核 ${item.pendingLessonCount} 次。取消后保留报名和历史考勤，未使用考勤作废，并释放一个名额。取消不代表退款，请另行核对退款。`,
      confirmText: '取消报名', cancelText: '保留报名',
      success: async res => {
        if (!res.confirm) return
        this.setData({ cancelling: true })
        try {
          const result = await request({ url: `${root}/${this.data.id}/cancel`, method: 'PUT' })
          wx.showToast({ title: result.msg || '取消成功', icon: 'none' })
          await this.load()
        } catch (e) { wx.showToast({ title: e.message, icon: 'none' }) }
        finally { this.setData({ cancelling: false }) }
      }
    })
  }
})
