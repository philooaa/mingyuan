import request from '@/utils/request'

// 查询课程报名列表
export function listEnrollment(query) {
  return request({
    url: '/system/enrollment/list',
    method: 'get',
    params: query
  })
}

// 查询课程报名详细
export function getEnrollment(enrollmentId) {
  return request({
    url: '/system/enrollment/' + enrollmentId,
    method: 'get'
  })
}

// 新增课程报名
export function addEnrollment(data) {
  return request({
    url: '/system/enrollment',
    method: 'post',
    data: data
  })
}

// 修改课程报名
export function updateEnrollment(data) {
  return request({
    url: '/system/enrollment',
    method: 'put',
    data: data
  })
}

// 取消课程报名，保留报名及考勤历史
export function cancelEnrollment(enrollmentId) {
  return request({
    url: '/system/enrollment/' + enrollmentId + '/cancel',
    method: 'put'
  })
}
