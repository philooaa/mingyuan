// Only the backend's financial projection drives progress; an enrollment label is not a receipt.
const states = {
  WAIT_PRICE: ['待教务核价', '报名已提交，请等待核定费用', 0],
  WAIT_PAYMENT: ['待线下缴费', '请按课程顾问安排缴费', 1],
  WAIT_CONFIRM: ['收款待确认', '已登记收款，等待管理员核对', 2],
  PAID: ['✓ 缴费已完成', '', -1, 'success'],
  WAIVED: ['✓ 费用已减免', '', -1, 'success'],
  HISTORY_PENDING: ['历史费用待核验', '请联系工作人员核实', -1, 'muted'],
  PAYMENT_UNKNOWN: ['收款结果待核查', '请等待管理员核实，请勿重复缴费', -1, 'warning'],
  REFUND_PENDING: ['退费处理中', '请等待工作人员处理', -1, 'warning'],
  REFUND_UNKNOWN: ['退款结果待核查', '请联系工作人员核实', -1, 'warning'],
  PARTIAL_REFUND: ['已部分退款', '', -1, 'muted'],
  REFUNDED: ['已退款', '', -1, 'muted'],
  CLOSED: ['账单已关闭', '如有疑问，请联系课程顾问', -1, 'muted'],
  CREDIT_RESERVED: ['课包结算', '已暂占额度，完成课程后扣次', -1, 'muted'],
  CREDIT_USED: ['课包已扣次', '', -1, 'success'],
  UNAVAILABLE: ['缴费进度待核实', '请联系课程顾问', -1, 'muted']
}

function describe(state) {
  const [title, note, stage, tone = 'active'] = states[state] || states.UNAVAILABLE
  return { title, note, tone, showSteps: stage >= 0,
    steps: ['教务核价', '缴费登记', '收款确认'].map((label, index) => ({
      label, marker: index < stage ? '✓' : String(index + 1),
      state: index < stage ? 'done' : index === stage ? 'current' : 'waiting',
      connected: index < stage
    })) }
}

module.exports = { describe }
