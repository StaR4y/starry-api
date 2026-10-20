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
import './assets/styles/global.scss';
import './assets/styles/locale.scss';

import App from './App.vue';
import router from './router';
import { useAppearanceStore } from '@/stores/appearance';
import { useRankingStore } from '@/stores/ranking';

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
void useAppearanceStore(pinia).load();
void useRankingStore(pinia).load();
app.use(router);
app.mount('#app');
