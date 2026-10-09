import { createApp } from 'vue'

import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
// 暗色模式的 CSS 变量，靠 <html class="dark"> 生效（见 store/theme.js）
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/index.css'

import App from './App.vue'
import router from './router/index.js'

const app = createApp(App)

// 图标全量注册成全局组件：控制台里图标按名字用在多处（菜单、按钮、状态），
// 逐个 import 的收益只是少几十 KB，代价是每加一个图标都要回头改 import。
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}

// `size: 'default'` 是显式的：控制台里 160 多处 `size="small"` 表达的是
// 「这个控件在同层级里次要」，没有写 size 的才该拿默认尺寸。
// 两者各自的绝对高度由 styles/index.css 决定：输入类跟着 `--el-component-size*`，
// 按钮被 Element Plus 钉在自己的 `--el-button-size` 上，那里单独补齐。
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.use(router)

app.mount('#app')

// 首屏数据不由这里拉取：需要登录的服务端上，登录前发这些请求只会拿到两个 401。
// 加载发生在 ConsoleLayout 挂载时（即真正进入控制台之后），见那里的说明。
