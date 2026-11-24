import request from '@/utils/request'

export const listRules = params => request({ url: '/system/schedule-rules', method: 'get', params })
export const getRule = id => request({ url: `/system/schedule-rules/${id}`, method: 'get' })
export const createRule = data => request({ url: '/system/schedule-rules', method: 'post', data })
export const updateRule = (id, data) => request({ url: `/system/schedule-rules/${id}`, method: 'put', data })
