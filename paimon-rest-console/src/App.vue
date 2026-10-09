<template>
  <ConsoleLayout v-if="!isPublic">
    <RouterView />
  </ConsoleLayout>
  <RouterView v-else />
</template>

<script setup>
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import ConsoleLayout from '@/layouts/ConsoleLayout.vue'

/**
 * 应用外壳：决定当前页面套不套管理布局。
 *
 * <p>判断依据是路由的 `meta.public`——登录页不套侧边栏与顶栏，
 * 因为那两处的控件都建立在「已经知道自己是谁、在看哪个目录」之上。
 * 这条规则放在这里而不是登录页自己 `position: fixed` 盖住布局：
 * 覆盖是视觉上的，DOM 还在，会被读到、被 Tab 到。
 */
const route = useRoute()

const isPublic = computed(() => Boolean(route.meta?.public))
</script>
