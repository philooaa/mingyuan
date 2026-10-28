import request from '@/utils/request'
const referralRequest = options => request({ ...options, referralRequest:true })
export const listReferrals = params => request({ url:'/system/referral', method:'get', params })
export const referralCounts = () => request({ url:'/system/referral/counts', method:'get' })
export const previewReferralReward = data => request({ url:'/system/referral/preview', method:'post', data, headers:{repeatSubmit:false} })
export const grantReferralReward = data => referralRequest({ url:'/system/referral/grant', method:'post', data, headers:{repeatSubmit:false} })
export const correctReferral = (id,data) => request({ url:`/system/referral/bindings/${id}/correct`, method:'post', data })
export const revokeReferralReward = (id,data) => request({ url:`/system/referral/rewards/${id}/revoke`, method:'post', data })
export const resolveReferralAnomaly = (id,data) => request({ url:`/system/referral/anomalies/${id}/resolve`, method:'post', data })
