const { request, root, guard, decorateEnrollment, decorateSchedule, label } = require('../../utils/admin-enrollment')
Page({
  data: { id: '', enrollment: null, schedule: null, history: [], loading: false, cancelling: false, error: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { if (guard()) this.load() },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await request({ url: `${root}/${this.data.id}` })
      const data = res.data
      this.setData({ enrollment: decorateEnrollment(data.enrollment), schedule: data.schedule ? decorateSchedule(data.schedule) : null,
        history: (data.history || []).map((h, index) => ({ ...h, key: index,
          date: String(h.classDate || '').slice(0, 10),
          signText: label(h.signStatus, { 0: '未到课', 1: '到课', 2: '录播', 3: '请假', 4: '试听到课', 5: '调课到课' }),
          reviewText: String(h.reviewStatus) === '1' ? '已复核' : '待复核' })) })
    } catch (e) { this.setData({ enrollment: null, error: e.message }); wx.showToast({ title: e.message, icon: 'none' }) }
    finally { this.setData({ loading: false }) }
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
