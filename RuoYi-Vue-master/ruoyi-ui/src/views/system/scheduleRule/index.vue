<template>
  <div class="app-container">
    <el-button type="primary" size="small" @click="openNew">新增规则</el-button>
    <p>修改规则只影响以后新使用该规则的排课，不会改变已保存课程。</p>
    <el-table v-loading="loading" :data="rules.slice((page-1)*20,page*20)">
      <el-table-column prop="ruleName" label="规则名称" />
      <el-table-column prop="firstDate" label="首个上课日期" />
      <el-table-column prop="lastDate" label="最后上课日期" />
      <el-table-column prop="updateTime" label="修改时间" />
      <el-table-column prop="dateCount" label="日期数" />
      <el-table-column label="状态"><template slot-scope="s">{{ s.row.status === '0' ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column label="操作"><template slot-scope="s"><el-button type="text" @click="edit(s.row.ruleId)">查看／编辑</el-button></template></el-table-column>
    </el-table>
    <el-pagination :current-page.sync="page" :page-size="20" layout="total, prev, pager, next" :total="rules.length" />
    <el-dialog :title="form.ruleId ? '编辑规则' : '新增规则'" :visible.sync="dialog" width="700px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="规则名称" required><el-input v-model.trim="form.ruleName" maxlength="100" /></el-form-item>
        <el-form-item label="上课日期" required>
          <el-date-picker v-model="form.dates" type="dates" value-format="yyyy-MM-dd" placeholder="选择日期（可跨月）" @change="sortDates" />
          <div>已选 {{ form.dates.length }} 天<span v-if="form.dates.length">，{{ form.dates[0] }} 至 {{ form.dates[form.dates.length - 1] }}</span></div>
          <el-tag v-for="date in form.dates" :key="date" closable style="margin:4px" @close="removeDate(date)">{{ date }}</el-tag>
        </el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio label="0">启用</el-radio><el-radio label="1">停用</el-radio></el-radio-group></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" maxlength="500" type="textarea" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { listRules, getRule, createRule, updateRule } from '@/api/system/scheduleRule'
export default {
  name: 'ScheduleRule',
  data () { return { page: 1, rules: [], loading: false, saving: false, dialog: false, picked: [], form: { ruleName: '', dates: [], status: '0', remark: '' } } },
  created () { this.load() },
  methods: {
    async load () { this.loading = true; try { const r = await listRules(); this.rules = r.data || [] } finally { this.loading = false } },
    openNew () { this.form = { ruleName: '', dates: [], status: '0', remark: '' }; this.picked = []; this.dialog = true },
    async edit (id) { const r = await getRule(id); this.form = { ...r.data, dates: r.data.dates || [] }; this.picked = []; this.dialog = true },
    sortDates (dates) { this.form.dates = [...new Set(dates || [])].sort() },
    removeDate (date) { this.form.dates = this.form.dates.filter(item => item !== date) },
    async save () {
      if (!this.form.ruleName || !this.form.dates.length) { this.$modal.msgError('请填写规则名称并选择上课日期'); return }
      this.saving = true
      try { if (this.form.ruleId) await updateRule(this.form.ruleId, this.form); else await createRule(this.form); this.dialog = false; this.$modal.msgSuccess('保存成功'); await this.load() } finally { this.saving = false }
    }
  }
}
</script>
