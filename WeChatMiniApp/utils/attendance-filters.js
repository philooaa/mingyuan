const { compareGrades } = require('./grade-order')
// Match the established education dictionary order.
const subjects = ['语文','数学','英语','政治','历史','地理','物理','化学','生物']
function compareSubjects(a,b) {
  const rank = name => subjects.includes(name) ? subjects.indexOf(name) : subjects.length
  return rank(a) - rank(b) || a.localeCompare(b,'zh-CN')
}
const labels = { subjectName: '全部学科', gradeName: '全部年级', teacherName: '全部老师' }
const teacherLabels = {year:'全部年份',termName:'全部季度',lessonNo:'全部讲次',gradeName:'全部年级'}
const reviewLabels = {...teacherLabels,teacherName:'全部教师'}
function state(rows = [], selected = {}, teacher = false, review = false) {
  const fields = teacher ? teacherLabels : review ? reviewLabels : labels
  const filterOptions = {}, filterIndexes = {}
  for (const field of Object.keys(fields)) {
    const values = [...new Set(rows.map(row => String(row[field] || '').trim()).filter(Boolean))].sort(field === 'gradeName' ? compareGrades : field === 'subjectName' ? compareSubjects : (a,b) => a.localeCompare(b,'zh-CN',{numeric:true}))
    filterOptions[field] = [fields[field], ...values]
    filterIndexes[field] = Math.max(0, filterOptions[field].indexOf(selected[field]))
  }
  return { filterOptions, filterIndexes }
}
function matches(row, selected = {}, teacher = false, review = false) {
  return (!selected.filterDate || String(row.classDate || '').slice(0,10) === selected.filterDate) && Object.keys(teacher ? teacherLabels : review ? reviewLabels : labels).every(field => !selected[field] || String(row[field] || '').trim() === selected[field])
}
function change(data, event) {
  const field = event.currentTarget.dataset.field
  if (!Object.prototype.hasOwnProperty.call(data.role === 'teacher' ? teacherLabels : data.reviewByLesson ? reviewLabels : labels,field)) return data.attendanceFilters
  const index = Number(event.detail.value), value = data.filterOptions[field][index]
  return value === undefined ? data.attendanceFilters : { ...data.attendanceFilters, [field]: index ? value : '' }
}
module.exports = {state,matches,change}
