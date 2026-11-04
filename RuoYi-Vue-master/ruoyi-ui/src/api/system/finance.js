import request from '@/utils/request'

export function getCourseDimensions() {
  return request({ url: '/system/finance/dashboard/course-dimensions', method: 'get' })
}
export function getDashboardStudentCount(params) {
  return request({ url: '/system/finance/dashboard/student-count', method: 'get', params })
}
export function getDashboardStudents(params) {
  return request({ url: '/system/finance/dashboard/students', method: 'get', params })
}

// 财务驾驶舱汇总
export function getDashboard(query) {
  return request({
    url: '/system/finance/dashboard',
    method: 'get',
    params: query
  })
}
