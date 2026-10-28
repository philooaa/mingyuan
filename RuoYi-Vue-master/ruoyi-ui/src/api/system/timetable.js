import request from '@/utils/request'

// 可视化课表：维度初始化（学期/期次/时段/班型/年级/学科/教室分组/教师下拉 + 当前角色）
export function getTimetableDimensions() {
  return request({
    url: '/system/courseTimetable/dimensions',
    method: 'get'
  })
}

// 可视化课表：网格查询（按 classroomId@timeSlot 映射）
export function getTimetableGrid(params) {
  return request({
    url: '/system/courseTimetable/grid',
    method: 'get',
    params
  })
}

// 单条排课详情（用于编辑弹窗回填）
export function getTimetableSchedule(scheduleId) {
  return request({
    url: '/system/courseTimetable/' + scheduleId,
    method: 'get'
  })
}

// 新增/编辑/删除：直接走 courseTimetable 自身的 Controller（带角色校验）
export function addTimetableSchedule(data) {
  return request({
    url: '/system/courseTimetable',
    method: 'post',
    data
  })
}

export function updateTimetableSchedule(data) {
  return request({
    url: '/system/courseTimetable',
    method: 'put',
    data
  })
}

export function delTimetableSchedule(scheduleIds) {
  return request({
    url: '/system/courseTimetable/' + scheduleIds,
    method: 'delete'
  })
}

// 课表导入：上传矩阵式 xlsx 预览（不落库）
export function importTimetablePreview(data) {
  return request({
    url: '/system/courseTimetable/importPreview',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

// 课表导入：确认落库
export function importTimetableConfirm(data) {
  return request({
    url: '/system/courseTimetable/importConfirm',
    method: 'post',
    data,
    timeout: 120000
  })
}

// 课表导入：一键（解析+同步教师/教室/字典+落库）
export function importTimetableOneClick(data) {
  return request({
    url: '/system/courseTimetable/importOneClick',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180000
  })
}

// 可视化课表：一次性交换两个教室在「年份+学期+期次」下的全部排课（只对调 classroom_id）
export function swapTimetableClassrooms(data) {
  return request({
    url: '/system/courseTimetable/swapClassrooms',
    method: 'post',
    data
  })
}

// 课次展开：把一条排课按起止日期+上课模式展开成具体上课日（含已停课课次）
export function getTimetableOccurrences(scheduleId) {
  return request({
    url: '/system/courseTimetable/' + scheduleId + '/occurrences',
    method: 'get'
  })
}

// 停课：取消某一天的课次（不顺延），用于法定节假日冲课
export function cancelTimetableOccurrence(scheduleId, data) {
  return request({
    url: '/system/courseTimetable/' + scheduleId + '/occurrences/cancel',
    method: 'post',
    data
  })
}

// 恢复课次：删除该日期的停课/调课记录，让这一天重新正常上课
export function restoreTimetableOccurrence(scheduleId, data) {
  return request({
    url: '/system/courseTimetable/' + scheduleId + '/occurrences/restore',
    method: 'post',
    data
  })
}
