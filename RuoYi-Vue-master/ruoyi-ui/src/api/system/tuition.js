import request from '@/utils/request'

export function listTuition(params) {
  return request({ url: '/system/tuition/list', method: 'get', params })
}
export function getTuitionSummary(params) {
  return request({ url: '/system/tuition/summary', method: 'get', params })
}
export function getTuition(id) {
  return request({ url: '/system/tuition/' + id, method: 'get' })
}
export function quoteTuition(id, userCouponId) {
  return request({
    url: '/system/tuition/' + id + '/quote',
    method: 'post',
    data: { userCouponId: userCouponId || null },
    headers: { repeatSubmit: false },
  })
}
export function priceTuition(id, data) {
  return request({ url: '/system/tuition/' + id + '/price', method: 'put', data })
}
export function verifyHistory(id, data) {
  return request({ url: '/system/tuition/' + id + '/history-verify', method: 'post', data })
}
export function createPayment(id, data) {
  return request({
    url: '/system/tuition/' + id + '/payments',
    method: 'post',
    data,
    headers: { repeatSubmit: false },
  })
}
export function confirmPayment(id, data) {
  return request({ url: '/system/tuition/payments/' + id + '/confirm', method: 'post', data })
}
export function closePayment(id, data) {
  return request({ url: '/system/tuition/payments/' + id + '/close', method: 'post', data })
}
export function uploadEvidence(file, businessType, businessId) {
  const data = new FormData()
  data.append('file', file)
  data.append('businessType', businessType)
  data.append('businessId', String(businessId))
  return request({
    url: '/system/tuition/evidence',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false },
    timeout: 60000,
  })
}
export function paymentEvidence(id) {
  return request({
    url: '/system/tuition/payments/' + id + '/evidence',
    method: 'get',
    responseType: 'blob',
  })
}
export function historyEvidence(id) {
  return request({
    url: '/system/tuition/' + id + '/history-evidence',
    method: 'get',
    responseType: 'blob',
  })
}

export function refundTargets(id) {
  return request({ url: '/system/tuition/' + id + '/refund-targets', method: 'get' })
}
