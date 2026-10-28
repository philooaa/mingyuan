<template>
  <div class="app-container edu-workspace">
    <div class="edu-page-heading"><div><h2>教室管理</h2><p>按校区查找教室</p></div><div class="edu-result-count">共 <strong>{{ total }}</strong> 间教室</div></div>

    <!-- 校区分类：全部 / 万达校区 / 百家湖校区（间数不受分页影响） -->
    <div class="campus-classify">
      <span class="cc-label">校区分类</span>
      <el-radio-group v-model="queryParams.campusName" size="small" @change="handleCampusChange">
        <el-radio-button label="">
          全部<span class="cc-count">{{ campusTotal }}</span>
        </el-radio-button>
        <el-radio-button v-for="c in campusStats" :key="c.campusName" :label="c.campusName">
          {{ campusLabel(c.campusName) }}<span class="cc-count">{{ c.roomCount }}</span>
        </el-radio-button>
      </el-radio-group>
    </div>

    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="100px">
      <el-form-item label="教室编码" prop="classroomCode">
        <el-input
          v-model="queryParams.classroomCode"
          placeholder="请输入教室编码"
          clearable
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="教室名称" prop="classroomName">
        <el-input
          v-model="queryParams.classroomName"
          placeholder="请输入教室名称"
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
          v-hasPermi="['system:classroom:add']"
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
          v-hasPermi="['system:classroom:edit']"
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
          v-hasPermi="['system:classroom:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-download"
          size="mini"
          @click="handleExport"
          v-hasPermi="['system:classroom:export']"
        >导出</el-button>
      </el-col>
      <el-checkbox v-model="showDetailedColumns" class="edu-column-toggle">详细字段</el-checkbox>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="classroomList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column v-if="showDetailedColumns" key="detail-classroomId" label="教室ID" align="center" prop="classroomId" />
      <el-table-column v-if="showDetailedColumns" key="detail-classroomCode" label="教室编码" align="center" prop="classroomCode" />
      <el-table-column v-if="showDetailedColumns" key="detail-campusName" label="校区名称" align="center" prop="campusName" />
      <el-table-column label="教室" prop="classroomName" min-width="180" fixed="left"><template slot-scope="scope"><div class="edu-table-name">{{ scope.row.classroomName }}</div><div class="edu-table-secondary">{{ scope.row.campusName || '校区待配置' }}</div></template></el-table-column>
      <el-table-column label="课表编码" align="center" prop="sheetCode" width="100"><template slot-scope="scope"><el-tag v-if="scope.row.sheetCode" size="mini" type="primary">{{ scope.row.sheetCode }}</el-tag><span v-else style="color:#c0c4cc">未配置</span></template></el-table-column>
      <el-table-column label="状态" align="center" prop="status"><template slot-scope="scope"><el-tag size="mini" :type="scope.row.status === '0' ? 'success' : 'info'">{{ { '0': '正常', '1': '停用' }[scope.row.status] || scope.row.status || '未知' }}</el-tag></template></el-table-column>
      <el-table-column v-if="showDetailedColumns" key="detail-remark" label="备注" align="center" prop="remark" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" fixed="right" width="150">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:classroom:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:classroom:remove']"
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

    <!-- 添加或修改教室信息对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-row>
          <el-col :span="24">
            <el-form-item label="教室编码" prop="classroomCode">
              <el-input v-model="form.classroomCode" placeholder="请输入教室编码" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="校区名称" prop="campusName">
              <el-input v-model="form.campusName" placeholder="请输入校区名称" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="教室名称" prop="classroomName">
              <el-input v-model="form.classroomName" placeholder="请输入教室名称" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="课表编码" prop="sheetCode">
              <el-input v-model="form.sheetCode" placeholder="学校课表里该教室的编号，如 W6 / B1" />
              <div style="color:#909399;font-size:12px;line-height:1.5;margin-top:2px">
                导入课表时按这个编号匹配教室。留空则该教室不参与编码匹配（退化为按校区内顺序猜测）。
              </div>
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
  </div>
</template>

<script>
import { listClassroom, getClassroom, delClassroom, addClassroom, updateClassroom, getClassroomCampusStats } from "@/api/system/classroom"

export default {
  name: "Classroom",
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      showDetailedColumns: false,
      // 总条数
      total: 0,
      // 教室信息表格数据
      classroomList: [],
      // 校区分类（全部 / 万达校区 / 百家湖校区…），含每校区间数
      campusStats: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        classroomCode: null,
        // 空字符串 = 「全部」分类（对应分类栏第一个按钮）
        campusName: '',
        classroomName: null,
        status: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        classroomCode: [
          { required: true, message: "教室编码不能为空", trigger: "blur" }
        ],
        campusName: [
          { required: true, message: "校区名称不能为空", trigger: "blur" }
        ],
        classroomName: [
          { required: true, message: "教室名称不能为空", trigger: "blur" }
        ],
      }
    }
  },
  computed: {
    /** 「全部」分类的间数 = 各校区间数之和 */
    campusTotal() {
      return (this.campusStats || []).reduce((sum, c) => sum + Number(c.roomCount || 0), 0)
    }
  },
  created() {
    this.getCampusStats()
    this.getList()
  },
  methods: {
    /** 校区分类统计（不受分页影响，保证标签上的间数准确） */
    getCampusStats() {
      getClassroomCampusStats().then(res => {
        this.campusStats = res.data || []
      }).catch(() => {})
    },
    /** 校区显示名：万达 → 万达校区 */
    campusLabel(name) {
      if (!name) return ''
      return name.indexOf('校区') >= 0 ? name : name + '校区'
    },
    /** 切换校区分类 */
    handleCampusChange() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    /** 查询教室信息列表 */
    getList() {
      this.loading = true
      listClassroom(this.queryParams).then(response => {
        this.classroomList = response.rows
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
      this.form = {
        classroomId: null,
        classroomCode: null,
        campusName: null,
        classroomName: null,
        sheetCode: null,
        status: null,
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
      // 校区分类不在搜索表单里，resetForm 管不到，需手动清空
      this.queryParams.campusName = ''
      this.handleQuery()
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.classroomId)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加教室信息"
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const classroomId = row.classroomId || this.ids
      getClassroom(classroomId).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改教室信息"
      })
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.classroomId != null) {
            updateClassroom(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getCampusStats()
              this.getList()
            })
          } else {
            addClassroom(this.form).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getCampusStats()
              this.getList()
            })
          }
        }
      })
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const classroomIds = row.classroomId || this.ids
      this.$modal.confirm('是否确认删除教室信息编号为"' + classroomIds + '"的数据项？').then(function() {
        return delClassroom(classroomIds)
      }).then(() => {
        this.getCampusStats()
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('system/classroom/export', {
        ...this.queryParams
      }, `classroom_${new Date().getTime()}.xlsx`)
    }
  }
}
</script>

<style scoped src="@/assets/styles/education-workspace.css"></style>

<style scoped>
/* 校区分类条 */
.campus-classify {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 12px;
}
.campus-classify .cc-label {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}
.campus-classify .cc-count {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  font-size: 11px;
  line-height: 16px;
  color: #909399;
  background: #f4f4f5;
  border-radius: 8px;
}
.campus-classify ::v-deep .el-radio-button.is-active .cc-count {
  color: #fff;
  background: rgba(255, 255, 255, .28);
}
</style>
