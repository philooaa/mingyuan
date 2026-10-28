<template>
  <div class="app-container">
    <el-form :inline="true" size="mini">
      <el-form-item label="统计区间">
        <el-date-picker v-model="monthRange" type="monthrange" value-format="yyyy-MM" range-separator="-"
          start-placeholder="开始月份" end-placeholder="结束月份" style="width: 240px" @change="loadDashboard" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="loadDashboard">查询</el-button>
      </el-form-item>
    </el-form>

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
import { getDashboard } from "@/api/system/finance"
import * as echarts from "echarts"
require("echarts/theme/macarons")

export default {
  name: "Finance",
  data() {
    return {
      monthRange: [],
      data: null,
      trendChart: null,
      revenueChart: null,
      expenseChart: null
    }
  },
  computed: {
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
    window.addEventListener("resize", this.resizeCharts)
  },
  beforeDestroy() {
    window.removeEventListener("resize", this.resizeCharts)
    ;[this.trendChart, this.revenueChart, this.expenseChart].forEach(c => c && c.dispose())
  },
  methods: {
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
