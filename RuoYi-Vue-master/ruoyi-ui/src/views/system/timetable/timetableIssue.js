/**
 * 课表问题的统一判定 —— 「信息不完整」与「格式未识别」两类。
 *
 * 课表页（index.vue）与单元格组件（TimetableCell.vue）共用这里的规则，
 * 避免两处各写一套、结果对不上：只要命中任意一条，该格子就整格红色高亮 + 红色感叹号。
 */

/** 导入时识别不到写入的占位值：未识别学期 / 未识别期次 / 未识别时段 / 未识别年级 / 未识别科目 / 未识别课程 */
export function isPlaceholder(v) {
  return typeof v === 'string' && v.indexOf('未识别') === 0
}

/** 教师显示名（占位值视为没有名字） */
export function resolveTeacherName(sch, teacherNameMap) {
  if (!sch) return ''
  const raw = sch.teacherName || ''
  if (!raw || isPlaceholder(raw)) return ''
  return (teacherNameMap && teacherNameMap[raw]) || raw
}

/** 字段 → 中文名（用于提示文案） */
const LABEL = {
  time: '时间', term: '学期', period: '期次', grade: '年级',
  subject: '科目', teacher: '教师名', location: '地点'
}

/**
 * 单条排课的问题列表。
 *
 * kind：
 *  · 'missing'      信息不完整（字段为空 / 未填）
 *  · 'unrecognized' 格式未识别（导入时识别不到，写入了「未识别xx」占位值）
 *
 * @returns {{field:string, label:string, kind:'missing'|'unrecognized'}[]}
 */
export function scheduleIssues(sch, teacherNameMap) {
  const out = []
  if (!sch) return out
  const add = (field, kind) => out.push({ field, label: LABEL[field], kind })

  // 时间：时段未填 / 未识别，或起止时间缺失
  if (!sch.timeSlot || isPlaceholder(sch.timeSlot) || !sch.startTime || !sch.endTime) {
    add('time', (!sch.timeSlot || isPlaceholder(sch.timeSlot)) ? 'unrecognized' : 'missing')
  }
  // 学期 / 期次
  if (!sch.termName || isPlaceholder(sch.termName)) add('term', isPlaceholder(sch.termName) ? 'unrecognized' : 'missing')
  if (!sch.periodName || isPlaceholder(sch.periodName)) add('period', isPlaceholder(sch.periodName) ? 'unrecognized' : 'missing')
  // 年级
  if (!sch.gradeName || isPlaceholder(sch.gradeName)) add('grade', isPlaceholder(sch.gradeName) ? 'unrecognized' : 'missing')
  // 科目
  if (!sch.subjectName || isPlaceholder(sch.subjectName)) add('subject', isPlaceholder(sch.subjectName) ? 'unrecognized' : 'missing')
  // 教师名（只看名字本身是否明确；名字明确但没关联系统账号不算课表问题）
  if (!resolveTeacherName(sch, teacherNameMap)) add('teacher', isPlaceholder(sch.teacherName) ? 'unrecognized' : 'missing')
  // 地点
  if (!sch.classroomId) add('location', 'missing')
  return out
}

/** 该条排课是否「信息不完整」 */
export function hasMissing(sch, teacherNameMap) {
  return scheduleIssues(sch, teacherNameMap).some(x => x.kind === 'missing')
}

/** 该条排课是否「格式未识别」 */
export function hasUnrecognized(sch, teacherNameMap) {
  return scheduleIssues(sch, teacherNameMap).some(x => x.kind === 'unrecognized')
}

/** 生成感叹号 tooltip 文案 */
export function issueTip(list) {
  if (!list || !list.length) return ''
  const miss = list.filter(x => x.kind === 'missing').map(x => x.label)
  const unrec = list.filter(x => x.kind === 'unrecognized').map(x => x.label)
  const parts = []
  if (miss.length) parts.push('信息不完整：' + miss.join('、'))
  if (unrec.length) parts.push('格式未识别：' + unrec.join('、'))
  return parts.join('\n')
}
