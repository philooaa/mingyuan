import request from '@/utils/request'

// 查询签到复核列表（按课次聚合，可选日期）
export function listReview(classDate) {
  return request({
    url: '/miniapp/admin/sign/list',
    method: 'get',
    params: classDate ? { classDate } : {}
  })
}

// 查询某课次签到明细与复核信息
export function getDetail(scheduleId, classDate) {
  return request({
    url: '/miniapp/admin/sign/detail',
    method: 'get',
    params: { scheduleId, classDate }
  })
}

// 教务或管理员确认或标记异常；原调用签名保留，增加可选版本及操作。
export function confirmReview(scheduleId, classDate, images, options = {}) {
  return request({
    url: '/miniapp/admin/sign/review',
    method: 'post',
    data: { scheduleId, classDate, images, ...options }
  })
}

export function attendanceCourses(keyword = '') { return request({url:'/miniapp/admin/sign/courses',method:'get',params:{keyword}}) }
export function attendanceOverview(scheduleId,from,to) { return request({url:'/miniapp/admin/sign/overview',method:'get',params:{scheduleId,from,to}}) }
export function attendanceStudent(params) { return request({url:'/miniapp/admin/sign/student-summary',method:'get',params}) }
