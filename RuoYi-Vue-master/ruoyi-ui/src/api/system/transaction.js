import request from '@/utils/request'
const base='/system/eduTransaction'
export const listAccounts=params=>request({url:base+'/accounts',params})
export const listParents=params=>request({url:base+'/parents',params})
export const createAccount=data=>request({url:base+'/accounts',method:'post',data})
export const editChild=(id,data)=>request({url:`${base}/accounts/${id}`,method:'put',data})
export const deleteChild=id=>request({url:`${base}/accounts/${id}`,method:'delete'})
export const adjustCredits=(id,data)=>request({url:`${base}/accounts/${id}/adjust`,method:'post',data})
export const creditHistory=id=>request({url:`${base}/accounts/${id}/history`})
export const listPersonalAccounts=params=>request({url:base+'/personal-accounts',params})
export const personalDetail=id=>request({url:`${base}/personal-accounts/${id}`})
export const personalRecords=(id,params)=>request({url:`${base}/personal-accounts/${id}/records`,params})
export const linkChild=(id,data)=>request({url:`${base}/enrollments/${id}/child`,method:'post',data})
