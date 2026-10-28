import request from '@/utils/request'

const base = '/system/schedule/single'
export const singleProfile = teacherId => request({ url: base + '/profile', params: { teacherId } })
export const singleAvailability = (teacherId, date, excludeId) => request({ url: base + '/availability', params: { teacherId, date, ...(excludeId ? { excludeId } : {}) } })
export const singleCourses = teacherId => request({ url: base + '/courses', params: { ...(teacherId ? { teacherId } : {}) } })
export const singleBookings = (teacherId, scheduleId) => request({ url: base + '/bookings', params: { ...(teacherId ? { teacherId } : {}), ...(scheduleId ? { scheduleId } : {}) } })
export const publishSingle = data => request({ url: base + '/courses', method: 'post', data })
export const updateSingle = (id, data) => request({ url: base + '/courses/' + id, method: 'put', data })
export const withdrawSingle = id => request({ url: base + '/courses/' + id + '/withdraw', method: 'post' })
export const decideSingle = (id, data) => request({ url: base + '/bookings/' + id + '/decision', method: 'post', data })
export const completeSingle = id => request({ url: base + '/bookings/' + id + '/complete', method: 'post' })
export const singleHistory = id => request({ url: base + '/bookings/' + id + '/history' })
