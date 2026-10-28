import request from '@/utils/request'

// 年级定价规则
export function listPriceRules() {
  return request({ url: '/system/coursePrice/rules', method: 'get' })
}

// 年级、学科下拉
export function getPriceDims() {
  return request({ url: '/system/coursePrice/dims', method: 'get' })
}

// 课程定价预览
export function listPriceCourses(query) {
  return request({ url: '/system/coursePrice/courses', method: 'get', params: query })
}

// 按年级 + 学科批量改价
export function batchCoursePrice(data) {
  return request({ url: '/system/coursePrice/batch', method: 'put', data })
}

// 修改年级规则单价（可选同步课程）
export function updatePriceRule(data) {
  return request({ url: '/system/coursePrice/rule', method: 'put', data })
}

// 把年级规则价套用到该年级全部课程
export function applyPriceRule(data) {
  return request({ url: '/system/coursePrice/rule/apply', method: 'put', data })
}
