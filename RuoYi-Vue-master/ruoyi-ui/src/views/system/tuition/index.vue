<template>
  <div class="app-container">
    <el-alert
      title="真实与模拟账单分别查询。历史金额待核验时不计作欠费或实收；课程收费仅登记线下实际收款，不提供课程在线支付。"
      type="info"
      :closable="false"
      class="mb8"
    />
    <el-form
      v-show="showSearch"
      :model="queryParams"
      :inline="true"
      size="small"
      label-width="80px"
    >
      <el-form-item label="报名号">
        <el-input
          v-model="queryParams.enrollmentCode"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="学生">
        <el-input v-model="queryParams.studentName" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="家长ID">
        <el-input v-model="queryParams.parentId" clearable />
      </el-form-item>
      <el-form-item label="校区">
        <el-input v-model="queryParams.campusName" clearable />
      </el-form-item>
      <el-form-item label="账单状态">
        <el-select v-model="queryParams.billingStatus" clearable>
          <el-option v-for="s in billStatuses" :key="s" :value="s" :label="label(s)" />
        </el-select>
      </el-form-item>
      <el-form-item label="资金模式">
        <el-select v-model="queryParams.financeMode" @change="handleQuery">
          <el-option label="真实" value="REAL" />
          <el-option label="模拟（本地）" value="MOCK" />
        </el-select>
      </el-form-item>
      <el-form-item label="支付状态">
        <el-select v-model="queryParams.payStatus" clearable>
          <el-option
            v-for="s in ['未支付', '已支付', '部分退款', '已退款', '已减免']"
            :key="s"
            :value="s"
            :label="s"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <div v-hasPermi="['system:tuition:list']" class="finance-summary mb8">
      <div class="summary-caption">
        {{
          queryParams.financeMode === 'MOCK' ? '本地模拟／无真实资金' : '真实资金'
        }}全库汇总（非当前筛选或分页）；今日收／退按实际收款／退款完成时间统计
      </div>
      <el-alert v-if="summaryError" :title="summaryError" type="error" :closable="false" />
      <el-row v-if="summary" :gutter="12">
        <el-col v-for="col in summaryAmounts" :key="col.key" :xs="12" :sm="8" :md="4">
          <div class="summary-label">{{ col.title }}（元）</div>
          <div class="summary-value">{{ money(summary[col.key]) }}</div>
        </el-col>
      </el-row>
      <div v-if="summary" class="summary-caption">
        历史待核验：{{ summary.historyPendingCount == null ? '—' : summary.historyPendingCount }}
        条（未知金额未计入实收／欠费）
      </div>
    </div>
    <el-row class="mb8">
      <el-button
        v-hasPermi="['system:tuition:export']"
        size="mini"
        type="warning"
        plain
        icon="el-icon-download"
        @click="handleExport"
      >
        导出台账
      </el-button>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows">
      <el-table-column label="报名号" prop="enrollmentCode" min-width="145" />
      <el-table-column label="学生" prop="studentName" width="100" />
      <el-table-column label="家长" prop="parentName" width="100" />
      <el-table-column label="课程／课包" prop="courseClassName" min-width="160" />
      <el-table-column label="校区" prop="campusName" width="90" />
      <el-table-column
        v-for="col in amounts"
        :key="col.key"
        :label="col.title"
        min-width="100"
        align="right"
      >
        <template slot-scope="scope">{{ money(scope.row[col.key]) }}</template>
      </el-table-column>
      <el-table-column label="账单状态" width="120">
        <template slot-scope="scope">
          <el-tag :type="statusType(scope.row.billingStatus)" size="small">
            {{ label(scope.row.billingStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="支付状态" prop="payStatus" width="100" />
      <el-table-column label="模式" width="100">
        <template slot-scope="scope">
          <el-tag :type="scope.row.financeMode === 'MOCK' ? 'warning' : 'info'">
            {{ scope.row.financeMode === 'MOCK' ? '模拟' : '真实' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template slot-scope="scope">
          <el-button
            v-hasPermi="['system:tuition:query']"
            type="text"
            size="mini"
            @click="handleDetail(scope.row)"
          >
            收费详情
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />
    <el-dialog title="课程收费详情" :visible.sync="detailOpen" width="1100px" append-to-body>
      <div v-loading="detailLoading">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <template v-if="detail">
          <el-alert
            v-if="detail.financeMode === 'MOCK'"
            title="本地模拟账单／不涉及真实资金"
            type="warning"
            :closable="false"
            class="mb8"
          />
          <el-alert
            v-if="detail.billingStatus === 'HISTORY_PENDING'"
            title="历史待核验：金额未知保持空值，请凭原始收款和退费证据核验。"
            type="warning"
            :closable="false"
            class="mb8"
          />
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="报名号">{{ detail.enrollmentCode }}</el-descriptions-item>
            <el-descriptions-item label="学生／家长">
              {{ detail.studentName }}／{{ detail.parentName }}
            </el-descriptions-item>
            <el-descriptions-item label="报名ID">{{ detail.enrollmentId }}</el-descriptions-item>
            <el-descriptions-item label="课程">
              {{ detail.feeTitleSnapshot || detail.courseClassName }}
            </el-descriptions-item>
            <el-descriptions-item label="校区">
              {{ detail.campusNameSnapshot || detail.campusName }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              {{ label(detail.billingStatus) }}／{{ detail.payStatus }}
            </el-descriptions-item>
            <el-descriptions-item v-for="col in amounts" :key="col.key" :label="col.title">
              {{ money(detail[col.key]) }} 元
            </el-descriptions-item>
          </el-descriptions>
          <template v-if="detail.billType === 'CREDIT_PACKAGE'">
            <el-alert :title="detail.packageCreditedTime ? '课包已入账：' + detail.packageCreditedTime : detail.billingStatus === 'CLOSED' ? '课包订单已取消' : '课包待确认：确认线下收款后一次性增加孩子额度'" type="info" :closable="false" class="mb8" />
            <el-descriptions v-if="detail.packageSnapshot" :column="3" border size="small">
              <el-descriptions-item label="购买年级">{{ detail.packageSnapshot.gradeName }}</el-descriptions-item>
              <el-descriptions-item label="购买包数">{{ detail.packageSnapshot.packageCount }}</el-descriptions-item>
              <el-descriptions-item label="基础额度">{{ detail.packageSnapshot.credits }}</el-descriptions-item>
              <el-descriptions-item label="基础单价">{{ money(detail.packageSnapshot.unitPrice) }} 元</el-descriptions-item>
              <el-descriptions-item label="原价">{{ money(detail.packageSnapshot.grossAmount) }} 元</el-descriptions-item>
              <el-descriptions-item label="课包优惠">{{ money(detail.packageSnapshot.packageDiscount) }} 元</el-descriptions-item>
              <el-descriptions-item label="折后金额">{{ money(detail.packageSnapshot.subtotal) }} 元</el-descriptions-item>
              <el-descriptions-item label="优惠券优惠">{{ money(detail.packageSnapshot.couponDiscount) }} 元</el-descriptions-item>
              <el-descriptions-item label="最终应付">{{ money(detail.packageSnapshot.payableAmount) }} 元</el-descriptions-item>
            </el-descriptions>
            <el-alert title="课包按家长订单锁定价格和优惠券。课包余额暂不支持班课退费流程，请联系教务核对。" type="info" :closable="false" />
          </template>
          <div class="finance-actions">
            <el-button
              v-hasPermi="['system:tuition:price']"
              :disabled="!canPrice(detail)"
              size="small"
              @click="openOperation('price')"
            >
              核价
            </el-button>
            <el-button
              v-hasPermi="['system:tuition:historyVerify']"
              v-if="detail.billingStatus === 'HISTORY_PENDING'"
              size="small"
              @click="openOperation('history')"
            >
              历史核验
            </el-button>
            <el-button
              v-hasPermi="['system:tuition:collect']"
              :disabled="!canCollect(detail)"
              size="small"
              type="primary"
              @click="openOperation('collect')"
            >
              登记收款／零元确认
            </el-button>
            <el-button size="small" icon="el-icon-refresh" @click="handleDetail(detail)">
              刷新详情
            </el-button>
            <el-button
              v-hasPermi="['system:tuition:historyVerify']"
              v-if="detail.billingStatus !== 'HISTORY_PENDING'"
              size="small"
              @click="downloadHistory"
            >
              历史核验凭证
            </el-button>
          </div>
          <el-tabs>
            <el-tab-pane label="收款流水与凭证">
              <el-table :data="detail.payments || []" size="small">
                <el-table-column label="缴费号" prop="paymentNo" min-width="170" />
                <el-table-column label="金额" width="100">
                  <template slot-scope="s">{{ money(s.row.amount) }}</template>
                </el-table-column>
                <el-table-column label="渠道" width="130">
                  <template slot-scope="s">{{ label(s.row.channel) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="170">
                  <template slot-scope="s">{{ label(s.row.paymentStatus) }}</template>
                </el-table-column>
                <el-table-column label="付款人" prop="payerName" />
                <el-table-column label="实际收款时间" prop="paidTime" width="165" />
                <el-table-column label="登记／确认人" width="140">
                  <template slot-scope="s">
                    {{ s.row.recordedBy || '—' }}／{{ s.row.confirmedBy || '—' }}
                  </template>
                </el-table-column>
                <el-table-column label="原因" prop="failureReason" />
                <el-table-column label="操作" width="255">
                  <template slot-scope="s">
                    <el-button
                      v-hasPermi="['system:tuition:confirm']"
                      v-if="['PENDING', 'UNKNOWN'].includes(s.row.paymentStatus)"
                      type="text"
                      @click="openOperation('confirm', s.row)"
                    >
                      核对确认
                    </el-button>
                    <el-button
                      v-hasPermi="['system:tuition:collect']"
                      v-if="canClosePayment(s.row)"
                      type="text"
                      @click="openOperation('close', s.row)"
                    >
                      关闭
                    </el-button>
                    <el-button
                      v-hasPermi="['system:tuition:query']"
                      type="text"
                      @click="downloadPayment(s.row)"
                    >
                      凭证
                    </el-button>
                    <el-button
                      v-hasPermi="['system:tuitionReceipt:issue']"
                      v-if="s.row.paymentStatus === 'SUCCESS'"
                      type="text"
                      @click="handleIssue(s.row)"
                    >
                      收据
                    </el-button>
                    <el-button
                      v-hasPermi="['system:tuitionRefund:apply']"
                      v-if="detail.billType !== 'CREDIT_PACKAGE' && s.row.paymentStatus === 'SUCCESS' && s.row.amount !== '0.00'"
                      type="text"
                      @click="goRefund(s.row)"
                    >
                      申请退费
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-alert
                title="结果未知须核对原流水后确认；在查明之前不可重复登记或关闭重收。"
                type="info"
                :closable="false"
              />
            </el-tab-pane>
            <el-tab-pane label="退费历史">
              <el-table :data="detail.refunds || []" size="small">
                <el-table-column label="退费号" prop="refundNo" />
                <el-table-column label="类型">
                  <template slot-scope="s">{{ label(s.row.refundKind) }}</template>
                </el-table-column>
                <el-table-column label="申请金额">
                  <template slot-scope="s">{{ money(s.row.requestedAmount) }}</template>
                </el-table-column>
                <el-table-column label="批准金额">
                  <template slot-scope="s">{{ money(s.row.approvedAmount) }}</template>
                </el-table-column>
                <el-table-column label="状态">
                  <template slot-scope="s">{{ label(s.row.refundStatus) }}</template>
                </el-table-column>
                <el-table-column label="申请／审核／执行人">
                  <template slot-scope="s">
                    {{ s.row.applicantId }}／{{ s.row.reviewerId || '—' }}／{{
                      s.row.executorId || '—'
                    }}
                  </template>
                </el-table-column>
                <el-table-column label="原因" prop="reason" />
                <el-table-column label="完成时间" prop="completedTime" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="收据">
              <el-table :data="detail.receipts || []" size="small">
                <el-table-column label="收据号" prop="receiptNo" />
                <el-table-column label="类型">
                  <template slot-scope="s">{{ label(s.row.documentType) }}</template>
                </el-table-column>
                <el-table-column label="金额">
                  <template slot-scope="s">{{ money(s.row.amount) }}</template>
                </el-table-column>
                <el-table-column label="状态">
                  <template slot-scope="s">
                    {{ label(s.row.receiptStatus) }}／{{
                      s.row.fileStatus === 'PENDING' ? '待生成' : label(s.row.fileStatus)
                    }}
                  </template>
                </el-table-column>
                <el-table-column label="操作">
                  <template slot-scope="s">
                    <el-button
                      v-hasPermi="['system:tuitionReceipt:download']"
                      :disabled="s.row.fileStatus !== 'READY' || s.row.receiptStatus !== 'ISSUED'"
                      type="text"
                      @click="downloadReceipt(s.row)"
                    >
                      下载 PDF
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-dialog>
    <el-dialog
      :title="operationTitle"
      :visible.sync="operationOpen"
      width="590px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="mb8" />
      <el-form :model="form" label-width="125px" size="small" :disabled="saving">
        <template v-if="operation === 'price' || operation === 'history'">
          <el-form-item label="课程原价（元）">
            <el-input v-model="form.originalAmount" placeholder="明确金额，最多两位小数" />
          </el-form-item>
        </template>
        <el-form-item v-if="operation === 'price'" label="资金模式">
          <el-select
            v-model="form.financeMode"
            :disabled="!!(detail && detail.payments && detail.payments.length)"
          >
            <el-option label="真实" value="REAL" />
            <el-option
              v-if="detail && detail.mockPricingAllowed"
              label="本地模拟／无真实资金"
              value="MOCK"
            />
          </el-select>
        </el-form-item>
        <template v-if="operation === 'history'">
          <el-form-item label="历史实收（元）">
            <el-input
              v-model="form.historyPaidAmount"
              placeholder="有证据的实际金额，未收款明确填0"
            />
          </el-form-item>
          <el-form-item label="历史已退（元）">
            <el-input
              v-model="form.historyRefundedAmount"
              placeholder="有证据的实际金额，未退款明确填0"
            />
          </el-form-item>
          <el-form-item label="历史收款时间">
            <el-date-picker
              v-model="form.paidTime"
              type="datetime"
              value-format="yyyy-MM-dd HH:mm:ss"
            />
          </el-form-item>
          <el-form-item label="历史退款时间">
            <el-date-picker
              v-model="form.refundedTime"
              type="datetime"
              value-format="yyyy-MM-dd HH:mm:ss"
            />
          </el-form-item>
        </template>
        <template v-if="operation === 'collect'">
          <el-form-item label="优惠券">
            <el-select
              v-model="form.userCouponId"
              clearable
              :disabled="quoteLoading || (detail && detail.billType === 'CREDIT_PACKAGE')"
              @change="refreshQuote"
            >
              <el-option
                v-for="c in availableCoupons"
                :key="c.userCouponId"
                :value="c.userCouponId"
                :label="c.couponName + '（抵扣' + money(c.discountAmount) + '元）'"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="后端最新报价">
            <span v-if="quote">
              原价 {{ money(quote.originalAmount) }} − 优惠 {{ money(quote.discountAmount) }} = 应收
              {{ money(quote.payableAmount) }} 元
            </span>
            <span v-else>正在试算／请重试</span>
            <el-button type="text" @click="refreshQuote">刷新报价</el-button>
          </el-form-item>
          <el-form-item v-if="detail && detail.financeMode === 'MOCK'" label="收款渠道">
            <el-tag type="warning">本地模拟／不涉及真实资金</el-tag>
          </el-form-item>
          <el-form-item v-else-if="quote && quote.payableAmount !== '0.00'" label="收款渠道">
            <el-select v-model="form.channel">
              <el-option
                v-for="c in ['CASH', 'BANK_TRANSFER', 'WECHAT_OFFLINE']"
                :key="c"
                :value="c"
                :label="label(c)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="实际付款人">
            <el-input v-model="form.payerName" maxlength="64" />
          </el-form-item>
        </template>
        <template v-if="showEvidence && (operation === 'collect' || operation === 'confirm')">
          <el-form-item label="实际收款时间">
            <el-date-picker
              v-model="form.paidTime"
              type="datetime"
              value-format="yyyy-MM-dd HH:mm:ss"
            />
          </el-form-item>
          <el-form-item label="完整流水参考号">
            <el-input
              v-model="form.providerTradeNo"
              maxlength="64"
              placeholder="现金可空，转账包含账户标识"
            />
          </el-form-item>
        </template>
        <el-form-item v-if="showEvidence" label="私有凭证">
          <el-upload
            action="#"
            :http-request="handleEvidenceUpload"
            :before-upload="beforeEvidence"
            :show-file-list="false"
            accept=".pdf,.png,.jpg,.jpeg"
            :disabled="uploading"
          >
            <el-button :loading="uploading" size="small">上传 PDF／图片（≤10 MB）</el-button>
          </el-upload>
          <span>
            {{
              form.evidenceKey
                ? '凭证已上传，提交时绑定业务'
                : operation === 'confirm'
                ? '已登记凭证保留；仅需更正时重新上传'
                : '请上传真实凭证'
            }}
          </span>
        </el-form-item>
        <el-form-item v-if="operation === 'history' || operation === 'close'" label="操作原因">
          <el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="operationOpen = false" :disabled="saving || uploading">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="uploading || quoteLoading"
          @click="submitOperation"
        >
          提交
        </el-button>
      </span>
    </el-dialog>
  </div>
</template>
<script>
import {
  listTuition,
  getTuitionSummary,
  getTuition,
  quoteTuition,
  priceTuition,
  verifyHistory,
  createPayment,
  confirmPayment,
  closePayment,
  uploadEvidence,
  paymentEvidence,
  historyEvidence,
} from '@/api/system/tuition'
import { issueReceipt, receiptFile } from '@/api/system/tuitionReceipt'
import {
  money,
  amount,
  required,
  requestKey,
  label,
  statusType,
  canCollect,
  canPrice,
  canClosePayment,
  validateEvidenceFile,
  evidenceKey,
  checkedBlob,
} from '@/utils/tuitionFinance'
import { saveAs } from 'file-saver'

export default {
  name: 'Tuition',
  data() {
    return {
      loading: false,
      listError: '',
      rows: [],
      total: 0,
      showSearch: true,
      queryParams: { pageNum: 1, pageSize: 10, financeMode: 'REAL' },
      billStatuses: ['PENDING_PRICE', 'OPEN', 'HISTORY_PENDING', 'CLOSED'],
      amounts: [
        { key: 'originalAmount', title: '原价' },
        { key: 'discountAmount', title: '优惠' },
        { key: 'payableAmount', title: '应付' },
        { key: 'receivedAmount', title: '已收' },
        { key: 'refundedAmount', title: '已退' },
        { key: 'netAmount', title: '净收款' },
        { key: 'dueAmount', title: '待缴' },
        { key: 'refundableAmount', title: '可新申请退费' },
      ],
      detailOpen: false,
      detailLoading: false,
      detailError: '',
      detail: null,
      operationOpen: false,
      operation: '',
      operationTitle: '',
      form: {},
      formError: '',
      saving: false,
      uploading: false,
      quote: null,
      quoteLoading: false,
      availableCoupons: [],
      payment: null,
      detailRequest: 0,
      quoteRequest: 0,
      summary: null,
      summaryError: '',
      summaryRequest: 0,
      summaryAmounts: [
        { key: 'receivedAmount', title: '累计实收' },
        { key: 'refundedAmount', title: '累计已退' },
        { key: 'netAmount', title: '净收款' },
        { key: 'dueAmount', title: '待缴' },
        { key: 'todayReceivedAmount', title: '今日实收' },
        { key: 'todayRefundedAmount', title: '今日已退' },
      ],
    }
  },
  computed: {
    showEvidence() {
      return (
        this.operation === 'history' ||
        (['collect', 'confirm'].includes(this.operation) &&
          this.detail &&
          this.detail.financeMode !== 'MOCK' &&
          !(this.operation === 'collect' && this.quote && this.quote.payableAmount === '0.00') &&
          !(this.operation === 'confirm' && this.payment && this.payment.channel === 'FREE'))
      )
    },
  },
  created() {
    this.getList()
    if (this.$route.query.enrollmentId)
      this.handleDetail({ enrollmentId: this.$route.query.enrollmentId })
  },
  watch: {
    '$route.query.enrollmentId'(id) {
      if (id) this.handleDetail({ enrollmentId: id })
    },
  },
  methods: {
    money,
    label,
    statusType,
    canCollect,
    canPrice,
    canClosePayment,
    async getList() {
      this.loading = true
      this.listError = ''
      try {
        const [r] = await Promise.all([listTuition(this.queryParams), this.getSummary()])
        this.rows = r.rows || []
        this.total = r.total || 0
      } catch (e) {
        this.rows = []
        this.total = 0
        this.listError = e.message || '台账查询失败'
      } finally {
        this.loading = false
      }
    },
    async getSummary() {
      const sequence = ++this.summaryRequest
      this.summary = null
      this.summaryError = ''
      try {
        const r = await getTuitionSummary({ financeMode: this.queryParams.financeMode })
        if (sequence === this.summaryRequest) this.summary = r.data
      } catch (e) {
        if (sequence === this.summaryRequest) this.summaryError = e.message || '汇总查询失败'
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
    handleExport() {
      this.download(
        'system/tuition/export',
        { ...this.queryParams },
        '课程收费_' + this.queryParams.financeMode + '_' + Date.now() + '.xlsx'
      )
    },
    async handleDetail(row) {
      const sequence = ++this.detailRequest
      this.detail = null
      this.detailError = ''
      this.detailOpen = true
      this.detailLoading = true
      try {
        const r = await getTuition(String(row.enrollmentId))
        if (sequence === this.detailRequest) this.detail = r.data
      } catch (e) {
        if (sequence === this.detailRequest) this.detailError = e.message || '详情查询失败'
      } finally {
        if (sequence === this.detailRequest) this.detailLoading = false
      }
    },
    async openOperation(operation, payment) {
      this.operation = operation
      this.payment = payment || null
      this.operationTitle = {
        price: '核定课程价格',
        history: '历史收费核验',
        collect: '登记收款（后端最终核价）',
        confirm: '核对凭证后确认收款',
        close: '关闭待确认收款',
      }[operation]
      this.formError = ''
      this.quote = null
      this.availableCoupons = []
      this.form = {
        originalAmount: this.detail.originalAmount,
        financialVersion: this.detail.financialVersion,
        financeMode: this.detail.financeMode || 'REAL',
        userCouponId: this.detail.billType === 'CREDIT_PACKAGE' ? (this.detail.packageSnapshot || {}).userCouponId || null : null,
        payerName: this.detail.parentName || '',
        channel: 'CASH',
        paidTime: (payment && payment.paidTime) || '',
        providerTradeNo: (payment && payment.providerTradeNo) || '',
        evidenceKey: '',
        reason: '',
        idempotencyKey: requestKey(),
      }
      this.operationOpen = true
      if (operation === 'collect') await this.refreshQuote()
    },
    async refreshQuote() {
      const sequence = ++this.quoteRequest
      this.quote = null
      this.quoteLoading = true
      this.formError = ''
      try {
        const userCouponId = this.detail.billType === 'CREDIT_PACKAGE' ? (this.detail.packageSnapshot || {}).userCouponId || null : this.form.userCouponId
        const r = await quoteTuition(this.detail.enrollmentId, userCouponId)
        if (sequence === this.quoteRequest) {
          this.quote = r.data
          this.availableCoupons = r.data.coupons || []
        }
      } catch (e) {
        if (sequence === this.quoteRequest) this.formError = e.message || '试算失败，请刷新报价'
      } finally {
        if (sequence === this.quoteRequest) this.quoteLoading = false
      }
    },
    beforeEvidence(file) {
      try {
        return validateEvidenceFile(file)
      } catch (e) {
        this.formError = e.message
        return false
      }
    },
    async handleEvidenceUpload(options) {
      this.uploading = true
      this.formError = ''
      try {
        const r = await uploadEvidence(
          options.file,
          this.operation === 'history' ? 'HISTORY' : 'PAYMENT',
          this.detail.enrollmentId
        )
        this.$set(this.form, 'evidenceKey', evidenceKey(r))
        options.onSuccess(r)
      } catch (e) {
        this.formError = e.message || '上传失败'
        options.onError(e)
      } finally {
        this.uploading = false
      }
    },
    async submitOperation() {
      if (this.saving || this.uploading || this.quoteLoading) return
      this.formError = ''
      this.saving = true
      try {
        const id = this.detail.enrollmentId
        if (this.operation === 'price') {
          if (!canPrice(this.detail)) throw new Error('已有有效收款，不能核价')
          if (this.form.financeMode === 'MOCK' && this.detail.mockPricingAllowed !== true)
            throw new Error('仅后端允许的本地环境可核价为模拟账单')
          await priceTuition(id, {
            originalAmount: amount(this.form.originalAmount),
            financialVersion: this.detail.financialVersion,
            financeMode: this.form.financeMode,
          })
        }
        if (this.operation === 'history') {
          const paid = amount(this.form.historyPaidAmount)
          const refunded = amount(this.form.historyRefundedAmount)
          await verifyHistory(id, {
            originalAmount: amount(this.form.originalAmount),
            historyPaidAmount: paid,
            historyRefundedAmount: refunded,
            paidTime: paid !== '0.00' ? required(this.form.paidTime, '历史收款时间') : null,
            refundedTime:
              refunded !== '0.00' ? required(this.form.refundedTime, '历史退款时间') : null,
            evidenceKey: required(this.form.evidenceKey, '历史核验凭证'),
            reason: required(this.form.reason, '核验原因'),
            financialVersion: this.detail.financialVersion,
          })
        }
        if (this.operation === 'collect') {
          if (!canCollect(this.detail) || !this.quote)
            throw new Error('请刷新账单和报价，当前不可登记')
          const zero = this.quote.payableAmount === '0.00'
          const mock = this.detail.financeMode === 'MOCK'
          if (mock && this.detail.mockAllowed !== true)
            throw new Error('仅本地允许的模拟账单可演示缴费')
          await createPayment(id, {
            channel: zero ? 'FREE' : mock ? 'MOCK' : this.form.channel,
            userCouponId: this.quote.userCouponId || null,
            expectedPayableAmount: this.quote.payableAmount,
            financialVersion: this.quote.financialVersion,
            idempotencyKey: this.form.idempotencyKey,
            payerName: required(this.form.payerName, '实际付款人'),
            providerTradeNo:
              zero || mock
                ? null
                : this.form.channel !== 'CASH'
                ? required(this.form.providerTradeNo, '完整流水参考号')
                : this.form.providerTradeNo || null,
            evidenceKey: zero || mock ? null : required(this.form.evidenceKey, '收款凭证'),
            paidTime: zero || mock ? null : required(this.form.paidTime, '实际收款时间'),
          })
        }
        if (this.operation === 'confirm') {
          const automatic = this.payment.channel === 'MOCK' || this.payment.channel === 'FREE'
          if (this.payment.channel === 'MOCK' && this.detail.mockAllowed !== true)
            throw new Error('仅本地允许的模拟账单可确认')
          await confirmPayment(
            this.payment.paymentId,
            automatic
              ? {}
              : {
                  evidenceKey: this.form.evidenceKey || null,
                  providerTradeNo:
                    this.form.providerTradeNo || this.payment.providerTradeNo || null,
                  paidTime: required(this.form.paidTime || this.payment.paidTime, '实际收款时间'),
                }
          )
        }
        if (this.operation === 'close') {
          if (!canClosePayment(this.payment)) throw new Error('结果未知须先核对，不能关闭')
          await closePayment(this.payment.paymentId, {
            reason: required(this.form.reason, '关闭原因'),
          })
        }
        this.operationOpen = false
        this.$modal.msgSuccess('操作已提交，请核对最新状态')
        await Promise.all([this.getList(), this.handleDetail({ enrollmentId: id })])
      } catch (e) {
        this.formError = e.message || '操作失败，请刷新后核对并重试'
      } finally {
        this.saving = false
      }
    },
    async downloadPayment(row) {
      try {
        saveAs(await checkedBlob(await paymentEvidence(row.paymentId)), '收款凭证_' + row.paymentNo)
      } catch (e) {
        this.$modal.msgError(e.message || '下载失败')
      }
    },
    async downloadHistory() {
      try {
        saveAs(
          await checkedBlob(await historyEvidence(this.detail.enrollmentId)),
          '历史核验凭证_' + this.detail.enrollmentCode
        )
      } catch (e) {
        this.$modal.msgError(e.message || '下载失败')
      }
    },
    async downloadReceipt(row) {
      try {
        saveAs(await checkedBlob(await receiptFile(row.receiptId)), row.receiptNo + '.pdf')
      } catch (e) {
        this.$modal.msgError(e.message || '下载失败')
      }
    },
    async handleIssue(row) {
      if (this.saving) return
      this.saving = true
      try {
        await issueReceipt(row.paymentId)
        this.$modal.msgSuccess('收据已创建／返回已有收据')
        await this.handleDetail(this.detail)
      } catch (e) {
        this.$modal.msgError(e.message || '开具失败')
      } finally {
        this.saving = false
      }
    },
    goRefund(row) {
      if (this.detail && this.detail.billType === 'CREDIT_PACKAGE') return
      this.detailOpen = false
      this.$router.push({
        path: '/finance/tuitionRefund',
        query: { paymentId: String(row.paymentId), enrollmentId: String(this.detail.enrollmentId) },
      })
    },
  },
}
</script>
<style scoped>
.finance-actions {
  margin: 16px 0;
}
.finance-summary {
  padding: 12px 16px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fafafa;
}
.summary-caption,
.summary-label {
  font-size: 12px;
  color: #606266;
  margin: 6px 0;
}
.summary-value {
  font-size: 19px;
  color: #303133;
  font-weight: 600;
}
</style>
