export const isWeekday = value => /^周[一二三四五六日]$/.test(value || '')

export function classPeriodOptions(dims, form) {
  const values = [...(dims.periods || []).map(item => item.value), ...((dims.periodMap || {})[form.termName] || [])]
  if (form.classPattern === 'CUSTOM_DATES') return [...new Set([...values, form.periodName].filter(Boolean))].map(value => ({ value, label: value }))
  return [...new Set(values)].filter(value => isWeekday(value) === (form.classPattern === 'WEEKLY'))
    .map(value => ({ value, label: value }))
}
