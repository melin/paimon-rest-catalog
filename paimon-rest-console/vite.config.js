import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物**直接写进服务端的静态资源目录**，而不是落到本地的 dist/。
//
// 这样做的原因：服务端 jar 要能在没有 Node 的环境里构建出来，
// 也就是 `mvnw -o package` 与 Dockerfile 的 `mvn package` 都不该依赖 npm。
// 把产物放在 src/main/resources/static/console 下，它随 resources 一起进 jar，
// 两处构建都只用 Maven 就能拿到完整的服务端。
// 代价是构建产物要进版本库——与 static/ 下已有的两个 OpenAPI 规格同一个处理方式。
//
// emptyOutDir 必须显式打开：outDir 在工程根之外，Vite 不会默认清空，
// 而 assets/ 下的文件名带内容哈希，不清空就会在目录里越积越多。
export default defineConfig({
  plugins: [vue()],
  base: '/console/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../paimon-rest-server/src/main/resources/static/console',
    emptyOutDir: true,
    // Element Plus 整包引入后单个 chunk 会超过默认的 500 kB 告警线。
    // 这是控制台的常见体量，调高阈值免得每次构建都刷一条噪音告警。
    chunkSizeWarningLimit: 2000,
  },
  server: {
    port: 5173,
    // 开发时把两套 API 代理到本地服务端，前端代码里始终用相对路径，
    // 生产构建后同源，不需要区分环境。
    proxy: {
      '/v1': 'http://127.0.0.1:8080',
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
