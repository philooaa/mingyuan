import request from '@/utils/request'

// 财务驾驶舱汇总
export function getDashboard(query) {
  return request({
    url: '/system/finance/dashboard',
    method: 'get',
    params: query
  })
}
