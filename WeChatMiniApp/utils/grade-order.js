// 依学段、年级排序，避免直接比较汉字；未分年级置于最后。
const gradeAliases = [
  ['幼小衔接'],
  ['一年级', '小学一年级', '小一', '1年级'],
  ['二年级', '小学二年级', '小二', '2年级'],
  ['三年级', '小学三年级', '小三', '3年级'],
  ['四年级', '小学四年级', '小四', '4年级'],
  ['五年级', '小学五年级', '小五', '5年级'],
  ['六年级', '小学六年级', '小六', '6年级'],
  ['初一', '初中一年级', '七年级', '7年级'],
  ['初二', '初中二年级', '八年级', '8年级'],
  ['初三', '初中三年级', '九年级', '9年级'],
  ['高一', '高中一年级'], ['高二', '高中二年级'], ['高三', '高中三年级']
]
function gradeRank(value) {
  const grade = String(value || '').trim()
  if (!grade || grade === '未分年级') return 100
  const rank = gradeAliases.findIndex(aliases => aliases.includes(grade))
  return rank < 0 ? 99 : rank
}
function compareGrades(a, b) {
  const difference = gradeRank(a) - gradeRank(b)
  if (difference) return difference
  return String(a || '').localeCompare(String(b || ''), 'zh-CN')
}
module.exports = { compareGrades }
