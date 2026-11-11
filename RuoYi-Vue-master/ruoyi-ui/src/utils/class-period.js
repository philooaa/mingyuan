export const isWeekday = value => /^周[一二三四五六日]$/.test(value || '')

export function classPeriodOptions(dims, form) {
  const values = [...(dims.periods || []).map(item => item.value), ...((dims.periodMap || {})[form.termName] || [])]
  return [...new Set(values)].filter(value => isWeekday(value) === (form.classPattern === 'WEEKLY'))
    .map(value => ({ value, label: value }))
}
