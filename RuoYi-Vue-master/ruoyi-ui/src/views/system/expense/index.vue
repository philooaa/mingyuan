<template>
  <div class="app-container">
    <el-form :inline="true" size="mini">
      <el-form-item label="支出类型">
        <el-select v-model="queryParams.expenseType" placeholder="全部" clearable style="width: 130px">
          <el-option v-for="dict in dict.type.edu_expense_type" :key="dict.value" :label="dict.label" :value="dict.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="摘要">
        <el-input v-model="queryParams.title" placeholder="摘要关键字" clearable style="width: 150px" @keyup.enter.native="getList" />
      </el-form-item>
      <el-form-item label="日期">
        <el-date-picker v-model="dateRange" type="daterange" value-format="yyyy-MM-dd" range-separator="-"
          start-placeholder="开始" end-placeholder="结束" style="width: 230px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="getList">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        <el-button type="success" plain icon="el-icon-plus" size="mini" v-hasPermi="['system:expense:add']" @click="handleAdd">新增</el-button>
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" v-hasPermi="['system:expense:remove']" @click="handleDelete()">删除</el-button>
        <el-button type="warning" plain icon="el-icon-download" size="mini" v-hasPermi="['system:expense:export']" @click="handleExport">导出</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="expenseList" border size="mini" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="45" align="center" />
      <el-table-column label="支出日期" align="center" prop="expenseDate" width="100" />
      <el-table-column label="类型" align="center" width="100">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.edu_expense_type" :value="scope.row.expenseType" />
        </template>
      </el-table-column>
      <el-table-column label="摘要" align="center" prop="title" width="160" show-overflow-tooltip />
      <el-table-column label="金额(元)" align="center" width="100">
        <template slot-scope="scope"><span class="amt">¥{{ scope.row.amount }}</span></template>
      </el-table-column>
      <el-table-column label="明细" align="center" prop="detail" show-overflow-tooltip />
      <el-table-column label="登记人" align="center" prop="registerBy" width="90" />
      <el-table-column label="登记时间" align="center" prop="createTime" width="140" />
      <el-table-column label="操作" align="center" width="120" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" v-hasPermi="['system:expense:edit']" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" v-hasPermi="['system:expense:remove']" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="560px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="支出类型" prop="expenseType">
          <el-select v-model="form.expenseType" placeholder="请选择" style="width: 100%">
            <el-option v-for="dict in dict.type.edu_expense_type" :key="dict.value" :label="dict.label" :value="dict.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="支出日期" prop="expenseDate">
          <el-date-picker v-model="form.expenseDate" type="date" value-format="yyyy-MM-dd" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="摘要" prop="title">
          <el-input v-model="form.title" placeholder="如：9月房租" maxlength="100" />
        </el-form-item>
        <el-form-item label="金额(元)" prop="amount">
          <el-input-number v-model="form.amount" :min="0" :precision="2" :step="100" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="明细" prop="detail">
          <el-input v-model="form.detail" type="textarea" :rows="3" placeholder="登记细节：用途、供应商、凭证号等" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listExpense, getExpense, addExpense, updateExpense, delExpense } from "@/api/system/expense"

export default {
  name: "Expense",
  dicts: ['edu_expense_type'],
  data() {
    return {
      loading: true,
      ids: [],
      multiple: true,
      total: 0,
      expenseList: [],
      title: "",
      open: false,
      dateRange: [],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        expenseType: undefined,
        title: undefined
      },
      form: {},
      rules: {
        expenseType: [{ required: true, message: "请选择支出类型", trigger: "change" }],
        title: [{ required: true, message: "摘要不能为空", trigger: "blur" }],
        amount: [{ required: true, message: "金额不能为空", trigger: "blur" }],
        detail: [{ required: true, message: "明细不能为空", trigger: "blur" }],
        expenseDate: [{ required: true, message: "请选择支出日期", trigger: "change" }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      const params = { ...this.queryParams }
      if (this.dateRange && this.dateRange.length === 2) {
        params["params[beginDate]"] = this.dateRange[0]
        params["params[endDate]"] = this.dateRange[1]
      }
      listExpense(params).then(response => {
        this.expenseList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    resetQuery() {
      this.dateRange = []
      this.queryParams = { pageNum: 1, pageSize: 10, expenseType: undefined, title: undefined }
      this.getList()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.expenseId)
      this.multiple = !selection.length
    },
    reset() {
      this.form = { expenseId: undefined, expenseType: undefined, title: undefined, amount: undefined, detail: undefined, expenseDate: undefined, remark: undefined }
      this.$nextTick(() => { this.$refs["form"] && this.$refs["form"].clearValidate() })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    handleAdd() {
      this.reset()
      this.title = "登记成本支出"
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      getExpense(row.expenseId).then(response => {
        this.form = response.data
        this.title = "修改成本支出"
        this.open = true
      })
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (!valid) return
        if (this.form.expenseId != null) {
          updateExpense(this.form).then(() => {
            this.$modal.msgSuccess("修改成功")
            this.open = false
            this.getList()
          })
        } else {
          addExpense(this.form).then(() => {
            this.$modal.msgSuccess("登记成功")
            this.open = false
            this.getList()
          })
        }
      })
    },
    handleDelete(row) {
      const expenseIds = row && row.expenseId ? [row.expenseId] : this.ids
      this.$modal.confirm('是否确认删除选中的 ' + expenseIds.length + ' 条成本记录？').then(() => {
        return delExpense(expenseIds)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    handleExport() {
      const params = { ...this.queryParams }
      if (this.dateRange && this.dateRange.length === 2) {
        params["params[beginDate]"] = this.dateRange[0]
        params["params[endDate]"] = this.dateRange[1]
      }
      this.download('system/expense/export', params, `expense_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style scoped>
.amt { color: #e6a23c; font-weight: bold; }
</style>
