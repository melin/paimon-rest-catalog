<template>
  <el-alert v-if="error" type="error" :closable="false" show-icon class="pc-state">
    <template #title>{{ error }}</template>
    <div class="pc-dim">
      请确认服务端已启动，并检查「连接设置」中的 API 地址与访问令牌，然后重新加载本页。
    </div>
  </el-alert>
  <el-empty v-else-if="!loading && empty" :description="emptyText" :image-size="70" />
  <slot v-else />
</template>

<script setup>
/**
 * 加载 / 失败 / 空三态的统一出口。
 *
 * <p>`loading` 为真时**照常渲染插槽**：表格用 `v-loading` 自己盖遮罩，
 * 若在这里换成骨架屏，会看到表格先消失再出现，页面跳一下。
 */
defineProps({
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
})
</script>

<style scoped>
.pc-state {
  margin-bottom: 12px;
}
</style>
