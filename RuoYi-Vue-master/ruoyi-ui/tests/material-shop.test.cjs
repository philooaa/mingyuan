const { test, before } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')
let shop = {}
before(async () => {
  const file = path.join(root, 'src/utils/materialShop.js')
  if (fs.existsSync(file)) shop = await import('data:text/javascript;base64,' + fs.readFileSync(file).toString('base64'))
})
const physical = () => ({ title: '数学练习册', subjectCode: 'math', gradeCode: 'g1', deliveryType: 'PHYSICAL', price: '12.30', shippingFee: '2.00', unit: '本', purchaseLimit: 3, stockQuantity: 3, stockLocked: 0, coverUrl: '/profile/cover.jpg', detailText: '学习内容', galleryUrls: '/profile/a.png,/profile/b.jpg', detailUrls: '/profile/detail.png', filePath: 'shop/1/file.pdf', fileName: '教材.pdf', fileSize: '9007199254740993' })
test('physical create submits initial stock and excludes private file and read-only counters', () => {
  assert.equal(typeof shop.productBody, 'function', 'productBody helper is missing')
  const body = shop.productBody({ ...physical(), saleCount: 20, bought: true }, '1')
  assert.equal(body.stockQuantity, 3)
  assert.equal(body.shelfStatus, '1')
  assert.equal(body.price, '12.30')
  for (const key of ['filePath', 'fileName', 'fileSize', 'stockLocked', 'saleCount', 'bought', 'availableStock']) assert.equal(key in body, false)
  assert.deepEqual(body.images, [
    { imageType: 'GALLERY', imageUrl: '/profile/a.png', sortOrder: 0 },
    { imageType: 'GALLERY', imageUrl: '/profile/b.jpg', sortOrder: 1 },
    { imageType: 'DETAIL', imageUrl: '/profile/detail.png', sortOrder: 0 }
  ])
  assert.equal(shop.availableStock(physical()), 3)
})
test('edit retains decimal ID/version and omits stock to avoid overwriting reservations', () => {
  assert.equal(typeof shop.productBody, 'function')
  const body = shop.productBody({ ...physical(), materialId: '9007199254740993', version: 7, stockLocked: 2 }, '2')
  assert.equal(body.materialId, '9007199254740993')
  assert.equal(body.version, 7)
  assert.equal('stockQuantity' in body, false)
  assert.equal(shop.availableStock({ stockQuantity: 3, stockLocked: 2 }), 1)
  assert.throws(() => shop.productBody({ ...physical(), materialId: '12' }, '2'), /版本/)
})
test('digital saves private upload metadata without numeric precision loss or delivery fields', () => {
  assert.equal(typeof shop.productBody, 'function')
  const body = shop.productBody({ ...physical(), deliveryType: 'DIGITAL' }, '1')
  assert.equal(body.fileSize, '9007199254740993')
  assert.equal(body.filePath, 'shop/1/file.pdf')
  assert.equal(body.fileName, '教材.pdf')
  assert.equal(body.purchaseLimit, 1)
  assert.equal(body.shippingFee, '0.00')
  for (const key of ['stockQuantity', 'shipFrom', 'dispatchDays']) assert.equal(key in body, false)
})
test('publish validates real cover, positive money, education, details and available stock', () => {
  assert.equal(typeof shop.validateProduct, 'function')
  assert.deepEqual(shop.validateProduct(physical(), '1'), [])
  for (const patch of [{ coverUrl: '' }, { subjectCode: '' }, { gradeCode: '' }, { detailText: ' ' }, { stockLocked: 3 }, { galleryUrls: '1,2,3,4,5' }]) {
    assert.ok(shop.validateProduct({ ...physical(), ...patch }, '1').length, JSON.stringify(patch))
  }
  assert.ok(shop.validateProduct({ ...physical(), deliveryType: 'DIGITAL', filePath: '' }, '1').length)
  assert.deepEqual(shop.validateProduct({ ...physical(), coverUrl: '', subjectCode: '', gradeCode: '', detailText: '', stockQuantity: 0, price: '0.00' }, '2'), [])
  assert.ok(shop.validateProduct({ ...physical(), price: '1.001' }, '2').length)
})
test('image editor round-trips sorted galleries separately from cover and detail', () => {
  assert.equal(typeof shop.productForm, 'function')
  const form = shop.productForm({ ...physical(), materialId: '9007199254740993', images: [ { imageType: 'GALLERY', imageUrl: '/profile/b.jpg', sortOrder: 1 }, { imageType: 'DETAIL', imageUrl: '/profile/detail.png', sortOrder: 0 }, { imageType: 'GALLERY', imageUrl: '/profile/a.png', sortOrder: 0 } ] })
  assert.equal(form.materialId, '9007199254740993')
  assert.equal(form.galleryUrls, '/profile/a.png,/profile/b.jpg')
  assert.equal(form.detailUrls, '/profile/detail.png')
  assert.equal(form.coverUrl, '/profile/cover.jpg')
})
test('money formatting keeps cents exact and never silently rounds malformed input', () => {
  assert.equal(typeof shop.money, 'function')
  assert.equal(shop.money('0'), '0.00')
  assert.equal(shop.money('12.3'), '12.30')
  assert.equal(shop.money('99999999.99'), '99999999.99')
  assert.equal(shop.money('1.001'), '—')
  assert.equal(shop.money(null), '—')
})
test('private upload unwraps nested metadata and rejects every non-200/malformed result', () => {
  assert.equal(typeof shop.privateFile, 'function')
  assert.deepEqual(shop.privateFile({ code: 200, data: { filePath: 'shop/1/a.pdf', fileName: 'a.pdf', fileSize: '9007199254740993' } }), { filePath: 'shop/1/a.pdf', fileName: 'a.pdf', fileSize: '9007199254740993' })
  for (const response of [{ code: 500, msg: '文件失败' }, { code: 0, data: {} }, { data: {} }, { code: 200, data: {} }]) assert.throws(() => shop.privateFile(response))
})
test('list phone rendering masks short, international and mainland phones even if backend leaks raw data', () => {
  assert.equal(typeof shop.maskPhone, 'function')
  assert.equal(shop.maskPhone('13812345678'), '138****5678')
  assert.equal(shop.maskPhone('138****5678'), '138****5678')
  for (const phone of ['123456', '+852 1234 5678', '12']) assert.ok(shop.maskPhone(phone).includes('****'))
  assert.equal(shop.maskPhone(''), '—')
})
test('order operations permit only paid pending physical shipping and unsent mock/free refunds', () => {
  assert.equal(typeof shop.canShip, 'function')
  const order = { orderStatus: 'WAIT_SHIP', payStatus: '1', aftersaleStatus: 'NONE', deliveryType: 'PHYSICAL', payment: { channel: 'MOCK' }, shipment: null }
  assert.equal(shop.canShip(order), true)
  assert.equal(shop.canRefund(order), true)
  for (const patch of [{ payStatus: '0' }, { orderStatus: 'WAIT_RECEIVE' }, { aftersaleStatus: 'REFUNDED' }, { shipment: { trackingNo: '001' } }]) {
    assert.equal(shop.canShip({ ...order, ...patch }), false)
    assert.equal(shop.canRefund({ ...order, ...patch }), false)
  }
  assert.equal(shop.canRefund({ ...order, payment: { channel: 'WECHAT' } }), false)
  assert.equal(shop.canRefund({ ...order, orderStatus: 'COMPLETED', deliveryType: 'DIGITAL', payment: { channel: 'FREE' } }), true)
})
test('order filters use top-level date boundaries and preserve string status/IDs', () => {
  assert.equal(typeof shop.orderQuery, 'function')
  const input = { title: '数学', payStatus: '0', orderStatus: 'WAIT_PAY', orderId: '9007199254740993', pageNum: 2 }
  assert.deepEqual(shop.orderQuery(input, ['2026-10-01', '2026-10-04']), { ...input, beginTime: '2026-10-01', endTime: '2026-10-04' })
  assert.equal('beginTime' in shop.orderQuery(input, []), false)
})

function api(file) {
  const calls = []
  const source = fs.readFileSync(path.join(root, 'src/api/system', file), 'utf8').replace(/^import .*$/gm, '').replace(/export function /g, 'function ')
  const context = vm.createContext({ shopResponse: shop.shopResponse, request: config => { calls.push(JSON.parse(JSON.stringify(config))); return Promise.resolve({ code: 200 }) } })
  vm.runInContext(source, context)
  return { context, calls }
}
test('product control API emits dedicated JSON shelf and stock operations', async () => {
  const { context, calls } = api('material.js')
  await context.changeShelfStatus('9007199254740993', '1')
  assert.deepEqual(calls[0], { url: '/system/material/9007199254740993/shelf', method: 'put', data: { shelfStatus: '1' } })
  assert.equal(typeof context.adjustMaterialStock, 'function')
  await context.adjustMaterialStock('9007199254740993', { delta: -1, remark: '盘点' })
  assert.deepEqual(calls[1], { url: '/system/material/9007199254740993/stock-adjustments', method: 'post', data: { delta: -1, remark: '盘点' } })
})
test('order API emits dedicated dispatch/refund payloads rather than generic edits', async () => {
  const { context, calls } = api('materialOrder.js')
  assert.equal(typeof context.shipMaterialOrder, 'function')
  await context.shipMaterialOrder('9007199254740993', { carrierCode: 'SF', carrierName: '顺丰', trackingNo: '00001' })
  await context.refundMaterialOrder('9007199254740993', '模拟退款')
  assert.deepEqual(calls, [ { url: '/system/materialOrder/9007199254740993/ship', method: 'post', data: { carrierCode: 'SF', carrierName: '顺丰', trackingNo: '00001' } }, { url: '/system/materialOrder/9007199254740993/refund', method: 'post', data: { reason: '模拟退款' } } ])
})

function component(name, dependencies = {}) {
  return loadComponent(path.join(root, 'src/views/system', name, 'index.vue'), dependencies)
}
function loadComponent(filename, dependencies = {}) {
  const file = fs.readFileSync(filename, 'utf8')
  const script = file.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'component =')
  const context = vm.createContext({ component: null, process: { env: { VUE_APP_BASE_API: '/test-api' } }, getToken: () => 'test-token', ...shop, ...dependencies })
  vm.runInContext(script, context)
  const definition = context.component
  const instance = { ...definition.data(), $modal: { msgSuccess() {}, msgError() {}, confirm: () => Promise.resolve() }, $refs: {}, ...dependencies }
  for (const [name, method] of Object.entries(definition.methods)) instance[name] = method.bind(instance)
  return { instance, definition }
}
test('product editor publishes a normalized body and keeps conflict errors with form open', async () => {
  let saved
  const { instance } = component('material', { addMaterial: body => { saved = body; return Promise.resolve({ code: 200, data: { ...body, materialId: '9007199254740993', version: 0 } }) }, updateMaterial: () => Promise.reject(new Error('商品已被更新，请刷新后重试')) })
  instance.getList = () => Promise.resolve()
  instance.form = physical()
  instance.open = true
  await instance.submitForm('1')
  assert.equal(saved.shelfStatus, '1')
  assert.equal(saved.stockQuantity, 3)
  assert.equal(instance.open, false)
  instance.open = true
  instance.form = { ...physical(), materialId: '9007199254740993', version: 0 }
  await instance.submitForm('2')
  assert.equal(instance.open, true)
  assert.match(instance.formError, /商品已被更新/)
  assert.equal(instance.saving, false)
})
test('private PDF upload blocks large/non-PDF files and applies nested metadata', () => {
  const { instance } = component('material')
  assert.equal(typeof instance.beforePdfUpload, 'function')
  assert.equal(instance.beforePdfUpload({ name: 'a.pdf', size: 10 * 1024 * 1024 + 1 }), false)
  assert.equal(instance.beforePdfUpload({ name: 'a.jpg', size: 100 }), false)
  assert.equal(instance.beforePdfUpload({ name: 'a.pdf', size: 1024 }), true)
  instance.pdfUploadSuccess({ code: 200, data: { filePath: 'shop/1/a.pdf', fileName: 'a.pdf', fileSize: '1024' } })
  assert.equal(instance.form.filePath, 'shop/1/a.pdf')
  assert.equal(instance.form.fileSize, '1024')
  assert.equal(instance.pdfUploading, false)
  instance.pdfUploadSuccess({ code: 500, msg: 'PDF无效' })
  assert.match(instance.formError, /PDF无效/)
  assert.equal(instance.form.filePath, 'shop/1/a.pdf')
})
test('order details never reuse a masked list row and clear private data on fetch failure', async () => {
  const calls = []
  const { instance } = component('materialOrder', { getMaterialOrder: id => { calls.push(id); return Promise.resolve({ code: 200, data: { orderId: id, detailAddress: '历史地址', receiverPhone: '13812345678', items: [{ subjectLabel: '旧学科', gradeLabel: '旧年级' }] } }) } })
  await instance.handleDetail({ orderId: '9007199254740993', receiverPhone: '138****5678' })
  assert.deepEqual(calls, ['9007199254740993'])
  assert.equal(instance.detailRow.detailAddress, '历史地址')
  assert.equal(instance.detailRow.items[0].subjectLabel, '旧学科')
  const failed = component('materialOrder', { getMaterialOrder: () => Promise.reject(new Error('没有查询权限')) }).instance
  failed.detailRow = instance.detailRow
  await failed.handleDetail({ orderId: '2' })
  assert.equal(failed.detailRow, null)
  assert.match(failed.detailError, /没有查询权限/)
  assert.equal(failed.detailLoading, false)
})
test('order polling installs one 15-second timer and removes it on deactivation/destruction', () => {
  const timers = []
  const cleared = []
  const { instance, definition } = component('materialOrder', { setInterval: (callback, milliseconds) => { timers.push({ callback, milliseconds }); return timers.length }, clearInterval: id => cleared.push(id) })
  assert.equal(typeof instance.startPolling, 'function')
  let refreshes = 0
  instance.getList = () => { refreshes++ }
  instance.startPolling()
  instance.startPolling()
  assert.equal(timers.length, 1)
  assert.equal(timers[0].milliseconds, 15000)
  timers[0].callback()
  assert.equal(refreshes, 1)
  definition.deactivated.call(instance)
  assert.deepEqual(cleared, [1])
  timers[0].callback()
  assert.equal(refreshes, 1)
  definition.activated.call(instance)
  assert.equal(timers.length, 2)
  definition.beforeDestroy.call(instance)
  assert.deepEqual(cleared, [1, 2])
})
test('mock refund submission requires confirmation and sends only a reason', async () => {
  const calls = []
  const { instance } = component('materialOrder', { refundMaterialOrder: (id, reason) => { calls.push({ id, reason }); return Promise.resolve({ code: 200, data: { orderId: id } }) } })
  instance.getList = () => Promise.resolve()
  let confirmed = ''
  instance.$modal.confirm = message => { confirmed = message; return Promise.resolve() }
  instance.refundForm = { orderId: '9007199254740993', reason: '测试退款' }
  instance.refundOpen = true
  await instance.submitRefund()
  assert.match(confirmed, /模拟.*真实/)
  assert.deepEqual(calls, [{ id: '9007199254740993', reason: '测试退款' }])
  assert.equal(instance.refundOpen, false)
  assert.equal(instance.operationSaving, false)
})
test('saving draft waits for the shared image uploader to finish so uploads are not lost', async () => {
  let saves = 0
  const { instance } = component('material', { addMaterial: () => { saves++; return Promise.resolve({ code: 200 }) } })
  instance.getList = () => Promise.resolve()
  instance.form = physical()
  instance.open = true
  instance.$refs.coverUpload = { $refs: { imageUpload: { uploadFiles: [{ status: 'uploading' }] } } }
  await instance.submitForm('2')
  assert.equal(saves, 0)
  assert.equal(instance.open, true)
  assert.match(instance.formError, /图片.*上传/)
  instance.$refs.coverUpload.$refs.imageUpload.uploadFiles = [{ status: 'success' }]
  await instance.submitForm('2')
  assert.equal(saves, 1)
})
test('a failed background request keeps the scoped timer available for the next refresh', async () => {
  const { instance } = component('materialOrder', { listMaterialOrder: () => Promise.reject(new Error('暂时断网')), setInterval: () => 1, clearInterval: () => {} })
  instance.startPolling()
  await instance.getList(true)
  assert.equal(instance.pollTimer, 1)
  assert.equal(instance.active, true)
  assert.equal(instance.loading, false)
  assert.match(instance.listError, /暂时断网/)
})

// Exercise the actual shared uploader; only external upload HTTP and modal UI are replaced.
for (const order of ['failure-first', 'success-first']) {
  test(`shared image ${order} completion emits the successful gallery into the saved product`, async () => {
    let saved
    const { instance: editor } = component('material', { addMaterial: body => { saved = body; return Promise.resolve({ code: 200 }) } })
    editor.getList = () => Promise.resolve()
    editor.form = { ...physical(), galleryUrls: '' }
    editor.open = true
    const emitted = []
    const modal = { loading() {}, closeLoading() {}, msgError() {} }
    const failed = { name: 'failed.png', type: 'image/png', size: 200, status: 'uploading' }
    const success = { name: 'success.png', type: 'image/png', size: 200, status: 'uploading' }
    const elementUpload = { uploadFiles: [failed, success] }
    const { instance: uploader } = loadComponent(path.join(root, 'src/components/ImageUpload/index.vue'), {
      action: '/common/upload', fileType: ['png', 'jpg', 'jpeg'], fileSize: 5, $modal: modal,
      $refs: { imageUpload: elementUpload },
      $emit: (event, value) => { emitted.push({ event, value }); editor.form.galleryUrls = value }
    })
    editor.$refs.galleryUpload = uploader
    uploader.handleBeforeUpload(failed)
    uploader.handleBeforeUpload(success)
    const finishFailure = () => { elementUpload.uploadFiles = elementUpload.uploadFiles.filter(file => file !== failed); uploader.handleUploadError(new Error('network failure'), failed) }
    const finishSuccess = () => { success.status = 'success'; uploader.handleUploadSuccess({ code: 200, fileName: '/profile/success.png' }, success) }
    if (order === 'failure-first') { finishFailure(); finishSuccess() } else { finishSuccess(); finishFailure() }
    assert.equal(uploader.number, 0)
    assert.equal(uploader.uploadList.length, 0)
    assert.deepEqual(emitted, [{ event: 'input', value: '/profile/success.png' }])
    await editor.submitForm('2')
    assert.equal(editor.open, false)
    assert.deepEqual(saved.images, [ { imageType: 'GALLERY', imageUrl: '/profile/success.png', sortOrder: 0 }, { imageType: 'DETAIL', imageUrl: '/profile/detail.png', sortOrder: 0 } ])
  })
}
test('all shared image failures settle the batch and a later retry emits normally', () => {
  const emitted = []
  let closed = 0
  const { instance: uploader } = loadComponent(path.join(root, 'src/components/ImageUpload/index.vue'), {
    action: '/common/upload', fileType: ['png'], fileSize: 5,
    $modal: { loading() {}, msgError() {}, closeLoading() { closed++ } },
    $emit: (event, value) => emitted.push(value)
  })
  const file = { name: 'image.png', type: 'image/png', size: 200 }
  uploader.handleBeforeUpload(file)
  uploader.handleBeforeUpload(file)
  uploader.handleUploadError()
  uploader.handleUploadError()
  assert.equal(uploader.number, 0)
  assert.equal(uploader.uploadList.length, 0)
  assert.equal(closed, 1)
  uploader.handleBeforeUpload(file)
  uploader.handleUploadSuccess({ code: 200, fileName: '/profile/retry.png' }, file)
  assert.equal(emitted.at(-1), '/profile/retry.png')
  assert.equal(uploader.number, 0)
})
test('product saves optional active material dictionary codes and rejects free text or inactive codes', async () => {
  const saved = []
  const dictionary = { type: { edu_material_type: [ { label: '讲义', value: 'handout' }, { label: '试卷', value: 'exam' }, { label: '练习册', value: 'workbook' }, { label: '其他', value: 'other' } ] } }
  const { instance } = component('material', { dict: dictionary, addMaterial: body => { saved.push(body); return Promise.resolve({ code: 200 }) } })
  instance.getList = () => Promise.resolve()
  instance.form = { ...physical(), materialType: 'workbook' }
  instance.open = true
  await instance.submitForm('2')
  assert.equal(saved[0].materialType, 'workbook')
  instance.form.materialType = ''
  await instance.submitForm('2')
  assert.equal(saved[1].materialType, '')
  instance.open = true
  instance.form.materialType = '自由文本'
  await instance.submitForm('2')
  assert.equal(saved.length, 2)
  assert.equal(instance.open, true)
  assert.match(instance.formError, /资料类型/)
  instance.form.materialType = 'workbook'
  dictionary.type.edu_material_type = dictionary.type.edu_material_type.filter(item => item.value !== 'workbook')
  await instance.submitForm('2')
  assert.equal(saved.length, 2)
})

test('zero price and optional limit are accepted without changing existing limits', () => {
  assert.deepEqual(shop.validateProduct({ ...physical(), price: '0.00', purchaseLimit: '' }, '1'), [])
  assert.equal(shop.productBody({ ...physical(), price: '0.00', purchaseLimit: '' }, '1').purchaseLimit, null)
  assert.equal(shop.productBody({ ...physical(), purchaseLimit: 4 }, '1').purchaseLimit, 4)
  assert.ok(shop.validateProduct({ ...physical(), purchaseLimit: 0 }, '1').length)
})
