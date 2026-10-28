import request from '@/utils/request'

// 查询教室信息列表
export function listClassroom(query) {
  return request({
    url: '/system/classroom/list',
    method: 'get',
    params: query
  })
}

// 查询教室信息详细
export function getClassroom(classroomId) {
  return request({
    url: '/system/classroom/' + classroomId,
    method: 'get'
  })
}

// 校区分类统计（教室管理页的校区分类用）
export function getClassroomCampusStats() {
  return request({
    url: '/system/classroom/campusStats',
    method: 'get'
  })
}

// 新增教室信息
export function addClassroom(data) {
  return request({
    url: '/system/classroom',
    method: 'post',
    data: data
  })
}

// 修改教室信息
export function updateClassroom(data) {
  return request({
    url: '/system/classroom',
    method: 'put',
    data: data
  })
}

// 删除教室信息
export function delClassroom(classroomId) {
  return request({
    url: '/system/classroom/' + classroomId,
    method: 'delete'
  })
}
