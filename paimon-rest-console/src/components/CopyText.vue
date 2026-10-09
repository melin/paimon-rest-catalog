<template>
  <span class="pc-copy pc-mono pc-break">
    <span>{{ display }}</span>
    <el-tooltip v-if="text" content="复制" placement="top">
      <el-icon class="pc-copy-icon" @click="copy"><CopyDocument /></el-icon>
    </el-tooltip>
    <span v-else-if="emptyText" class="pc-dim">{{ emptyText }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  text: { type: [String, Number], default: '' },
  emptyText: { type: String, default: '' },
  /** 展示用文本，默认与复制内容相同（例如令牌只显示前几位时用得上） */
  label: { type: String, default: '' },
})

const text = computed(() => (props.text === null || props.text === undefined ? '' : String(props.text)))
const display = computed(() => props.label || text.value)

/**
 * 复制到剪贴板。`navigator.clipboard` 只在安全上下文（https 或 localhost）可用，
 * 明文 http 的部署里会直接抛错，因此留一条 `execCommand` 的退路——
 * 控制台经常跑在内网 http 上。
 */
async function copy() {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text.value)
    } else {
      const area = document.createElement('textarea')
      area.value = text.value
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    ElMessage.success({ message: '已复制', duration: 1200 })
  } catch {
    ElMessage.warning({ message: '复制失败，请手动选择文本', duration: 2500 })
  }
}
</script>

<style scoped>
.pc-copy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pc-copy-icon {
  cursor: pointer;
  color: var(--pc-text-dim);
  font-size: 13px;
}

.pc-copy-icon:hover {
  color: var(--el-color-primary);
}
</style>
