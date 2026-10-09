<script setup>
/**
 * 表单字段的标签，备注收进字段名后的问号图标。
 *
 * <p>字段备注如果直接铺在输入框下面（原先的 `.pc-tip`），一段说明就有三四行，
 * 表单页会被文字占满——真正要填的字段反而被挤到视野外。因此备注改成
 * 字段名后面的一个小图标，鼠标放上去才显示。
 *
 * <p>用法（放在 `el-form-item` 的 `#label` 插槽里）：
 *
 * <pre>
 * &lt;el-form-item&gt;
 *   &lt;template #label&gt;
 *     &lt;FieldLabel label="访问令牌" tip="服务端开启鉴权后才需要填" /&gt;
 *   &lt;/template&gt;
 *   &lt;el-input ... /&gt;
 * &lt;/el-form-item&gt;
 * </pre>
 *
 * <p>短备注用 `tip` 属性传纯文本；需要 `code` / `strong` / 分段的富文本
 * 用默认插槽（内容渲染在深色气泡里，`code` 的样式由全局的
 * `.pc-field-tip code` 提供，不要在这里再叠 `pc-mono`）。
 * 没有 `tip` 也没有插槽时就只渲染标签文字，问号图标不出现。
 */
defineProps({
  label: { type: String, required: true },
  /** 纯文本备注；富文本用默认插槽。 */
  tip: { type: String, default: '' },
})
</script>

<template>
  <span class="pc-field-label">
    <span>{{ label }}</span>
    <el-tooltip
      v-if="tip || $slots.default"
      placement="top"
      effect="dark"
      :show-after="100"
      :hide-after="0"
      popper-class="pc-field-tip-popper"
    >
      <template #content>
        <div class="pc-field-tip"><slot>{{ tip }}</slot></div>
      </template>
      <el-icon class="pc-field-label-icon" :size="13"><QuestionFilled /></el-icon>
    </el-tooltip>
  </span>
</template>
