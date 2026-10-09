<script setup>
/**
 * 图标动作按钮：列表操作列里「只显示图标、悬停出文字」的按钮。
 *
 * <p>全站操作列统一走这一个组件，而不是各自写 el-tooltip 包 el-button：
 * 悬停提示的位置与延迟、按钮尺寸、aria-label 才能保持一致；每加一个动作
 * 也只需要挑一个图标名，不必重复那一圈模板。
 *
 * <p>图标按名字解析：main.js 已把 @element-plus/icons-vue 全量注册成全局组件，
 * 因此这里传字符串（如 "Delete"）经 <component :is> 解析即可，
 * 使用方不需要自己 import 图标。
 *
 * <p>文字仍然只存在于 tooltip 里，这是刻意的取舍：操作列按钮多且动作含义
 * 靠图标+悬停可辨，全部展开成文字会让每一行高出一截；但 aria-label 保留了
 * 文字语义，读屏与键盘导航不受影响。
 */
defineProps({
  /** 图标组件名，需是 @element-plus/icons-vue 里导出的名字（如 "Delete"） */
  icon: { type: String, required: true },
  /** 悬停提示，同时作为按钮的 aria-label */
  label: { type: String, required: true },
  /** 语义色：'' 默认、primary、danger……与 el-button 的 type 同义 */
  type: { type: String, default: '' },
})

defineEmits(['click'])
</script>

<template>
  <el-tooltip :content="label" placement="top" :show-after="300">
    <el-button
      class="pc-icon-action"
      size="small"
      text
      :type="type || undefined"
      :aria-label="label"
      @click="$emit('click')"
    >
      <el-icon><component :is="icon" /></el-icon>
    </el-button>
  </el-tooltip>
</template>
