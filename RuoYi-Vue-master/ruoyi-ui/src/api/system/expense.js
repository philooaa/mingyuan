import request from '@/utils/request'

// 成本支出列表
export function listExpense(query) {
  return request({
    url: '/system/expense/list',
    method: 'get',
    params: query
  })
}

// 成本支出详情
export function getExpense(expenseId) {
  return request({
    url: '/system/expense/' + expenseId,
    method: 'get'
  })
}

// 新增成本支出
export function addExpense(data) {
  return request({
    url: '/system/expense',
    method: 'post',
    data: data
  })
}

// 修改成本支出
export function updateExpense(data) {
  return request({
    url: '/system/expense',
    method: 'put',
    data: data
  })
}

// 删除成本支出
export function delExpense(expenseId) {
  return request({
    url: '/system/expense/' + expenseId,
    method: 'delete'
  })
}
