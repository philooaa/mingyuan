import request from '@/utils/request'

export async function recognizeAndFill(vm) {
  const form=vm.form, original=String(form.courseClassName || '').trim()
  if (!original) { vm.$message.warning('请先填写课程名称'); return }
  if (vm.recognizing) return
  vm.recognizing=true
  try {
    const response=await request({url:'/miniapp/staff/schedule/recognize-name',method:'post',data:{name:original}})
    if (vm.form!==form || String(form.courseClassName || '').trim()!==original || !(vm.visible || vm.open)) return
    const data=response.data || {}, warnings=(data.warnings || []).slice()
    const teacher=(vm.dims.teachers || []).find(t=>String(t.userId)===String(data.teacherId))
    const proposed={gradeName:data.gradeName,subjectName:data.subjectName,classType:data.classType,teacherId:teacher && teacher.userId}
    if (data.teacherId && !teacher) warnings.push('教师选项已变化，请重新选择教师')
    if (proposed.classType && !(vm.dims.classTypes || []).some(t=>t.value===proposed.classType)) { delete proposed.classType; warnings.push('识别的班型不在启用选项中，请手动选择') }
    const labels={gradeName:'年级',subjectName:'学科',classType:'班型',teacherId:'教师'}
    const lines=Object.keys(proposed).filter(k=>proposed[k]!==undefined && proposed[k]!==null && proposed[k]!=='').map(k=>`${labels[k]}：${k==='teacherId' ? (data.teacherName || proposed[k]) : proposed[k]}`)
    const candidates=data.teacherCandidates || []
    if (candidates.length>1) warnings.push('候选教师：'+candidates.map(t=>`${t.name || t.nickName || '教师'}（账号编号 ${t.id}）`).join('、'))
    const conflicts=Object.keys(proposed).filter(k=>proposed[k]!==undefined && proposed[k]!==null && proposed[k]!=='' && form[k]!==undefined && form[k]!==null && form[k]!=='' && String(form[k])!==String(proposed[k]))
    const message=[...(lines.length?lines:['没有可自动填写的字段']),...warnings,...(conflicts.length?['已有字段与识别结果不同：'+conflicts.map(k=>labels[k]).join('、')]:[])].join('；')
    let replace=false
    if(conflicts.length) {
      try { await vm.$confirm(message,'识别结果',{confirmButtonText:'替换已有字段',cancelButtonText:'保留已有字段',distinguishCancelAndClose:true,type:'warning'}); replace=true } catch(e) { if(e==='close') return }
    } else { try { await vm.$alert(message,'识别结果',{confirmButtonText:'确定'}) } catch(e) { return } }
    if (vm.form!==form || String(form.courseClassName || '').trim()!==original || !(vm.visible || vm.open)) return
    Object.keys(proposed).forEach(k=>{const value=proposed[k]; if(value!==undefined && value!==null && value!=='' && (replace || form[k]===undefined || form[k]===null || form[k]==='')) vm.$set(form,k,value)})
  } finally { vm.recognizing=false }
}
