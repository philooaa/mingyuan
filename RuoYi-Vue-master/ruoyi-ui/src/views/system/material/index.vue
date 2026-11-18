<template>
  <div class="app-container material-shop">
    <el-form ref="queryForm" :model="queryParams" size="small" :inline="true" v-show="showSearch">
      <el-form-item label="商品名称" prop="title"><el-input v-model="queryParams.title" clearable @keyup.enter.native="handleQuery" /></el-form-item>
      <el-form-item label="学科" prop="subjectCode"><el-select v-model="queryParams.subjectCode" clearable><el-option v-for="item in dict.type.edu_subject" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="年级" prop="gradeCode"><el-select v-model="queryParams.gradeCode" clearable><el-option v-for="item in dict.type.edu_grade" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="销售状态" prop="shelfStatus"><el-select v-model="queryParams.shelfStatus" clearable><el-option label="草稿" value="2" /><el-option label="已上架" value="1" /><el-option label="已下架" value="0" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button><el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['system:material:add']">新增商品</el-button></el-col>
      <el-col :span="1.5"><el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="!ids.length" @click="handleDelete()" v-hasPermi="['system:material:remove']">删除</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="el-icon-download" size="mini" @click="handleExport" v-hasPermi="['system:material:export']">导出</el-button></el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="mb8" />
    <el-table v-loading="loading" :data="materialList" row-key="materialId" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="48" />
      <el-table-column label="封面" width="82"><template slot-scope="scope"><image-preview v-if="scope.row.coverUrl" :src="scope.row.coverUrl" :width="52" :height="52" /><span v-else>暂无封面</span></template></el-table-column>
      <el-table-column label="商品名称" prop="title" min-width="180" show-overflow-tooltip />
      <el-table-column label="学科 / 年级" width="135"><template slot-scope="scope">{{ scope.row.subjectName || '—' }} / {{ scope.row.gradeName || '—' }}</template></el-table-column>
      <el-table-column label="交付方式" width="105"><template slot-scope="scope"><el-tag :type="scope.row.deliveryType === 'PHYSICAL' ? '' : 'info'" size="small">{{ scope.row.deliveryType === 'PHYSICAL' ? '纸质配送' : '电子下载' }}</el-tag></template></el-table-column>
      <el-table-column label="售价" width="105"><template slot-scope="scope">¥{{ money(scope.row.price) }}</template></el-table-column>
      <el-table-column label="可售 / 预占" width="115"><template slot-scope="scope"><span v-if="scope.row.deliveryType === 'PHYSICAL'">{{ availableStock(scope.row) }} / {{ scope.row.stockLocked || 0 }}</span><span v-else>无需库存</span></template></el-table-column>
      <el-table-column label="销量" prop="saleCount" width="65" />
      <el-table-column label="状态" width="90"><template slot-scope="scope"><el-tag :type="scope.row.shelfStatus === '1' ? 'success' : 'info'" size="small">{{ shelfText(scope.row.shelfStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="240" fixed="right"><template slot-scope="scope">
        <el-button size="mini" type="text" @click="handleUpdate(scope.row)" v-hasPermi="['system:material:edit']">编辑</el-button>
        <el-button size="mini" type="text" @click="handleToggleShelf(scope.row)" v-hasPermi="['system:material:edit']">{{ scope.row.shelfStatus === '1' ? '下架' : '上架' }}</el-button>
        <el-button v-if="scope.row.deliveryType === 'PHYSICAL'" size="mini" type="text" @click="handleStock(scope.row)" v-hasPermi="['system:material:edit']">调整库存</el-button>
        <el-button size="mini" type="text" @click="handleDelete(scope.row)" v-hasPermi="['system:material:remove']">删除</el-button>
      </template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="form.materialId ? '编辑商品' : '新增商品'" :visible.sync="open" width="850px" append-to-body :close-on-click-modal="false" :before-close="closeEditor">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="mb8" />
      <el-form ref="form" :model="form" label-width="108px" size="small" :disabled="saving || pdfUploading">
        <h3>基础信息</h3>
        <el-row :gutter="16">
          <el-col :span="24"><el-form-item label="商品名称" required><el-input v-model="form.title" maxlength="100" show-word-limit /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="简短卖点"><el-input v-model="form.subtitle" maxlength="200" show-word-limit /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="商品编码"><el-input :value="form.materialCode || '保存时自动生成'" disabled /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="交付方式"><el-radio-group v-model="form.deliveryType"><el-radio label="PHYSICAL">纸质配送</el-radio><el-radio label="DIGITAL">电子下载</el-radio></el-radio-group></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="学科"><el-select v-model="form.subjectCode" clearable><el-option v-for="item in dict.type.edu_subject" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="年级"><el-select v-model="form.gradeCode" clearable><el-option v-for="item in dict.type.edu_grade" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="教材版本"><el-input v-model="form.textbookVersion" maxlength="100" placeholder="如人教版" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="资料类型"><el-select v-model="form.materialType" clearable placeholder="请选择资料类型"><el-option v-for="item in dict.type.edu_material_type" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item></el-col>
        </el-row>
        <h3>图片与详情</h3>
        <el-form-item label="商品封面"><image-upload ref="coverUpload" v-model="form.coverUrl" :limit="1" :file-size="5" :disabled="saving || pdfUploading" /></el-form-item>
        <el-form-item label="额外轮播图"><image-upload ref="galleryUpload" v-model="form.galleryUrls" :limit="4" :file-size="5" :disabled="saving || pdfUploading" /><div class="help">封面与额外图片组成1—5张轮播，拖动可排序。</div></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.intro" type="textarea" :rows="2" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="详情描述"><el-input v-model="form.detailText" type="textarea" :rows="4" placeholder="纯文本介绍，上架必填" /></el-form-item>
        <el-form-item label="详情图片"><image-upload ref="detailUpload" v-model="form.detailUrls" :limit="25 - imageUrls(form.galleryUrls).length" :file-size="5" :disabled="saving || pdfUploading" /></el-form-item>
        <h3>价格与库存</h3>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="销售价格" required><el-input v-model="form.price" placeholder="如12.30"><template slot="prepend">¥</template></el-input></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="计量单位" required><el-input v-model="form.unit" maxlength="20" placeholder="本 / 套 / 份" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="单次限购"><el-input v-model="form.purchaseLimit" placeholder="留空不限购，填写1至99" :disabled="form.deliveryType === 'DIGITAL'" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="2147483647" :precision="0" /></el-form-item></el-col>
          <el-col :span="24" v-if="form.deliveryType === 'PHYSICAL'"><el-form-item :label="form.materialId ? '当前库存' : '初始库存'"><template v-if="form.materialId">可售 {{ availableStock(form) }}，预占 {{ form.stockLocked || 0 }}，总库存 {{ form.stockQuantity || 0 }}<div class="help">库存调整请在列表使用“调整库存”，调整后重新打开编辑。</div></template><el-input-number v-else v-model="form.stockQuantity" :min="0" :max="2147483647" :precision="0" /></el-form-item></el-col>
        </el-row>
        <h3>交付信息</h3>
        <template v-if="form.deliveryType === 'PHYSICAL'">
          <el-row :gutter="16">
            <el-col :span="12"><el-form-item label="订单运费"><el-input v-model="form.shippingFee" placeholder="0.00为包邮"><template slot="prepend">¥</template></el-input></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="发货地"><el-input v-model="form.shipFrom" maxlength="200" placeholder="如江苏省南京市" /></el-form-item></el-col>
            <el-col :span="24"><el-form-item label="预计发货天数"><el-input-number v-model="form.dispatchDays" :min="0" :max="365" :precision="0" /> 天</el-form-item></el-col>
          </el-row>
        </template>
        <el-form-item v-else label="私有PDF">
          <el-upload :action="pdfUploadUrl" :headers="uploadHeaders" name="file" accept=".pdf,application/pdf" :show-file-list="false" :disabled="saving || pdfUploading" :before-upload="beforePdfUpload" :on-success="pdfUploadSuccess" :on-error="pdfUploadError">
            <el-button size="small" :loading="pdfUploading" :disabled="saving || pdfUploading" icon="el-icon-upload2">{{ form.filePath ? '替换PDF' : '上传PDF' }}</el-button>
          </el-upload>
          <div v-if="form.filePath">{{ form.fileName }}（{{ fileSizeText(form.fileSize) }}）</div>
          <div class="help">仅PDF，单文件不超过10MB。文件受购买权限保护，替换不影响旧订单交付版本。</div>
        </el-form-item>
        <div class="help">保存草稿可稍后补齐；上架需填写学科、年级、封面、详情、非负价格与可售库存或私有PDF。</div>
      </el-form>
      <div slot="footer">
        <el-button :disabled="saving || pdfUploading" @click="closeEditor">取消</el-button>
        <el-button :loading="saving" :disabled="pdfUploading" @click="submitForm('2')">保存草稿</el-button>
        <el-button type="primary" :loading="saving" :disabled="pdfUploading" @click="submitForm('1')">保存并上架</el-button>
      </div>
    </el-dialog>
    <el-dialog title="调整纸质库存" :visible.sync="stockOpen" width="500px" append-to-body :close-on-click-modal="false">
      <el-alert v-if="stockError" :title="stockError" type="error" :closable="false" class="mb8" />
      <el-form :model="stockForm" label-width="100px" :disabled="stockSaving">
        <el-form-item label="商品">{{ stockForm.title }}</el-form-item>
        <el-form-item label="当前库存">可售 {{ stockForm.availableStock }}，预占 {{ stockForm.stockLocked }}</el-form-item>
        <el-form-item label="增减数量"><el-input-number v-model="stockForm.delta" :min="-100000000" :max="100000000" :precision="0" /><div class="help">正数入库，负数减库；调整后不能低于预占库存。</div></el-form-item>
        <el-form-item label="调整原因" required><el-input v-model="stockForm.remark" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="stockSaving" @click="stockOpen = false">取消</el-button><el-button type="primary" :loading="stockSaving" @click="submitStock">确认调整</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { listMaterial, getMaterial, delMaterial, addMaterial, updateMaterial, changeShelfStatus, adjustMaterialStock } from '@/api/system/material'
import { getToken } from '@/utils/auth'
import { money, imageUrls, availableStock, productForm, productBody, privateFile } from '@/utils/materialShop'

export default {
  name: 'Material',
  dicts: ['edu_subject', 'edu_grade', 'edu_material_type'],
  data() {
    return { loading: false, ids: [], showSearch: true, total: 0, materialList: [], listError: '', listSequence: 0,
      queryParams: { pageNum: 1, pageSize: 10, title: '', subjectCode: '', gradeCode: '', shelfStatus: '' },
      open: false, saving: false, form: productForm(), formError: '', pdfUploading: false,
      pdfUploadUrl: process.env.VUE_APP_BASE_API + '/system/material/file', uploadHeaders: { Authorization: 'Bearer ' + getToken() },
      stockOpen: false, stockSaving: false, stockForm: {}, stockError: '' }
  },
  created() { this.getList() },
  methods: {
    money, imageUrls, availableStock,
    shelfText(status) { return { '0': '已下架', '1': '已上架', '2': '草稿' }[status] || '未知' },
    fileSizeText(size) { return size ? (Number(size) / 1024 / 1024).toFixed(2) + ' MB' : '大小未知' },
    errorMessage(error) { return error && error.message || (typeof error === 'string' && error !== 'error' ? error : '操作失败，请查看服务提示后重试') },
    async getList() {
      const sequence = ++this.listSequence
      this.loading = true
      this.listError = ''
      try {
        const response = await listMaterial({ ...this.queryParams })
        if (sequence === this.listSequence) { this.materialList = response.rows || []; this.total = response.total || 0 }
      } catch (error) { if (sequence === this.listSequence) this.listError = this.errorMessage(error) }
      finally { if (sequence === this.listSequence) this.loading = false }
    },
    handleQuery() { this.queryParams.pageNum = 1; return this.getList() },
    resetQuery() { this.resetForm('queryForm'); return this.handleQuery() },
    handleSelectionChange(rows) { this.ids = rows.map(row => String(row.materialId)) },
    handleAdd() { this.form = productForm(); this.formError = ''; this.open = true },
    async handleUpdate(row) {
      try { const response = await getMaterial(row.materialId); this.form = productForm(response.data); this.formError = ''; this.open = true }
      catch (error) { this.$modal.msgError(this.errorMessage(error)) }
    },
    imagesUploading() {
      return ['coverUpload', 'galleryUpload', 'detailUpload'].some(name => {
        const component = this.$refs[name]
        const uploader = component && component.$refs.imageUpload
        return component && (component.number > 0 || component.uploadList && component.uploadList.length > 0 || uploader && uploader.uploadFiles.some(file => file.status === 'uploading' || file.status === 'ready'))
      })
    },
    closeEditor(done) { if (this.saving || this.pdfUploading || this.imagesUploading()) return; this.open = false; if (typeof done === 'function') done() },
    async submitForm(shelfStatus) {
      if (this.saving || this.pdfUploading) return
      this.formError = ''
      if (this.imagesUploading()) { this.formError = '请等待图片上传完成后保存'; return }
      if (this.form.materialType && !this.dict.type.edu_material_type.some(item => item.value === this.form.materialType)) { this.formError = '请选择有效资料类型或清空后保存'; return }
      try {
        const body = productBody(this.form, shelfStatus)
        this.saving = true
        await (body.materialId ? updateMaterial(body) : addMaterial(body))
        this.open = false
        this.$modal.msgSuccess(shelfStatus === '1' ? '商品已保存并上架' : '草稿已保存')
        await this.getList()
      } catch (error) { this.formError = this.errorMessage(error) }
      finally { this.saving = false }
    },
    beforePdfUpload(file) {
      this.formError = ''
      if (!/\.pdf$/i.test(file.name) || file.size <= 0 || file.size > 10 * 1024 * 1024) { this.formError = '请上传10MB以内的PDF文件'; return false }
      this.pdfUploading = true
      return true
    },
    pdfUploadSuccess(response) {
      try { const file = privateFile(response); this.form = { ...this.form, ...file }; this.formError = '' }
      catch (error) { this.formError = this.errorMessage(error) }
      finally { this.pdfUploading = false }
    },
    pdfUploadError() { this.pdfUploading = false; this.formError = 'PDF上传失败，请检查连接后重试' },
    async handleToggleShelf(row) {
      const status = row.shelfStatus === '1' ? '0' : '1'
      try { await this.$modal.confirm(`确认${status === '1' ? '上架' : '下架'}“${row.title}”？`); await changeShelfStatus(row.materialId, status); this.$modal.msgSuccess('状态已更新'); await this.getList() }
      catch (error) { if (error && error !== 'cancel' && error !== 'close') this.$modal.msgError(this.errorMessage(error)) }
    },
    async handleStock(row) {
      try { const response = await getMaterial(row.materialId); this.stockForm = { materialId: String(response.data.materialId), title: response.data.title, availableStock: availableStock(response.data), stockLocked: response.data.stockLocked || 0, delta: 0, remark: '' }; this.stockError = ''; this.stockOpen = true }
      catch (error) { this.$modal.msgError(this.errorMessage(error)) }
    },
    async submitStock() {
      if (this.stockSaving) return
      this.stockError = ''
      const delta = Number(this.stockForm.delta)
      const remark = String(this.stockForm.remark || '').trim()
      if (!Number.isInteger(delta) || delta === 0 || Math.abs(delta) > 100000000 || !remark || remark.length > 500) { this.stockError = '请填写非零整数增减数量和500字以内调整原因'; return }
      this.stockSaving = true
      try { await adjustMaterialStock(this.stockForm.materialId, { delta, remark }); this.stockOpen = false; this.$modal.msgSuccess('库存已调整'); await this.getList() }
      catch (error) { this.stockError = this.errorMessage(error) }
      finally { this.stockSaving = false }
    },
    async handleDelete(row) {
      const ids = row ? String(row.materialId) : this.ids.join(',')
      if (!ids) return
      try { await this.$modal.confirm('确认删除所选商品？已有订单仍保留交付记录。'); await delMaterial(ids); this.$modal.msgSuccess('商品已删除'); await this.getList() }
      catch (error) { if (error && error !== 'cancel' && error !== 'close') this.$modal.msgError(this.errorMessage(error)) }
    },
    handleExport() { this.download('system/material/export', { ...this.queryParams }, `material_${Date.now()}.xlsx`) }
  }
}
</script>

<style scoped>
.material-shop h3 { margin: 22px 0 18px; padding-bottom: 10px; border-bottom: 1px solid #ebeef5; font-size: 16px; }
.help { color: #909399; font-size: 12px; line-height: 1.7; }
</style>
