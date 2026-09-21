import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import ElementPlus from 'unplugin-element-plus/vite';
import viteCompression from 'vite-plugin-compression';

/**
 * Vite 配置。
 *
 * 通过 VITE_API_BASE 控制开发环境调用的后端地址，
 * 默认指向本地 Spring Boot 服务（http://127.0.0.1:8080）。
 * 部署到生产时建议同源部署，避免跨域。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_API_BASE || 'http://127.0.0.1:8080';

  return {
    base: './',
    plugins: [
      vue(),
      // 自动按需导入 Element Plus 样式
      ElementPlus({}),
      // Gzip 静态资源压缩
      viteCompression({
        verbose: true,
        disable: false,
        threshold: 1024,
        algorithm: 'gzip',
        ext: '.gz',
      }),
      // Brotli 静态资源压缩
      viteCompression({
        verbose: true,
        disable: false,
        threshold: 1024,
        algorithm: 'brotliCompress',
        ext: '.br',
      }),
      // Element Plus 按需自动注册组件与组合式 API
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver()],
        dts: 'auto-imports.d.ts',
      }),
      Components({
        resolvers: [ElementPlusResolver()],
        dts: 'components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        // 仅代理后端管理与代理 API 路径，避免拦截前端路由
        '^/(auth|admin|system|payment|tickets|assistant|v1|health|models|chat|responses|messages|actuator|assets/model-icons|assets/branding|plugin-assets)':
          {
            target,
            changeOrigin: true,
          },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      target: 'es2022',
      chunkSizeWarningLimit: 1024,
      rollupOptions: {
        output: {
          // 优化 Chunk 拆包策略，将第三方库与核心代码分离，提升浏览器缓存利用率
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.includes('element-plus')) {
                return 'vendor-element-plus';
              }
              if (
                id.includes('vue') ||
                id.includes('pinia') ||
                id.includes('vue-router') ||
                id.includes('axios')
              ) {
                return 'vendor-core';
              }
              return 'vendor-libs';
            }
          },
        },
      },
    },
  };
});
