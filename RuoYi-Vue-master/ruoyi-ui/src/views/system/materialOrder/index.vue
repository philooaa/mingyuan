<template>
  <div class="app-container material-orders">
    <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="订单编码" prop="orderCode"><el-input v-model="queryParams.orderCode" clearable @keyup.enter.native="handleQuery" /></el-form-item>
      <el-form-item label="商品名称" prop="title"><el-input v-model="queryParams.title" clearable @keyup.enter.native="handleQuery" /></el-form-item>
      <el-form-item label="家长" prop="parentName"><el-input v-model="queryParams.parentName" clearable @keyup.enter.native="handleQuery" /></el-form-item>
      <el-form-item label="支付状态" prop="payStatus"><el-select v-model="queryParams.payStatus" clearable><el-option label="未支付" value="0" /><el-option label="已支付" value="1" /><el-option label="已退款" value="2" /></el-select></el-form-item>
      <el-form-item label="订单状态" prop="orderStatus"><el-select v-model="queryParams.orderStatus" clearable><el-option v-for="(label, value) in orderStates" :key="value" :value="value" :label="label" /></el-select></el-form-item>
      <el-form-item label="下单时间"><el-date-picker v-model="dateRange" value-format="yyyy-MM-dd" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" /></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button><el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button plain size="mini" icon="el-icon-refresh" :loading="loading" @click="getList()">刷新订单</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain size="mini" icon="el-icon-download" @click="handleExport" v-hasPermi="['system:materialOrder:export']">导出</el-button></el-col>
      <el-col :span="9"><span class="refresh-note">每15秒自动刷新 · {{ lastRefresh ? '最近更新 ' + lastRefresh : '正在加载' }}</span></el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="mb8" />
    <el-table v-loading="loading" :data="materialOrderList" row-key="orderId">
      <el-table-column label="订单编码" prop="orderCode" min-width="175" show-overflow-tooltip />
      <el-table-column label="商品快照" min-width="165"><template slot-scope="scope">{{ itemTitles(scope.row) }}</template></el-table-column>
      <el-table-column label="家长" prop="parentName" width="95" />
      <el-table-column label="数量" prop="totalQuantity" width="60" />
      <el-table-column label="应付 / 实付" width="145"><template slot-scope="scope">¥{{ money(scope.row.payableAmount) }} / ¥{{ money(scope.row.amount) }}</template></el-table-column>
      <el-table-column label="订单状态" width="100"><template slot-scope="scope"><el-tag size="small" :type="scope.row.orderStatus === 'WAIT_SHIP' ? 'warning' : 'info'">{{ orderText(scope.row.orderStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="支付状态" width="95"><template slot-scope="scope"><el-tag size="small" :type="scope.row.payStatus === '1' ? 'success' : scope.row.payStatus === '2' ? 'warning' : 'info'">{{ payText(scope.row.payStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="支付方式" width="90"><template slot-scope="scope">{{ paymentText(scope.row) }}</template></el-table-column>
      <el-table-column label="收货人" prop="receiverName" width="95" />
      <el-table-column label="联系电话" width="135"><template slot-scope="scope">{{ maskPhone(scope.row.receiverPhone) }}</template></el-table-column>
      <el-table-column label="支付时间" prop="payTime" width="160" />
      <el-table-column label="操作" fixed="right" width="170"><template slot-scope="scope">
        <el-button size="mini" type="text" @click="handleDetail(scope.row)" v-hasPermi="['system:materialOrder:query']">详情</el-button>
        <el-button v-if="canShip(scope.row)" size="mini" type="text" @click="handleShip(scope.row)" v-hasPermi="['system:materialOrder:edit']">发货</el-button>
        <el-button v-if="canRefund(scope.row)" size="mini" type="text" @click="handleRefund(scope.row)" v-hasPermi="['system:materialOrder:edit']">模拟退款</el-button>
      </template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-drawer title="订单详情" :visible.sync="detailOpen" size="min(850px, 95vw)" append-to-body @close="clearDetail">
      <div class="order-detail" v-loading="detailLoading">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
        <template v-if="detailRow">
          <el-alert v-if="detailRow.payment && detailRow.payment.channel === 'MOCK'" title="模拟支付订单，不涉及真实扣款；模拟退款不代表真实资金退回。" type="warning" :closable="false" show-icon class="mb8" />
          <el-descriptions :column="2" border>
            <el-descriptions-item label="订单编码" :span="2">{{ detailRow.orderCode }}</el-descriptions-item>
            <el-descriptions-item label="订单状态">{{ orderText(detailRow.orderStatus) }}</el-descriptions-item>
            <el-descriptions-item label="支付状态">{{ payText(detailRow.payStatus) }}</el-descriptions-item>
            <el-descriptions-item label="售后状态">{{ aftersaleText(detailRow.aftersaleStatus) }}</el-descriptions-item>
            <el-descriptions-item label="交付方式">{{ detailRow.deliveryType === 'PHYSICAL' ? '纸质配送' : '电子下载' }}</el-descriptions-item>
            <el-descriptions-item label="家长">{{ detailRow.parentName }}</el-descriptions-item>
            <el-descriptions-item label="数量">{{ detailRow.totalQuantity }}</el-descriptions-item>
            <el-descriptions-item label="商品金额">¥{{ money(detailRow.goodsAmount) }}</el-descriptions-item>
            <el-descriptions-item label="运费">¥{{ money(detailRow.shippingAmount) }}</el-descriptions-item>
            <el-descriptions-item label="应付金额">¥{{ money(detailRow.payableAmount) }}</el-descriptions-item>
            <el-descriptions-item label="实付金额">¥{{ money(detailRow.amount) }}</el-descriptions-item>
            <el-descriptions-item label="已退款金额">¥{{ money(detailRow.refundedAmount) }}</el-descriptions-item>
            <el-descriptions-item label="支付方式">{{ paymentText(detailRow) }}</el-descriptions-item>
            <el-descriptions-item label="下单时间">{{ detailRow.createTime || '—' }}</el-descriptions-item>
            <el-descriptions-item label="支付期限">{{ detailRow.expireTime || '—' }}</el-descriptions-item>
            <el-descriptions-item label="支付时间">{{ detailRow.payTime || '—' }}</el-descriptions-item>
            <el-descriptions-item label="支付记录状态">{{ detailRow.payment && detailRow.payment.paymentStatus || '—' }}</el-descriptions-item>
            <el-descriptions-item label="微信交易号" :span="2">{{ detailRow.payment && detailRow.payment.wxTransactionId || '—' }}</el-descriptions-item>
          </el-descriptions>
          <h3>购买时商品快照</h3>
          <el-table :data="detailRow.items || []" border>
            <el-table-column label="封面" width="70"><template slot-scope="scope"><image-preview v-if="scope.row.coverUrl" :src="scope.row.coverUrl" :width="40" :height="40" /></template></el-table-column>
            <el-table-column label="商品" prop="materialTitle" min-width="140" />
            <el-table-column label="学科 / 年级" width="125"><template slot-scope="scope">{{ scope.row.subjectLabel || scope.row.subjectCode || '—' }} / {{ scope.row.gradeLabel || scope.row.gradeCode || '—' }}</template></el-table-column>
            <el-table-column label="单价" width="90"><template slot-scope="scope">¥{{ money(scope.row.unitPrice) }}</template></el-table-column>
            <el-table-column label="数量" width="75"><template slot-scope="scope">{{ scope.row.quantity }} {{ scope.row.unit }}</template></el-table-column>
            <el-table-column label="小计" width="90"><template slot-scope="scope">¥{{ money(scope.row.lineAmount) }}</template></el-table-column>
          </el-table>
          <template v-if="detailRow.deliveryType === 'PHYSICAL'">
            <h3>收货信息快照</h3>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="收货人">{{ detailRow.receiverName }}　{{ detailRow.receiverPhone }}</el-descriptions-item>
              <el-descriptions-item label="完整地址">{{ fullAddress(detailRow) }}</el-descriptions-item>
            </el-descriptions>
          </template>
          <h3>留言与备注</h3>
          <el-descriptions :column="1" border><el-descriptions-item label="买家留言">{{ detailRow.buyerRemark || '—' }}</el-descriptions-item><el-descriptions-item label="内部备注">{{ detailRow.adminRemark || '—' }}</el-descriptions-item></el-descriptions>
          <h3>发货信息</h3>
          <el-descriptions v-if="detailRow.shipment" :column="2" border>
            <el-descriptions-item label="快递公司">{{ detailRow.shipment.carrierName }}（{{ detailRow.shipment.carrierCode }}）</el-descriptions-item>
            <el-descriptions-item label="运单号">{{ detailRow.shipment.trackingNo }}</el-descriptions-item>
            <el-descriptions-item label="发货时间">{{ detailRow.shipment.shipTime }}</el-descriptions-item>
            <el-descriptions-item label="确认收货">{{ detailRow.shipment.receiveTime || '尚未确认' }}</el-descriptions-item>
          </el-descriptions><p v-else>{{ detailRow.deliveryType === 'DIGITAL' ? '电子资料无需发货' : '尚未发货' }}</p>
          <h3>操作历史</h3>
          <el-timeline><el-timeline-item v-for="(log, index) in detailRow.logs || []" :key="log.logId || index" :timestamp="log.createTime"><div>{{ eventText(log.eventType) }} · {{ log.operatorType }}</div><div>{{ orderText(log.fromOrderStatus) }} → {{ orderText(log.toOrderStatus) }}</div><div v-if="log.remark" class="remark">{{ log.remark }}</div></el-timeline-item></el-timeline>
          <el-button v-if="canShip(detailRow)" type="primary" size="small" @click="handleShip(detailRow)" v-hasPermi="['system:materialOrder:edit']">登记发货</el-button>
          <el-button v-if="canRefund(detailRow)" type="warning" size="small" @click="handleRefund(detailRow)" v-hasPermi="['system:materialOrder:edit']">模拟全额退款</el-button>
          <el-button size="small" @click="handleDetail(detailRow)">刷新详情</el-button>
        </template>
      </div>
    </el-drawer>
    <el-dialog title="登记发货" :visible.sync="shipOpen" width="560px" append-to-body :close-on-click-modal="false" :before-close="closeShip">
      <el-alert v-if="operationError" :title="operationError" type="error" :closable="false" class="mb8" />
      <el-form :model="shipForm" label-width="95px" :disabled="operationSaving">
        <el-form-item label="订单编码">{{ shipForm.orderCode }}</el-form-item>
        <el-form-item label="收货信息"><div>{{ shipForm.receiverName }} {{ shipForm.receiverPhone }}</div><div>{{ shipForm.address }}</div></el-form-item>
        <el-form-item label="快递编码" required><el-input v-model="shipForm.carrierCode" maxlength="32" placeholder="如SF" /></el-form-item>
        <el-form-item label="快递公司" required><el-input v-model="shipForm.carrierName" maxlength="50" placeholder="如顺丰速运" /></el-form-item>
        <el-form-item label="运单号" required><el-input v-model="shipForm.trackingNo" maxlength="64" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="operationSaving" @click="closeShip">取消</el-button><el-button type="primary" :loading="operationSaving" @click="submitShip">确认发货</el-button></div>
    </el-dialog>
    <el-dialog title="模拟全额退款" :visible.sync="refundOpen" width="500px" append-to-body :close-on-click-modal="false" :before-close="closeRefund">
      <el-alert title="仅修改测试订单和库存，不会退回任何真实资金。已发货订单暂不支持退款。" type="warning" :closable="false" show-icon class="mb8" />
      <el-alert v-if="operationError" :title="operationError" type="error" :closable="false" class="mb8" />
      <el-form :model="refundForm" label-width="90px" :disabled="operationSaving">
        <el-form-item label="订单编码">{{ refundForm.orderCode }}</el-form-item>
        <el-form-item label="全额退款">¥{{ money(refundForm.amount) }}</el-form-item>
        <el-form-item label="退款原因" required><el-input v-model="refundForm.reason" type="textarea" :rows="3" maxlength="255" show-word-limit /></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="operationSaving" @click="closeRefund">取消</el-button><el-button type="warning" :loading="operationSaving" @click="submitRefund">确认模拟退款</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { listMaterialOrder, getMaterialOrder, shipMaterialOrder, refundMaterialOrder } from '@/api/system/materialOrder'
import { money, maskPhone, canShip, canRefund, orderQuery } from '@/utils/materialShop'

export default {
  name: 'MaterialOrder',
  data() {
    return { loading: false, showSearch: true, total: 0, materialOrderList: [], listError: '', listSequence: 0, lastRefresh: '', dateRange: [],
      queryParams: { pageNum: 1, pageSize: 10, orderCode: '', title: '', parentName: '', payStatus: '', orderStatus: '' },
      orderStates: { WAIT_PAY: '待支付', WAIT_SHIP: '待发货', WAIT_RECEIVE: '待收货', COMPLETED: '已完成', CANCELLED: '已取消', CLOSED: '已关闭' },
      detailOpen: false, detailRow: null, detailLoading: false, detailError: '', detailSequence: 0,
      shipOpen: false, shipForm: {}, refundOpen: false, refundForm: {}, operationSaving: false, operationError: '',
      pollTimer: null, active: false }
  },
  created() { this.getList() },
  mounted() { this.startPolling() },
  activated() { this.startPolling(); this.getList() },
  deactivated() { this.stopPolling(); this.clearDetail(); this.shipOpen = false; this.refundOpen = false },
  beforeDestroy() { this.stopPolling(); this.clearDetail() },
  methods: {
    money, maskPhone, canShip, canRefund,
    orderText(status) { return this.orderStates[status] || status || '—' },
    payText(status) { return { '0': '未支付', '1': '已支付', '2': '已退款' }[status] || '未知' },
    aftersaleText(status) { return { NONE: '无售后', REFUNDING: '退款处理中', REFUNDED: '已全额退款' }[status] || status || '—' },
    paymentText(order) { return { MOCK: '模拟支付', WECHAT: '微信支付', FREE: '免费领取' }[order.payment && order.payment.channel] || '—' },
    itemTitles(order) { return (order.items || []).map(item => item.materialTitle).join('、') || '—' },
    fullAddress(order) { return [order.provinceName, order.cityName, order.districtName, order.detailAddress].filter(Boolean).join(' ') },
    eventText(event) { return { CREATE: '创建订单', PAY_SUCCESS: '支付成功', CANCEL: '取消订单', EXPIRE: '超时关闭', SHIP: '登记发货', RECEIVE: '确认收货', MOCK_REFUND: '模拟全额退款', MIGRATION: '历史数据迁移' }[event] || event },
    errorMessage(error) { return error && error.message || (typeof error === 'string' && error !== 'error' ? error : '操作失败，请查看服务提示后重试') },
    async getList() {
      const sequence = ++this.listSequence
      this.loading = true
      this.listError = ''
      try {
        const response = await listMaterialOrder(orderQuery(this.queryParams, this.dateRange))
        if (sequence === this.listSequence) { this.materialOrderList = response.rows || []; this.total = response.total || 0; this.lastRefresh = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
      } catch (error) { if (sequence === this.listSequence) this.listError = this.errorMessage(error) }
      finally { if (sequence === this.listSequence) this.loading = false }
    },
    handleQuery() { this.queryParams.pageNum = 1; this.startPolling(); return this.getList() },
    resetQuery() { this.dateRange = []; this.resetForm('queryForm'); return this.handleQuery() },
    startPolling() {
      this.active = true
      if (this.pollTimer !== null) return
      this.pollTimer = setInterval(() => { if (this.active && !this.loading && !this.operationSaving && !this.shipOpen && !this.refundOpen) this.getList() }, 15000)
    },
    stopPolling() { this.active = false; if (this.pollTimer !== null) clearInterval(this.pollTimer); this.pollTimer = null },
    clearDetail() { ++this.detailSequence; this.detailRow = null; this.detailOpen = false; this.detailLoading = false; this.detailError = '' },
    async handleDetail(row) {
      const sequence = ++this.detailSequence
      this.detailRow = null
      this.detailError = ''
      this.detailLoading = true
      this.detailOpen = true
      try {
        const response = await getMaterialOrder(String(row.orderId))
        if (sequence === this.detailSequence) { this.detailRow = response.data; return response.data }
      } catch (error) { if (sequence === this.detailSequence) this.detailError = this.errorMessage(error) }
      finally { if (sequence === this.detailSequence) this.detailLoading = false }
      return null
    },
    async handleShip(row) {
      const detail = await this.handleDetail(row)
      if (!detail) return
      if (!canShip(detail)) { this.$modal.msgError('当前订单不可发货，请核对最新状态'); return }
      this.shipForm = { orderId: String(detail.orderId), orderCode: detail.orderCode, receiverName: detail.receiverName, receiverPhone: detail.receiverPhone, address: this.fullAddress(detail), carrierCode: '', carrierName: '', trackingNo: '' }
      this.operationError = ''; this.shipOpen = true
    },
    closeShip(done) { if (this.operationSaving) return; this.shipOpen = false; if (typeof done === 'function') done() },
    closeRefund(done) { if (this.operationSaving) return; this.refundOpen = false; if (typeof done === 'function') done() },
    async submitShip() {
      if (this.operationSaving) return
      const body = {}
      for (const [key, max] of [['carrierCode', 32], ['carrierName', 50], ['trackingNo', 64]]) {
        body[key] = String(this.shipForm[key] || '').trim()
        if (!body[key] || body[key].length > max) { this.operationError = '请完整填写有效快递公司编码、名称和运单号'; return }
      }
      this.operationSaving = true; this.operationError = ''
      try { const response = await shipMaterialOrder(this.shipForm.orderId, body); this.shipOpen = false; this.detailRow = response.data; this.$modal.msgSuccess('发货已登记'); await this.getList() }
      catch (error) { this.operationError = this.errorMessage(error) }
      finally { this.operationSaving = false }
    },
    async handleRefund(row) {
      const detail = await this.handleDetail(row)
      if (!detail) return
      if (!canRefund(detail)) { this.$modal.msgError('当前订单不可模拟退款，请核对最新状态'); return }
      this.refundForm = { orderId: String(detail.orderId), orderCode: detail.orderCode, amount: detail.amount, reason: '' }
      this.operationError = ''; this.refundOpen = true
    },
    async submitRefund() {
      if (this.operationSaving) return
      const reason = String(this.refundForm.reason || '').trim()
      if (!reason || reason.length > 255) { this.operationError = '请填写255字以内的退款原因'; return }
      this.operationError = ''; this.operationSaving = true
      try {
        await this.$modal.confirm('确认模拟全额退款？仅更新测试订单与库存，不会退回真实资金。')
        const response = await refundMaterialOrder(this.refundForm.orderId, reason)
        this.refundOpen = false; this.detailRow = response.data; this.$modal.msgSuccess('模拟退款完成，未发生真实资金退款')
        await this.getList()
      } catch (error) { if (error && error !== 'cancel' && error !== 'close') this.operationError = this.errorMessage(error) }
      finally { this.operationSaving = false }
    },
    handleExport() { this.download('system/materialOrder/export', orderQuery(this.queryParams, this.dateRange), `materialOrder_${Date.now()}.xlsx`) }
  }
}
</script>

<style scoped>
.refresh-note { font-size: 12px; color: #909399; line-height: 28px; }
.order-detail { padding: 0 24px 30px; min-height: 120px; }
.order-detail h3 { margin-top: 24px; font-size: 16px; }
.remark { white-space: pre-wrap; color: #606266; }
</style>
