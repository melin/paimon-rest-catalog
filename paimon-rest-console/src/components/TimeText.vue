<template>
  <el-tooltip :content="raw" placement="top" :disabled="!millis">
    <span class="pc-nowrap">{{ text }}</span>
  </el-tooltip>
</template>

<script setup>
import { computed } from 'vue'

import { formatTime, relativeTime } from '@/utils/format.js'

const props = defineProps({
  millis: { type: [Number, String], default: null },
  /** `absolute` 显示完整时间，`relative` 显示「3 分钟前」 */
  mode: { type: String, default: 'absolute' },
})

const text = computed(() => (props.mode === 'relative' ? relativeTime(props.millis) : formatTime(props.millis)))
const raw = computed(() => (props.millis ? `${formatTime(props.millis)}（${props.millis}）` : ''))
</script>
