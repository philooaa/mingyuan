// 仅依据课程真实学期、期次匹配；未配置及其他期次仍可在“全部”查看。
const options = ['全部时段', '春季', '秋季', '寒假一期', '寒假二期', '暑假一期', '暑假二期']
const keys = ['', 'spring', 'autumn', 'winter:1', 'winter:2', 'summer:1', 'summer:2']
const clean = value => String(value || '').replace(/\s+/g, '').replace(/１/g, '1').replace(/２/g, '2')
function courseKey(course) {
  const term = clean(course.termName), period = clean(course.periodName)
  if (['春季', '春期', '春季班', '春季学期'].includes(term)) return 'spring'
  if (['秋季', '秋期', '秋季班', '秋季学期'].includes(term)) return 'autumn'
  const season = ['寒假', '寒期', '寒假班'].includes(term) ? 'winter' : ['暑假', '暑期', '暑假班'].includes(term) ? 'summer' : ''
  const part = /^(第)?(一|1)期$/.test(period) ? '1' : /^(第)?(二|2)期$/.test(period) ? '2' : ''
  return season && part ? season + ':' + part : ''
}
function matches(course, index) {
  if (index === 0) return true
  return Number.isInteger(index) && index > 0 && index < keys.length && courseKey(course) === keys[index]
}
// 报名页按学期合并期次，课程原始期次仍保留在详情中。
const seasonOptions = ['全部时段', '春季', '秋季', '寒假', '暑假']
function matchesSeason(course, index) {
  if (index === 0) return true
  if (!Number.isInteger(index) || index < 1 || index >= seasonOptions.length) return false
  const term = clean(course.termName)
  const seasons = [[], ['春季', '春期', '春季班', '春季学期'], ['秋季', '秋期', '秋季班', '秋季学期'], ['寒假', '寒期', '寒假班'], ['暑假', '暑期', '暑假班']]
  return seasons[index].includes(term)
}
module.exports = { options, matches, seasonOptions, matchesSeason }
