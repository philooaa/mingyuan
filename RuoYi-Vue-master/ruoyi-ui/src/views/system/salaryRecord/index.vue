<template>
  <div class="app-container">
    <el-form :inline="true" size="mini">
      <el-form-item label="结算月份">
        <el-date-picker v-model="queryParams.salaryMonth" type="month" value-format="yyyy-MM"
          placeholder="选择月份" style="width: 140px" @change="getList" />
      </el-form-item>
      <el-form-item label="教师">
        <el-input v-model="queryParams.teacherName" placeholder="教师姓名" clearable style="width: 120px"
          @keyup.enter.native="getList" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="queryParams.confirmStatus" placeholder="全部" clearable style="width: 110px">
          <el-option label="待确认" value="0" />
          <el-option label="已确认" value="1" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="getList">搜索</el-button>
        <el-button type="success" icon="el-icon-data-analysis" size="mini"
          v-hasPermi="['system:salaryRecord:add']" @click="openPreview">按月预览/生成</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="recordList" border size="mini" show-summary :summary-method="getSummary"
      empty-text="该月份尚未生成结算单，请点击右上角「按月预览/生成」">
      <el-table-column label="月份" align="center" prop="salaryMonth" width="90" />
      <el-table-column label="教师" align="center" prop="teacherName" width="90" />
      <el-table-column label="底薪" align="center" prop="baseSalary" width="90" />
      <el-table-column label="小学班课" align="center">
        <template slot-scope="scope">
          {{ scope.row.primaryClassCount }}节 / {{ scope.row.primaryClassHours }}h<br />
          <span class="amt">¥{{ scope.row.primaryClassAmount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="初高班课" align="center">
        <template slot-scope="scope">
          {{ scope.row.jhClassCount }}节 / {{ scope.row.jhClassHours }}h<br />
          <span class="amt">¥{{ scope.row.jhClassAmount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="小学一对一" align="center">
        <template slot-scope="scope">
          {{ scope.row.primary1on1Count }}人次 / {{ scope.row.primary1on1Hours }}h<br />
          <span class="amt">¥{{ scope.row.primary1on1Amount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="初中一对一" align="center">
        <template slot-scope="scope">
          {{ scope.row.junior1on1Count }}人次 / {{ scope.row.junior1on1Hours }}h<br />
          <span class="amt">¥{{ scope.row.junior1on1Amount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="高中一对一" align="center">
        <template slot-scope="scope">
          {{ scope.row.senior1on1Count }}人次 / {{ scope.row.senior1on1Hours }}h<br />
          <span class="amt">¥{{ scope.row.senior1on1Amount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="课时费合计" align="center" prop="lessonAmount" width="110">
        <template slot-scope="scope"><span class="amt">¥{{ scope.row.lessonAmount }}</span></template>
      </el-table-column>
      <el-table-column label="提成" align="center" prop="commissionAmount" width="100">
        <template slot-scope="scope">
          <span class="amt">¥{{ scope.row.commissionAmount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="应发合计" align="center" prop="totalAmount" width="110">
        <template slot-scope="scope"><span class="total-amt">¥{{ scope.row.totalAmount }}</span></template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="90">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.confirmStatus === '1' ? 'success' : 'warning'">
            {{ scope.row.confirmStatus === '1' ? '已确认' : '待确认' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="130" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-check"
            v-if="scope.row.confirmStatus !== '1'"
            v-hasPermi="['system:salaryRecord:edit']" @click="handleConfirm(scope.row)">确认</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete"
            v-hasPermi="['system:salaryRecord:remove']" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 预览对话框 -->
    <el-dialog :title="`月度预览（${previewMonth}）`" :visible.sync="previewOpen" width="1100px" append-to-body>
      <el-table :data="previewList" border size="mini" max-height="480" show-summary :summary-method="getPreviewSummary">
        <el-table-column label="教师" align="center" prop="teacherName" width="90" fixed="left" />
        <el-table-column label="底薪" align="center" prop="baseSalary" width="80" />
        <el-table-column label="小学班课" align="center">
          <template slot-scope="scope">{{ scope.row.primaryClassCount }}节/{{ scope.row.primaryClassHours }}h/¥{{ scope.row.primaryClassAmount }}</template>
        </el-table-column>
        <el-table-column label="初高班课" align="center">
          <template slot-scope="scope">{{ scope.row.jhClassCount }}节/{{ scope.row.jhClassHours }}h/¥{{ scope.row.jhClassAmount }}</template>
        </el-table-column>
        <el-table-column label="小学一对一" align="center">
          <template slot-scope="scope">{{ scope.row.primary1on1Count }}人次/{{ scope.row.primary1on1Hours }}h/¥{{ scope.row.primary1on1Amount }}</template>
        </el-table-column>
        <el-table-column label="初中一对一" align="center">
          <template slot-scope="scope">{{ scope.row.junior1on1Count }}人次/{{ scope.row.junior1on1Hours }}h/¥{{ scope.row.junior1on1Amount }}</template>
        </el-table-column>
        <el-table-column label="高中一对一" align="center">
          <template slot-scope="scope">{{ scope.row.senior1on1Count }}人次/{{ scope.row.senior1on1Hours }}h/¥{{ scope.row.senior1on1Amount }}</template>
        </el-table-column>
        <el-table-column label="课时费" align="center" width="90">
          <template slot-scope="scope"><b>¥{{ scope.row.lessonAmount }}</b></template>
        </el-table-column>
        <el-table-column label="提成" align="center" width="90">
          <template slot-scope="scope"><b>¥{{ scope.row.commissionAmount }}</b></template>
        </el-table-column>
        <el-table-column label="应发" align="center" width="90">
          <template slot-scope="scope"><b class="total-amt">¥{{ scope.row.totalAmount }}</b></template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="90">
          <template slot-scope="scope">
            <el-tag size="mini" :type="scope.row.confirmStatus === '1' ? 'success' : (scope.row.recordId ? 'info' : '')">
              {{ scope.row.confirmStatus === '1' ? '已确认' : (scope.row.recordId ? '已生成待确认' : '未生成') }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div slot="footer" class="dialog-footer">
        <el-button @click="previewOpen = false">取 消</el-button>
        <el-button type="primary" :loading="generating" @click="handleGenerate">生成/更新结算单</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listRecord, previewMonth, generateMonth, confirmRecord, delRecord } from "@/api/system/salaryRecord"

export default {
  name: "SalaryRecord",
  data() {
    const now = new Date()
    return {
      loading: false,
      recordList: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 20,
        salaryMonth: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`,
        teacherName: null,
        confirmStatus: null
      },
      previewOpen: false,
      previewMonth: null,
      previewList: [],
      generating: false
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRecord(this.queryParams).then(res => {
        this.recordList = res.rows || []
        this.total = res.total || 0
      }).finally(() => { this.loading = false })
    },
    openPreview() {
      if (!this.queryParams.salaryMonth) {
        this.$modal.msgWarning("请先选择结算月份")
        return
      }
      this.previewMonth = this.queryParams.salaryMonth
      this.previewOpen = true
      this.previewList = []
      previewMonth(this.previewMonth).then(res => {
        this.previewList = res.data || []
      })
    },
    handleGenerate() {
      this.generating = true
      generateMonth(this.previewMonth).then(res => {
        this.$modal.msgSuccess(res.msg || "生成成功")
        this.previewOpen = false
        this.getList()
      }).finally(() => { this.generating = false })
    },
    handleConfirm(row) {
      this.$modal.confirm(`确认「${row.teacherName}」${row.salaryMonth} 的结算单（应发 ¥${row.totalAmount}）？确认后锁定不可修改。`).then(() => {
        return confirmRecord(row.recordId)
      }).then(() => {
        this.$modal.msgSuccess("确认成功")
        this.getList()
      }).catch(() => {})
    },
    handleDelete(row) {
      this.$modal.confirm(`确认删除「${row.teacherName}」${row.salaryMonth} 的结算单？`).then(() => {
        return delRecord(row.recordId)
      }).then(() => {
        this.$modal.msgSuccess("删除成功")
        this.getList()
      }).catch(() => {})
    },
    sumAmount(list, field) {
      return list.reduce((s, r) => s + Number(r[field] || 0), 0)
    },
    getSummary({ columns, data }) {
      const sums = []
      columns.forEach((col, i) => {
        if (i === 0) { sums[i] = '合计'; return }
        const prop = col.property
        if (['baseSalary', 'primaryClassAmount', 'jhClassAmount', 'primary1on1Amount', 'junior1on1Amount', 'senior1on1Amount', 'lessonAmount', 'commissionAmount', 'totalAmount'].includes(prop)) {
          sums[i] = '¥' + this.sumAmount(data, prop).toFixed(2)
        } else {
          sums[i] = ''
        }
      })
      return sums
    },
    getPreviewSummary({ columns, data }) {
      const sums = []
      columns.forEach((col, i) => {
        if (i === 0) { sums[i] = '合计'; return }
        if (['baseSalary', 'lessonAmount', 'commissionAmount', 'totalAmount'].includes(col.property)) {
          sums[i] = '¥' + this.sumAmount(data, col.property).toFixed(2)
        } else {
          sums[i] = ''
        }
      })
      return sums
    }
  }
}
</script>

<style scoped>
.amt { color: #606266; font-size: 12px; }
.total-amt { color: #c27a08; font-weight: 700; }
</style>
