<template>
  <div class="tt-cell"
       :class="{
         'has-schedule': schedules.length > 0,
         'is-editable': editable,
         'is-conflict': isConflict,
         'has-issue': cellHasIssue
       }">
    <!-- 冲突提示：同一时间、同一地点有多门课程（右下角红色感叹号） -->
    <div v-if="isConflict" class="conflict-flag" :title="conflictTip">
      <i class="el-icon-warning"></i>
    </div>

    <!-- 已有排课卡片（一格可多条） -->
    <div v-for="sch in schedules" :key="sch.scheduleId" class="tt-card-wrap">
      <div class="tt-card" :class="['tt-card-' + subjectKey(sch.subjectName), {'has-issue': issueList(sch).length}]">
        <!-- 问题提示：信息不完整 / 格式未识别 —— 两类都标红 -->
        <div v-if="issueList(sch).length" class="issue-flag" :title="issueTip(issueList(sch))">
          <i class="el-icon-warning"></i>
        </div>

        <div class="card-grade" :class="{'text-missing': !sch.gradeName || isPlaceholder(sch.gradeName)}">
          <span v-if="!sch.gradeName || isPlaceholder(sch.gradeName)" class="miss-icon el-icon-warning"></span>{{ sch.gradeName || '年级未填' }}
        </div>

        <div class="card-subject" :class="{'text-missing': !sch.subjectName || isPlaceholder(sch.subjectName)}">
          <span v-if="!sch.subjectName || isPlaceholder(sch.subjectName)" class="miss-icon el-icon-warning"></span>{{ sch.subjectName || '科目未填' }}
          <span class="card-type-tag" v-if="sch.classType">{{ sch.classType }}</span>
        </div>

        <div class="card-teacher" :class="{'text-missing': !teacherName(sch)}">
          <span v-if="!teacherName(sch)" class="miss-icon el-icon-warning"></span>{{ teacherName(sch) || '教师未填' }}
        </div>

        <!-- 地点不明确时把原文地点显示出来，便于定位是哪个格子出的问题 -->
        <div v-if="!sch.classroomId && sch.lessonLocation" class="card-location text-missing" :title="sch.lessonLocation">
          <span class="miss-icon el-icon-warning"></span>{{ sch.lessonLocation }}
        </div>

        <div class="card-footer">
          <span class="card-enroll" v-if="sch.enrolledCount!=null">报{{ sch.enrolledCount }}</span>
          <span v-if="!sch.timeSlot || !sch.startTime || !sch.endTime" class="text-missing">
            <i class="el-icon-warning"></i> 时间待补
          </span>
          <span v-if="isPlaceholder(sch.termName) || isPlaceholder(sch.periodName)" class="text-missing">
            <i class="el-icon-warning"></i> 学期/期次未识别
          </span>
          <span class="card-status" :class="'rs-'+sch.recruitStatus">
            {{ recruitLabel(sch.recruitStatus) }}
          </span>
        </div>

        <div v-if="cardEditable(sch)" class="card-actions" @click.stop>
          <i class="el-icon-date" title="课次（按日期展开，可停课 / 恢复）" @click="$emit('occurrences', sch)"></i>
          <i class="el-icon-edit" title="编辑（可改教室/时段，保存后课程移动到新位置）" @click="$emit('edit', sch)"></i>
          <i class="el-icon-delete" title="删除" @click="$emit('remove', sch)"></i>
        </div>
      </div>
    </div>

    <!-- 空位 / 追加：hover 显示 + 号（已有课程时也能再排一条，冲突由红色感叹号提示） -->
    <div class="tt-empty-cell" :class="{'is-add-more': schedules.length > 0}">
      <i v-if="editable" class="el-icon-plus add-icon"
         :title="schedules.length ? '在此格再排一门课程' : '新增排课'"
         @click.stop="$emit('add', classroomId, { value: timeSlot })"></i>
      <span v-if="!editable && !schedules.length" class="empty-placeholder">—</span>
    </div>
  </div>
</template>

<script>
import { isPlaceholder, resolveTeacherName, scheduleIssues, issueTip } from './timetableIssue'

export default {
  name: 'TimetableCell',
  props: {
    // 同一格可能有多条排课（同时间同地点多次课程）
    schedules: { type: Array, default: () => [] },
    classroomId: { type: [Number, String], default: '' },
    timeSlot: { type: String, default: '' },
    editable: { type: Boolean, default: false },
    // 由父组件注入的单条排课权限判定（教师只能改自己的）
    canEditSchedule: { type: Function, default: null },
    teacherNameMap: { type: Object, default: () => ({}) }
  },
  computed: {
    isConflict() {
      return this.schedules.length > 1
    },
    /** 整格是否有任一「红色感叹号」问题（信息不完整 / 格式未识别）→ 用于整格红色高亮 */
    cellHasIssue() {
      return this.schedules.some(s => this.issueList(s).length > 0)
    },
    conflictTip() {
      const names = this.schedules
        .map(s => `${s.gradeName || ''}${s.subjectName || '科目未填'}`)
        .join('、')
      return `冲突：同一时间、同一地点有 ${this.schedules.length} 门课程\n${names}`
    }
  },
  methods: {
    isPlaceholder,
    issueTip,
    /** 该条排课的问题列表（含 信息不完整 / 格式未识别 两类） */
    issueList(sch) {
      return scheduleIssues(sch, this.teacherNameMap)
    },
    /** 单条卡片是否可编辑（admin 全可；teacher 仅本人） */
    cardEditable(sch) {
      if (!this.editable) return false
      if (typeof this.canEditSchedule !== 'function') return true
      return !!this.canEditSchedule(sch)
    },
    teacherName(sch) {
      return resolveTeacherName(sch, this.teacherNameMap)
    },
    recruitLabel(s) {
      if (s === '1') return '停招'
      if (s === '2') return '满班'
      return '可报'
    },
    subjectKey(name) {
      if (!name) return 'none'
      const map = {
        '语文': 'chinese', '数学': 'math', '英语': 'english',
        '物理': 'physics', '化学': 'chemistry', '生物': 'bio',
        '历史': 'history', '地理': 'geo', '政治': 'politics',
        '科学': 'science', '信息技术': 'it'
      }
      return map[name] || 'other'
    }
  }
}
</script>
