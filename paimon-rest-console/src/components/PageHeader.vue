<template>
  <div class="pc-page-head">
    <div>
      <h1 class="pc-page-title">{{ title }}</h1>
      <p v-if="subtitle" class="pc-page-sub">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="pc-page-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
})
</script>
