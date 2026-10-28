<template>
  <div class="app-container">
    <el-alert
      title="课程单价按年级固定：一年级/二年级 150 元，三年级~六年级 220 元，初中 250 元，高中 280 元。课程总价 = 每节单价 × 本期课次数量。"
      type="info"
      :closable="false"
      class="mb8"
    />

    <el-card shadow="never" class="mb16">
      <div slot="header" class="card-header">
        <span>年级定价规则</span>
        <el-button size="mini" icon="el-icon-refresh" @click="loadRules">刷新</el-button>
      </div>
      <el-table v-loading="ruleLoading" :data="rules" size="small" border>
        <el-table-column label="年级" prop="gradeName" width="120" />
        <el-table-column label="规则单价（元/节）" width="230">
          <template slot-scope="scope">
            <el-input-number
              v-model="scope.row.editPrice"
              :min="0"
              :precision="2"
              :step="10"
              size="small"
              controls-position="right"
              style="width: 150px"
            />
          </template>
        </el-table-column>
        <el-table-column label="该年级课程数" prop="courseCount" width="130" align="center" />
        <el-table-column label="与规则不一致" width="130" align="center">
          <template slot-scope="scope">
            <el-tag v-if="Number(scope.row.pendingCount) > 0" type="warning" size="small">
              {{ scope.row.pendingCount }} 门
            </el-tag>
            <span v-else class="muted">已一致</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" prop="remark" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="230" fixed="right">
          <template slot-scope="scope">
            <el-button
              v-hasPermi="['system:coursePrice:edit']"
              type="text"
              size="mini"
              :loading="savingGrade === scope.row.gradeName"
              @click="saveRule(scope.row, false)"
            >
              只改规则
            </el-button>
            <el-button
              v-hasPermi="['system:coursePrice:edit']"
              type="text"
              size="mini"
              :loading="savingGrade === scope.row.gradeName"
              @click="saveRule(scope.row, true)"
            >
              保存并套用到课程
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>按年级 + 学科批量改价</span>
      </div>
      <el-form :inline="true" size="small" label-width="80px">
        <el-form-item label="年级">
          <el-select v-model="query.gradeName" filterable placeholder="请选择年级" @change="handlePreview">
            <el-option v-for="g in dims.grades" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="学科">
          <el-select v-model="query.subjectName" filterable clearable placeholder="全部学科" @change="handlePreview">
            <el-option v-for="s in dims.subjects" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="单价">
          <el-input-number
            v-model="query.unitPrice"
            :min="0"
            :precision="2"
            :step="10"
            controls-position="right"
            style="width: 150px"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="query.syncRule">同时更新该年级定价规则</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handlePreview">预览</el-button>
          <el-button
            v-hasPermi="['system:coursePrice:edit']"
            type="success"
            icon="el-icon-check"
            :disabled="!previewTotal"
            :loading="saving"
            @click="handleBatch"
          >
            应用到 {{ previewTotal }} 门课程
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="query.gradeName && !query.subjectName"
        title="未选择学科时，该年级下所有学科课程都会改成同一个单价。"
        type="warning"
        :closable="false"
        class="mb8"
      />
      <el-alert v-if="previewError" :title="previewError" type="error" :closable="false" class="mb8" />

      <el-table v-loading="previewLoading" :data="previewRows" size="small" border max-height="420">
        <el-table-column label="课程" prop="courseClassName" min-width="200" show-overflow-tooltip />
        <el-table-column label="年级" prop="gradeName" width="90" />
        <el-table-column label="学科" prop="subjectName" width="90" />
        <el-table-column label="学期 / 期次" width="140">
          <template slot-scope="scope">{{ scope.row.termName }} · {{ scope.row.periodName }}</template>
        </el-table-column>
        <el-table-column label="课次数" prop="lessonCount" width="80" align="center" />
        <el-table-column label="当前单价" width="100" align="right">
          <template slot-scope="scope">{{ money(scope.row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column label="改后单价" width="100" align="right">
          <template slot-scope="scope">{{ money(query.unitPrice) }}</template>
        </el-table-column>
        <el-table-column label="改后总价" width="120" align="right">
          <template slot-scope="scope">{{ money(total(scope.row)) }}</template>
        </el-table-column>
        <el-table-column label="校区" prop="campusName" width="110" />
      </el-table>
      <pagination
        v-show="previewTotal > 0"
        :total="previewTotal"
        :page.sync="pageNum"
        :limit.sync="pageSize"
        @pagination="handlePreview"
      />
    </el-card>
  </div>
</template>

<script>
import {
  listPriceRules,
  getPriceDims,
  listPriceCourses,
  batchCoursePrice,
  updatePriceRule,
} from '@/api/system/coursePrice'

export default {
  name: 'CoursePrice',
  data() {
    return {
      rules: [],
      ruleLoading: false,
      savingGrade: '',
      dims: { grades: [], subjects: [] },
      query: { gradeName: '', subjectName: '', unitPrice: 150, syncRule: false },
      previewRows: [],
      previewTotal: 0,
      previewLoading: false,
      previewError: '',
      pageNum: 1,
      pageSize: 20,
      saving: false,
    }
  },
  created() {
    this.loadDims()
    this.loadRules()
  },
  methods: {
    money(value) {
      if (value == null || value === '') return '—'
      const n = Number(value)
      return Number.isFinite(n) ? n.toFixed(2) : String(value)
    },
    total(row) {
      const price = Number(this.query.unitPrice || 0)
      const count = Math.max(Number(row.lessonCount || 1), 1)
      return (price * count).toFixed(2)
    },
    async loadDims() {
      try {
        const r = await getPriceDims()
        this.dims = r.data || this.dims
      } catch (e) {
        this.$modal.msgError(e.message || '年级学科加载失败')
      }
    },
    async loadRules() {
      this.ruleLoading = true
      try {
        const r = await listPriceRules()
        this.rules = (r.data || []).map((row) => ({
          ...row,
          editPrice: Number(row.unitPrice || 0),
        }))
      } catch (e) {
        this.$modal.msgError(e.message || '定价规则加载失败')
      } finally {
        this.ruleLoading = false
      }
    },
    async saveRule(row, applyCourses) {
      if (this.savingGrade) return
      const price = Number(row.editPrice)
      if (!(price > 0)) {
        this.$modal.msgError('单价必须大于 0')
        return
      }
      this.savingGrade = row.gradeName
      try {
        const r = await updatePriceRule({
          gradeName: row.gradeName,
          unitPrice: price.toFixed(2),
          applyCourses,
        })
        const updated = (r.data && r.data.updated) || 0
        this.$modal.msgSuccess(
          applyCourses ? `已保存并更新 ${updated} 门课程` : '规则已保存，未改动已有课程'
        )
        await this.loadRules()
        if (applyCourses) await this.handlePreview()
      } catch (e) {
        this.$modal.msgError(e.message || '保存失败')
      } finally {
        this.savingGrade = ''
      }
    },
    async handlePreview() {
      if (!this.query.gradeName) {
        this.previewRows = []
        this.previewTotal = 0
        return
      }
      this.previewLoading = true
      this.previewError = ''
      try {
        const r = await listPriceCourses({
          gradeName: this.query.gradeName,
          subjectName: this.query.subjectName || undefined,
          pageNum: this.pageNum,
          pageSize: this.pageSize,
        })
        this.previewRows = r.rows || []
        this.previewTotal = r.total || 0
      } catch (e) {
        this.previewRows = []
        this.previewTotal = 0
        this.previewError = e.message || '预览失败'
      } finally {
        this.previewLoading = false
      }
    },
    async handleBatch() {
      if (this.saving) return
      const price = Number(this.query.unitPrice)
      if (!(price > 0)) {
        this.$modal.msgError('单价必须大于 0')
        return
      }
      const scope = this.query.subjectName
        ? `${this.query.gradeName} · ${this.query.subjectName}`
        : `${this.query.gradeName}（全部学科）`
      try {
        await this.$modal.confirm(`确认把「${scope}」的 ${this.previewTotal} 门课程单价改为 ${price.toFixed(2)} 元？`)
      } catch (e) {
        return
      }
      this.saving = true
      try {
        const r = await batchCoursePrice({
          gradeName: this.query.gradeName,
          subjectName: this.query.subjectName || '',
          unitPrice: price.toFixed(2),
          syncRule: this.query.syncRule,
        })
        this.$modal.msgSuccess(`已更新 ${(r.data && r.data.updated) || 0} 门课程`)
        await Promise.all([this.loadRules(), this.handlePreview()])
      } catch (e) {
        this.$modal.msgError(e.message || '改价失败')
      } finally {
        this.saving = false
      }
    },
  },
}
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
