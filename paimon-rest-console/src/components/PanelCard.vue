<template>
  <section class="pc-card">
    <header v-if="title || $slots.actions" class="pc-card-head">
      <div class="pc-card-title">
        <slot name="icon" />
        <span>{{ title }}</span>
        <span v-if="hint" class="pc-card-hint">{{ hint }}</span>
      </div>
      <div v-if="$slots.actions" class="pc-row">
        <slot name="actions" />
      </div>
    </header>
    <div class="pc-card-body" :class="{ 'is-flush': flush }">
      <slot />
    </div>
  </section>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  hint: { type: String, default: '' },
  /** 表格类内容需要自己铺满卡片，去掉内边距 */
  flush: { type: Boolean, default: false },
})
</script>
