import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { describeError } from '@/api/client.js'

/** 顶栏刷新按钮广播的事件名。 */
export const REFRESH_EVENT = 'console:refresh'

/**
 * 视图的加载状态封装。
 *
 * <p>用递增序号丢弃过期响应：连续切换 catalog 时，先发的请求可能后到，
 * 不做这个保护就会出现「列表和当前选中的目录对不上」这种偶发错乱，
 * 而且它只在慢网络下复现，很难查。
 */
export function useResource(loader, { immediate = true } = {}) {
  const loading = ref(false)
  const error = ref('')
  const data = ref(null)
  let sequence = 0

  async function load() {
    const current = ++sequence
    loading.value = true
    error.value = ''
    try {
      const result = await loader()
      if (current === sequence) {
        data.value = result
      }
      return result
    } catch (cause) {
      if (current === sequence) {
        error.value = describeError(cause)
      }
      return null
    } finally {
      if (current === sequence) {
        loading.value = false
      }
    }
  }

  if (immediate) {
    onMounted(load)
  }
  return { loading, error, data, load }
}

/** 订阅顶栏的刷新广播，组件卸载时自动退订。 */
export function onConsoleRefresh(handler) {
  onMounted(() => window.addEventListener(REFRESH_EVENT, handler))
  onUnmounted(() => window.removeEventListener(REFRESH_EVENT, handler))
}

/** 请求顶栏刷新当前页面。 */
export function requestRefresh() {
  window.dispatchEvent(new Event(REFRESH_EVENT))
}

export function notifyError(cause) {
  ElMessage.error({ message: describeError(cause), duration: 5000, showClose: true })
}

export function notifySuccess(message) {
  ElMessage.success(message)
}

/**
 * 破坏性操作前的二次确认。
 *
 * <p>文案里带上资源定位信息：控制台的删除按钮密集，只写「确认删除？」的弹窗
 * 在连续操作时很容易点错对象。
 */
export async function confirmDanger({ title, target, detail, confirmText = '删除' }) {
  try {
    await ElMessageBox.confirm(
      detail ? `${target}\n\n${detail}` : String(target),
      title || '确认操作',
      {
        type: 'warning',
        confirmButtonText: confirmText,
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
        customStyle: { whiteSpace: 'pre-line' },
      },
    )
    return true
  } catch {
    return false
  }
}
