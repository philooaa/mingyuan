<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="100px">
      <el-form-item label="学期" prop="termName">
        <el-select v-model="queryParams.termName" placeholder="全部学期" clearable style="width: 140px;">
          <el-option v-for="dict in dict.type.edu_term" :key="dict.value" :label="dict.label" :value="dict.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="期次" prop="periodName">
        <el-input
          v-model="queryParams.periodName"
          placeholder="如 周六"
          clearable
          style="width: 120px;"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="原上课日期" prop="originalDate">
        <el-date-picker clearable
          v-model="queryParams.originalDate"
          type="date"
          value-format="yyyy-MM-dd"
          placeholder="请选择原上课日期">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="调课原因" prop="reason">
        <el-input
          v-model="queryParams.reason"
          placeholder="请输入调课原因"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['system:scheduleAdjustment:add']"
        >新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['system:scheduleAdjustment:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['system:scheduleAdjustment:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['system:scheduleAdjustment:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="scheduleAdjustmentList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="学期" align="center" prop="termName" width="100" />
      <el-table-column label="期次" align="center" prop="periodName" width="100" />
      <el-table-column label="原上课日期" align="center" prop="originalDate" width="130" />
      <el-table-column label="调整后日期" align="center" prop="adjustedDate" width="130">
        <template slot-scope="scope">
          <span v-if="scope.row.adjustedDate">{{ scope.row.adjustedDate }}</span>
          <el-tag v-else type="danger">停课</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="涉及课程" align="center" width="110">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.scheduleId === 0" type="success" size="mini">
            {{ scope.row.courseCount ? scope.row.courseCount + ' 门' : '整期次' }}
          </el-tag>
          <span v-else style="color: #909399;">单课</span>
        </template>
      </el-table-column>
      <el-table-column label="调课原因" align="center" prop="reason" :show-overflow-tooltip="true" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="160" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="160">
        <template slot-scope="scope">
          <el-button
            v-if="scope.row.scheduleId !== 0"
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:scheduleAdjustment:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:scheduleAdjustment:remove']"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total>0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <el-dialog :title="title" :visible.sync="open" width="620px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="调课方式" v-if="form.adjustmentId == null">
          <el-radio-group v-model="modeType">
            <el-radio label="batch">批量调课</el-radio>
            <el-radio label="single">单独调课</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.adjustmentId == null && modeType === 'batch'">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px;"
            title="按学期+期次把日期范围内的每个上课日统一调整或停课；每个上课日只生成一条记录，管当天该期次的全部课程" />
          <el-form-item label="学期" required>
            <el-select v-model="batchForm.termName" placeholder="请选择学期" style="width: 100%;" @change="handleBatchTermChange">
              <el-option v-for="dict in dict.type.edu_term" :key="dict.value" :label="dict.label" :value="dict.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="期次/上课日" required>
            <el-select v-model="batchForm.periodName" placeholder="请先选择学期" style="width: 100%;">
              <el-option v-for="p in availablePeriods" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item label="年级筛选">
            <el-select v-model="batchForm.gradeName" placeholder="全部年级（可选）" clearable style="width: 100%;">
              <el-option v-for="dict in dict.type.edu_grade" :key="dict.value" :label="dict.label" :value="dict.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="科目筛选">
            <el-select v-model="batchForm.subjectName" placeholder="全部科目（可选）" clearable style="width: 100%;">
              <el-option v-for="dict in dict.type.edu_subject" :key="dict.value" :label="dict.label" :value="dict.label" />
            </el-select>
            <div style="color: #909399; font-size: 12px; margin-top: 4px;">留空＝整期次一起调，一天只记一条；选了年级/科目就只调这部分课，按课程单独记录</div>
          </el-form-item>
          <el-form-item label="原上课日期" required>
            <el-date-picker v-model="batchForm.dateRange" type="daterange" value-format="yyyy-MM-dd"
              range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" style="width: 100%;" />
            <div style="color: #909399; font-size: 12px; margin-top: 4px;">只匹配范围内的实际上课日（按每周一次/上5休1自动计算），已调过的日期自动跳过</div>
          </el-form-item>
          <el-form-item label="调整方式" required>
            <el-radio-group v-model="batchForm.adjustMode">
              <el-radio label="MOVE_TO">调至指定日期</el-radio>
              <el-radio label="POSTPONE">顺延</el-radio>
              <el-radio label="CANCEL">停课</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="batchForm.adjustMode === 'MOVE_TO'" label="目标日期" required>
            <el-date-picker v-model="batchForm.targetDate" type="date" value-format="yyyy-MM-dd" placeholder="调整到哪一天" style="width: 100%;" />
            <div style="color: #909399; font-size: 12px; margin-top: 4px;">范围内第一个上课日调到该日期，其余保持原有间隔依次顺移</div>
          </el-form-item>
          <el-form-item v-if="batchForm.adjustMode === 'POSTPONE'" label="顺延天数" required>
            <el-input-number v-model="batchForm.offsetDays" :min="1" :max="90" />
            <span style="margin-left: 8px; color: #909399;">天（如周六调到周一填 2）</span>
          </el-form-item>
          <el-form-item label="调课原因" required>
            <el-input v-model="batchForm.reason" placeholder="如：国庆节放假调休" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="排课ID" prop="scheduleId">
            <el-input-number v-model="form.scheduleId" :min="0" :step="1" placeholder="请输入排课ID" style="width: 100%;" />
          </el-form-item>
          <el-form-item label="原上课日期" prop="originalDate">
            <el-date-picker clearable
              v-model="form.originalDate"
              type="date"
              value-format="yyyy-MM-dd"
              placeholder="请选择原上课日期"
              style="width: 100%;">
            </el-date-picker>
          </el-form-item>
          <el-form-item label="调整后日期" prop="adjustedDate">
            <el-date-picker clearable
              v-model="form.adjustedDate"
              type="date"
              value-format="yyyy-MM-dd"
              placeholder="请选择调整后日期"
              style="width: 100%;">
            </el-date-picker>
            <div style="color: #909399; font-size: 12px; margin-top: 4px;">留空表示停课</div>
          </el-form-item>
          <el-form-item label="调课原因" prop="reason">
            <el-input v-model="form.reason" placeholder="请输入调课原因" />
          </el-form-item>
          <el-form-item label="备注" prop="remark">
            <el-input v-model="form.remark" type="textarea" placeholder="请输入备注" />
          </el-form-item>
        </template>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listScheduleAdjustment, getScheduleAdjustment, delScheduleAdjustment, addScheduleAdjustment, updateScheduleAdjustment, batchScheduleAdjustment } from "@/api/system/scheduleAdjustment"

export default {
  name: "ScheduleAdjustment",
  dicts: ['edu_term', 'edu_grade', 'edu_subject'],
  data() {
    return {
      loading: true,
      modeType: "batch",
      batchForm: {
        termName: null,
        periodName: null,
        gradeName: null,
        subjectName: null,
        dateRange: [],
        adjustMode: "MOVE_TO",
        offsetDays: 1,
        targetDate: null,
        reason: null
      },
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      scheduleAdjustmentList: [],
      title: "",
      open: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        termName: null,
        periodName: null,
        originalDate: null,
        reason: null
      },
      form: {},
      rules: {
        scheduleId: [
          { required: true, message: "排课ID不能为空", trigger: "blur" }
        ],
        originalDate: [
          { required: true, message: "原上课日期不能为空", trigger: "change" }
        ],
        reason: [
          { required: true, message: "调课原因不能为空", trigger: "blur" }
        ]
      }
    }
  },
  computed: {
    // 期次随学期联动：暑假/寒假为一二三期，春季/秋季为周六周日
    availablePeriods() {
      if (this.batchForm.termName === '暑假' || this.batchForm.termName === '寒假') {
        return ['一期', '二期', '三期']
      }
      return ['周六', '周日']
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listScheduleAdjustment(this.queryParams).then(response => {
        this.scheduleAdjustmentList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        adjustmentId: null,
        scheduleId: null,
        originalDate: null,
        adjustedDate: null,
        reason: null,
        remark: null
      }
      this.modeType = "batch"
      this.batchForm = {
        termName: null,
        periodName: null,
        gradeName: null,
        subjectName: null,
        dateRange: [],
        adjustMode: "MOVE_TO",
        offsetDays: 1,
        targetDate: null,
        reason: null
      }
      this.resetForm("form")
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.adjustmentId)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "新增调课"
    },
    handleUpdate(row) {
      this.reset()
      const adjustmentId = row.adjustmentId || this.ids[0]
      getScheduleAdjustment(adjustmentId).then(response => {
        this.form = response.data
        this.open = true
        this.modeType = "single"
        this.title = "修改调课"
      })
    },
    submitForm() {
      if (this.form.adjustmentId != null) {
        this.$refs["form"].validate(valid => {
          if (valid) {
            updateScheduleAdjustment(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          }
        })
        return
      }
      if (this.modeType === "batch") {
        this.submitBatch()
        return
      }
      this.$refs["form"].validate(valid => {
        if (valid) {
          addScheduleAdjustment(this.form).then(response => {
            this.$modal.msgSuccess("新增成功")
            this.open = false
            this.getList()
          })
        }
      })
    },
    // 学期切换后重置期次为该学期的第一个可选项
    handleBatchTermChange() {
      this.batchForm.periodName = this.availablePeriods.length ? this.availablePeriods[0] : null
    },
    // 批量调课提交
    submitBatch() {
      const b = this.batchForm
      if (!b.termName) {
        this.$modal.msgError("请选择学期")
        return
      }
      if (!b.periodName) {
        this.$modal.msgError("请选择期次")
        return
      }
      if (!b.dateRange || b.dateRange.length !== 2 || !b.dateRange[0] || !b.dateRange[1]) {
        this.$modal.msgError("请选择原上课日期范围")
        return
      }
      if (b.adjustMode === "MOVE_TO" && !b.targetDate) {
        this.$modal.msgError("请选择调整后的目标日期")
        return
      }
      if (b.adjustMode === "POSTPONE" && (!b.offsetDays || b.offsetDays < 1)) {
        this.$modal.msgError("请填写顺延天数（至少1天）")
        return
      }
      if (!b.reason) {
        this.$modal.msgError("请填写调课原因")
        return
      }
      const payload = {
        termName: b.termName,
        periodName: b.periodName,
        gradeName: b.gradeName || undefined,
        subjectName: b.subjectName || undefined,
        originalStartDate: b.dateRange[0],
        originalEndDate: b.dateRange[1],
        adjustMode: b.adjustMode,
        offsetDays: b.adjustMode === "POSTPONE" ? b.offsetDays : undefined,
        targetDate: b.adjustMode === "MOVE_TO" ? b.targetDate : undefined,
        reason: b.reason
      }
      batchScheduleAdjustment(payload).then(response => {
        this.$modal.msgSuccess(response.msg || "批量调课成功")
        this.open = false
        this.getList()
      })
    },
    handleDelete(row) {
      const adjustmentIds = row.adjustmentId || this.ids
      this.$modal.confirm('是否确认删除选中的调课记录？').then(function() {
        return delScheduleAdjustment(adjustmentIds)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    handleExport() {
      this.download('system/scheduleAdjustment/export', {
        ...this.queryParams
      }, `scheduleAdjustment_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>
