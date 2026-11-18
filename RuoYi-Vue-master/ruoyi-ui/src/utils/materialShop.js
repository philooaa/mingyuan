// Amounts, file sizes and IDs stay as strings at the API boundary.
export function money(value) {
  if (value === null || value === undefined || value === '') return '—'
  const match = String(value).trim().match(/^(\d+)(?:\.(\d{1,2}))?$/)
  return match ? `${match[1].replace(/^0+(?=\d)/, '')}.${(match[2] || '').padEnd(2, '0')}` : '—'
}

export function imageUrls(value) {
  const list = Array.isArray(value) ? value : String(value || '').split(',')
  return list.map(item => typeof item === 'string' ? item.trim() : item.imageUrl || item.url || '').filter(Boolean)
}

export function availableStock(product) {
  return Math.max(0, Number(product.stockQuantity || 0) - Number(product.stockLocked || 0))
}

export function productForm(product = {}) {
  const form = { title: '', subtitle: '', subjectCode: '', gradeCode: '', materialType: '', textbookVersion: '',
    deliveryType: 'PHYSICAL', price: '0.00', intro: '', detailText: '', coverUrl: '', unit: '份',
    stockQuantity: 0, stockLocked: 0, purchaseLimit: null, shippingFee: '0.00', shipFrom: '', dispatchDays: null,
    shelfStatus: '2', sortOrder: 0, filePath: '', fileName: '', fileSize: '', ...product }
  if (form.materialId !== undefined && form.materialId !== null) form.materialId = String(form.materialId)
  const images = (product.images || []).slice().sort((a, b) => a.sortOrder - b.sortOrder)
  form.galleryUrls = images.filter(image => image.imageType === 'GALLERY').map(image => image.imageUrl).join(',')
  form.detailUrls = images.filter(image => image.imageType === 'DETAIL').map(image => image.imageUrl).join(',')
  return form
}

export function validateProduct(form, shelfStatus) {
  const errors = []
  const text = key => String(form[key] || '').trim()
  if (!text('title') || text('title').length > 100) errors.push('请填写100字以内的商品名称')
  if (!['PHYSICAL', 'DIGITAL'].includes(form.deliveryType)) errors.push('请选择交付方式')
  for (const key of ['price', 'shippingFee']) {
    if (money(form[key]) === '—' || Number(form[key]) > 21474836.47) errors.push('金额需为非负两位小数且不超过21474836.47')
  }
  if (!text('unit') || text('unit').length > 20) errors.push('请填写20字以内的计量单位')
  for (const [key, max] of [['subtitle', 200], ['intro', 1000], ['textbookVersion', 100], ['materialType', 30], ['shipFrom', 200]]) {
    if (text(key).length > max) errors.push(`${key}内容超过${max}字`)
  }
  if (form.deliveryType === 'PHYSICAL') {
    if (form.purchaseLimit !== null && form.purchaseLimit !== undefined && form.purchaseLimit !== '' && (!Number.isInteger(Number(form.purchaseLimit)) || Number(form.purchaseLimit) < 1 || Number(form.purchaseLimit) > 99)) errors.push('单次限购需为1至99的整数')
    if (!form.materialId && (!Number.isInteger(Number(form.stockQuantity)) || Number(form.stockQuantity) < 0 || Number(form.stockQuantity) > 2147483647)) errors.push('初始库存需为非负整数')
    if (form.dispatchDays !== null && form.dispatchDays !== undefined && form.dispatchDays !== '' && (!Number.isInteger(Number(form.dispatchDays)) || Number(form.dispatchDays) < 0 || Number(form.dispatchDays) > 365)) errors.push('发货天数需为0至365的整数')
  }
  const cover = imageUrls(form.coverUrl)
  const gallery = imageUrls(form.galleryUrls)
  const details = imageUrls(form.detailUrls)
  if (cover.length > 1 || gallery.length > 4 || details.length + gallery.length > 25) errors.push('封面1张、额外轮播最多4张，额外图片合计最多25张')
  if ([...cover, ...gallery, ...details].some(url => !/^(\/profile\/|https?:\/\/).+\.(png|jpe?g)([?#].*)?$/i.test(url) || url.length > 500)) errors.push('图片仅支持JPG/PNG公开路径')
  if (shelfStatus === '1') {
    if (!cover.length || !text('subjectCode') || !text('gradeCode') || !text('detailText')) errors.push('上架需填写封面、学科、年级和详情描述')
    if (form.deliveryType === 'PHYSICAL' && availableStock(form) <= 0) errors.push('上架需有可售库存，请先调整库存')
    if (form.deliveryType === 'DIGITAL' && (!text('filePath') || !text('fileName'))) errors.push('电子资料上架需上传私有PDF')
  }
  return errors
}

export function productBody(form, shelfStatus) {
  const errors = validateProduct(form, shelfStatus)
  if (errors.length) throw new Error(errors[0])
  const body = {}
  for (const key of ['title', 'subtitle', 'subjectCode', 'gradeCode', 'materialType', 'textbookVersion', 'deliveryType', 'intro', 'detailText', 'coverUrl', 'unit', 'sortOrder']) body[key] = form[key]
  body.price = money(form.price)
  body.shippingFee = form.deliveryType === 'DIGITAL' ? '0.00' : money(form.shippingFee)
  body.purchaseLimit = form.deliveryType === 'DIGITAL' ? 1 : (form.purchaseLimit === null || form.purchaseLimit === undefined || form.purchaseLimit === '' ? null : Number(form.purchaseLimit))
  body.shelfStatus = shelfStatus
  body.images = ['GALLERY', 'DETAIL'].flatMap(type => imageUrls(form[type === 'GALLERY' ? 'galleryUrls' : 'detailUrls']).map((url, index) => ({ imageType: type, imageUrl: url, sortOrder: index })))
  if (form.materialId) {
    if (form.version === null || form.version === undefined) throw new Error('缺少商品版本，请重新打开编辑')
    body.materialId = String(form.materialId)
    body.version = form.version
  }
  if (form.deliveryType === 'DIGITAL') {
    for (const key of ['filePath', 'fileName', 'fileSize']) body[key] = form[key] === null || form[key] === undefined ? null : String(form[key])
  } else {
    body.shipFrom = form.shipFrom || ''
    body.dispatchDays = form.dispatchDays === '' || form.dispatchDays === undefined ? null : form.dispatchDays
    if (!form.materialId) body.stockQuantity = Number(form.stockQuantity)
  }
  return body
}

export function shopResponse(response) {
  if (!response || response.code !== 200) throw new Error(response && response.msg || '商城接口返回异常，请重试')
  return response
}

export function privateFile(response) {
  const data = shopResponse(response).data
  if (!data || !data.filePath || !data.fileName || !/^\d+$/.test(String(data.fileSize))) throw new Error('PDF上传结果不完整，请重试')
  return { filePath: data.filePath, fileName: data.fileName, fileSize: String(data.fileSize) }
}

export function maskPhone(value) {
  const phone = String(value || '')
  if (!phone) return '—'
  if (phone.includes('*')) return phone
  const digits = phone.replace(/\D/g, '')
  const prefix = digits.length >= 11 ? 3 : digits.length >= 8 ? 2 : digits.length >= 3 ? 1 : 0
  const suffix = digits.length >= 11 ? 4 : prefix
  return prefix ? digits.slice(0, prefix) + '****' + digits.slice(-suffix) : '****'
}

export function canShip(order) {
  return order.deliveryType === 'PHYSICAL' && order.orderStatus === 'WAIT_SHIP' && order.payStatus === '1' && order.aftersaleStatus === 'NONE' && !order.shipment
}

export function canRefund(order) {
  return order.payStatus === '1' && order.aftersaleStatus === 'NONE' && !order.shipment &&
    (order.orderStatus === 'WAIT_SHIP' || order.deliveryType === 'DIGITAL' && order.orderStatus === 'COMPLETED') &&
    !!order.payment && ['MOCK', 'FREE'].includes(order.payment.channel)
}

export function orderQuery(query, dates = []) {
  const result = { ...query }
  if (dates && dates.length === 2) { result.beginTime = dates[0]; result.endTime = dates[1] }
  return result
}
