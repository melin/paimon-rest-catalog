import { reactive, watchEffect } from 'vue'

/**
 * 明暗主题。Element Plus 的暗色模式靠 `<html class="dark">` 激活，
 * 这里只负责维护这个类名与用户选择。
 */

const THEME_KEY = 'paimon.console.theme'

function initial() {
  const stored = localStorage.getItem(THEME_KEY)
  if (stored === 'light' || stored === 'dark') {
    return stored
  }
  // 没选过就跟系统走
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const state = reactive({ mode: initial() })

watchEffect(() => {
  document.documentElement.classList.toggle('dark', state.mode === 'dark')
  // 让浏览器把表单控件、滚动条也切到对应色系
  document.documentElement.style.colorScheme = state.mode
})

export const theme = {
  state,
  toggle() {
    state.mode = state.mode === 'dark' ? 'light' : 'dark'
    localStorage.setItem(THEME_KEY, state.mode)
  },
  set(mode) {
    state.mode = mode === 'dark' ? 'dark' : 'light'
    localStorage.setItem(THEME_KEY, state.mode)
  },
}
