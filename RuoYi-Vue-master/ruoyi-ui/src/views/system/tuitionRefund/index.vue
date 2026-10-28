<template>
  <div class="app-container">
    <el-alert
      title="课程退费在线下办理：家长申请 → 教务确认金额并已阅 → 管理员登记已退款。同一管理员可分两步操作，不能跳过金额确认；成功后家长才看到最终金额。"
      type="info"
      :closable="false"
      class="mb8"
    />
    <el-form
      v-show="showSearch"
      :model="queryParams"
      :inline="true"
      size="small"
    >
      <el-form-item label="退费号"
        ><el-input v-model="queryParams.refundNo" clearable
      /></el-form-item>
      <el-form-item label="报名ID"
        ><el-input v-model="queryParams.enrollmentId" clearable
      /></el-form-item>
      <el-form-item label="学生"
        ><el-input v-model="queryParams.studentName" clearable
      /></el-form-item>
      <el-form-item label="状态"
        ><el-select v-model="queryParams.refundStatus" clearable
          ><el-option
            v-for="s in statuses"
            :key="s"
            :value="s"
            :label="refundLabel(s)" /></el-select
      ></el-form-item>
      <el-form-item label="模式"
        ><el-select v-model="queryParams.financeMode" @change="handleQuery"
          ><el-option value="REAL" label="真实" /><el-option
            value="MOCK"
            label="模拟（本地）" /></el-select
      ></el-form-item>
      <el-form-item
        ><el-button type="primary" @click="handleQuery">搜索</el-button
        ><el-button @click="resetQuery">重置</el-button></el-form-item
      >
    </el-form>
    <el-row class="mb8">
      <el-button
        v-if="permitted('apply')"
        v-hasPermi="['system:tuitionRefund:apply']"
        type="primary"
        plain
        size="mini"
        :disabled="saving"
        @click="openApply"
        >{{ applyIntent ? '继续原退费申请' : '代家长申请退费' }}</el-button
      >
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>
    <el-alert
      v-if="listError"
      :title="listError"
      type="error"
      :closable="false"
    />
    <el-table v-loading="loading" :data="rows">
      <el-table-column label="退费号" prop="refundNo" min-width="180" />
      <el-table-column label="学生" prop="studentName" width="100" />
      <el-table-column label="缴费号" prop="paymentNo" min-width="170" />
      <el-table-column label="范围" width="110"
        ><template slot-scope="s">{{
          label(s.row.refundKind)
        }}</template></el-table-column
      >
      <el-table-column label="核定／历史批准金额" width="140"
        ><template slot-scope="s">{{
          money(s.row.approvedAmount)
        }}</template></el-table-column
      >
      <el-table-column label="状态" min-width="220"
        ><template slot-scope="s"
          ><el-tag :type="statusType(s.row.refundStatus)">{{
            refundLabel(s.row.refundStatus)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="申请原因" prop="reason" min-width="160" />
      <el-table-column label="完成时间" prop="completedTime" width="165" />
      <el-table-column label="操作" width="100"
        ><template slot-scope="s"
          ><el-button
            v-hasPermi="['system:tuitionRefund:query']"
            type="text"
            @click="handleDetail(s.row)"
            >详情／处理</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />
    <el-dialog
      title="退费详情与处理"
      :visible.sync="detailOpen"
      width="850px"
      append-to-body
    >
      <div v-loading="detailLoading">
        <el-alert
          v-if="detailError"
          :title="detailError"
          type="error"
          :closable="false"
        />
        <template v-if="detail">
          <el-alert
            v-if="legacy(detail)"
            title="历史流程，请由管理员核对原线下退款事实和历史凭证后处理；旧在途记录不会自动批准或转入新两步流程。原成功记录不可修改。"
            type="warning"
            :closable="false"
            class="mb8"
          />
          <el-alert
            v-if="detail.financeMode === 'MOCK'"
            title="本地模拟记录／不涉及真实资金"
            type="warning"
            :closable="false"
            class="mb8"
          />
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="退费号">{{
              detail.refundNo
            }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{
              refundLabel(detail.refundStatus)
            }}</el-descriptions-item>
            <el-descriptions-item label="学生／报名ID"
              >{{ detail.studentName }}／{{
                detail.enrollmentId
              }}</el-descriptions-item
            >
            <el-descriptions-item label="退费范围">{{
              label(detail.refundKind)
            }}</el-descriptions-item>
            <el-descriptions-item label="原始实收"
              >{{
                money(detail.receivedAmount || detail.paymentAmount)
              }}
              元</el-descriptions-item
            >
            <el-descriptions-item label="当前剩余可申请"
              >{{ money(detail.refundableAmount) }} 元</el-descriptions-item
            >
            <el-descriptions-item label="本申请可核定上限"
              >{{
                money(detail.availableForThisRefund)
              }}
              元（以服务端校验为准）</el-descriptions-item
            >
            <el-descriptions-item
              :label="legacy(detail) ? '旧流程批准金额' : '教务核定金额'"
              >{{ money(detail.approvedAmount) }} 元</el-descriptions-item
            >
            <el-descriptions-item label="申请人"
              >{{ detail.applicantName || detail.applicantId }}
              <div>{{ detail.createTime || '—' }}</div></el-descriptions-item
            >
            <el-descriptions-item
              :label="legacy(detail) ? '旧流程审核人' : '教务已阅'"
              >{{ detail.reviewerName || detail.reviewerId || '—' }}
              <div>{{ detail.reviewTime || '—' }}</div></el-descriptions-item
            >
            <el-descriptions-item
              :label="legacy(detail) ? '旧流程执行人' : '退款登记人'"
              >{{
                detail.executorName || detail.executorId || '—'
              }}</el-descriptions-item
            >
            <el-descriptions-item label="实际完成时间">{{
              detail.completedTime || '尚未登记退款'
            }}</el-descriptions-item>
            <el-descriptions-item label="申请原因" :span="2">{{
              detail.reason
            }}</el-descriptions-item>
            <el-descriptions-item label="审核备注" :span="2">{{
              detail.reviewRemark || '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="历史处理情况" :span="2">{{
              detail.failureReason || '—'
            }}</el-descriptions-item>
          </el-descriptions>
          <div class="finance-actions">
            <el-button
              v-if="
                !legacy(detail) &&
                detail.refundStatus === 'WAIT_EDU' &&
                permitted('review')
              "
              v-hasPermi="['system:tuitionRefund:review']"
              type="primary"
              size="small"
              :disabled="saving || detailLoading"
              @click="openOperation('review')"
              >教务确认金额／驳回</el-button
            >
            <el-button
              v-if="
                !legacy(detail) &&
                detail.refundStatus === 'WAIT_ADMIN' &&
                permitted('markRefunded', true)
              "
              v-hasPermi="['system:tuitionRefund:markRefunded']"
              type="primary"
              size="small"
              :disabled="saving || detailLoading"
              @click="openOperation('markRefunded')"
              >已退款登记</el-button
            >
            <el-button
              v-if="
                !legacy(detail) &&
                detail.refundStatus === 'WAIT_ADMIN' &&
                permitted('review', true)
              "
              v-hasPermi="['system:tuitionRefund:review']"
              size="small"
              :disabled="saving || detailLoading"
              @click="openOperation('reject')"
              >管理员驳回</el-button
            >
            <el-button
              size="small"
              :disabled="saving || detailLoading"
              @click="handleDetail(detail)"
              >刷新</el-button
            >
          </div>
        </template>
      </div>
    </el-dialog>
    <el-dialog
      :title="
        operation === 'apply'
          ? '代家长申请退费'
          : operation === 'review'
          ? '教务核定金额并已阅'
          : operation === 'reject'
          ? '管理员驳回'
          : '登记线下已退款'
      "
      :visible.sync="operationOpen"
      width="650px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="formError"
        :title="formError"
        type="error"
        :closable="false"
        class="mb8"
      />
      <el-alert
        v-if="operation === 'apply' && applyIntent"
        title="原申请结果未确定，范围、对象、原因和请求标识已保留。继续会重试原申请；刷新或离开页面前请先核对申请记录。"
        type="warning"
        :closable="false"
        class="mb8"
      />
      <el-form
        :model="form"
        label-width="125px"
        size="small"
        :disabled="saving || (operation === 'apply' && !!applyIntent)"
      >
        <template v-if="operation === 'apply'">
          <el-form-item label="原报名ID"
            ><el-input
              v-model="form.enrollmentId"
              placeholder="填写报名ID后查询成功缴费"
              ><el-button slot="append" :loading="billLoading" @click="loadBill"
                >查询</el-button
              ></el-input
            ></el-form-item
          >
          <el-form-item label="原成功缴费"
            ><el-select v-model="form.paymentId"
              ><el-option
                v-for="p in successfulPayments"
                :key="p.paymentId"
                :value="p.paymentId"
                :label="
                  p.paymentNo + '／实收 ' + money(p.amount) + ' 元'
                " /></el-select
          ></el-form-item>
          <el-form-item label="退费范围"
            ><el-radio-group v-model="form.refundKind" @change="changeKind"
              ><el-radio label="PARTIAL">费用调整</el-radio
              ><el-radio label="WITHDRAWAL">退出此课程</el-radio
              ><el-radio label="SESSION">单次请假</el-radio></el-radio-group
            ></el-form-item
          >
          <el-form-item v-if="form.refundKind === 'SESSION'" label="单次对象">
            <el-select
              v-model="form.targetId"
              :loading="targetsLoading"
              placeholder="选择已审核请假／已取消返次记录"
              ><el-option
                v-for="t in targets"
                :key="t.targetId"
                :value="t.targetId"
                :label="t.label"
            /></el-select>
            <div>{{ targetError }}</div>
            <el-button
              type="text"
              :disabled="targetsLoading"
              @click="loadTargets"
              >重新查询课次</el-button
            >
            <div>
              返还次数在申请期间预留，退款成功后收回；不影响其他正常授课。
            </div>
          </el-form-item>
          <el-form-item label="金额说明"
            >本次申请不报价，由教务依据授课与合同核定金额。</el-form-item
          >
          <el-form-item label="申请原因"
            ><el-input
              v-model="form.reason"
              type="textarea"
              maxlength="500"
              show-word-limit
          /></el-form-item>
        </template>
        <template v-if="operation === 'review' || operation === 'reject'">
          <el-form-item v-if="operation === 'review'" label="教务决定"
            ><el-radio-group v-model="form.approved"
              ><el-radio :label="true">确认金额并已阅</el-radio
              ><el-radio :label="false">驳回</el-radio></el-radio-group
            ></el-form-item
          >
          <el-form-item
            v-if="operation === 'review' && form.approved"
            label="实际核定金额"
            ><el-input
              v-model="form.approvedAmount"
              placeholder="元，最多两位小数"
            />
            <div>
              按实际授课和合同核定；提交后等待管理员线下退款登记。
            </div></el-form-item
          >
          <el-form-item label="审核备注"
            ><el-input
              v-model="form.reviewRemark"
              type="textarea"
              maxlength="500"
              placeholder="说明金额依据；驳回必须填写原因"
              show-word-limit
          /></el-form-item>
        </template>
        <template v-if="operation === 'markRefunded'">
          <el-form-item label="教务已阅金额"
            >{{ detail ? money(detail.approvedAmount) : '—' }} 元</el-form-item
          >
          <el-form-item label="实际退款时间"
            ><el-date-picker
              v-model="form.completedTime"
              type="datetime"
              value-format="yyyy-MM-dd HH:mm:ss"
              placeholder="留空使用登记时间"
          /></el-form-item>
          <el-form-item label="线下事实"
            >请核对已实际退还教务确认的金额，再登记已退款。</el-form-item
          >
        </template>
      </el-form>
      <span slot="footer"
        ><el-button :disabled="saving" @click="operationOpen = false"
          >关闭</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="saving || billLoading || targetsLoading"
          @click="submitOperation"
          >提交</el-button
        ></span
      >
    </el-dialog>
  </div>
</template>
<script>
import {
  listRefunds,
  getRefund,
  applyRefund,
  reviewRefund,
  markRefunded,
} from '@/api/system/tuitionRefund'
import { getTuition, refundTargets } from '@/api/system/tuition'
import {
  money,
  amount,
  compareAmount,
  required,
  label,
  statusType,
  requestKey,
  canReview,
} from '@/utils/tuitionFinance'
export default {
  name: 'TuitionRefund',
  data() {
    return {
      queryParams: { pageNum: 1, pageSize: 10, financeMode: 'REAL' },
      statuses: [
        'WAIT_EDU',
        'WAIT_ADMIN',
        'SUCCESS',
        'REJECTED',
        'PENDING_REVIEW',
        'APPROVED',
        'PROCESSING',
        'UNKNOWN',
        'CANCELLED',
        'FAILED',
      ],
      rows: [],
      total: 0,
      loading: false,
      listError: '',
      showSearch: true,
      detail: null,
      detailOpen: false,
      detailLoading: false,
      detailError: '',
      detailRequest: 0,
      operation: '',
      operationOpen: false,
      operationId: '',
      form: {},
      formError: '',
      saving: false,
      bill: null,
      successfulPayments: [],
      billLoading: false,
      billRequest: 0,
      targets: [],
      targetsLoading: false,
      targetError: '',
      targetRequest: 0,
      applyIntent: null,
      applyForm: null,
    }
  },
  created() {
    this.getList()
    if (this.$route.query.paymentId) this.openApply()
  },
  watch: {
    '$route.query.paymentId'(id) {
      if (id) this.openApply()
    },
  },
  methods: {
    money,
    label,
    statusType,
    canReview,
    refundLabel(value) {
      return value === 'SUCCESS' ? '已退款' : label(value)
    },
    legacy(refund) {
      return (
        refund.refundWorkflow === 'LEGACY' ||
        (!refund.refundWorkflow &&
          !['WAIT_EDU', 'WAIT_ADMIN'].includes(refund.refundStatus))
      )
    },
    permitted(action, adminOnly = false) {
      const user = this.$store.state.user
      return (
        (user.roles || []).some(
          (r) => r === 'admin' || (!adminOnly && r === 'education')
        ) &&
        (user.permissions || []).some(
          (p) => p === '*:*:*' || p === 'system:tuitionRefund:' + action
        )
      )
    },
    async getList() {
      this.loading = true
      this.listError = ''
      try {
        const r = await listRefunds(this.queryParams)
        this.rows = r.rows || []
        this.total = r.total || 0
      } catch (e) {
        this.rows = []
        this.total = 0
        this.listError = e.message || '查询失败'
      } finally {
        this.loading = false
      }
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      return this.getList()
    },
    resetQuery() {
      this.queryParams = { pageNum: 1, pageSize: 10, financeMode: 'REAL' }
      return this.getList()
    },
    async handleDetail(row) {
      const sequence = ++this.detailRequest
      this.detail = null
      this.detailOpen = true
      this.detailError = ''
      this.detailLoading = true
      try {
        const r = await getRefund(String(row.refundId))
        if (sequence === this.detailRequest) this.detail = r.data
      } catch (e) {
        if (sequence === this.detailRequest)
          this.detailError = e.message || '详情查询失败'
      } finally {
        if (sequence === this.detailRequest) this.detailLoading = false
      }
    },
    async openApply() {
      if (this.saving || !this.permitted('apply')) return
      this.operation = 'apply'
      this.operationOpen = true
      this.formError = ''
      if (this.applyIntent) {
        this.form = { ...this.applyForm }
        return
      }
      this.bill = null
      this.successfulPayments = []
      this.targets = []
      this.targetError = ''
      this.form = {
        enrollmentId: this.$route.query.enrollmentId || '',
        paymentId: this.$route.query.paymentId || '',
        refundKind: 'PARTIAL',
        targetId: '',
        reason: '',
        idempotencyKey: requestKey(),
      }
      if (this.form.enrollmentId) await this.loadBill()
    },
    async loadBill() {
      if (this.applyIntent || this.saving) return
      const id = String(this.form.enrollmentId),
        sequence = ++this.billRequest
      this.billLoading = true
      this.bill = null
      this.successfulPayments = []
      this.targets = []
      this.form.targetId = ''
      this.targetRequest++
      this.targetsLoading = false
      this.formError = ''
      try {
        required(id, '原报名ID')
        const r = await getTuition(id)
        if (
          sequence !== this.billRequest ||
          id !== String(this.form.enrollmentId)
        )
          return
        this.bill = r.data
        this.successfulPayments = (r.data.payments || []).filter(
          (p) =>
            p.paymentStatus === 'SUCCESS' &&
            p.channel !== 'FREE' &&
            compareAmount(p.amount, '0') > 0
        )
        if (
          !this.successfulPayments.some(
            (p) => p.paymentId === this.form.paymentId
          )
        )
          this.form.paymentId =
            this.successfulPayments.length === 1
              ? this.successfulPayments[0].paymentId
              : ''
        if (!this.successfulPayments.length)
          throw new Error('没有可退现金的成功缴费，零元减免退出课程请联系教务')
        if (this.form.refundKind === 'SESSION') await this.loadTargets()
      } catch (e) {
        if (sequence === this.billRequest)
          this.formError = e.message || '查询失败'
      } finally {
        if (sequence === this.billRequest) this.billLoading = false
      }
    },
    changeKind() {
      this.form.targetId = ''
      this.targets = []
      this.targetRequest++
      this.targetsLoading = false
      this.targetError = ''
      if (this.form.refundKind === 'SESSION') return this.loadTargets()
    },
    async loadTargets() {
      if (
        !this.bill ||
        String(this.bill.enrollmentId) !== String(this.form.enrollmentId) ||
        this.applyIntent
      ) {
        this.targetError = '请先查询原报名'
        return
      }
      const id = String(this.bill.enrollmentId),
        sequence = ++this.targetRequest
      this.targetsLoading = true
      this.targets = []
      this.form.targetId = ''
      this.targetError = ''
      try {
        const r = await refundTargets(id)
        if (
          sequence !== this.targetRequest ||
          id !== String(this.form.enrollmentId) ||
          this.form.refundKind !== 'SESSION'
        )
          return
        this.targets = ((r.data || {}).rows || []).filter(
          (t) =>
            typeof t.targetId === 'string' &&
            ['CLASS_SIGN_IN', 'LESSON_BOOKING'].includes(t.targetType) &&
            t.label
        )
        if (!this.targets.length)
          this.targetError = '暂无可申请的已审核请假／已取消返次记录'
      } catch (e) {
        if (sequence === this.targetRequest)
          this.targetError = e.message || '课次查询失败，不能提交单次申请'
      } finally {
        if (sequence === this.targetRequest) this.targetsLoading = false
      }
    },
    openOperation(operation) {
      if (this.saving || !this.detail) return
      this.operation = operation
      this.operationId = String(this.detail.refundId)
      this.operationOpen = true
      this.formError = ''
      this.form = {
        version: this.detail.version,
        ...(operation === 'markRefunded'
          ? { completedTime: '' }
          : {
              approved: operation !== 'reject',
              approvedAmount: '',
              reviewRemark: '',
            }),
      }
    },
    async submitOperation() {
      if (this.saving || this.billLoading || this.targetsLoading) return
      this.saving = true
      this.formError = ''
      const row = this.detail
      try {
        if (this.operation === 'apply') {
          if (!this.permitted('apply')) throw new Error('当前账号没有申请权限')
          if (!this.applyIntent) {
            if (
              !this.bill ||
              String(this.bill.enrollmentId) !==
                String(this.form.enrollmentId) ||
              !this.successfulPayments.some(
                (p) => p.paymentId === this.form.paymentId
              )
            )
              throw new Error('请先查询并选择原成功缴费')
            if (
              !['PARTIAL', 'WITHDRAWAL', 'SESSION'].includes(
                this.form.refundKind
              )
            )
              throw new Error('请选择退费范围')
            const target =
              this.form.refundKind === 'SESSION'
                ? this.targets.find((t) => t.targetId === this.form.targetId)
                : null
            if (
              this.form.refundKind === 'SESSION' &&
              (!target ||
                this.targetError ||
                typeof target.targetId !== 'string')
            )
              throw new Error('请查询并选择可申请的单次记录')
            this.applyIntent = {
              paymentId: String(this.form.paymentId),
              refundKind: this.form.refundKind,
              reason: required(this.form.reason, '申请原因'),
              idempotencyKey: this.form.idempotencyKey,
              ...(target
                ? {
                    [target.targetType === 'CLASS_SIGN_IN'
                      ? 'classSignInId'
                      : 'lessonBookingId']: target.targetId,
                  }
                : {}),
            }
            this.applyForm = { ...this.form }
          }
          const result = await applyRefund({ ...this.applyIntent })
          if (
            !result ||
            !result.data ||
            typeof result.data.refundId !== 'string' ||
            !result.data.refundId.trim()
          )
            throw new Error('申请结果尚未确定，请核对申请记录后重试原申请')
          this.applyIntent = null
          this.applyForm = null
        } else {
          if (
            !row ||
            this.legacy(row) ||
            String(row.refundId) !== this.operationId ||
            this.form.version !== row.version
          )
            throw new Error('记录版本已变化，请刷新后重新操作')
          if (this.operation === 'review' || this.operation === 'reject') {
            const adminReject = this.operation === 'reject'
            if (
              !this.permitted('review', adminReject) ||
              row.refundStatus !== (adminReject ? 'WAIT_ADMIN' : 'WAIT_EDU')
            )
              throw new Error('当前阶段或角色不允许审核，请刷新状态')
            const approved = adminReject ? false : this.form.approved
            if (typeof approved !== 'boolean') throw new Error('请选择审核决定')
            const approvedAmount = approved
              ? amount(this.form.approvedAmount, true)
              : null
            if (
              approved &&
              row.availableForThisRefund != null &&
              compareAmount(approvedAmount, row.availableForThisRefund) > 0
            )
              throw new Error('核定金额超过本申请可退范围')
            const reviewRemark = approved
              ? String(this.form.reviewRemark || '').trim()
              : required(this.form.reviewRemark, '驳回说明')
            await reviewRefund(row.refundId, {
              approved,
              approvedAmount,
              reviewRemark,
              version: this.form.version,
            })
          } else if (this.operation === 'markRefunded') {
            if (
              !this.permitted('markRefunded', true) ||
              row.refundStatus !== 'WAIT_ADMIN'
            )
              throw new Error('请先由教务确认金额，再由管理员登记已退款')
            const body = {
              version: this.form.version,
              ...(this.form.completedTime
                ? { completedTime: this.form.completedTime }
                : {}),
            }
            await this.$modal.confirm(
              '确认已在线下实际退还 ' +
                money(row.approvedAmount) +
                ' 元？提交后将登记为已退款，不能修改成功事实。'
            )
            await markRefunded(row.refundId, body)
          } else throw new Error('请选择有效操作')
        }
        this.operationOpen = false
        this.$modal.msgSuccess('已提交，请核对最新退费状态')
        await this.getList()
        if (row && this.operation !== 'apply') await this.handleDetail(row)
      } catch (e) {
        this.formError = e.message || '提交失败，请核对后重试'
        if (row && /版本|刷新|状态/.test(this.formError)) {
          this.operationOpen = false
          await this.handleDetail(row)
        }
      } finally {
        this.saving = false
      }
    },
  },
}
</script>
<style scoped>
.finance-actions {
  margin-top: 16px;
}
</style>
