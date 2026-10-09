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
        服务端只保存密钥摘要，关闭本窗口后无法再取回。请立即复制并妥善保存；
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
      <el-button type="primary" @click="emit('update:modelValue', false)">我已保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import CopyText from '@/components/CopyText.vue'

defineProps({
  modelValue: { type: Boolean, default: false },
  principalName: { type: String, default: '' },
  /** 服务端返回的 `credentials` 原样展示，不假设字段名（clientId / clientSecret 等） */
  credentials: { type: Object, default: () => ({}) },
})

const emit = defineEmits(['update:modelValue'])
</script>

<style scoped>
.pc-cred-alert {
  margin-bottom: 14px;
}

.pc-cred-desc :deep(.el-descriptions__label) {
  width: 116px;
}
</style>
