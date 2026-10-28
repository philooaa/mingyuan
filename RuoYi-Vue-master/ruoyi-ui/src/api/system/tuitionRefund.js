import request from '@/utils/request'
export function listRefunds(params) {
  return request({ url: '/system/tuition/refunds/list', method: 'get', params })
}
export function getRefund(id) {
  return request({ url: '/system/tuition/refunds/' + id, method: 'get' })
}
export function applyRefund(data) {
  return request({
    url: '/system/tuition/refunds',
    method: 'post',
    data,
    headers: { repeatSubmit: false },
  })
}
export function reviewRefund(id, data) {
  return request({ url: '/system/tuition/refunds/' + id + '/review', method: 'post', data })
}
export function cancelRefund(id) {
  return request({ url: '/system/tuition/refunds/' + id + '/cancel', method: 'post' })
}
export function executeRefund(id) {
  return request({ url: '/system/tuition/refunds/' + id + '/execute', method: 'post' })
}
export function confirmRefund(id, data) {
  return request({ url: '/system/tuition/refunds/' + id + '/confirm', method: 'post', data })
}
export function refundEvidence(id) {
  return request({
    url: '/system/tuition/refunds/' + id + '/evidence',
    method: 'get',
    responseType: 'blob',
  })
}

export function markRefunded(id, data) {
  return request({ url: '/system/tuition/refunds/' + id + '/mark-refunded', method: 'post', data })
}
