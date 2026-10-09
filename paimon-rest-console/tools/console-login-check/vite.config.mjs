import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'

/**
 * 把 `src/` 下的真实模块（store、api）打成能在 Node 里跑的产物，供 `main.js` 使用。
 *
 * <p>为什么不复用工程根目录的 `vite.config.js`：那个配置的 `outDir` 指向服务端的
 * 静态资源目录，用它会把控制台的正式产物覆盖掉。这里只做打包，不碰任何发布产物。
 *
 * <p>`ssr: true` 表示目标是 Node——不做浏览器 polyfill、不注入 HTML。
 * 于是 `src/store/auth.js`、`src/api/client.js` 就是**线上跑的那几个文件**，
 * 别名解析也与正式构建一致（都走 `@` → `src/`）。
 */
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('../../src', import.meta.url)) },
  },
  build: {
    ssr: true,
    minify: false,
    emptyOutDir: true,
    outDir: fileURLToPath(new URL('./dist', import.meta.url)),
    lib: {
      entry: fileURLToPath(new URL('./main.js', import.meta.url)),
      formats: ['es'],
      fileName: () => 'main.js',
    },
  },
})
