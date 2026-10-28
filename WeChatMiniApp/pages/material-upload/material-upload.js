const shop = require('../../utils/material-shop')
const blank = role => ({ title: '', subtitle: '', subjectCode: '', gradeCode: '', materialType: '', textbookVersion: '', deliveryType: role === 'teacher' ? 'DIGITAL' : 'PHYSICAL', price: '0.00', unit: '份', stockQuantity: 0, purchaseLimit: role === 'teacher' ? 1 : 99, shippingFee: '0.00', shipFrom: '', dispatchDays: 0, sortOrder: 0, intro: '', detailText: '', coverUrl: '', images: [], shelfStatus: '2', filePath: '', fileName: '', fileSize: 0 })
Page({
  data: { role: '', loading: false, submitting: false, error: '', subjects: [], grades: [], materialTypes: [], subjectIndex: -1, gradeIndex: -1, typeIndex: -1, list: [], total: 0, pageNum: 1, showForm: false, form: blank(''), file: null, stockDelta: '', stockRemark: '' },
  onLoad() { this._sequence = 0; this._removeOperation = 0 },
  async onShow() {
    this._hidden = false
    if (this._removing) { this._removing = false; this.setData({ submitting: false }) }
    const sequence = ++this._sequence
    this.setData({ loading: true, error: '', role: '', list: [] }); this._role = ''
    try {
      const info = await shop.request({ url: '/getInfo' })
      if (sequence !== this._sequence || this._hidden) return
      const roles = info.roles || []
      this._role = roles.includes('admin') ? 'admin' : roles.includes('teacher') ? 'teacher' : ''
      if (!this._role) throw new Error('当前账号没有资料管理权限')
      this.setData({ role: this._role })
      await this.loadDict()
      if (sequence === this._sequence && !this._hidden) await this.loadList()
    } catch (error) { if (sequence === this._sequence && !this._hidden) this.setData({ error: error.message, loading: false, list: [] }) }
  },
  onHide() { this._hidden = true; this._sequence++; this._removeOperation++ },
  onUnload() { this.onHide() },
  onPullDownRefresh() { this.onShow().finally(() => wx.stopPullDownRefresh()) },
  onReachBottom() { if (!this.data.loading && this.data.list.length < this.data.total) this.loadList(true) },
  async loadDict() {
    const response = await shop.request({ url: '/miniapp/material/dict' })
    if (!this._hidden) this.setData({ subjects: response.data.subjects || [], grades: response.data.grades || [], materialTypes: response.data.materialTypes || [] })
  },
  async loadList(append = false) {
    if (!['admin', 'teacher'].includes(this._role)) return
    const sequence = ++this._sequence, pageNum = append ? this.data.pageNum + 1 : 1
    this.setData({ loading: true, error: '', ...(append ? {} : { list: [] }) })
    try {
      const response = await shop.request({ url: this._role === 'admin' ? '/system/material/list' : '/miniapp/teacher/material/list', data: { pageNum, pageSize: 20 } })
      if (sequence !== this._sequence || this._hidden) return
      const result = this._role === 'admin' ? response : response.data
      this.setData({ list: (append ? this.data.list : []).concat((result.rows || []).map(item => ({ ...item, displayCover: shop.imageUrl(item.coverUrl), shelfText: { '0': '已下架', '1': '已上架', '2': '草稿' }[item.shelfStatus] }))), total: Number(result.total || 0), pageNum })
    } catch (error) { if (sequence === this._sequence && !this._hidden) this.setData({ error: error.message }) }
    finally { if (sequence === this._sequence && !this._hidden) this.setData({ loading: false }) }
  },
  openForm() { if (this._role && !this.data.submitting) this.setForm(blank(this._role)) },
  setForm(form) {
    this._formDirty = false
    form = { ...form, coverUrl: shop.imageUrl(form.coverUrl), images: (form.images || []).map(image => ({ ...image, imageUrl: shop.imageUrl(image.imageUrl) })) }
    this.setData({ showForm: true, form, file: null, stockDelta: '', stockRemark: '', subjectIndex: this.data.subjects.findIndex(item => item.dictValue === form.subjectCode), gradeIndex: this.data.grades.findIndex(item => item.dictValue === form.gradeCode), typeIndex: this.data.materialTypes.findIndex(item => item.dictValue === form.materialType) }, () => { if (wx.pageScrollTo) wx.pageScrollTo({ selector: '.editor', duration: 200 }) })
  },
  async edit(e) {
    if (this.data.submitting || !this._role) return
    const item = this.data.list.find(row => row.materialId === e.currentTarget.dataset.id)
    if (!item || (this._role === 'teacher' && item.shelfStatus !== '2')) return
    try {
      const form = this._role === 'admin' ? (await shop.request({ url: `/system/material/${item.materialId}` })).data : item
      this.setForm({ ...blank(this._role), ...form })
    } catch (error) { shop.message(error) }
  },
  closeForm() { if (!this.data.submitting) this.setData({ showForm: false }) },
  input(e) { this._formDirty = true; this.setData({ [`form.${e.currentTarget.dataset.field}`]: e.detail.value }) },
  select(e) {
    const kind = e.currentTarget.dataset.kind
    const index = Number(e.detail.value), dictionaries = { subject: 'subjects', grade: 'grades', type: 'materialTypes' }, fields = { subject: 'subjectCode', grade: 'gradeCode', type: 'materialType' }
    if (!dictionaries[kind]) return
    this._formDirty = true
    this.setData({ [`${kind}Index`]: index, [`form.${fields[kind]}`]: this.data[dictionaries[kind]][index].dictValue })
  },
  deliveryChange(e) {
    if (this._role !== 'admin') return
    this._formDirty = true
    const deliveryType = Number(e.detail.value) ? 'DIGITAL' : 'PHYSICAL'
    this.setData({ 'form.deliveryType': deliveryType, ...(deliveryType === 'DIGITAL' ? { 'form.purchaseLimit': 1, 'form.shippingFee': '0.00' } : {}) })
  },
  chooseFile() {
    if (!this._role || this.data.submitting) return
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf'], success: response => {
      const file = (response.tempFiles || [])[0]
      if (!file) return
      if (!/\.pdf$/i.test(file.name) || file.size > 10 * 1024 * 1024) { shop.message(new Error('请选择10MB以内的PDF文件')); return }
      this.setData({ file })
      this._formDirty = true
    } })
  },
  chooseImages(e) {
    if (!this._role || this.data.submitting) return
    const kind = e.currentTarget.dataset.kind
    if (!['cover', 'GALLERY', 'DETAIL'].includes(kind)) return
    const existing = this.data.form.images.filter(item => item.imageType === kind).length
    const remaining = kind === 'cover' ? 1 : (kind === 'GALLERY' ? 4 : 21) - existing
    if (remaining <= 0) { shop.message(new Error('已达到图片数量上限')); return }
    wx.chooseMedia({ count: Math.min(9, remaining), mediaType: ['image'], sourceType: ['album', 'camera'], success: async response => {
      this.setData({ submitting: true })
      try {
        for (const file of response.tempFiles) {
          if (!/\.(png|jpe?g)$/i.test(file.tempFilePath) || file.size > 5 * 1024 * 1024) throw new Error('请选择5MB以内JPG/PNG图片')
          const result = await shop.upload('/common/upload', { path: file.tempFilePath, size: file.size })
          const imageUrl = shop.imageUrl(result.fileName || result.url)
          this._formDirty = true
          if (kind === 'cover') this.setData({ 'form.coverUrl': imageUrl })
          else this.setData({ 'form.images': this.data.form.images.concat({ imageType: kind, imageUrl, sortOrder: this.data.form.images.length }) })
        }
      } catch (error) { shop.message(error) }
      finally { this.setData({ submitting: false }) }
    } })
  },
  removeImage(e) {
    if (this.data.submitting) return
    this._formDirty = true
    if (e.currentTarget.dataset.kind === 'cover') this.setData({ 'form.coverUrl': '' })
    else this.setData({ 'form.images': this.data.form.images.filter((item, index) => index !== Number(e.currentTarget.dataset.index)) })
  },
  async submit(e) {
    if (this.data.submitting || !['admin', 'teacher'].includes(this._role)) return
    const role = this._role
    this.setData({ submitting: true })
    try {
      const form = { ...this.data.form, images: this.data.form.images || [] }
      if (!form.title.trim()) throw new Error('请填写商品名称')
      shop.cents(form.price || '0.00'); shop.cents(form.shippingFee || '0.00')
      if (this.data.subjectIndex >= 0) form.subjectCode = this.data.subjects[this.data.subjectIndex].dictValue
      if (this.data.gradeIndex >= 0) form.gradeCode = this.data.grades[this.data.gradeIndex].dictValue
      form.shelfStatus = role === 'teacher' ? '2' : (e && e.currentTarget.dataset.publish === '1' ? '1' : '2')
      if (role === 'teacher') form.deliveryType = 'DIGITAL'
      if (form.deliveryType === 'DIGITAL' && this.data.file) {
        const metadata = await shop.upload(role === 'admin' ? '/system/material/file' : '/miniapp/teacher/material/file', this.data.file)
        Object.assign(form, metadata)
        this.setData({ 'form.filePath': metadata.filePath, 'form.fileName': metadata.fileName, 'form.fileSize': metadata.fileSize, file: null })
      }
      if (form.shelfStatus === '1' && form.deliveryType === 'DIGITAL' && !form.filePath) throw new Error('请选择私有PDF文件')
      const response = await shop.request({ url: role === 'admin' ? '/system/material' : '/miniapp/teacher/material', method: form.materialId ? 'PUT' : 'POST', data: form })
      this.setData({ showForm: false, form: { ...form, ...(response.data || {}) } }); wx.showToast({ title: form.shelfStatus === '1' ? '已上架' : '草稿已保存', icon: 'success' }); await this.loadList()
    } catch (error) { shop.message(error) }
    finally { this.setData({ submitting: false }) }
  },
  stockInput(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  async adjustStock() {
    if (this._role !== 'admin' || this.data.submitting || !this.data.form.materialId) return
    if (this._formDirty || this.data.file) { shop.message(new Error('请先保存商品修改，再重新打开进行库存调整')); return }
    this.setData({ submitting: true })
    try {
      const delta = Number(this.data.stockDelta)
      if (!Number.isSafeInteger(delta) || !delta || !this.data.stockRemark.trim()) throw new Error('填写非零整数增减数量和原因')
      const response = await shop.request({ url: `/system/material/${this.data.form.materialId}/stock-adjustments`, method: 'POST', data: { delta, remark: this.data.stockRemark } })
      this.setForm(response.data); await this.loadList()
    } catch (error) { shop.message(error) }
    finally { this.setData({ submitting: false }) }
  },
  async shelf(e) {
    if (this._role !== 'admin' || this.data.submitting) return
    this.setData({ submitting: true })
    try {
      await shop.request({ url: `/system/material/${e.currentTarget.dataset.id}/shelf`, method: 'PUT', data: { shelfStatus: e.currentTarget.dataset.status } }); await this.loadList()
    } catch (error) { shop.message(error) }
    finally { this.setData({ submitting: false }) }
  },
  async remove(e) {
    if (!['admin', 'teacher'].includes(this._role) || this._hidden || this.data.submitting) return
    const role = this._role, id = e.currentTarget.dataset.id, operation = ++this._removeOperation
    const isActive = () => !this._hidden && operation === this._removeOperation
    this._removing = true
    this.setData({ submitting: true })
    try {
      if (!await shop.confirm('移除商品', '确认移除此商品？已购买订单记录会保留。')) return
      if (!isActive()) return
      await shop.request({ url: `${role === 'admin' ? '/system/material' : '/miniapp/teacher/material'}/${id}`, method: 'DELETE' })
      if (isActive()) await this.loadList()
    } catch (error) { if (isActive()) shop.message(error) }
    finally { if (isActive()) { this._removing = false; this.setData({ submitting: false }) } }
  }
})
