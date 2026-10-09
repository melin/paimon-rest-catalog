<template>
  <div class="pc-page">
    <el-result icon="warning" title="页面不存在" :sub-title="`没有找到路径 ${$route.fullPath}`">
      <template #extra>
        <el-button type="primary" @click="$router.push({ name: 'dashboard' })">回到控制台概览</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup>
// 路由兜底页。history 模式下用户可能手改地址栏或用了过期的书签，
// 给出一个明确的落点比白屏好。
</script>
