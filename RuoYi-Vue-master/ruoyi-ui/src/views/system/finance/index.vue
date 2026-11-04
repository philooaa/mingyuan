<template>
  <div class="app-container">
    <el-card v-if="isAdmin" shadow="never" class="course-dashboard">
      <div slot="header">管理员看板 · 课程筛选</div>
      <admin-course-filters :dimensions="courseDimensions" @change="onCourseFilter" />
      <el-alert v-if="courseError" :title="courseError" type="error" :closable="false" show-icon />
      <div class="course-result" v-loading="courseLoading">
        <div><div class="course-result-label">学生数（去重）</div><div class="course-result-value">{{ studentCount === null ? '—' : studentCount }}</div></div>
        <div><div class="course-result-label">{{ selectedMetricLabel }}</div><div class="course-result-value">{{ metricValue }}</div></div>
      </div>
      <div class="course-note">学生按有效报名对应的学生编号去重，不按姓名猜测。<span v-if="unidentifiedEnrollments">另有 {{ unidentifiedEnrollments }} 条报名未关联学生编号，未计入人数，请核对。</span></div>
      <div class="course-note">财务指标显示整个机构按下方月份区间统计的数据，不受学生筛选条件影响。收入为已确认净收入；退款按原收款／订单月份归集。</div>
      <el-table :data="students" v-loading="courseLoading" size="small" empty-text="暂无符合条件的学生">
        <el-table-column prop="studentName" label="学生姓名" min-width="160"><template slot-scope="scope">{{ scope.row.studentName || '学生资料待核对' }}</template></el-table-column>
        <el-table-column prop="studentId" label="学生编号" min-width="140" />
      </el-table>
      <el-pagination v-if="studentTotal > studentPageSize" class="student-pages" background layout="prev, pager, next, total" :total="studentTotal" :current-page="studentPage" :page-size="studentPageSize" @current-change="changeStudentPage" />
    </el-card>
    <el-form :inline="true" size="mini">
      <el-form-item label="统计区间">
        <el-date-picker v-model="monthRange" type="monthrange" value-format="yyyy-MM" range-separator="-"
          start-placeholder="开始月份" end-placeholder="结束月份" style="width: 240px" @change="loadDashboard" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="loadDashboard">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="overall-label">整体财务统计（按上方月份区间）</div>
    <el-row :gutter="16" class="card-row">
      <el-col :xs="12" :sm="12" :md="6" v-for="card in summaryCards" :key="card.label">
        <div class="fin-card" :style="{ borderTopColor: card.color }">
          <div class="fin-card-label">{{ card.label }}</div>
          <div class="fin-card-value" :style="{ color: card.color }">¥{{ formatMoney(card.value) }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :span="24">
        <el-card shadow="never">
          <div slot="header">月度经营趋势（合同金额 / 已确认收入 / 老师工资 / 成本 / 净利润）</div>
          <div ref="trendChart" style="height: 360px"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :span="12">
        <el-card shadow="never">
          <div slot="header">已确认收入构成（课时费 / 资料商城）</div>
          <div ref="revenueChart" style="height: 320px"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <div slot="header">成本支出构成（按类型）</div>
          <div ref="expenseChart" style="height: 320px"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { getDashboard, getCourseDimensions, getDashboardStudentCount, getDashboardStudents } from "@/api/system/finance"
import AdminCourseFilters from "@/views/dashboard/AdminCourseFilters"
import * as echarts from "echarts"
require("echarts/theme/macarons")

export default {
  name: "Finance",
  components: { AdminCourseFilters },
  data() {
    return {
      monthRange: [],
      courseDimensions: [], courseFilter: { metric: 'income' }, students: [], studentTotal: 0, studentPage: 1, studentPageSize: 20, studentCount: null, unidentifiedEnrollments: 0, courseLoading: false, courseError: '', courseRequestId: 0,
      data: null,
      trendChart: null,
      revenueChart: null,
      expenseChart: null
    }
  },
  computed: {
    isAdmin() { return (this.$store.getters.roles || []).includes('admin') },
    selectedMetricLabel() { return ({ income: '机构已确认收入', refund: '机构退费', grossProfit: '机构毛利' })[this.courseFilter.metric] || '机构已确认收入' },
    metricValue() {
      if (!this.data || !this.data.summary) return '—'
      const field = ({ income: 'confirmedRevenue', refund: 'refundTotal', grossProfit: 'grossProfit' })[this.courseFilter.metric]
      return this.data.summary[field] == null ? '—' : '¥' + this.formatMoney(this.data.summary[field])
    },
    summaryCards() {
      const s = (this.data && this.data.summary) || {}
      return [
        { label: "合同金额", value: s.contractAmount, color: "#409EFF" },
        { label: "已确认收入", value: s.confirmedRevenue, color: "#67C23A" },
        { label: "欠款（合同-已确认）", value: s.receivable, color: "#E6A23C" },
        { label: "老师工资", value: s.teacherSalary, color: "#F56C6C" },
        { label: "毛利（收入-工资）", value: s.grossProfit, color: "#409EFF" },
        { label: "净利润（毛利-成本）", value: s.netProfit, color: "#67C23A" },
        { label: "成本支出", value: s.expenseTotal, color: "#909399" }
      ]
    }
  },
  created() {
    const now = new Date()
    const cur = this.fmtMonth(now)
    const start = this.fmtMonth(new Date(now.getFullYear(), now.getMonth() - 11, 1))
    this.monthRange = [start, cur]
  },
  mounted() {
    this.loadDashboard()
    if (this.isAdmin) {
      getCourseDimensions().then(r => { this.courseDimensions = r.data || []; return this.loadStudentCount() }).catch(() => { this.courseError = '课程筛选加载失败，请刷新页面重试' })
    }
    window.addEventListener("resize", this.resizeCharts)
  },
  beforeDestroy() {
    window.removeEventListener("resize", this.resizeCharts)
    ;[this.trendChart, this.revenueChart, this.expenseChart].forEach(c => c && c.dispose())
  },
  methods: {
    onCourseFilter(value) { this.courseFilter = value; this.studentPage = 1; this.loadStudentCount() },
    changeStudentPage(page) { this.studentPage = page; this.loadStudentCount() },
    async loadStudentCount() {
      const requestId = ++this.courseRequestId
      const { year, term, stage, grade, subject, teacherId } = this.courseFilter
      this.courseLoading = true; this.courseError = ''; this.studentCount = null; this.students = []; this.studentTotal = 0; this.unidentifiedEnrollments = 0
      try {
        const params = { year: year || undefined, term: term || undefined, stage: stage || undefined, grade: grade || undefined, subject: subject || undefined, teacherId: teacherId || undefined }
        const [response, list] = await Promise.all([getDashboardStudentCount(params), getDashboardStudents({ ...params, pageNum: this.studentPage, pageSize: this.studentPageSize })])
        if (requestId !== this.courseRequestId) return
        const result = response.data || {}; this.studentCount = result.studentCount == null ? null : result.studentCount; this.unidentifiedEnrollments = result.unidentifiedEnrollments || 0; this.students = list.rows || []; this.studentTotal = list.total || 0
      } catch (e) { if (requestId === this.courseRequestId) this.courseError = '学生统计加载失败，请刷新页面重试' }
      finally { if (requestId === this.courseRequestId) this.courseLoading = false }
    },
    fmtMonth(d) {
      return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0")
    },
    formatMoney(v) {
      const n = Number(v || 0)
      return n.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    },
    loadDashboard() {
      const params = {}
      if (this.monthRange && this.monthRange.length === 2) {
        params.beginMonth = this.monthRange[0]
        params.endMonth = this.monthRange[1]
      }
      getDashboard(params).then(response => {
        this.data = response.data
        this.$nextTick(() => this.renderCharts())
      })
    },
    renderCharts() {
      this.renderTrend()
      this.renderRevenue()
      this.renderExpense()
    },
    renderTrend() {
      if (!this.trendChart) {
        this.trendChart = echarts.init(this.$refs.trendChart, "macarons")
      }
      const trend = this.data.monthlyTrend || []
      this.trendChart.setOption({
        tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
        legend: { data: ["合同金额", "已确认收入", "老师工资", "成本支出", "净利润"] },
        grid: { left: 60, right: 20, bottom: 30 },
        xAxis: { type: "category", data: trend.map(t => t.month) },
        yAxis: { type: "value", name: "元" },
        series: [
          { name: "合同金额", type: "bar", data: trend.map(t => Number(t.contractAmount)) },
          { name: "已确认收入", type: "bar", data: trend.map(t => Number(t.confirmedRevenue)) },
          { name: "老师工资", type: "bar", data: trend.map(t => Number(t.teacherSalary)) },
          { name: "成本支出", type: "bar", data: trend.map(t => Number(t.expenseAmount)) },
          { name: "净利润", type: "line", itemStyle: { color: "#67C23A" }, data: trend.map(t => Number(t.netProfit)) }
        ]
      }, true)
    },
    renderRevenue() {
      if (!this.revenueChart) {
        this.revenueChart = echarts.init(this.$refs.revenueChart, "macarons")
      }
      const items = (this.data.revenueBySource || []).filter(i => Number(i.value) !== 0)
      this.revenueChart.setOption({
        tooltip: { trigger: "item", formatter: "{a} <br/>{b}: ¥{c} ({d}%)" },
        legend: { bottom: 0 },
        series: [{
          name: "已确认收入",
          type: "pie",
          radius: ["35%", "65%"],
          label: { formatter: "{b}\n¥{c} ({d}%)" },
          data: items.map(i => ({ name: i.name, value: Number(i.value) }))
        }]
      }, true)
    },
    renderExpense() {
      if (!this.expenseChart) {
        this.expenseChart = echarts.init(this.$refs.expenseChart, "macarons")
      }
      const typeLabels = { rent: "房租", utility: "水电", printing: "教材印刷", marketing: "市场推广", office: "办公", other: "其他" }
      const items = (this.data.expenseByType || []).filter(i => Number(i.value) !== 0)
      this.expenseChart.setOption({
        tooltip: { trigger: "item", formatter: "{a} <br/>{b}: ¥{c} ({d}%)" },
        legend: { bottom: 0 },
        series: [{
          name: "成本支出",
          type: "pie",
          radius: "60%",
          label: { formatter: "{b}\n¥{c} ({d}%)" },
          data: items.map(i => ({ name: typeLabels[i.name] || i.name, value: Number(i.value) }))
        }]
      }, true)
    },
    resizeCharts() {
      ;[this.trendChart, this.revenueChart, this.expenseChart].forEach(c => c && c.resize())
    }
  }
}
</script>

<style scoped>
.course-dashboard { margin-bottom: 18px; }
.course-result { display: flex; flex-wrap: wrap; gap: 60px; padding: 8px 16px 16px; }
.course-result-label { font-size: 13px; color: #7b8998; }
.course-result-value { font-size: 24px; font-weight: 600; color: #214d83; margin-top: 5px; }
.course-note { color: #697989; font-size: 12px; line-height: 1.7; padding: 0 16px 6px; }
.student-pages { margin-top: 12px; text-align: right; }
.overall-label { font-weight: 600; padding: 0 0 10px; color: #415569; }
.card-row { margin-top: 4px; }
.fin-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-top: 3px solid #409EFF;
  border-radius: 4px;
  padding: 14px 16px;
  margin-bottom: 16px;
}
.fin-card-label { font-size: 13px; color: #909399; margin-bottom: 8px; }
.fin-card-value { font-size: 20px; font-weight: bold; }
.chart-row { margin-bottom: 16px; }
</style>
