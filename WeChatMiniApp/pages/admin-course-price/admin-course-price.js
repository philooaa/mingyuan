const { request } = require('../../utils/request')

function money(value) {
  const n = Number(value || 0)
  return Number.isFinite(n) ? n.toFixed(2) : '0.00'
}

Page({
  data: {
    loading: false,
    rules: [],
    grades: [],
    subjects: [],
    // 年级规则改价弹层
    editVisible: false,
    editForm: { gradeName: '', unitPrice: '', applyCourses: true, courseCount: 0 },
    // 按年级 + 学科批量改价
    batchGradeIndex: 0,
    batchSubjectIndex: 0,
    batchPrice: '',
    batchPreview: null,
    batchPreviewing: false,
    saving: false
  },

  onLoad() {
    this.loadAll()
  },

  onShow() {
    if (this._loadedOnce) this.loadRules()
    this._loadedOnce = true
  },

  onPullDownRefresh() {
    this.loadAll().finally(() => wx.stopPullDownRefresh())
  },

  async loadAll() {
    this.setData({ loading: true })
    try {
      const [ruleRes, dimRes] = await Promise.all([
        request({ url: '/system/coursePrice/rules' }),
        request({ url: '/system/coursePrice/dims' })
      ])
      const dims = dimRes.data || {}
      this.setData({
        grades: ['全部年级', ...(dims.grades || [])],
        subjects: ['全部学科', ...(dims.subjects || [])],
        rules: (ruleRes.data || []).map(rule => ({
          ...rule,
          unitPriceText: money(rule.unitPrice)
        }))
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadRules() {
    try {
      const res = await request({ url: '/system/coursePrice/rules' })
      this.setData({
        rules: (res.data || []).map(rule => ({ ...rule, unitPriceText: money(rule.unitPrice) }))
      })
    } catch (_) { /* 下拉刷新失败时保留旧数据 */ }
  },

  openEdit(e) {
    const gradeName = e.currentTarget.dataset.grade
    const rule = this.data.rules.find(item => item.gradeName === gradeName)
    if (!rule) return
    this.setData({
      editVisible: true,
      editForm: {
        gradeName,
        unitPrice: money(rule.unitPrice),
        applyCourses: true,
        courseCount: rule.courseCount || 0
      }
    })
  },

  closeEdit() {
    this.setData({ editVisible: false })
  },

  onEditField(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ [`editForm.${field}`]: e.detail.value })
  },

  onEditToggle(e) {
    this.setData({ 'editForm.applyCourses': e.detail.value })
  },

  async submitEdit() {
    if (this.data.saving) return
    const { gradeName, unitPrice, applyCourses } = this.data.editForm
    const price = Number(unitPrice)
    if (!Number.isFinite(price) || price <= 0) {
      wx.showToast({ title: '请填写大于 0 的单价', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      const res = await request({
        url: '/system/coursePrice/rule',
        method: 'PUT',
        data: { gradeName, unitPrice: price, applyCourses }
      })
      const updated = Number((res.data || {}).updated || 0)
      wx.showToast({
        title: applyCourses && updated ? `已更新 ${updated} 门课程` : '规则已更新',
        icon: 'none'
      })
      this.setData({ editVisible: false })
      this.loadRules()
    } catch (e) {
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  onBatchGrade(e) {
    this.setData({ batchGradeIndex: Number(e.detail.value), batchPreview: null })
  },

  onBatchSubject(e) {
    this.setData({ batchSubjectIndex: Number(e.detail.value), batchPreview: null })
  },

  onBatchPrice(e) {
    this.setData({ batchPrice: e.detail.value, batchPreview: null })
  },

  batchFilter() {
    const grade = this.data.grades[this.data.batchGradeIndex]
    const subject = this.data.subjects[this.data.batchSubjectIndex]
    return {
      gradeName: grade === '全部年级' ? '' : grade,
      subjectName: subject === '全部学科' ? '' : subject
    }
  },

  async previewBatch() {
    if (this.data.batchPreviewing) return
    const filter = this.batchFilter()
    if (!filter.gradeName) {
      wx.showToast({ title: '请先选择年级', icon: 'none' })
      return
    }
    this.setData({ batchPreviewing: true })
    try {
      const res = await request({
        url: '/system/coursePrice/courses',
        data: { ...filter, pageNum: 1, pageSize: 5 }
      })
      this.setData({
        batchPreview: {
          total: res.total || 0,
          rows: (res.rows || []).map(row => ({
            scheduleId: row.scheduleId,
            courseClassName: row.courseClassName,
            lessonCount: row.lessonCount,
            unitPrice: money(row.unitPrice),
            tuitionPrice: money(row.tuitionPrice)
          }))
        }
      })
      if (!res.total) wx.showToast({ title: '该条件下没有课程', icon: 'none' })
    } catch (e) {
      wx.showToast({ title: e.message || '预览失败', icon: 'none' })
    } finally {
      this.setData({ batchPreviewing: false })
    }
  },

  async submitBatch() {
    if (this.data.saving) return
    const filter = this.batchFilter()
    const price = Number(this.data.batchPrice)
    if (!filter.gradeName) {
      wx.showToast({ title: '请先选择年级', icon: 'none' })
      return
    }
    if (!Number.isFinite(price) || price <= 0) {
      wx.showToast({ title: '请填写大于 0 的单价', icon: 'none' })
      return
    }
    const scope = filter.subjectName ? `${filter.gradeName} · ${filter.subjectName}` : `${filter.gradeName} 全部学科`
    const confirm = await new Promise(resolve => wx.showModal({
      title: '确认批量改价',
      content: `将 ${scope} 的课程单价统一改为 ${price.toFixed(2)} 元/节，总价按课次数自动重算。`,
      success: res => resolve(res.confirm),
      fail: () => resolve(false)
    }))
    if (!confirm) return
    this.setData({ saving: true })
    try {
      const res = await request({
        url: '/system/coursePrice/batch',
        method: 'PUT',
        data: { ...filter, unitPrice: price, syncRule: false }
      })
      wx.showToast({ title: `已更新 ${(res.data || {}).updated || 0} 门课程`, icon: 'none' })
      this.setData({ batchPrice: '', batchPreview: null })
      this.loadRules()
    } catch (e) {
      wx.showToast({ title: e.message || '改价失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  noop() {}
})
