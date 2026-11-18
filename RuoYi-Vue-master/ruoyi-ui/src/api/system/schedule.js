import request from '@/utils/request'

// 查询课程排课列表
export function listSchedule(query) {
  return request({
    url: '/system/schedule/list',
    method: 'get',
    params: query
  })
}

// 查询课程排课详细
export function getSchedule(scheduleId) {
  return request({
    url: '/system/schedule/' + scheduleId,
    method: 'get'
  })
}

// 新增课程排课
export function addSchedule(data) {
  return request({
    url: '/system/schedule',
    method: 'post',
    data: data
  })
}

// 修改课程排课
export function updateSchedule(data, force = false) {
  return request({
    url: '/system/schedule',
    method: 'put',
    params: { force },
    data: data
  })
}

// 删除课程排课
export function delSchedule(scheduleId) {
  return request({
    url: '/system/schedule/' + scheduleId,
    method: 'delete'
  })
}
