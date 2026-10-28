<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="10">
        <span class="summary">
          共 <b>{{ configList.length }}</b> 位教师，已配置 <b>{{ configuredCount }}</b> 位
        </span>
      </el-col>
      <el-col :span="14">
        <el-form :inline="true" size="mini" style="float: right">
          <el-form-item label="教师">
            <el-input
              v-model="queryParams.teacherName"
              placeholder="姓名/登录名"
              clearable
              style="width: 150px"
              @keyup.enter.native="getList"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" size="mini" @click="getList">搜索</el-button>
            <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="configList" border size="mini">
      <el-table-column label="教师" align="center" prop="teacherName" width="110" fixed>
        <template slot-scope="scope">
          <span>{{ scope.row.teacherName }}</span>
        </template>
      </el-table-column>

      <el-table-column label="负责课程" align="center" min-width="190">
        <template slot-scope="scope">
          <span v-if="scope.row.primaryClassCount" class="pill pill-primary">小学 {{ scope.row.primaryClassCount }} 门</span>
          <span v-if="scope.row.jhClassCount" class="pill pill-jh">中学 {{ scope.row.jhClassCount }} 门</span>
          <span v-if="scope.row.oneOnOneClassCount" class="pill pill-1on1">一对一 {{ scope.row.oneOnOneClassCount }} 门</span>
          <span v-if="!scope.row.primaryClassCount && !scope.row.jhClassCount && !scope.row.oneOnOneClassCount" class="muted">暂无排课</span>
        </template>
      </el-table-column>

      <el-table-column label="底薪(元/月)" align="center" width="110">
        <template slot-scope="scope">
          <span v-if="scope.row.configId">{{ money(scope.row.baseSalary) }}</span>
          <el-tag v-else size="mini" type="info">未配置</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="小学班课(元/时)" align="center" width="120">
        <template slot-scope="scope">
          <span v-if="scope.row.configId">{{ money(scope.row.ratePrimaryClass) }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="中学班课(元/时)" align="center" width="120">
        <template slot-scope="scope">
          <span v-if="scope.row.configId">{{ money(scope.row.rateJhClass) }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column align="center" width="180">
        <template slot="header">
          <span>一对一(元/时)</span>
          <el-tooltip content="按顺序：小学 / 初中 / 高中" placement="top">
            <i class="el-icon-question" style="margin-left: 3px; color: #909399" />
          </el-tooltip>
        </template>
        <template slot-scope="scope">
          <span v-if="scope.row.configId">
            {{ money(scope.row.ratePrimary1on1) }} / {{ money(scope.row.rateJunior1on1) }} /
            {{ money(scope.row.rateSenior1on1) }}
          </span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="提成(元/月)" align="center" width="105">
        <template slot-scope="scope">
          <span v-if="scope.row.configId">{{ money(scope.row.commissionAmount) }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="班课时长" align="center" width="95">
        <template slot-scope="scope">
          <span v-if="scope.row.configId">{{ scope.row.classHoursPerSession || 2 }} 时/次</span>
          <span v-else class="muted">2 时/次</span>
        </template>
      </el-table-column>

      <el-table-column label="状态" align="center" width="80">
        <template slot-scope="scope">
          <el-tag v-if="!scope.row.configId" size="mini" type="info">未配置</el-tag>
          <el-tag v-else size="mini" :type="scope.row.status === '0' ? 'success' : 'info'">
            {{ scope.row.status === '0' ? '正常' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="操作" align="center" width="120" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:salaryConfig:edit']"
          >编辑</el-button>
          <el-button
            v-if="scope.row.configId"
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:salaryConfig:remove']"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 单个教师的薪资编辑 -->
    <el-dialog :title="title" :visible.sync="open" width="620px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="150px">
        <el-form-item label="教师">
          <span class="teacher-name">{{ form.teacherName }}</span>
          <span class="pill pill-primary" v-if="form.primaryClassCount">小学 {{ form.primaryClassCount }} 门</span>
          <span class="pill pill-jh" v-if="form.jhClassCount">中学 {{ form.jhClassCount }} 门</span>
          <span class="pill pill-1on1" v-if="form.oneOnOneClassCount">一对一 {{ form.oneOnOneClassCount }} 门</span>
        </el-form-item>

        <el-form-item label="底薪（元/月）" prop="baseSalary">
          <el-input-number
            v-model="form.baseSalary"
            :min="0"
            :precision="2"
            :step="100"
            controls-position="right"
            style="width: 200px"
          />
        </el-form-item>

        <el-divider content-position="left">班课课时费（元/小时）</el-divider>
        <el-row>
          <el-col :span="12">
            <el-form-item label="小学班课">
              <el-input-number
                v-model="form.ratePrimaryClass"
                :min="0"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 160px"
              />
              <div class="hint">负责 {{ form.primaryClassCount || 0 }} 门小学课</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="中学班课">
              <el-input-number
                v-model="form.rateJhClass"
                :min="0"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 160px"
              />
              <div class="hint">负责 {{ form.jhClassCount || 0 }} 门中学课</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">一对一课时费（元/小时）</el-divider>
        <el-row>
          <el-col :span="12">
            <el-form-item label="小学一对一">
              <el-input-number
                v-model="form.ratePrimary1on1"
                :min="0"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 160px"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="初中一对一">
              <el-input-number
                v-model="form.rateJunior1on1"
                :min="0"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 160px"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="高中一对一">
              <el-input-number
                v-model="form.rateSenior1on1"
                :min="0"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 160px"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="一对一课门数">
              <span class="hint" style="line-height: 32px">共 {{ form.oneOnOneClassCount || 0 }} 门</span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">提成</el-divider>
        <el-form-item label="提成（元/月）">
          <el-input-number
            v-model="form.commissionAmount"
            :min="0"
            :precision="2"
            :step="100"
            controls-position="right"
            style="width: 160px"
          />
          <span class="hint" style="margin-left: 8px">固定金额，每月直接计入应发合计</span>
        </el-form-item>

        <el-divider content-position="left">其他</el-divider>
        <el-row>
          <el-col :span="12">
            <el-form-item label="班课时长(时/次)">
              <el-input-number
                v-model="form.classHoursPerSession"
                :min="0.5"
                :max="6"
                :precision="1"
                :step="0.5"
                controls-position="right"
                style="width: 160px"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio label="0">正常</el-radio>
                <el-radio label="1">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="open = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listConfig, addConfig, updateConfig, delConfig } from "@/api/system/salaryConfig"

export default {
  name: "SalaryConfig",
  data() {
    return {
      loading: false,
      configList: [],
      open: false,
      title: "",
      queryParams: { teacherName: null },
      form: {},
      rules: {
        teacherId: [{ required: true, message: "缺少教师信息", trigger: "change" }]
      }
    }
  },
  computed: {
    configuredCount() {
      return this.configList.filter(r => r.configId).length
    }
  },
  created() {
    this.getList()
  },
  methods: {
    money(v) {
      if (v === null || v === undefined || v === "") return "0.00"
      return Number(v).toFixed(2)
    },
    getList() {
      this.loading = true
      listConfig(this.queryParams)
        .then(res => {
          this.configList = res.data || []
        })
        .finally(() => {
          this.loading = false
        })
    },
    resetQuery() {
      this.queryParams = { teacherName: null }
      this.getList()
    },
    reset() {
      this.form = {
        configId: null,
        teacherId: null,
        teacherName: null,
        baseSalary: 0,
        ratePrimaryClass: 0,
        rateJhClass: 0,
        ratePrimary1on1: 0,
        rateJunior1on1: 0,
        rateSenior1on1: 0,
        classHoursPerSession: 2.0,
        commissionAmount: 0,
        status: "0",
        remark: null,
        primaryClassCount: 0,
        jhClassCount: 0,
        oneOnOneClassCount: 0
      }
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...this.form, ...row }
      if (!this.form.configId) {
        // 未配置：所有费率从 0 起填
        this.form.baseSalary = 0
        this.form.ratePrimaryClass = 0
        this.form.rateJhClass = 0
        this.form.ratePrimary1on1 = 0
        this.form.rateJunior1on1 = 0
        this.form.rateSenior1on1 = 0
        this.form.classHoursPerSession = 2.0
        this.form.commissionAmount = 0
        this.form.status = "0"
        this.form.remark = null
      }
      this.title = this.form.configId
        ? "编辑薪资标准 — " + this.form.teacherName
        : "配置薪资标准 — " + this.form.teacherName
      this.open = true
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        const payload = {
          configId: this.form.configId || null,
          teacherId: this.form.teacherId,
          teacherName: this.form.teacherName,
          baseSalary: this.form.baseSalary,
          ratePrimaryClass: this.form.ratePrimaryClass,
          rateJhClass: this.form.rateJhClass,
          ratePrimary1on1: this.form.ratePrimary1on1,
          rateJunior1on1: this.form.rateJunior1on1,
          rateSenior1on1: this.form.rateSenior1on1,
          classHoursPerSession: this.form.classHoursPerSession,
          commissionAmount: this.form.commissionAmount,
          status: this.form.status,
          remark: this.form.remark
        }
        const action = payload.configId ? updateConfig(payload) : addConfig(payload)
        action.then(() => {
          this.$modal.msgSuccess("保存成功")
          this.open = false
          this.getList()
        })
      })
    },
    handleDelete(row) {
      this.$modal
        .confirm(`确认删除「${row.teacherName}」的薪资标准？删除后该教师当月结算将没有底薪与课时单价。`)
        .then(() => {
          return delConfig(row.configId)
        })
        .then(() => {
          this.getList()
          this.$modal.msgSuccess("删除成功")
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.summary {
  font-size: 13px;
  color: #606266;
  line-height: 32px;
}
.summary b {
  color: #303133;
}
.muted {
  color: #c0c4cc;
}
.pill {
  display: inline-block;
  margin-right: 4px;
  padding: 0 6px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
}
.pill-primary {
  color: #529b2e;
  background: #f0f9eb;
}
.pill-jh {
  color: #b88230;
  background: #fdf6ec;
}
.pill-1on1 {
  color: #909399;
  background: #f4f4f5;
}
.teacher-name {
  font-weight: 600;
  margin-right: 8px;
}
.hint {
  font-size: 12px;
  color: #909399;
  line-height: 18px;
}
</style>
