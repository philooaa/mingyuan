<template>
  <div class="app-container edu-workspace">
    <div class="edu-page-heading"><div><h2>课程报名</h2><p>查看学员报名、课程安排和上课记录</p></div><div class="edu-result-count">共 <strong>{{ total }}</strong> 条报名</div></div>
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="100px">
      <el-form-item label="报名编码" prop="enrollmentCode" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.enrollmentCode"
          placeholder="请输入报名编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="排课ID" prop="scheduleId" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.scheduleId"
          placeholder="请输入排课ID"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="家长用户ID" prop="parentId" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.parentId"
          placeholder="请输入家长用户ID"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="学生姓名" prop="studentName">
        <el-input
          v-model="queryParams.studentName"
          placeholder="请输入学生姓名"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="学生手机号" prop="studentPhone">
        <el-input
          v-model="queryParams.studentPhone"
          placeholder="请输入学生手机号"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="联系电话" prop="contactPhone">
        <el-input
          v-model="queryParams.contactPhone"
          placeholder="请输入联系电话"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="取消时间" prop="cancelTime" v-show="showAdvancedFilters">
        <el-date-picker clearable
          v-model="queryParams.cancelTime"
          type="date"
          value-format="yyyy-MM-dd"
          placeholder="请选择取消时间">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="取消原因" prop="cancelReason" v-show="showAdvancedFilters">
        <el-input
          v-model="queryParams.cancelReason"
          placeholder="请输入取消原因"
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
      <el-col :span="1.5"><el-button type="primary" plain size="mini" icon="el-icon-date" v-hasRole="['admin']" @click="openSingle()">一对一预约管理</el-button></el-col>
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['system:enrollment:add']"
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
          v-hasPermi="['system:enrollment:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="cancelIds.length === 0"
          @click="handleCancelEnrollment"
          v-hasPermi="['system:enrollment:remove']"
        >取消报名</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['system:enrollment:export']"
        >导出</el-button>
      </el-col>
      <el-checkbox v-model="showDetailedColumns" class="edu-column-toggle">详细字段</el-checkbox>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="enrollmentList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="学生" prop="studentName" min-width="140" fixed="left"><template slot-scope="scope"><div class="edu-table-name">{{ scope.row.studentName || '未提供姓名' }}</div><div class="edu-table-secondary">{{ scope.row.contactPhone || scope.row.studentPhone || '电话未提供' }}</div></template></el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-enrollmentId" label="报名ID" align="center" prop="enrollmentId" />
      <el-table-column v-if="showDetailedColumns" key="detail-enrollmentCode" label="报名编码" align="center" prop="enrollmentCode" min-width="160" show-overflow-tooltip />
      <el-table-column label="课程信息" align="left" min-width="190">
        <template slot-scope="scope">
          <div v-if="scope.row.courseClassName || scope.row.subjectName" class="schedule-info">
            <div class="edu-table-name">{{ scope.row.courseClassName || '未命名课程' }}</div>
            <div class="info-grade">{{ scope.row.gradeName || '-' }} · {{ scope.row.subjectName || '-' }} <span v-if="scope.row.classType" class="info-type">{{ scope.row.classType }}</span></div>
            <div class="info-teacher">{{ scope.row.teacherName || '-' }} · <span class="info-status" :class="'rs-' + scope.row.recruitStatus">{{ getRecruitLabel(scope.row.recruitStatus) }}</span></div>
          </div>
          <span v-else class="text-muted">暂无排课信息</span>
        </template>
      </el-table-column>

      <el-table-column v-if="showDetailedColumns" key="detail-studentPhone" label="学生手机号" align="center" prop="studentPhone" />
      <el-table-column v-if="showDetailedColumns" key="detail-contactPhone" label="联系电话" align="center" prop="contactPhone" />
      <el-table-column label="上课记录" align="center" width="180">
        <template slot-scope="scope">
          <div>已复核到课：{{ scope.row.attendedLessonCount || 0 }} 次</div>
          <div>待复核到课：{{ scope.row.pendingLessonCount || 0 }} 次</div>
          <div>录播：{{ scope.row.recordedLessonCount || 0 }} 次</div>
          <div>请假：{{ scope.row.leaveLessonCount || 0 }} 次</div>
        </template>
      </el-table-column>
      <el-table-column label="报名状态" align="center" prop="enrollmentStatus">
        <template slot-scope="scope">
          {{ getEnrollmentStatusLabel(scope.row.enrollmentStatus) }}
        </template>
      </el-table-column>
      <el-table-column label="支付状态" align="center" width="120">
        <template slot-scope="scope">
          <el-tag :type="getPayStatusType(scope.row.payStatus)" size="small">
            {{ scope.row.payStatus || '待核对' }}
          </el-tag>
          <div class="pay-actions">
            <el-button
              size="mini"
              type="text"
              style="color: #67C23A;"
              @click="handleFinance(scope.row)"
              v-hasPermi="['system:tuition:query']"
            >收费详情</el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-cancelTime" label="取消时间" align="center" prop="cancelTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.cancelTime, '{y}-{m}-{d}') }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-cancelReason" label="取消原因" align="center" prop="cancelReason" />
      <el-table-column v-if="showDetailedColumns" key="detail-status" label="状态" align="center" prop="status">
        <template slot-scope="scope">
          {{ getStatusLabel(scope.row.status) }}
        </template>
      </el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-remark" label="备注" align="center" prop="remark" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" fixed="right" width="180">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:enrollment:edit']"
          >{{ scope.row.classDate ? '预约管理' : '修改' }}</el-button>
          <el-button v-if="canTransfer(scope.row)" size="mini" type="text" icon="el-icon-sort" @click="handleTransfer(scope.row)">换课</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            :disabled="isEnrollmentCancelled(scope.row)"
            @click="handleCancelEnrollment(scope.row)"
            v-hasPermi="['system:enrollment:remove']"
          >取消报名</el-button>
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

    <!-- 添加或修改课程报名对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-row>
          <el-col :span="24">
            <el-form-item label="报名编码" prop="enrollmentCode">
              <el-input v-model="form.enrollmentCode" placeholder="请输入报名编码" :disabled="form.enrollmentId != null" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="排课ID" prop="scheduleId">
              <el-input v-model="form.scheduleId" placeholder="请输入排课ID" :disabled="form.enrollmentId != null" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="家长用户ID" prop="parentId">
              <el-input v-model="form.parentId" placeholder="请输入家长用户ID" :disabled="form.enrollmentId != null" @change="loadChildChoices" />
            </el-form-item>
          </el-col>
          <el-col :span="24" v-if="form.enrollmentId == null">
            <el-form-item label="报名孩子"><el-select v-model="childChoice" :loading="childrenLoading" :disabled="!form.parentId || childrenLoading" placeholder="先填写家长用户ID" @change="chooseChild"><el-option v-for="a in childChoices" :key="a.accountId" :value="String(a.accountId)" :label="`${a.studentName} · ${a.gradeName} · #${a.accountId}`"/><el-option value="new" label="＋ 添加孩子信息（报名成功自动建零余额账户）"/></el-select><div class="edu-table-secondary" v-if="childError">{{childError}}</div></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="学生姓名" prop="studentName">
              <el-input v-model="form.studentName" placeholder="请输入学生姓名" :disabled="!!form.lessonAccountId || (form.enrollmentId == null && childChoice!=='new')" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="学生手机号" prop="studentPhone">
              <el-input v-model="form.studentPhone" placeholder="请输入学生手机号" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="联系电话" prop="contactPhone">
              <el-input v-model="form.contactPhone" placeholder="请输入联系电话" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="取消时间" prop="cancelTime">
              <el-date-picker clearable
                v-model="form.cancelTime"
                disabled
                type="date"
                value-format="yyyy-MM-dd"
                placeholder="请选择取消时间">
              </el-date-picker>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="取消原因" prop="cancelReason">
              <el-input v-model="form.cancelReason" placeholder="请输入取消原因" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="删除标志" prop="delFlag">
              <el-input v-model="form.delFlag" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注" prop="remark">
              <el-input v-model="form.remark" type="textarea" placeholder="请输入内容" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
    <el-dialog title="学生换课" :visible.sync="transferOpen" width="760px" append-to-body :close-on-click-modal="false" :show-close="!transferSaving" :close-on-press-escape="!transferSaving">
      <template v-if="transferRow">
        <p><b>{{transferRow.studentName}}</b> · 原课程：{{transferRow.courseClassName || transferRow.scheduleId}} · {{transferRow.payStatus || '待核对'}}</p>
        <el-alert title="换课后保留原报名号、缴费、收据及历史考勤；差价需另行收费或退费。" type="warning" :closable="false" show-icon />
        <el-form label-width="100px" style="margin-top:20px">
          <el-form-item label="搜索目标课程"><el-input v-model="transferKeyword" maxlength="100" placeholder="课程、年级、学科或老师" :disabled="transferSaving" @keyup.enter.native="searchTransfer"><el-button slot="append" icon="el-icon-search" :disabled="transferSaving" @click="searchTransfer">搜索</el-button></el-input></el-form-item>
          <el-form-item label="目标班课">
            <el-select v-model="transferTarget" placeholder="请选择目标课程" :loading="transferLoading && !transferRows.length" :disabled="transferSaving" popper-class="transfer-course-dropdown" style="width:100%">
              <el-option v-for="course in transferRows" :key="course.scheduleId" :value="course.scheduleId" :label="transferLabel(course)" :disabled="transferLoading || transferSaving" />
              <li v-if="transferTotal>20" class="transfer-dropdown-pager" @mousedown.prevent @click.stop>
                <el-pagination small layout="prev,pager,next" :total="transferTotal" :page-size="20" :current-page="transferPage" :disabled="transferSaving || transferLoading" @current-change="loadTransferOptions"/>
              </li>
            </el-select>
            <div class="edu-table-secondary">共 {{transferTotal}} 门可选课程，满班、停用及一对一课程不在此列表中。</div>
          </el-form-item>
          <el-form-item label="换课原因"><el-input v-model="transferReason" type="textarea" :rows="3" maxlength="255" show-word-limit :disabled="transferSaving" placeholder="请填写换课原因" /></el-form-item>
        </el-form>
        <template v-if="transferHistory.length"><h4>换课记录</h4><p v-for="item in transferHistory" :key="item.transferId" style="word-break:break-all">{{item.fromCourseName}} → {{item.toCourseName}}<br>{{item.reason}} · {{item.operatorName}} · {{item.createTime}}</p></template>
      </template>
      <div slot="footer"><el-button :disabled="transferSaving" @click="transferOpen=false">取消</el-button><el-button type="primary" :loading="transferSaving" :disabled="transferLoading || !transferTarget || !transferReason.trim()" @click="submitTransfer">确认换课</el-button></div>
    </el-dialog>
    <OneToOneManager ref="oneToOne" :dims="dims" @changed="getList" />
  </div>
</template>

<script>
import { listEnrollment, getEnrollment, cancelEnrollment, addEnrollment, updateEnrollment, listTransferOptions, transferEnrollment } from "@/api/system/enrollment"
import { personalDetail } from "@/api/system/transaction"
import { getTimetableDimensions } from '@/api/system/timetable'
import OneToOneManager from '../schedule/OneToOneManager.vue'

export default {
  name: "Enrollment",
  components: { OneToOneManager },
  data() {
    return {
      transferOpen: false, transferRow: null, transferRows: [], transferHistory: [], transferTotal: 0,
      transferPage: 1, transferKeyword: '', transferQuery: '', transferTarget: null, transferReason: '', transferLoading: false, transferSaving: false,
      dims: { teachers: [], grades: [], isAdmin: false },
      childChoices: [], childChoice: 'new', childrenLoading:false, childError:'', childVersion:0, childrenParent:null,
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      cancelIds: [],
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
      // 课程报名表格数据
      enrollmentList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        enrollmentCode: null,
        scheduleId: null,
        parentId: null,
        studentName: null,
        studentPhone: null,
        contactPhone: null,
        enrollmentStatus: null,
        payStatus: null,
        cancelTime: null,
        cancelReason: null,
        status: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        enrollmentCode: [
          { required: true, message: "报名编码不能为空", trigger: "blur" }
        ],
        scheduleId: [
          { required: true, message: "排课ID不能为空", trigger: "blur" }
        ],
        parentId: [
          { required: true, message: "家长用户ID不能为空", trigger: "blur" }
        ],
        studentName: [
          { required: true, message: "学生姓名不能为空", trigger: "blur" }
        ],
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    canTransfer(row) {
      return !row.classDate && !this.isEnrollmentCancelled(row) && ['0','1','待确认','已确认','报名成功'].includes(String(row.enrollmentStatus))
        && (this.$store.getters.roles || []).some(role=>['admin','business_admin','education'].includes(role))
    },
    transferLabel(course) {
      return [course.courseClassName, course.gradeName, course.subjectName, course.teacherName,
        [course.courseYear,course.termName,course.periodName].filter(Boolean).join(' '),
        course.startTime ? `${course.startTime}–${course.endTime}` : course.timeSlot,
        [course.campusName,course.classroomName || '教室待分配'].filter(Boolean).join(' '),
        course.startDate ? `${course.startDate}至${course.endDate}` : '日期待配置'].filter(Boolean).join(' · ')
    },
    handleTransfer(row) {
      this.transferRow={...row};this.transferOpen=true;this.transferKeyword='';this.transferQuery='';this.transferTarget=null;this.transferReason='';this.transferHistory=[];this.transferRows=[];this.transferTotal=0
      return this.loadTransferOptions(1)
    },
    searchTransfer() { if(this.transferSaving)return; this.transferQuery=this.transferKeyword;this.transferTarget=null;return this.loadTransferOptions(1) },
    async loadTransferOptions(page) {
      const version=this._transferVersion=(this._transferVersion || 0)+1
      this.transferLoading=true
      try {
        const res=await listTransferOptions(this.transferRow.enrollmentId,{keyword:this.transferQuery,page})
        if(version!==this._transferVersion)return
        this.transferRows=res.data.rows || [];this.transferHistory=res.data.history || [];this.transferTotal=res.data.total;this.transferPage=page
      } catch(e) { if(version===this._transferVersion){this.transferRows=[];this.transferTotal=0;this.$modal.msgError(e.message || '课程加载失败')} }
      finally {if(version===this._transferVersion)this.transferLoading=false}
    },
    async submitTransfer() {
      if(this.transferSaving || !this.transferTarget || !this.transferReason.trim())return
      this.transferSaving=true
      try {
        await this.$confirm('确认将该学生换至所选课程？原缴费、收据和历史考勤将保留，差价需另行处理。','确认学生换课',{type:'warning'})
        await transferEnrollment(this.transferRow.enrollmentId,{fromScheduleId:this.transferRow.scheduleId,targetScheduleId:this.transferTarget,reason:this.transferReason.trim()})
        this.$modal.msgSuccess('换课成功');this.transferOpen=false;this.getList()
      } catch(e) { if(e!=='cancel' && e!=='close')this.$modal.msgError(e.message || '换课失败，请刷新后重试') }
      finally {this.transferSaving=false}
    },
    async openSingle(row = {}) {
      const response = await getTimetableDimensions()
      this.dims = response.data || this.dims
      if (!this.dims.isAdmin) { this.$modal.msgError('仅管理员可在网页统一管理一对一'); return }
      return this.$refs.oneToOne.open(row, 'bookings')
    },
    /** 查询课程报名列表 */
    getList() {
      this.loading = true
      listEnrollment(this.queryParams).then(response => {
        this.enrollmentList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    // 表单重置
    reset() {
      this.childVersion++
      this.childChoices=[];this.childChoice='new';this.childError='';this.childrenParent=null;this.childrenLoading=false
      this.form = {
        lessonAccountId:null,studentRequestKey:`child_${Date.now()}_${Math.random().toString(36).slice(2,14)}`,
        enrollmentId: null,
        enrollmentCode: null,
        scheduleId: null,
        parentId: null,
        studentName: null,
        studentPhone: null,
        contactPhone: null,
        enrollmentStatus: null,
        payStatus: null,
        cancelTime: null,
        cancelReason: null,
        status: null,
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
      this.ids = selection.map(item => item.enrollmentId)
      this.cancelIds = selection.filter(item => !this.isEnrollmentCancelled(item)).map(item => item.enrollmentId)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加课程报名"
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const enrollmentId = row.enrollmentId || this.ids
      getEnrollment(enrollmentId).then(response => {
        if (response.data.classDate) { this.openSingle(response.data); return }
        this.form = response.data
        this.open = true
        this.title = "修改课程报名"
      })
    },
    async loadChildChoices() {
      const parentId=this.form.parentId,v=++this.childVersion
      this.childChoices=[];this.form.lessonAccountId=null;this.form.studentName='';this.childChoice='new';this.childrenParent=null;this.childError=''
      if(!parentId)return
      this.childrenLoading=true
      try{const r=await personalDetail(parentId);if(v!==this.childVersion)return;this.childChoices=r.data.children || [];this.childrenParent=String(parentId)}catch(e){if(v===this.childVersion)this.childError='家长或孩子资料查询失败，请检查家长ID后重新填写'}finally{if(v===this.childVersion)this.childrenLoading=false}
    },
    chooseChild(value) {
      const a=this.childChoices.find(a=>String(a.accountId)===value)
      this.form.lessonAccountId=a?a.accountId:null;this.form.studentName=a?a.studentName:''
      this.form.studentRequestKey=`child_${Date.now()}_${Math.random().toString(36).slice(2,14)}`
    },
    /** 提交按钮 */
    submitForm() {
      if(this.form.enrollmentId == null && (this.childrenLoading || this.childError || this.childrenParent!==String(this.form.parentId))){this.$modal.msgError('请先核对家长并加载孩子资料');return}
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.enrollmentId != null) {
            updateEnrollment(this.enrollmentBody()).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addEnrollment(this.enrollmentBody()).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    isEnrollmentCancelled(row) {
      return !!row.classDate || ['2', '已取消'].includes(String(row.enrollmentStatus)) || String(row.delFlag) === '2'
    },
    /** 取消报名，保留记录及已出勤历史 */
    handleCancelEnrollment(row) {
      const enrollmentIds = row.enrollmentId ? [row.enrollmentId] : this.cancelIds
      if (!enrollmentIds.length || (row.enrollmentId && this.isEnrollmentCancelled(row))) return
      this.$modal.confirm('确认取消选中的 ' + enrollmentIds.length + ' 条报名？报名和已出勤历史将保留，未使用考勤设为无效，并释放课程名额。').then(function() {
        return cancelEnrollment(enrollmentIds.join(','))
      }).then(response => {
        this.getList()
        this.$modal.msgSuccess(response.msg || "取消报名成功")
      }).catch(() => {})
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('system/enrollment/export', {
        ...this.queryParams
      }, `enrollment_${new Date().getTime()}.xlsx`)
    },
    /** 招生状态标签 */
    getRecruitLabel(status) {
      if (status === '1') return '停招'
      if (status === '2') return '满班'
      if (status === '0') return '招生中'
      return '-'
    },
    /** 报名状态显示文字 */
    getEnrollmentStatusLabel(status) {
      const labels = { '0': '待确认', '1': '报名成功', '2': '已取消', '3': '老师已拒绝' }
      return labels[String(status)] || status || '-'
    },
    /** 记录状态显示文字 */
    getStatusLabel(status) {
      const labels = { '0': '正常', '1': '停用' }
      return labels[String(status)] || status || '-'
    },
    /** 支付状态标签颜色 */
    getPayStatusType(status) {
      if (status === '已支付') return 'success'
      if (status === '已退款') return 'warning'
      return 'info'
    },
    /** 通用报名表单只发送允许编辑的字段，财务汇总由专用流程维护。 */
    enrollmentBody() {
      const body = {}
      for (const key of ['enrollmentId', 'enrollmentCode', 'scheduleId', 'parentId', 'lessonAccountId', 'studentRequestKey', 'studentName', 'studentPhone', 'contactPhone', 'remark']) {
        if (this.form[key] !== undefined) body[key] = this.form[key]
      }
      return body
    },
    handleFinance(row) {
      this.$router.push({ path: '/finance/tuition', query: { enrollmentId: String(row.enrollmentId) } })
    }
  }
}
</script>

<style scoped lang="scss">
.schedule-info {
  text-align: left;
  padding: 4px 0;
  .info-grade {
    font-size: 12px;
    color: #909399;
  }
  .info-type {
    display: inline-block;
    margin-left: 4px;
    padding: 0 6px;
    font-size: 11px;
    color: #2459d9;
    background: #edf2ff;
    border-radius: 3px;
  }
  .info-teacher {
    font-size: 12px;
    color: #606266;
  }
  .info-status {
    font-size: 11px;
    margin-top: 2px;
    &.rs-0 { color: #67C23A; }
    &.rs-1 { color: #909399; }
    &.rs-2 { color: #E6A23C; }
  }
}
.text-muted {
  color: #C0C4CC;
  font-size: 12px;
}
.pay-actions {
  margin-top: 4px;
  display: flex;
  gap: 4px;
  justify-content: center;
}
</style>

<style scoped src="@/assets/styles/education-workspace.css"></style>

<style lang="scss">
.transfer-course-dropdown {
  .el-select-dropdown__list { padding-bottom: 0; }
  .transfer-dropdown-pager {
    position: sticky;
    bottom: 0;
    z-index: 1;
    padding: 8px 12px;
    list-style: none;
    border-top: 1px solid #ebeef5;
    background: #fff;
    .el-pagination { display: flex; justify-content: center; }
  }
}
</style>
