<template>
  <el-form-item v-if="form.classMode !== '2'" label="排课规则">
    <template v-if="form.classPattern === 'CUSTOM_DATES'">
      <div v-if="form.scheduleRuleName">{{ form.scheduleRuleName }} · {{ (form.customDates || []).length }} 次计划课</div>
      <el-select v-model="selected" filterable placeholder="选择或重新应用启用规则" @change="apply">
        <el-option v-for="rule in rules" :key="rule.ruleId" :value="String(rule.ruleId)" :label="rule.ruleName" />
      </el-select>
      <el-button type="text" :disabled="!selected || loading" @click="apply(selected)">应用最新规则</el-button>
      <div v-if="loading">正在读取规则…</div>
      <div v-if="form.customDates && form.customDates.length" style="line-height:1.8;word-break:break-word">{{ form.customDates.join('、') }}</div>
      <div>起止日期自动取首尾上课日期。保存后不随规则修改而改变。</div>
    </template>
    <span v-else>使用上方递推模式；选择“自定义日期规则”可按指定日期排课。</span>
  </el-form-item>
</template>
<script>
import { listRules, getRule } from '@/api/system/scheduleRule'
export default {
  name: 'ScheduleRulePicker',
  props: { form: { type: Object, required: true } },
  data () { return { rules: [], selected: '', loading: false, serial: 0 } },
  watch: {
    form (value, previous) {
      if (value === previous) return
      this.selected = ''; this.serial++; this.loading = false
      if (value.classPattern === 'CUSTOM_DATES') this.load()
    },
    'form.classPattern': { immediate: true, handler (value) { if (value === 'CUSTOM_DATES') this.load() } }
  },
  methods: {
    async load () { const r = await listRules({ enabledOnly: true }); this.rules = r.data || [] },
    async apply (id) {
      if (!id) return
      const serial = ++this.serial
      const form = this.form
      this.loading = true
      this.$set(form, 'ruleSelectionLoading', true)
      try {
        const r = await getRule(id)
        if (serial !== this.serial || form !== this.form || form.classPattern !== 'CUSTOM_DATES') return
        const rule = r.data
        if (rule.status !== '0' || !rule.dates || !rule.dates.length) throw new Error('规则已停用或未配置日期')
        const dates = [...new Set(rule.dates)].sort()
        for (const [key, value] of Object.entries({ scheduleRuleId: rule.ruleId, scheduleRuleName: rule.ruleName, scheduleRuleVersion: rule.version, customDates: dates, startDate: dates[0], endDate: dates[dates.length - 1], applyScheduleRule: true, clearScheduleRule: false })) this.$set(form, key, value)
        if (!form.scheduleId && !form.requestedLessonCount) this.$set(form, 'requestedLessonCount', dates.length)
      } catch (e) { if (e.message) this.$message.error(e.message) } finally { if (serial === this.serial) { this.loading = false; this.$set(form, 'ruleSelectionLoading', false) } }
    }
  }
}
</script>
