import request from '@/utils/request'
const base='/miniapp/staff/attendance'
export const rosterCourses=()=>request({url:base+'/courses'})
export const rosterLessons=scheduleId=>request({url:base+'/lessons',params:{scheduleId}})
export const rosterStudents=keyword=>request({url:base+'/students',params:{keyword}})
export const rosterList=(scheduleId,lessonId)=>request({url:base+'/roster',params:{scheduleId,lessonId}})
export const rosterAdd=data=>request({url:base+'/roster',method:'post',data})
export const rosterRemove=(row,data)=>request({url:base+'/roster/'+(row.membershipId?row.membershipId:'enrollment/'+row.enrollmentId),method:'put',data})
export const rosterAddOffline=data=>request({url:base+'/roster/offline',method:'post',data})
export const rosterBindParent=(studentId,data)=>request({url:base+'/students/'+studentId+'/parent',method:'put',data})
export const rosterPendingParents=()=>request({url:base+'/students/pending-parent'})
