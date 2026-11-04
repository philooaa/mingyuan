export function updateAutomaticCourseName(vm) {
  const form = vm.form
  const teacher = (vm.dims.teachers || []).find(t => String(t.userId) === String(form.teacherId))
  const teacherName = teacher && (teacher.realName || teacher.userName || teacher.nickName)
  if (!form.gradeName || !form.subjectName || !form.classType || !teacherName) {
    if (vm._automaticCourseName && form.courseClassName === vm._automaticCourseName) { vm.$set(form, 'courseClassName', ''); vm._automaticCourseName = null }
    return
  }
  if (form.courseClassName && form.courseClassName !== vm._automaticCourseName) return
  const name = [form.gradeName, form.subjectName, form.classType, teacherName].join('')
  vm._automaticCourseName = name
  vm.$set(form, 'courseClassName', name)
}
