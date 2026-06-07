import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';

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
        '^/(auth|admin|v1|health|models|chat|responses|messages|actuator)': {
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
    },
  };
});
