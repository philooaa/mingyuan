<template>
  <div class="app-container edu-workspace">
    <div class="edu-page-heading"><div><h2>排课管理</h2><p>查看课程时间与教师安排，维护班课和一对一课程</p></div><div class="edu-result-count">共 <strong>{{ total }}</strong> 条排课</div></div>
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="100px">
      <el-form-item label="排课编码" prop="scheduleCode" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.scheduleCode"
          placeholder="请输入排课编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="教室" prop="classroomId" v-show="showAdvancedFilters">
        <el-select v-model="queryParams.classroomId" filterable clearable placeholder="全部教室" @change="handleQuery"><el-option v-for="item in dims.classroomList" :key="item.classroomId" :label="item.campusName + ' · ' + item.classroomName" :value="item.classroomId" /></el-select>
      </el-form-item>
      <el-form-item label="课程年份" prop="courseYear" v-show="showAdvancedFilters">
        <el-date-picker v-model="queryParams.courseYear" type="year" value-format="yyyy" placeholder="请选择年份" clearable @change="handleQuery" />
      </el-form-item>
      <el-form-item label="学期" prop="termName" v-show="showAdvancedFilters">
        <el-select v-model="queryParams.termName" filterable clearable placeholder="全部学期" @change="handleQuery"><el-option v-for="item in dims.terms" :key="item.value" :label="item.label" :value="item.value" /></el-select>
      </el-form-item>
      <el-form-item label="期次或上课日" prop="periodName">
        <el-select v-model="queryParams.periodName" filterable clearable placeholder="全部期次或上课日" @change="handleQuery"><el-option v-for="item in periodOptions(queryParams.termName)" :key="item.value" :label="item.label" :value="item.value" /></el-select>
      </el-form-item>
      <el-form-item label="时段" prop="timeSlot" v-show="showAdvancedFilters">
        <el-select v-model="queryParams.timeSlot" filterable clearable placeholder="全部时段" @change="handleQuery"><el-option v-for="item in timeSlotOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select>
      </el-form-item>
      <el-form-item label="开始时间" prop="startTime" v-show="showAdvancedFilters">
        <el-time-picker clearable
          v-model="queryParams.startTime"
          value-format="HH:mm:ss"
          placeholder="请选择开始时间">
        </el-time-picker>
      </el-form-item>
      <el-form-item label="结束时间" prop="endTime" v-show="showAdvancedFilters">
        <el-time-picker clearable
          v-model="queryParams.endTime"
          value-format="HH:mm:ss"
          placeholder="请选择结束时间">
        </el-time-picker>
      </el-form-item>
      <el-form-item label="年级" prop="gradeName">
        <el-select v-model="queryParams.gradeName" filterable clearable placeholder="全部年级" @change="handleQuery"><el-option v-for="item in dims.grades" :key="item.value" :label="item.label" :value="item.value" /></el-select>
      </el-form-item>
      <el-form-item label="科目" prop="subjectName">
        <el-select v-model="queryParams.subjectName" filterable clearable placeholder="全部学科" @change="handleQuery"><el-option v-for="item in dims.subjects" :key="item.value" :label="item.label" :value="item.value" /></el-select>
      </el-form-item>
      <el-form-item label="教师姓名" prop="teacherName">
        <el-select v-model="queryParams.teacherName" filterable clearable placeholder="全部教师" @change="handleQuery"><el-option v-for="item in dims.teachers" :key="item.userId" :label="item.nickName + '（' + item.userName + '）'" :value="item.nickName" /></el-select>
      </el-form-item>
      <el-form-item label="已报名人数" prop="enrolledCount" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.enrolledCount"
          placeholder="请输入已报名人数"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="课程班名称" prop="courseClassName">
        <el-input
          v-model="queryParams.courseClassName"
          placeholder="请输入课程班名称"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="来源Sheet" prop="sourceSheet" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.sourceSheet"
          placeholder="请输入来源Sheet"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="来源行" prop="sourceRow" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.sourceRow"
          placeholder="请输入来源行"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="来源列" prop="sourceCol" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.sourceCol"
          placeholder="请输入来源列"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        <el-button type="text" @click="showAdvancedFilters = !showAdvancedFilters">{{ showAdvancedFilters ? "收起更多筛选" : "更多筛选" }}</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="el-icon-date" size="mini" v-hasRole="['admin']" @click="openSingle()">一对一管理</el-button></el-col>
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['system:schedule:add']"
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
          v-hasPermi="['system:schedule:edit']"
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
          v-hasPermi="['system:schedule:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['system:schedule:export']"
        >导出</el-button>
      </el-col>
      <el-checkbox v-model="showDetailedColumns" class="edu-column-toggle">详细字段</el-checkbox>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="scheduleList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="课程" prop="courseClassName" min-width="220" fixed="left"><template slot-scope="scope"><div class="edu-table-name">{{ scope.row.courseClassName || '未命名课程' }}</div><div class="edu-table-secondary">{{ scope.row.gradeName }} · {{ scope.row.subjectName }} · {{ scope.row.teacherName || '教师待配置' }}</div></template></el-table-column>
      <el-table-column label="上课时间" min-width="180"><template slot-scope="scope"><div class="edu-table-time">{{ scope.row.startTime || '待配置' }} – {{ scope.row.endTime || '待配置' }}</div><div class="edu-table-secondary">{{ scope.row.periodName || scope.row.timeSlot || '上课日待配置' }}</div></template></el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-scheduleId" label="排课ID" align="center" prop="scheduleId" />
      <el-table-column v-if="showDetailedColumns" key="detail-scheduleCode" label="排课编码" align="center" prop="scheduleCode" />
      <el-table-column v-if="showDetailedColumns" key="detail-classroomId" label="教室ID" align="center" prop="classroomId" />
      <el-table-column label="自定义上课地点" align="center" prop="lessonLocation" min-width="160" />
      <el-table-column v-if="showDetailedColumns" key="detail-courseYear" label="课程年份" align="center" prop="courseYear" />
      <el-table-column v-if="showDetailedColumns" key="detail-termName" label="学期" align="center" prop="termName" />
      <el-table-column v-if="showDetailedColumns" key="detail-periodName" label="期次或上课日" align="center" prop="periodName" />
      <el-table-column v-if="showDetailedColumns" key="detail-timeSlot" label="时段" align="center" prop="timeSlot" />
      <el-table-column v-if="showDetailedColumns" key="detail-startTime" label="开始时间" align="center" prop="startTime" width="180">
        <template slot-scope="scope">
          <span>{{ scope.row.startTime || '待配置' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-endTime" label="结束时间" align="center" prop="endTime" width="180">
        <template slot-scope="scope">
          <span>{{ scope.row.endTime || '待配置' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-gradeName" label="年级" align="center" prop="gradeName" />
      <el-table-column v-if="showDetailedColumns" key="detail-subjectName" label="科目" align="center" prop="subjectName" />
      <el-table-column v-if="showDetailedColumns" key="detail-teacherName" label="教师姓名" align="center" prop="teacherName" />
      <el-table-column label="班型" align="center" prop="classType" />
      <el-table-column label="授课形式" align="center" width="90">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.classMode === '2' ? 'warning' : 'success'">
            {{ scope.row.classMode === '2' ? '一对一' : '班课' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="报名 / 上限" align="center" width="110">
        <template slot-scope="scope">
          <span>{{ scope.row.enrolledCount || 0 }}</span>
          <span style="color: #c0c4cc"> / {{ scope.row.enrollLimit > 0 ? scope.row.enrollLimit : '不限' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="课时单价" align="center" prop="unitPrice" />
      <el-table-column label="招生状态" align="center"><template slot-scope="scope">{{ { '0': '可报名', '1': '停招', '2': '满班' }[scope.row.recruitStatus] || '未知' }}</template></el-table-column>
      <el-table-column label="排课状态" align="center"><template slot-scope="scope">{{ scope.row.status === '1' ? '停用' : '正常' }}</template></el-table-column>
      <el-table-column label="排课日期" min-width="210"><template slot-scope="scope">{{ scope.row.startDate && scope.row.endDate ? `${scope.row.startDate} 至 ${scope.row.endDate}` : '待配置' }}</template></el-table-column>

      <el-table-column v-if="showDetailedColumns" key="detail-sourceSheet" label="来源Sheet" align="center" prop="sourceSheet" />
      <el-table-column v-if="showDetailedColumns" key="detail-sourceRow" label="来源行" align="center" prop="sourceRow" />
      <el-table-column v-if="showDetailedColumns" key="detail-sourceCol" label="来源列" align="center" prop="sourceCol" />
      <el-table-column v-if="showDetailedColumns" key="detail-status" label="状态" align="center" prop="status" />
      <el-table-column v-if="showDetailedColumns" key="detail-remark" label="备注" align="center" prop="remark" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" fixed="right" width="180">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:schedule:edit']"
          >{{ scope.row.classMode === '2' ? '管理' : '修改' }}</el-button>
          <el-button
            v-if="scope.row.classMode !== '2'"
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:schedule:remove']"
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

    <!-- 添加或修改课程排课对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-row>
          <el-col :span="24">
            <el-form-item label="排课编码" prop="scheduleCode">
              <el-input v-model="form.scheduleCode" placeholder="留空自动生成" :disabled="!!form.scheduleId" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item v-if="form.classMode !== '2'" label="教室" prop="classroomId">
              <el-select v-model="form.classroomId" filterable placeholder="请选择教室"><el-option v-for="room in dims.classroomList" :key="room.classroomId" :value="room.classroomId" :label="`${room.campusName} · ${room.classroomName}`" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24" v-if="form.classMode === '2'">
            <el-form-item label="上课地点" prop="lessonLocation"><el-input v-model="form.lessonLocation" maxlength="255" placeholder="填写实际上课地点" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="课程年份" prop="courseYear">
              <el-date-picker v-model="form.courseYear" type="year" value-format="yyyy" placeholder="请选择年份" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="学期" prop="termName">
              <el-select v-model="form.termName" filterable placeholder="请选择学期" style="width:100%"><el-option v-for="item in dims.terms" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="期次或上课日" prop="periodName">
              <el-select v-model="form.periodName" filterable placeholder="请选择期次或上课日" style="width:100%"><el-option v-for="item in periodOptions(form.termName)" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="时段" prop="timeSlot">
              <el-select v-model="form.timeSlot" filterable placeholder="请选择时段" style="width:100%" @change="onTimeSlotChange"><el-option v-for="item in timeSlotOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="开始时间" prop="startTime">
              <el-time-picker clearable
                v-model="form.startTime"
                      value-format="HH:mm:ss"
                placeholder="请选择开始时间">
              </el-time-picker>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="结束时间" prop="endTime">
              <el-time-picker clearable
                v-model="form.endTime"
                      value-format="HH:mm:ss"
                placeholder="请选择结束时间">
              </el-time-picker>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="年级" prop="gradeName">
              <el-select v-model="form.gradeName" filterable placeholder="请选择年级" style="width:100%"><el-option v-for="item in dims.grades" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="科目" prop="subjectName">
              <el-select v-model="form.subjectName" filterable placeholder="请选择学科" style="width:100%"><el-option v-for="item in dims.subjects" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="教师" prop="teacherId">
              <el-select v-model="form.teacherId" filterable placeholder="请选择教师账号" :disabled="dims.isTeacher && !dims.isAdmin"><el-option v-for="teacher in dims.teachers" :key="teacher.userId" :value="teacher.userId" :label="`${teacher.nickName}（${teacher.userName}）`" /></el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="授课形式" prop="classMode">
              <el-select v-model="form.classMode" placeholder="请选择授课形式" :disabled="!form.scheduleId && dims.isTeacher && !dims.isAdmin">
                <el-option label="班课" value="1" />
                <el-option label="一对一（请使用一对一管理）" value="2" :disabled="dims.isAdmin" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="已报名人数" prop="enrolledCount">
              <el-input v-model="form.enrolledCount" disabled placeholder="由报名业务维护" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="报名上限" prop="enrollLimit">
              <el-input-number v-model="form.enrollLimit" :min="0" :precision="0" :step="1"
                controls-position="right" style="width: 100%" placeholder="留空或 0 表示不限" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="课时单价(元)" prop="unitPrice">
              <el-input-number v-model="form.unitPrice" :min="0" :precision="2" :step="50" controls-position="right" style="width: 100%" placeholder="元/人·次，用于财务统计" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="课程班名称" prop="courseClassName">
              <el-input v-model="form.courseClassName" placeholder="请输入课程班名称" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="来源Sheet" prop="sourceSheet">
              <el-input v-model="form.sourceSheet" placeholder="请输入来源Sheet" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="来源行" prop="sourceRow">
              <el-input v-model="form.sourceRow" placeholder="请输入来源行" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="来源列" prop="sourceCol">
              <el-input v-model="form.sourceCol" placeholder="请输入来源列" />
            </el-form-item>
          </el-col>
          <el-col :span="24"><el-form-item label="开课日期"><el-date-picker v-model="form.startDate" type="date" value-format="yyyy-MM-dd" placeholder="待配置" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="结课日期"><el-date-picker v-model="form.endDate" type="date" value-format="yyyy-MM-dd" placeholder="待配置" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="上课模式"><el-select v-model="form.classPattern" clearable><el-option label="每周一次（周六／周日）" value="WEEKLY" /><el-option label="上5天休1天" value="DAILY_5_1" /></el-select></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="招生状态"><el-select v-model="form.recruitStatus"><el-option label="可报名" value="0" /><el-option label="停招" value="1" /><el-option label="满班" value="2" /></el-select></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="排课状态"><el-select v-model="form.status"><el-option label="正常" value="0" /><el-option label="停用" value="1" /></el-select></el-form-item></el-col>
          <el-col :span="24">
            <el-form-item label="备注" prop="remark">
              <el-input v-model="form.remark" type="textarea" placeholder="请输入内容" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitting" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
    <OneToOneManager ref="oneToOne" :dims="dims" @changed="getList" />
  </div>
</template>

<script>
import { listSchedule, getSchedule, delSchedule, addSchedule, updateSchedule } from "@/api/system/schedule"
import { getTimetableDimensions } from "@/api/system/timetable"
import OneToOneManager from './OneToOneManager.vue'

export default {
  name: "Schedule",
  components: { OneToOneManager },
  data() {
    return {
      // 遮罩层
      loading: true,
      submitting: false,
      dims: { classroomList: [], teachers: [], terms: [], periods: [], periodMap: {}, timeSlots: [], grades: [], subjects: [], classTypes: [], isTeacher: false, isAdmin: false },
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      showDetailedColumns: false,
      showAdvancedFilters: false,
      // 总条数
      total: 0,
      // 课程排课表格数据
      scheduleList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        scheduleCode: null,
        classroomId: null,
        courseYear: null,
        termName: null,
        periodName: null,
        timeSlot: null,
        startTime: null,
        endTime: null,
        gradeName: null,
        subjectName: null,
        teacherName: null,
        classType: null,
        enrolledCount: null,
        enrollLimit: null,
        unitPrice: null,
        recruitStatus: null,
        courseClassName: null,
        sourceSheet: null,
        sourceRow: null,
        sourceCol: null,
        status: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        lessonLocation: [{ validator: (rule, value, callback) => this.form.classMode !== '2' || (value && value.trim()) ? callback() : callback(new Error("请填写上课地点")), trigger: "blur" }],
        teacherId: [{ required: true, message: "请选择教师账号", trigger: "change" }],
        classroomId: [
          { validator: (rule, value, callback) => this.form.classMode === '2' || value ? callback() : callback(new Error("请选择教室")), trigger: "change" }
        ],
        termName: [
          { required: true, message: "学期不能为空", trigger: "blur" }
        ],
        periodName: [
          { required: true, message: "期次或上课日不能为空", trigger: "blur" }
        ],
        timeSlot: [
          { required: true, message: "时段不能为空", trigger: "blur" }
        ],
        gradeName: [
          { required: true, message: "请选择年级", trigger: "change" }
        ],
        subjectName: [
          { required: true, message: "请选择学科", trigger: "change" }
        ],
        courseClassName: [
          { required: true, message: "课程班名称不能为空", trigger: "blur" }
        ],
      }
    }
  },
  computed: {
    timeSlotOptions() {
      const values = [...(this.dims.timeSlots || []).map(item => item.value), this.form.timeSlot].filter(Boolean)
      return [...new Set(values)].map(value => ({ label: value, value }))
    }
  },
  created() {
    this.getList()
    getTimetableDimensions().then(response => { this.dims = response.data || this.dims }).catch(() => {})
  },
  methods: {
    openSingle(row = {}, tab = 'slots') {
      if (!this.dims.isAdmin) { this.$modal.msgError('仅管理员可在网页统一管理一对一'); return }
      return this.$refs.oneToOne.open(row, tab)
    },
    periodOptions(term) {
      const values = [...((this.dims.periodMap || {})[term] || []), ...(this.dims.periods || []).map(item => item.value)]
      return [...new Set(values)].map(value => ({ label: value, value }))
    },
    onTimeSlotChange(value) {
      const parts = String(value || "").match(/^(\d{1,2}:\d{2})-(\d{1,2}:\d{2})$/)
      if (parts) { this.form.startTime = parts[1].padStart(5, "0") + ":00"; this.form.endTime = parts[2].padStart(5, "0") + ":00" }
    },
    /** 查询课程排课列表 */
    getList() {
      this.loading = true
      listSchedule(this.queryParams).then(response => {
        this.scheduleList = response.rows
        this.total = response.total
      }).finally(() => { this.loading = false })
    },
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    // 表单重置
    reset() {
      this.form = {
        scheduleId: null,
        scheduleCode: null,
        classroomId: null, lessonLocation: null,
        courseYear: String(new Date().getFullYear()),
        termName: null,
        periodName: null,
        timeSlot: null,
        startTime: null,
        endTime: null,
        startDate: null, endDate: null, classPattern: null,
        gradeName: null,
        subjectName: null,
        teacherName: null,
        teacherId: this.dims.isTeacher && !this.dims.isAdmin ? this.dims.currentUserId : null,
        classType: null,
        classMode: this.dims.isTeacher && !this.dims.isAdmin ? '2' : '1',
        enrolledCount: 0,
        enrollLimit: null,
        unitPrice: null,
        recruitStatus: '0',
        courseClassName: null,
        sourceSheet: null,
        sourceRow: null,
        sourceCol: null,
        status: '0',
        delFlag: null,
        createBy: null,
        createTime: null,
        updateBy: null,
        updateTime: null,
        remark: null
      }
      this.resetForm("form")
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.scheduleId)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加课程排课"
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const scheduleId = row.scheduleId || this.ids[0]
      getSchedule(scheduleId).then(response => {
        if (response.data.classMode === '2' && this.dims.isAdmin) { this.openSingle(response.data, 'courses'); return }
        this.form = response.data
        this.form.courseYear = this.form.courseYear == null ? null : String(this.form.courseYear)
        this.open = true
        this.title = "修改课程排课"
      })
    },
    /** 提交按钮 */
    submitForm() {
      if (this.submitting) return
      this.$refs.form.validate(async valid => {
        if (!valid || this.submitting) return
        this.submitting = true
        try {
          const payload = { ...this.form }
          delete payload.enrolledCount
          delete payload.delFlag
          if (payload.scheduleId) await updateSchedule(payload)
          else await addSchedule(payload)
          this.$modal.msgSuccess(payload.scheduleId ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        } finally { this.submitting = false }
      })
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const selected = row.scheduleId ? [row] : this.scheduleList.filter(s => this.ids.includes(s.scheduleId))
      if (selected.some(s => s.classMode === '2')) { this.$modal.msgWarning('一对一请在“一对一管理”中撤回，预约和考勤历史将保留'); return }
      const scheduleIds = row.scheduleId || this.ids
      this.$modal.confirm('是否确认删除课程排课编号为"' + scheduleIds + '"的数据项？').then(function() {
        return delSchedule(scheduleIds)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('system/schedule/export', {
        ...this.queryParams
      }, `schedule_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style scoped src="@/assets/styles/education-workspace.css"></style>
