/**
 * 应用入口。
 *
 * 约定：
 * - 全局样式只在此处导入一次
 * - Pinia / Router 实例在此挂载
 * - Element Plus 通过 unplugin 自动按需注册，无需在此导入组件
 */

import { createApp } from 'vue';
import { createPinia } from 'pinia';
import 'element-plus/theme-chalk/index.css';
import './assets/styles/global.scss';

import App from './App.vue';
import router from './router';

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.mount('#app');
