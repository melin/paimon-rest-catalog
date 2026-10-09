<template>
  <div>
    <div v-if="label || $slots.actions" class="pc-json-head">
      <span v-if="label" class="pc-dim">{{ label }}</span>
      <span class="pc-spacer" />
      <el-button v-if="text" size="small" text @click="copy">
        <el-icon><CopyDocument /></el-icon>
        <span class="pc-json-copy-text">复制</span>
      </el-button>
      <slot name="actions" />
    </div>
    <pre v-if="text" class="pc-json" :style="{ maxHeight: maxHeight }">{{ text }}</pre>
    <el-empty v-else :description="emptyText" :image-size="64" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

import { prettyJson } from '@/utils/format.js'

const props = defineProps({
  value: { type: null, default: null },
  label: { type: String, default: '' },
  emptyText: { type: String, default: '无内容' },
  maxHeight: { type: String, default: '420px' },
})

const text = computed(() => prettyJson(props.value))

async function copy() {
  try {
    await navigator.clipboard.writeText(text.value)
    ElMessage.success({ message: '已复制', duration: 1200 })
  } catch {
    ElMessage.warning({ message: '复制失败，请手动选择文本', duration: 2500 })
  }
}
</script>

<style scoped>
.pc-json-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.pc-json-copy-text {
  margin-left: 4px;
}
</style>
