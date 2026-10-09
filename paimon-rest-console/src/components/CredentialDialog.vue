<template>
  <el-dialog
    :model-value="modelValue"
    title="主体凭据"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
    destroy-on-close
  >
    <el-alert type="warning" :closable="false" show-icon class="pc-cred-alert">
      <template #title>密钥只在这里显示一次</template>
      <div>
        服务端只保存密钥摘要，关闭本窗口后无法再取回。请立即复制、导出或妥善保存；
        遗失了只能重置凭据。
      </div>
    </el-alert>

    <el-descriptions :column="1" border size="small" class="pc-cred-desc">
      <el-descriptions-item label="主体">
        <CopyText :text="principalName" />
      </el-descriptions-item>
      <el-descriptions-item v-for="(value, key) in credentials" :key="key" :label="String(key)">
        <CopyText :text="value" />
      </el-descriptions-item>
    </el-descriptions>

    <template #footer>
      <el-button :disabled="!canExport" @click="exportJson">
        <el-icon><Download /></el-icon>
        <span class="pc-btn-text">导出 JSON</span>
      </el-button>
      <el-button type="primary" @click="emit('update:modelValue', false)">我已保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

import CopyText from '@/components/CopyText.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  principalName: { type: String, default: '' },
  /** 服务端返回的 `credentials` 原样展示，不假设字段名（clientId / clientSecret 等） */
  credentials: { type: Object, default: () => ({}) },
})

const emit = defineEmits(['update:modelValue'])

/** 密钥只在弹窗打开期间存在于内存里，导出按钮只在这段时间可用 */
const canExport = computed(() => Boolean(props.principalName) && Object.keys(props.credentials).length > 0)

/**
 * 把主体与凭据导出为 JSON 文件。
 *
 * <p>文件内容就是 `{"principal": ..., "clientId": ..., "clientSecret": ...}`，
 * credentials 里有什么字段就带什么字段，与服务端响应保持一致，不做二次包装——
 * 这样文件既给人看，也能直接喂给需要这三个字段的脚本。
 *
 * <p>文件名不带密钥，只带主体名；主体名里出现文件系统不允许的字符时替换成下划线。
 * 下载走临时 `<a download>`，用完立刻 revoke URL，避免明文密钥常驻内存。
 */
function exportJson() {
  const payload = { principal: props.principalName, ...props.credentials }
  const name = props.principalName.replace(/[\\/:*?"<>|]/g, '_') || 'principal'
  const blob = new Blob([JSON.stringify(payload, null, 2) + '\n'], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${name}-credentials.json`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.pc-cred-alert {
  margin-bottom: 14px;
}

.pc-cred-desc :deep(.el-descriptions__label) {
  width: 116px;
}
</style>
