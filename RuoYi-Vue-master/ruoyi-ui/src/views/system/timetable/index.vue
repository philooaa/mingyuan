<template>
  <div class="app-container timetable-page">
    <!-- 顶部筛选栏 -->
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="64px" class="tt-toolbar">
      <el-form-item label="年份">
        <el-input-number v-model="queryParams.courseYear" :min="2020" :max="2035" :step="1" controls-position="right" style="width:120px" />
      </el-form-item>
      <el-form-item label="学期">
        <el-select v-model="queryParams.termName" placeholder="请选择学期" style="width:140px" @change="handleTermChange">
          <el-option v-for="d in dims.terms" :key="d.value" :label="d.extra ? d.label + '（数据）' : d.label" :value="d.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="期次">
        <el-select v-model="queryParams.periodName" placeholder="请选择期次" style="width:110px" @change="handleQuery">
          <el-option v-for="d in availablePeriods" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="校区">
        <el-select v-model="queryParams.campusName" placeholder="全部校区" clearable style="width:120px" @change="handleQuery">
          <el-option v-for="(list, name) in dims.classrooms" :key="name" :label="name" :value="name" />
        </el-select>
      </el-form-item>
      <el-form-item label="科目">
        <el-select v-model="queryParams.subjectName" placeholder="全部科目" clearable style="width:100px" @change="handleQuery">
          <el-option v-for="d in dims.subjects" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="年级">
        <el-select v-model="queryParams.gradeName" placeholder="全部年级" clearable style="width:100px" @change="handleQuery">
          <el-option v-for="d in dims.grades" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="教师">
        <!-- 按 teacher_id 精确筛选：教师改名/重名都不影响，不要改回按姓名匹配 -->
        <el-select v-model="queryParams.teacherId" placeholder="全部教师" clearable filterable style="width:140px" @change="handleQuery">
          <el-option v-for="t in dims.teachers" :key="t.userId" :label="t.realName || t.userName || t.nickName" :value="t.userId" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">查询</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        <el-button icon="el-icon-full-screen" size="mini" @click="toggleFullScreen">全屏</el-button>
        <el-button type="primary" plain icon="el-icon-date" size="mini" v-hasRole="['admin']" @click="openSingle()">一对一管理</el-button>
        <el-button type="success" icon="el-icon-upload2" size="mini" @click="openImport" v-hasPermi="['system:courseschedule:add']">课表导入</el-button>
        <el-button type="warning" icon="el-icon-sort" size="mini" @click="openSwap" v-hasPermi="['system:courseschedule:edit']">交换教室列</el-button>
      </el-form-item>
    </el-form>

    <!-- 课表主体 -->
    <div ref="ttContainer" class="tt-container" :class="{fullscreen: isFullscreen}">
      <!-- 图例 / 问题汇总 -->
      <div class="tt-legend">
        <span class="lg-item"><i class="el-icon-warning lg-warn"></i> 红色格子 = 信息不完整（时间·学期·期次·年级·科目·教师名·地点 缺失）／格式未识别（导入时识别不到，写入「未识别xx」）／冲突（同一时间、同一地点多门课程）</span>
        <span v-if="issueStats.conflictCells" class="lg-item lg-conflict">
          <i class="el-icon-warning"></i> 冲突 {{ issueStats.conflictCells }} 格
        </span>
        <span v-if="issueStats.incomplete" class="lg-item lg-incomplete">
          <i class="el-icon-warning"></i> 信息不完整 {{ issueStats.incomplete }} 条
        </span>
        <span v-if="issueStats.unrecognized" class="lg-item lg-unrecognized">
          <i class="el-icon-warning"></i> 格式未识别 {{ issueStats.unrecognized }} 条
        </span>
        <span class="lg-item lg-dim">纵轴 = 时段，横轴 = 教室（可横向滚动）</span>
      </div>

      <div v-loading="loading" class="tt-grid-wrapper">
        <table class="tt-table" cellpadding="0" cellspacing="0">
          <thead>
            <!-- 校区表头行 -->
            <tr>
              <th class="tt-corner" rowspan="2">
                <div class="corner-line1">时段 ↓</div>
                <div class="corner-line2">教室 →</div>
              </th>
              <th v-for="g in campusSpans" :key="'cp-'+g.name" :colspan="g.count" class="tt-th-campus">
                — {{ g.name }} —<span class="campus-count">（{{ g.count }} 间）</span>
              </th>
              <th v-if="hasUnspecifiedRoom" class="tt-th-unspec" rowspan="2">未指定教室</th>
            </tr>
            <!-- 教室表头行 -->
            <tr>
              <th v-for="cls in flatRooms" :key="'rm-'+cls.classroomId" class="tt-th-room">
                <span class="room-name" :title="cls.classroomName + (cls.sheetCode ? '（课表编码 ' + cls.sheetCode + '）' : '')">{{ cls.classroomName }}</span>
                <span v-if="cls.sheetCode" class="room-code">{{ cls.sheetCode }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ts in rowSlots" :key="'ts-'+ts" class="tt-slot-row" :class="{'is-placeholder-row': isPlaceholder(ts)}">
              <td class="tt-time-cell">
                <div class="time-label" :class="{'text-missing': isPlaceholder(ts)}">{{ ts }}</div>
                <div v-if="slotHasIssue(ts)" class="time-warn"><i class="el-icon-warning"></i></div>
              </td>
              <td v-for="cls in flatRooms" :key="'c-'+ts+'-'+cls.classroomId" class="tt-grid-cell">
                <TimetableCell
                  :schedules="cellList(cls.classroomId, ts)"
                  :classroomId="cls.classroomId"
                  :timeSlot="ts"
                  :editable="canEditCell(cls.classroomId, ts)"
                  :canEditSchedule="canEditSchedule"
                  :teacherNameMap="teacherNameMap"
                  @edit="onEdit"
                  @add="onAdd"
                  @remove="onRemove"
                  @occurrences="onOccurrences"
                />
              </td>
              <td v-if="hasUnspecifiedRoom" :key="'u-'+ts" class="tt-grid-cell tt-unspec-cell">
                <TimetableCell
                  :schedules="cellList(null, ts)"
                  :classroomId="''"
                  :timeSlot="ts"
                  :editable="canEditCell(null, ts)"
                  :canEditSchedule="canEditSchedule"
                  :teacherNameMap="teacherNameMap"
                  @edit="onEdit"
                  @add="onAdd"
                  @remove="onRemove"
                  @occurrences="onOccurrences"
                />
              </td>
            </tr>

            <!-- 未指定时段（导入后时间不明确的排课） -->
            <tr v-if="hasUnspecifiedSlot" class="tt-slot-row tt-unspec-row">
              <td class="tt-time-cell">
                <div class="time-label text-missing">未指定时段</div>
              </td>
              <td v-for="cls in flatRooms" :key="'us-'+cls.classroomId" class="tt-grid-cell">
                <TimetableCell
                  :schedules="cellList(cls.classroomId, '')"
                  :classroomId="cls.classroomId"
                  :timeSlot="''"
                  :editable="canEditCell(cls.classroomId, '')"
                  :canEditSchedule="canEditSchedule"
                  :teacherNameMap="teacherNameMap"
                  @edit="onEdit"
                  @add="onAdd"
                  @remove="onRemove"
                  @occurrences="onOccurrences"
                />
              </td>
              <td v-if="hasUnspecifiedRoom" class="tt-grid-cell tt-unspec-cell">
                <TimetableCell
                  :schedules="cellList(null, '')"
                  :classroomId="''"
                  :timeSlot="''"
                  :editable="canEditCell(null, '')"
                  :canEditSchedule="canEditSchedule"
                  :teacherNameMap="teacherNameMap"
                  @edit="onEdit"
                  @add="onAdd"
                  @remove="onRemove"
                  @occurrences="onOccurrences"
                />
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!flatRooms.length" class="tt-empty">
          暂无匹配的教室数据，请先在「教室信息」页面录入，或调整筛选条件
        </div>
      </div>
    </div>

    <!-- 新增/编辑 弹窗 -->
    <OneToOneManager ref="oneToOne" :dims="dims" @changed="handleQuery" />
    <TimetableDialog
      ref="dialog"
      :dims="dims"
      :preset="dialogPreset"
      @success="handleQuery"
    />

    <!-- 交换教室列 弹窗 -->
    <el-dialog
      title="交换两个教室的全部课程"
      :visible.sync="swap.open"
      width="600px"
      top="10vh"
      append-to-body
      :close-on-click-modal="false">
      <div class="swap-scope">
        <span class="swap-scope-label">交换范围：</span>
        <el-tag size="small">{{ queryParams.termName || '—' }}</el-tag>
        <el-tag size="small" type="success">{{ queryParams.periodName || '—' }}</el-tag>
        <span class="swap-scope-tip">仅交换该学期 / 期次下的排课，其它学期期次不受影响</span>
      </div>

      <el-form size="small" label-width="80px">
        <el-form-item label="教室 A">
          <el-select v-model="swap.a" filterable clearable placeholder="请选择教室" style="width:100%">
            <el-option-group v-for="(list, campus) in (dims.classrooms || {})" :key="'sa-' + campus" :label="campus">
              <el-option v-for="c in list" :key="'sa-' + c.classroomId" :label="roomLabel(c)" :value="c.classroomId" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="教室 B">
          <el-select v-model="swap.b" filterable clearable placeholder="请选择教室" style="width:100%">
            <el-option-group v-for="(list, campus) in (dims.classrooms || {})" :key="'sb-' + campus" :label="campus">
              <el-option v-for="c in list" :key="'sb-' + c.classroomId" :label="roomLabel(c)" :value="c.classroomId" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="">
          <el-button size="mini" icon="el-icon-sort" :disabled="swap.a === null && swap.b === null" @click="swapAB">对调 A / B</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="swap.countsLoading" class="swap-preview">
        <div class="swap-row">
          <span class="swap-tag">A</span>
          <span class="swap-name">{{ swap.a === null ? '未选择' : roomLabelById(swap.a) }}</span>
          <span class="swap-num">{{ swap.a === null ? '—' : swapCountA }}</span>
          <span class="swap-unit">门课</span>
        </div>
        <div class="swap-row">
          <span class="swap-tag swap-tag-b">B</span>
          <span class="swap-name">{{ swap.b === null ? '未选择' : roomLabelById(swap.b) }}</span>
          <span class="swap-num">{{ swap.b === null ? '—' : swapCountB }}</span>
          <span class="swap-unit">门课</span>
        </div>
      </div>

      <div class="swap-warn">
        <i class="el-icon-warning"></i>
        交换后：A 教室的课整体搬到 B，B 的搬到 A（时段、教师、课程内容都不变）。该操作直接改排课数据；
        <b>导入来源位置不会跟着变</b> —— 之后若用同一份课表文件重新「覆盖更新」导入，教室会被改回去。
      </div>

      <div slot="footer" class="dialog-footer">
        <el-button size="mini" @click="swap.open = false">取 消</el-button>
        <el-button type="primary" size="mini" :loading="swap.loading" :disabled="!canSwap" @click="doSwap">确认交换</el-button>
      </div>
    </el-dialog>

    <!-- 课表导入弹窗 -->
    <el-dialog title="课表导入" :visible.sync="imp.open" width="920px" append-to-body :close-on-click-modal="false">
      <el-form size="small" label-width="90px">
        <el-form-item label="课程年份">
          <el-input-number v-model="imp.courseYear" :min="2020" :max="2035" :step="1" controls-position="right" style="width:130px" :disabled="imp.previewing || imp.rows.length>0" />
          <span style="margin-left:14px;color:#8993a4;font-size:12px">导入的排课归属到该年份，解析后可在预览中核对</span>
        </el-form-item>
        <el-form-item label="递推规则">
          <div style="color:#606266;font-size:12px;line-height:1.9">
            <div><b>寒假 / 暑假</b>：上 5 休 1 —— 从起始日期起连续上 5 天、第 6 天休息，循环递推到结课日期</div>
            <div><b>春季 / 秋季</b>：按课表里的「周六课表 / 周日课表」分类 —— 期次为周六则每周六上课、为周日则每周日上课</div>
            <div style="color:#e6a23c">上课日期按「学期 + 期次」分别设置：解析预览后，在下方为每个期次（如寒假一期/二期、春季周六/周日）单独填写起止日期；未填的期次保持「待排」</div>
          </div>
        </el-form-item>
        <el-form-item label="课表文件">
          <el-upload
            ref="impUpload"
            action="#"
            :auto-upload="false"
            :limit="1"
            accept=".xlsx,.xls"
            :file-list="imp.fileList"
            :on-change="impFileChange"
            :on-remove="impFileRemove"
            :disabled="imp.previewing">
            <el-button size="mini" icon="el-icon-document">选择文件</el-button>
            <span slot="tip" style="margin-left:12px;color:#8993a4;font-size:12px">支持：①矩阵式（行=时段、列=教室 W1/B1）；②学校原始课表（学期写在单元格里，如总表 B2「暑假」、初一年级 C3「秋季」）；③平铺表（含「排课编码/学期/期次或上课日」表头）。学期优先取 Sheet 名，其次取表内单元格/「学期」列</span>
          </el-upload>
        </el-form-item>
      </el-form>

      <div style="margin:-6px 0 10px 90px">
        <el-button type="primary" size="mini" icon="el-icon-view" :loading="imp.previewing" @click="doPreview">解析预览</el-button>
        <template v-if="imp.rows.length">
          <span style="margin-left:14px;font-size:13px">
            共 <b>{{ imp.rows.length }}</b> 条课程，
            <span :style="{color: imp.warnCount ? '#e6a23c' : '#67c23a'}">{{ imp.warnCount }} 条有告警</span>
            <template v-if="imp.datePlans.length">
              ，<span :style="{color: imp.undatedCount ? '#e6a23c' : '#67c23a'}">{{ imp.datedCount }} 条将写入日期{{ imp.undatedCount ? '、' + imp.undatedCount + ' 条待排' : '' }}</span>
            </template>
            <template v-if="imp.sheetNames.length">，Sheet：{{ imp.sheetNames.join('、') }}</template>
          </span>
        </template>
      </div>

      <!-- 按「学期 + 期次」分别设置上课起止日期 -->
      <div v-if="imp.datePlans.length" class="imp-range-block">
        <div class="imp-range-head">
          <b>按学期 / 期次设置上课日期</b>
          <span class="imp-range-sub">同一学期里各期次的上课周期不同（寒假一期 / 二期、春季周六 / 周日），请分别为每个期次填写起止日期；留空的期次导入后保持「待排」</span>
        </div>
        <el-table :data="imp.datePlans" size="mini" border class="imp-range-table">
          <el-table-column label="学期" width="76">
            <template slot-scope="s"><b>{{ s.row.termName }}</b></template>
          </el-table-column>
          <el-table-column label="期次" width="76">
            <template slot-scope="s">{{ s.row.periodName || '—' }}</template>
          </el-table-column>
          <el-table-column label="来源工作表" min-width="120" show-overflow-tooltip>
            <template slot-scope="s">{{ (s.row.sheetNames || []).join('、') || '—' }}</template>
          </el-table-column>
          <el-table-column label="课程数" width="62" align="center">
            <template slot-scope="s">{{ s.row.rowCount }}</template>
          </el-table-column>
          <el-table-column label="递推规则" width="110" show-overflow-tooltip>
            <template slot-scope="s">
              <span v-if="s.row.schedulable">{{ s.row.patternNote }}</span>
              <el-tooltip v-else :content="s.row.patternNote" placement="top">
                <span style="color:#e6a23c">无法判定 <i class="el-icon-question"></i></span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="起始日期" width="152">
            <template slot-scope="s">
              <el-date-picker
                v-if="s.row.schedulable"
                :value="rangeValue(s.row, 'startDate')"
                @input="onRangeInput(s.row, 'startDate', $event)"
                type="date"
                size="mini"
                value-format="yyyy-MM-dd"
                placeholder="起始日期"
                style="width:138px" />
              <span v-else style="color:#c0c4cc">—</span>
            </template>
          </el-table-column>
          <el-table-column label="结课日期" width="152">
            <template slot-scope="s">
              <el-date-picker
                v-if="s.row.schedulable"
                :value="rangeValue(s.row, 'endDate')"
                @input="onRangeInput(s.row, 'endDate', $event)"
                type="date"
                size="mini"
                value-format="yyyy-MM-dd"
                placeholder="结课日期"
                style="width:138px" />
              <span v-else style="color:#c0c4cc">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-table v-if="imp.rows.length" :data="imp.pageRows" size="mini" border max-height="330" v-loading="imp.confirming"
                :row-class-name="impRowClass">
        <el-table-column prop="sheetName" label="Sheet" width="90" show-overflow-tooltip />
        <el-table-column prop="termName" label="学期" width="55" />
        <el-table-column prop="periodName" label="期次" width="65" />
        <el-table-column label="日期规则" width="120">
          <template slot-scope="s">
            <span v-if="s.row.plannedPattern === 'WEEKLY'">每周{{ s.row.periodName }}</span>
            <span v-else-if="s.row.plannedPattern === 'DAILY_5_1'">上5休1</span>
            <el-tooltip v-else :content="s.row.datePlanNote || '无法套用日期规则，将保持待排'" placement="top">
              <span style="color:#e6a23c">待排 <i class="el-icon-question"></i></span>
            </el-tooltip>
            <div v-if="s.row.plannedPattern && !s.row.hasDateRange" style="color:#e6a23c;font-size:11px">未设日期</div>
          </template>
        </el-table-column>
        <el-table-column label="时段" width="95">
          <template slot-scope="s">
            <span v-if="s.row.timeSlot">{{ s.row.timeSlot }}</span>
            <span v-else style="color:#f56c6c">未识别</span>
          </template>
        </el-table-column>
        <el-table-column label="教室（课表编码）" width="145">
          <template slot-scope="s">
            <span v-if="s.row.classroomName">
              {{ s.row.campusName }}·{{ s.row.classroomName }}
              <span v-if="s.row.sheetCode" class="imp-room-code">{{ s.row.sheetCode }}</span>
            </span>
            <span v-else style="color:#f56c6c">未匹配({{ s.row.classroomCode }})</span>
          </template>
        </el-table-column>
        <el-table-column prop="courseClassName" label="课程（原文）" min-width="170" show-overflow-tooltip />
        <el-table-column label="年级/科目/班型" width="130">
          <template slot-scope="s">{{ [s.row.gradeName, s.row.subjectName, s.row.classType].filter(Boolean).join(' ') || '-' }}</template>
        </el-table-column>
        <el-table-column label="教师" width="90">
          <template slot-scope="s">
            <span v-if="s.row.teacherName">{{ s.row.teacherName }}</span>
            <span v-else style="color:#f56c6c">未识别</span>
          </template>
        </el-table-column>
        <el-table-column label="告警" min-width="120">
          <template slot-scope="s">
            <span v-if="s.row.warning" class="imp-warn-text">
              <i class="el-icon-warning"></i> {{ s.row.warning }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="imp.rows.length" style="margin-top:8px;text-align:right">
        <el-pagination small layout="prev, pager, next, sizes" :total="imp.rows.length" :page-size.sync="imp.pageSize" :current-page.sync="imp.pageNo" :page-sizes="[20,50,100,500]" />
      </div>

      <div slot="footer" style="text-align:left">
        <el-button type="warning" size="mini" icon="el-icon-magic-stick" :loading="imp.oneClicking" @click="doOneClick">一键导入</el-button>
        <el-button type="success" size="mini" :disabled="!imp.rows.length" :loading="imp.confirming" @click="doConfirm">确认导入</el-button>
        <el-button size="mini" @click="imp.open = false">取 消</el-button>
        <div v-if="!imp.rows.length" style="margin-top:6px;color:#8993a4;font-size:12px">一键导入：自动同步课表、教师（缺失自动建号，密码123456）、教室（缺失自动补建）。首次点击会先解析文件列出各学期/期次，填好各自的上课日期后再点一次即落库</div>
        <div v-else-if="imp.datePlans.some(p => p.schedulable)" style="margin-top:6px;color:#e6a23c;font-size:12px">提示：{{ imp.datePlans.filter(p => p.schedulable).length }} 个「学期 / 期次」可设上课日期，未填写的导入后保持「待排」（可稍后在课表中补填）</div>
      </div>
    </el-dialog>

    <!-- 课次（按日期展开）弹窗：停课 / 恢复 -->
    <el-dialog title="课次（按具体日期）" :visible.sync="occ.open" width="760px" top="8vh" append-to-body :close-on-click-modal="false">
      <div v-loading="occ.loading" style="min-height:140px">
        <div class="occ-head">
          <el-tag size="small">{{ occ.termName || '—' }}</el-tag>
          <el-tag size="small" type="success">{{ occ.periodName || '—' }}</el-tag>
          <span class="occ-title">{{ occ.courseClassName || '课程' }}</span>
          <span v-if="occ.startDate" class="occ-tip">
            {{ occ.startDate }} ~ {{ occ.endDate }}，
            递推规则：<b>{{ occ.classPattern === 'DAILY_5_1' ? '上5休1' : (occ.classPattern === 'WEEKLY' ? '每周' + occ.periodName : '未设置') }}</b>
          </span>
        </div>

        <el-alert v-if="occ.pending" :title="occ.message" type="warning" :closable="false" show-icon style="margin-bottom:10px" />
        <div v-else class="occ-sum">
          共 <b>{{ occ.total }}</b> 个课次，已停课
          <b :style="{color: occ.cancelledCount ? '#f56c6c' : '#67c23a'}">{{ occ.cancelledCount }}</b> 个。
          <span class="occ-sum-tip">停课 = 直接取消这一天，不顺延到后面（用于法定节假日冲课）</span>
        </div>

        <el-table v-if="!occ.pending" :data="occ.items" size="mini" border max-height="400" :row-class-name="occRowClass">
          <el-table-column prop="date" label="日期" width="115" />
          <el-table-column prop="weekday" label="星期" width="70" />
          <el-table-column label="状态" width="130">
            <template slot-scope="s">
              <el-tag v-if="s.row.status === 'CANCELLED'" size="mini" type="danger">已停课</el-tag>
              <el-tag v-else-if="s.row.status === 'MOVED'" size="mini" type="warning">已调至 {{ s.row.adjustedDate }}</el-tag>
              <el-tag v-else size="mini" type="success">正常上课</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
          <el-table-column label="操作" width="100" align="center">
            <template slot-scope="s">
              <el-button v-if="s.row.status === 'CANCELLED'" size="mini" type="text" @click="doRestoreOccurrence(s.row)">恢复</el-button>
              <el-button v-else-if="s.row.status === 'NORMAL'" size="mini" type="text" style="color:#f56c6c" @click="doCancelOccurrence(s.row)">停课</el-button>
              <span v-else style="color:#c0c4cc">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div slot="footer" class="dialog-footer">
        <el-button size="mini" @click="occ.open = false">关 闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import TimetableCell from './TimetableCell.vue'
import TimetableDialog from './TimetableDialog.vue'
import OneToOneManager from '../schedule/OneToOneManager.vue'
import { isPlaceholder, scheduleIssues, issueTip } from './timetableIssue'
import {
  getTimetableDimensions,
  getTimetableGrid,
  delTimetableSchedule,
  importTimetablePreview,
  importTimetableConfirm,
  importTimetableOneClick,
  getTimetableOccurrences,
  cancelTimetableOccurrence,
  restoreTimetableOccurrence,
  swapTimetableClassrooms
} from '@/api/system/timetable'

export default {
  name: 'CourseTimetable',
  components: { TimetableCell, TimetableDialog, OneToOneManager },
  data() {
    return {
      loading: false,
      isFullscreen: false,
      dims: {
        terms: [], periods: [], timeSlots: [], classTypes: [],
        grades: [], subjects: [], teachers: [],
        classrooms: {}, classroomList: [], periodMap: {},
        currentYear: new Date().getFullYear(),
        isAdmin: false, isTeacher: false,
        currentUserNick: '', currentUserId: null
      },
      queryParams: {
        courseYear: new Date().getFullYear(),
        termName: '',
        periodName: '',
        campusName: '',
        subjectName: '',
        gradeName: '',
        teacherId: null,
        teacherName: ''
      },
      // key: classroomId@timeSlot（缺失部分用 "-" 占位）-> 排课数组（同格可多条）
      cellMap: {},
      dialogPreset: null,
      // 交换教室列：a/b 为教室主键，counts 为该学期期次下每个教室的课程数（key 为字符串）
      swap: {
        open: false,
        a: null,
        b: null,
        loading: false,
        countsLoading: false,
        counts: {}
      },
      imp: {
        open: false,
        file: null,
        fileList: [],
        courseYear: new Date().getFullYear(),
        // 解析出的「按学期+期次设置日期」分组（后端 datePlans）：每项 { key, termName, periodName, sheetNames, rowCount, schedulable, patternNote }
        datePlans: [],
        // 每个「学期|期次」用户填写的起止日期：{ [key]: { startDate, endDate } }
        termRanges: {},
        previewing: false,
        confirming: false,
        oneClicking: false,
        rows: [],
        sheetNames: [],
        warnCount: 0,
        datedCount: 0,
        undatedCount: 0,
        pageNo: 1,
        pageSize: 50,
        pageRows: []
      },
      occ: {
        open: false,
        loading: false,
        scheduleId: null,
        courseClassName: '',
        termName: '',
        periodName: '',
        classPattern: '',
        startDate: '',
        endDate: '',
        total: 0,
        cancelledCount: 0,
        pending: false,
        message: '',
        items: []
      }
    }
  },
  computed: {
    // 旧数据兼容映射：昵称 -> 真名。新数据 teacher_name 已直接存真名，命中不到就原样显示。
    teacherNameMap() {
      const m = {}
      ;(this.dims.teachers || []).forEach(t => {
        const real = t.realName || t.userName || t.nickName
        if (t.nickName) m[t.nickName] = real
        if (t.userName) m[t.userName] = real
      })
      return m
    },
    availablePeriods() {
      const term = this.queryParams.termName
      const fixed = (term === '春季' || term === '秋季')
        ? [
            { value: '周六', label: '周六' },
            { value: '周日', label: '周日' }
          ]
        : [
            { value: '一期', label: '一期' },
            { value: '二期', label: '二期' },
            { value: '三期', label: '三期' }
          ]
      // 数据里实际存在的期次（含导入未识别时写入的「未识别期次」）也一并列出
      const extra = (this.dims.periodMap && this.dims.periodMap[term]) || []
      const out = fixed.slice()
      extra.forEach(p => {
        if (p && !out.some(o => o.value === p)) out.push({ value: p, label: p })
      })
      return out
    },
    visibleCampusGroup() {
      const all = this.dims.classrooms || {}
      if (!this.queryParams.campusName) return all
      const out = {}
      out[this.queryParams.campusName] = all[this.queryParams.campusName] || []
      return out
    },
    // 横轴：扁平化的教室列表（按校区顺序）
    flatRooms() {
      const groups = this.visibleCampusGroup || {}
      const out = []
      Object.keys(groups).forEach(name => { (groups[name] || []).forEach(c => out.push(c)) })
      return out
    },
    // 校区表头 colspan
    campusSpans() {
      const groups = this.visibleCampusGroup || {}
      return Object.keys(groups).map(name => ({ name, count: (groups[name] || []).length }))
    },
    hasUnspecifiedRoom() {
      return Object.keys(this.cellMap).some(k => k.startsWith('-@'))
    },
    hasUnspecifiedSlot() {
      return Object.keys(this.cellMap).some(k => k.endsWith('@-'))
    },
    /**
     * 纵轴时段行 = 字典固定时段 ∪ 数据中实际出现的时段
     * 学校原始课表里存在 8:00-9:30 / 周六 09:00-11:00 等非标准时段，
     * 若只渲染字典时段，这些课程会整行消失，因此这里做并集。
     */
    rowSlots() {
      const dict = (this.dims.timeSlots || []).map(t => t.value).filter(Boolean)
      const dictSet = new Set(dict)
      const extra = new Set()
      Object.keys(this.cellMap).forEach(k => {
        const idx = k.indexOf('@')
        const slot = idx >= 0 ? k.slice(idx + 1) : ''
        if (slot && slot !== '-' && !dictSet.has(slot)) extra.add(slot)
      })
      const extras = Array.from(extra).sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))
      return dict.concat(extras)
    },
    // 冲突格数 / 信息不完整条数 / 格式未识别条数（用于图例汇总）
    issueStats() {
      let conflictCells = 0
      let incomplete = 0
      let unrecognized = 0
      Object.keys(this.cellMap).forEach(k => {
        const list = this.cellMap[k] || []
        if (list.length > 1) conflictCells++
        list.forEach(s => {
          const is = this.issueList(s)
          if (is.some(x => x.kind === 'missing')) incomplete++
          if (is.some(x => x.kind === 'unrecognized')) unrecognized++
        })
      })
      return { conflictCells, incomplete, unrecognized }
    },
    // ============ 交换教室列 ============
    swapCountA() {
      return this.swap.a === null ? 0 : (this.swap.counts[String(this.swap.a)] || 0)
    },
    swapCountB() {
      return this.swap.b === null ? 0 : (this.swap.counts[String(this.swap.b)] || 0)
    },
    canSwap() {
      return this.swap.a !== null && this.swap.b !== null
        && String(this.swap.a) !== String(this.swap.b)
        && !!this.queryParams.termName && !!this.queryParams.periodName
    }
  },
  created() {
    this.init()
  },
  watch: {
    'imp.pageNo'() { this.impSlice() },
    'imp.pageSize'() { this.impSlice() },
    // 解析结果一变就重建日期槽位；顺带丢弃结构过期的旧结果（缺 key，多见于前端热更新保留了上一版 state），
    // 避免渲染出一张「只有表头、没有数据行」的空表 —— 让用户重新点「解析预览」即可。
    'imp.datePlans'(v) {
      if ((v || []).some(p => !p || !p.key)) { this.imp.datePlans = []; return }
      this.syncTermRanges(v)
    }
  },
  methods: {
    // ============ 单元格取值 ============
    cellKey(classroomId, timeSlot) {
      const c = (classroomId === null || classroomId === undefined || classroomId === '') ? '-' : String(classroomId)
      const s = timeSlot ? String(timeSlot) : '-'
      return c + '@' + s
    },
    cellList(classroomId, timeSlot) {
      return this.cellMap[this.cellKey(classroomId, timeSlot)] || []
    },
    /** 导入时识别不到写入的占位值（未识别学期/期次/时段/年级/科目/课程） */
    isPlaceholder,
    issueTip,
    /** 单条排课的问题列表（信息不完整 + 格式未识别），与单元格组件同一套规则 */
    issueList(sch) {
      return scheduleIssues(sch, this.teacherNameMap)
    },
    /** 该时段行是否存在「时间缺失 / 时段未识别」的排课（用于行首红色感叹号） */
    slotHasIssue(ts) {
      const suffix = '@' + ts
      return Object.keys(this.cellMap).some(k => {
        if (!k.endsWith(suffix)) return false
        return (this.cellMap[k] || []).some(s => this.issueList(s).some(x => x.field === 'time'))
      })
    },
    // ============ 课表导入 ============
    openImport() {
      this.imp.open = true
      this.imp.file = null
      this.imp.fileList = []
      this.imp.rows = []
      this.imp.sheetNames = []
      this.imp.warnCount = 0
      this.imp.datedCount = 0
      this.imp.undatedCount = 0
      this.imp.datePlans = []
      this.imp.termRanges = {}
      this.imp.pageNo = 1
      this.imp.pageRows = []
      this.imp.courseYear = this.queryParams.courseYear || new Date().getFullYear()
    },
    impFileChange(file, fileList) {
      this.imp.file = file.raw
      this.imp.fileList = fileList.slice(-1)
      this.resetImportPreview()
    },
    impFileRemove() {
      this.imp.file = null
      this.imp.fileList = []
      this.resetImportPreview()
    },
    /** 换文件后旧解析结果全部作废（含已填的学期日期） */
    resetImportPreview() {
      this.imp.rows = []
      this.imp.pageRows = []
      this.imp.sheetNames = []
      this.imp.datePlans = []
      this.imp.termRanges = {}
      this.imp.datedCount = 0
      this.imp.undatedCount = 0
      this.imp.warnCount = 0
    },
    impSlice() {
      const size = this.imp.pageSize || 50
      const start = (this.imp.pageNo - 1) * size
      this.imp.pageRows = this.imp.rows.slice(start, start + size)
    },
    /** 预览表：有告警的行整行标红（格式不对的格子） */
    /** 预览行标红：有告警、或关键字段缺失/未识别 —— 只要有问题就整行标红 */
    impRowClass({ row }) {
      if (!row) return ''
      const bad = !!row.warning
        || !row.timeSlot || isPlaceholder(row.timeSlot)
        || !row.termName || isPlaceholder(row.termName)
        || !row.periodName || isPlaceholder(row.periodName)
        || !row.gradeName || isPlaceholder(row.gradeName)
        || !row.subjectName || isPlaceholder(row.subjectName)
        || !row.teacherName || isPlaceholder(row.teacherName)
        || !row.classroomName
        // 套不上递推规则（学期/期次无法判定）→ 该行会保持「待排」，标红提醒
        || !row.plannedPattern
      return bad ? 'imp-row-warn' : ''
    },
    /**
     * 日期分组（学期|期次）的唯一键。旧版解析结果没有 key 字段，回退用学期名，保证不因数据形状变化而报错。
     */
    planKey(row) {
      return (row && (row.key || row.termName)) || ''
    },
    /** 读取某「学期|期次」已填的日期；键不存在时返回空串（不抛错，避免整块数据行渲染失败） */
    rangeValue(row, field) {
      const r = (this.imp.termRanges || {})[this.planKey(row)]
      return (r && r[field]) || ''
    },
    /** 写入某「学期|期次」的日期；键不存在时先补建，避免直接写 undefined 报错 */
    onRangeInput(row, field, value) {
      const key = this.planKey(row)
      if (!key) return
      let r = this.imp.termRanges[key]
      if (!r) { r = { startDate: '', endDate: '' }; this.$set(this.imp.termRanges, key, r) }
      this.$set(r, field, value || '')
    },
    /**
     * 汇总「按学期 + 期次分别设置」的起止日期。
     * 只提交填写完整的期次（起止都填）；留空的期次不提交，其排课落库后保持「待排」。
     * 某一项只填了一半或结课早于开课 → 直接拦下，避免落库出半截日期。
     */
    collectDateRanges() {
      const out = []
      const plans = this.imp.datePlans || []
      for (const p of plans) {
        if (!p.schedulable) continue
        const r = this.imp.termRanges[this.planKey(p)] || {}
        const s = r.startDate || ''
        const e = r.endDate || ''
        if (!s && !e) continue
        const label = p.periodName ? `「${p.termName} / ${p.periodName}」` : `「${p.termName}」`
        if (!s || !e) {
          this.$message.warning(`${label}的起始日期与结课日期需同时填写，或同时留空`)
          return null
        }
        if (s > e) {
          this.$message.warning(`${label}的结课日期不能早于起始日期`)
          return null
        }
        out.push({ termName: p.termName, periodName: p.periodName || '', startDate: s, endDate: e })
      }
      return out
    },
    /** 解析结果里每个「学期+期次」补一个日期槽位；已填过的不覆盖（重新预览时保留用户输入） */
    syncTermRanges(datePlans) {
      const next = {}
      ;(datePlans || []).forEach(p => {
        const key = this.planKey(p)
        if (!key) return
        const old = this.imp.termRanges[key] || {}
        next[key] = { startDate: old.startDate || p.startDate || '', endDate: old.endDate || p.endDate || '' }
      })
      this.imp.termRanges = next
    },
    async doPreview() {
      if (!this.imp.file) { this.$message.warning('请先选择课表文件'); return false }
      const ranges = this.collectDateRanges()
      if (!ranges) return false
      this.imp.previewing = true
      try {
        const fd = new FormData()
        fd.append('file', this.imp.file)
        fd.append('courseYear', this.imp.courseYear)
        if (ranges.length) fd.append('dateRanges', JSON.stringify(ranges))
        const res = await importTimetablePreview(fd)
        const d = res.data || {}
        this.imp.rows = d.rows || []
        this.imp.sheetNames = d.sheets || []
        this.imp.datePlans = d.datePlans || []
        this.imp.warnCount = d.warnCount || 0
        this.imp.datedCount = d.datedCount || 0
        this.imp.undatedCount = d.undatedCount || 0
        this.syncTermRanges(this.imp.datePlans)
        this.imp.pageNo = 1
        this.impSlice()
        if (!this.imp.rows.length) this.$message.warning('未解析到课程数据')
        return true
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
        return false
      } finally {
        this.imp.previewing = false
      }
    },
    async doConfirm() {
      if (!this.imp.rows.length) return
      const ranges = this.collectDateRanges()
      if (!ranges) return
      this.imp.confirming = true
      try {
        const res = await importTimetableConfirm({
          rows: this.imp.rows,
          // 导入一律「以新课表为准」：同位置已有课程覆盖更新，新课表里已无课的格子清空。
          // 原先的「已存在同时段排课时覆盖更新」勾选框已去掉，这里固定传 true。
          updateSupport: true,
          dateRanges: ranges
        })
        const switched = await this.applyImportScope(res.data)
        this.imp.open = false
        this.showImportResult(res.data, switched)
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
      } finally {
        this.imp.confirming = false
      }
    },
    async doOneClick() {
      if (!this.imp.file) { this.$message.warning('请先选择课表文件'); return }
      // 一键导入也要按学期设日期，而学期要解析后才知道 → 未解析时先自动解析一次，让用户填完再点
      if (!this.imp.datePlans.length) {
        const ok = await this.doPreview()
        if (ok && this.imp.datePlans.length) {
          this.$message.info(`已解析出 ${this.imp.datePlans.length} 个「学期 / 期次」（${this.imp.datePlans.map(p => p.periodName ? p.termName + '/' + p.periodName : p.termName).join('、')}）。请在「按学期 / 期次设置上课日期」中填写起止日期后再次点击「一键导入」；留空的将保持「待排」`)
        }
        return
      }
      const ranges = this.collectDateRanges()
      if (!ranges) return
      this.imp.oneClicking = true
      try {
        const fd = new FormData()
        fd.append('file', this.imp.file)
        fd.append('courseYear', this.imp.courseYear)
        fd.append('updateSupport', 'true')
        if (ranges.length) fd.append('dateRanges', JSON.stringify(ranges))
        const res = await importTimetableOneClick(fd)
        const switched = await this.applyImportScope(res.data)
        this.imp.open = false
        this.showImportResult(res.data, switched)
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
      } finally {
        this.imp.oneClicking = false
      }
    },
    async applyImportScope(d) {
      // 先刷新维度：导入可能新增「未识别学期 / 未识别期次」等占位值，必须让下拉能选到
      await this.loadDims().catch(() => {})
      const scope = (d && d.scope) || []
      if (!scope.length) { this.handleQuery(); return '' }
      const terms = Array.from(new Set(scope.map(x => x.termName).filter(Boolean)))
      const periods = Array.from(new Set(scope.map(x => x.periodName).filter(Boolean)))
      if (terms.length !== 1) {
        this.handleQuery()
        return `多个学期（${terms.join('、')}），请手动切换查看`
      }
      this.queryParams.termName = terms[0]
      const allowed = this.availablePeriods.map(p => p.value)
      const pick = periods.find(p => allowed.includes(p))
      this.queryParams.periodName = pick || allowed[0] || ''
      this.handleQuery()
      return this.queryParams.periodName ? `${terms[0]} / ${this.queryParams.periodName}` : terms[0]
    },
    showImportResult(d, switched) {
      d = d || {}
      const s = d.sync || {}
      const syncParts = []
      if (s.newTeachers) syncParts.push(`新建教师 ${s.newTeachers} 人`)
      if (s.newRooms) syncParts.push(`新建教室 ${s.newRooms} 间`)
      if (s.newDicts) syncParts.push(`新增字典项 ${s.newDicts} 个`)
      let msg = `新增 ${d.inserted || 0} 条，覆盖更新 ${d.updated || 0} 条，跳过 ${d.skipped || 0} 条。`
      if (d.incomplete) msg += `\n其中 ${d.incomplete} 条为格式未识别：已按原文保留（学期/期次/时段/年级/科目用「未识别xx」占位），课表上用红色感叹号标出，请核对后修正。`
      if (d.dated) msg += `\n已为 ${d.dated} 条排课写入上课日期与递推模式（寒假/暑假=上5休1，春季/秋季=每周周六或周日）。`
      if (d.undated) {
        const terms = (d.undatedTerms || []).filter(Boolean)
        msg += `\n${d.undated} 条未写入日期，仍保持「待排」${terms.length ? '（涉及：' + terms.join('、') + '）' : ''}：请确认已在导入时按「学期 / 期次」填写起止日期；若学期/期次写法无法判定上课模式，可在课表中编辑该排课补充起止日期。`
      }
      if (d.cleared) msg += `\n已按新课表清空 ${d.cleared} 个格子：这些位置库里原有课程、而本次导入的文件里是空的，已按「以新课表为准」删除。`
      if (d.keptStale) msg += `\n另有 ${d.keptStale} 条历史遗留行因已有报名/考勤/调课等关联数据，未自动清除；请先处理报名，再到课表上手动删除。`
      if (d.staleMsgs && d.staleMsgs.length) msg += '\n已清空示例：\n' + d.staleMsgs.join('\n')
      if (d.keptMsgs && d.keptMsgs.length) msg += '\n未清除示例：\n' + d.keptMsgs.join('\n')
      if (switched) msg += `\n已切换到：${switched}`
      if (syncParts.length) msg += '\n主数据同步：' + syncParts.join('，')
      if (d.skipMsgs && d.skipMsgs.length) msg += '\n跳过示例：\n' + d.skipMsgs.join('\n')
      msg += '\n\n课表中红色感叹号格子表示：冲突（同一时间、同一地点多门课）、时间／科目／教师名／地点不明确，或导入时格式未识别。'
      this.$alert(msg, '导入完成', { type: 'success' })
    },

    // ============ 课次（按日期展开）· 停课 / 恢复 ============
    /** 点击卡片上的日历图标：展开该排课的全部具体上课日 */
    async onOccurrences(sch) {
      if (!sch || !sch.scheduleId) return
      this.occ.open = true
      this.occ.loading = true
      this.occ.scheduleId = sch.scheduleId
      this.occ.courseClassName = sch.courseClassName || ''
      this.occ.termName = sch.termName || ''
      this.occ.periodName = sch.periodName || ''
      this.occ.items = []
      try {
        await this.loadOccurrences(sch.scheduleId)
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
      } finally {
        this.occ.loading = false
      }
    },
    async loadOccurrences(scheduleId) {
      const res = await getTimetableOccurrences(scheduleId)
      const d = res.data || {}
      this.occ.classPattern = d.classPattern || ''
      this.occ.startDate = d.startDate || ''
      this.occ.endDate = d.endDate || ''
      this.occ.total = d.total || 0
      this.occ.cancelledCount = d.cancelledCount || 0
      this.occ.pending = !!d.pending
      this.occ.message = d.message || ''
      this.occ.items = d.items || []
    },
    /** 已停课的行整体标灰，和正常课次区分开 */
    occRowClass({ row }) {
      return row && row.status === 'CANCELLED' ? 'occ-row-cancelled' : ''
    },
    /** 停课：取消某一天的课次，不顺延（法定节假日冲课） */
    async doCancelOccurrence(row) {
      let reason = ''
      try {
        const r = await this.$prompt(
          `停课 ${row.date}（${row.weekday}）\n\n这一天的课直接取消，不会顺延到后面的日期。\n请填写停课原因（可留空）。`,
          '停课确认',
          {
            type: 'warning',
            inputPlaceholder: '如：国庆节、元旦',
            confirmButtonText: '确认停课',
            cancelButtonText: '取消'
          }
        )
        reason = (r && r.value) || ''
      } catch (e) {
        return
      }
      const loading = this.$loading({ lock: true, text: '正在停课…' })
      try {
        await cancelTimetableOccurrence(this.occ.scheduleId, { date: row.date, reason })
        this.$message.success(`${row.date} 已停课（不顺延）`)
        await this.loadOccurrences(this.occ.scheduleId)
        this.handleQuery()
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
      } finally {
        loading.close()
      }
    },
    /** 恢复：删除该日期的停课/调课记录 */
    async doRestoreOccurrence(row) {
      try {
        await this.$confirm(
          `恢复 ${row.date}（${row.weekday}）的这节课？\n\n恢复后该日期重新正常上课。`,
          '恢复确认',
          { type: 'info', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
        )
      } catch (e) {
        return
      }
      const loading = this.$loading({ lock: true, text: '正在恢复…' })
      try {
        await restoreTimetableOccurrence(this.occ.scheduleId, { date: row.date })
        this.$message.success(`${row.date} 已恢复上课`)
        await this.loadOccurrences(this.occ.scheduleId)
        this.handleQuery()
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
      } finally {
        loading.close()
      }
    },

    async loadDims() {
      const res = await getTimetableDimensions()
      this.dims = { ...this.dims, ...(res.data || {}) }
      return this.dims
    },
    async init() {
      this.loading = true
      try {
        await this.loadDims()
        this.queryParams.courseYear = this.dims.currentYear || this.queryParams.courseYear
        if (!this.queryParams.termName && this.dims.terms.length) {
          this.queryParams.termName = this.dims.terms[0].value
        }
        if (!this.queryParams.periodName && this.availablePeriods.length) {
          this.queryParams.periodName = this.availablePeriods[0].value
        }
        await this.loadGrid()
      } catch (e) {
        // 错误由 request 拦截器统一提示
      } finally {
        this.loading = false
      }
    },
    async loadGrid() {
      if (!this.queryParams.termName || !this.queryParams.periodName) {
        this.cellMap = {}
        return
      }
      const res = await getTimetableGrid(this.queryParams)
      this.cellMap = (res.data && res.data.cellMap) || {}
    },
    handleQuery() {
      this.loading = true
      this.loadGrid().finally(() => { this.loading = false })
    },
    handleTermChange() {
      const periods = this.availablePeriods
      this.queryParams.periodName = periods.length ? periods[0].value : ''
      this.handleQuery()
    },
    resetQuery() {
      this.$refs.queryForm.resetFields()
      this.queryParams.courseYear = this.dims.currentYear
      this.queryParams.termName = this.dims.terms[0] && this.dims.terms[0].value
      this.queryParams.periodName = this.availablePeriods[0] && this.availablePeriods[0].value
      this.handleQuery()
    },
    toggleFullScreen() {
      this.isFullscreen = !this.isFullscreen
      if (this.isFullscreen) {
        const el = this.$refs.ttContainer
        if (el && el.requestFullscreen) el.requestFullscreen().catch(() => {})
      } else if (document.fullscreenElement) {
        document.exitFullscreen().catch(() => {})
      }
    },
    // ============ 权限 ============
    canEditCell(classroomId, timeSlot) {
      if (this.dims.isAdmin) return true
      if (!this.dims.isTeacher) return false
      // 教师：空格可新增；已有课时只要其中有一条是自己的即可继续操作
      const list = this.cellList(classroomId, timeSlot)
      if (!list.length) return true
      return list.some(s => this.canEditSchedule(s))
    },
    canEditSchedule(sch) {
      if (!sch) return false
      if (this.dims.isAdmin) return true
      return this.dims.isTeacher && String(sch.teacherId) === String(this.dims.currentUserId)
    },
    // ============ 交互 ============
    openSingle(row = {}, tab = 'slots') {
      if (!this.dims.isAdmin) { this.$message.warning('仅管理员可在网页统一管理一对一'); return }
      return this.$refs.oneToOne.open(row, tab)
    },
    onEdit(sch) {
      if (this.dims.isAdmin && sch.classMode === '2') { this.openSingle(sch, 'courses'); return }
      if (!this.canEditSchedule(sch)) {
        this.$message.info('仅管理员或负责教师可编辑此排课')
        return
      }
      this.dialogPreset = { mode: 'edit', defaults: { ...sch } }
      this.$nextTick(() => {
        this.$refs.dialog && this.$refs.dialog.open()
      })
    },
    onAdd(classroomId, ts) {
      const cls = this.dims.classroomList.find(room => String(room.classroomId) === String(classroomId)) || null
      const slot = (ts && ts.value) || ''
      if (!this.canEditCell(classroomId, slot)) {
        this.$message.warning('当前身份无权限在此格新增排课')
        return
      }
      this.dialogPreset = {
        mode: 'add',
        defaults: {
          courseYear: Number(this.queryParams.courseYear),
          termName: this.queryParams.termName,
          periodName: this.queryParams.periodName,
          classroomId: cls ? cls.classroomId : null,
          classroomName: cls ? cls.classroomName : '',
          campusName: cls ? cls.campusName : '',
          timeSlot: slot,
          teacherName: this.dims.isAdmin ? '' : (this.dims.currentUserName || this.dims.currentUserNick),
          teacherId: this.dims.isAdmin ? null : this.dims.currentUserId
        }
      }
      this.$nextTick(() => {
        this.$refs.dialog && this.$refs.dialog.open()
      })
    },
    // ============ 交换教室列 ============
    /** 教室下拉展示名：教室名（课表编码） */
    roomLabel(c) {
      if (!c) return '—'
      return c.classroomName + (c.sheetCode ? '（' + c.sheetCode + '）' : '')
    },
    roomLabelById(id) {
      const c = (this.dims.classroomList || []).find(r => String(r.classroomId) === String(id))
      return c ? this.roomLabel(c) : '—'
    },
    async openSwap() {
      if (!this.queryParams.termName || !this.queryParams.periodName) {
        this.$message.warning('请先选择学期和期次')
        return
      }
      this.swap.a = null
      this.swap.b = null
      this.swap.counts = {}
      this.swap.open = true
      await this.loadSwapCounts()
    },
    /**
     * 统计各教室课程数。
     * 只按「年份 + 学期 + 期次」取数（忽略校区/科目/年级/教师等筛选），
     * 保证弹窗里显示的门数与后端实际交换的范围一致，不会被当前筛选条件误导。
     */
    async loadSwapCounts() {
      this.swap.countsLoading = true
      try {
        const res = await getTimetableGrid({
          courseYear: this.queryParams.courseYear,
          termName: this.queryParams.termName,
          periodName: this.queryParams.periodName
        })
        const map = (res.data && res.data.cellMap) || {}
        const counts = {}
        Object.keys(map).forEach(k => {
          const cid = k.slice(0, k.indexOf('@'))
          if (!cid || cid === '-') return
          counts[cid] = (counts[cid] || 0) + ((map[k] || []).length)
        })
        this.swap.counts = counts
      } catch (e) {
        this.swap.counts = {}
      } finally {
        this.swap.countsLoading = false
      }
    },
    swapAB() {
      const t = this.swap.a
      this.swap.a = this.swap.b
      this.swap.b = t
    },
    async doSwap() {
      if (!this.canSwap || this.swap.loading) return
      const nameA = this.roomLabelById(this.swap.a)
      const nameB = this.roomLabelById(this.swap.b)
      const scope = `${this.queryParams.termName} / ${this.queryParams.periodName}`
      try {
        await this.$confirm(
          `确认在「${scope}」下交换：\n\n${nameA}（${this.swapCountA} 门课） ⇄ ${nameB}（${this.swapCountB} 门课）\n\n交换后两间教室的课程会整体对调。`,
          '交换教室列', { type: 'warning', confirmButtonText: '确认交换', cancelButtonText: '取消' }
        )
      } catch (e) {
        return // 用户取消
      }
      this.swap.loading = true
      try {
        const res = await swapTimetableClassrooms({
          courseYear: Number(this.queryParams.courseYear),
          termName: this.queryParams.termName,
          periodName: this.queryParams.periodName,
          classroomIdA: this.swap.a,
          classroomIdB: this.swap.b
        })
        const n = (res.data && res.data.swapped) || 0
        this.swap.open = false
        if (n === 0) {
          this.$message.warning('这两间教室在该学期 / 期次下都没有课程，未做任何改动')
        } else {
          this.$message.success(`交换完成，共调整 ${n} 条排课`)
        }
        this.handleQuery()
      } catch (e) {
        /* 错误由 request 拦截器统一提示 */
      } finally {
        this.swap.loading = false
      }
    },
    async onRemove(sch) {
      if (this.dims.isAdmin && sch.classMode === '2') { this.openSingle(sch, 'courses'); return }
      if (!this.canEditSchedule(sch)) {
        this.$message.info('仅管理员或负责教师可删除此排课')
        return
      }
      try {
        await this.$confirm(`确认删除「${sch.gradeName || ''}${sch.subjectName || ''} ${this.teacherNameMap[sch.teacherName] || sch.teacherName || ''}」？`, '提示', { type: 'warning' })
        await delTimetableSchedule(sch.scheduleId)
        this.$message.success('删除成功')
        this.handleQuery()
      } catch (e) { /* 取消不提示 */ }
    }
  }
}
</script>

<style lang="scss" scoped src="./timetable.scss"></style>
