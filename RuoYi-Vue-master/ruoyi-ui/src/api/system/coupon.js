import request from '@/utils/request'
export function listTemplates(params) {
  return request({ url: '/system/coupon/templates', method: 'get', params })
}
export function addTemplate(data) {
  return request({ url: '/system/coupon/templates', method: 'post', data })
}
export function editTemplate(id, data) {
  return request({ url: '/system/coupon/templates/' + id, method: 'put', data })
}
export function grantCoupon(id, data) {
  return request({
    url: '/system/coupon/templates/' + id + '/grant',
    method: 'post',
    data,
    headers: { repeatSubmit: false },
  })
}
export function listGrants(params) {
  return request({ url: '/system/coupon/grants', method: 'get', params })
}
export function revokeCoupon(id, reason) {
  return request({
    url: '/system/coupon/grants/' + id + '/revoke',
    method: 'post',
    data: { reason },
  })
}
export function listParents(params) {
  return request({ url: '/system/coupon/parents', method: 'get', params })
}

export function couponOptions() { return request({url:'/system/coupon/options',method:'get'}) }
