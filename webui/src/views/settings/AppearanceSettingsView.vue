<script setup lang="ts">
  import { t } from '@/i18n';
  /** 站点品牌、主题色与控制台背景。 */
  import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { ArrowDown, Brush, Check, Refresh, RefreshLeft } from '@element-plus/icons-vue';
  import type { UploadRequestOptions } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import SiteBrandLogo from '@/components/SiteBrandLogo.vue';
  import { appearanceApi } from '@/api';
  import { DEFAULT_APPEARANCE, normalizeChrome, useAppearanceStore } from '@/stores/appearance';
  import type { Appearance, AppearanceChrome } from '@/types/api';
  import { prepareBrandingImage } from '@/utils/brandingImage';

  const appearance = useAppearanceStore();
  const loading = ref(false);
  const saving = ref(false);
  const resetting = ref(false);
  const uploading = ref<'logo' | 'favicon' | 'background' | null>(null);
  const detailColorsOpen = ref(false);
  let applying = false;

  const form = reactive<Appearance>({ ...DEFAULT_APPEARANCE });

  function applyForm(next: Appearance, fallbackChrome?: AppearanceChrome) {
    applying = true;
    Object.assign(form, {
      ...DEFAULT_APPEARANCE,
      ...next,
      chrome: normalizeChrome(next.chrome ?? fallbackChrome),
    });
    appearance.replace(form);
    queueMicrotask(() => {
      applying = false;
    });
  }

  async function load() {
    loading.value = true;
    try {
      applyForm(await appearanceApi.get());
    } finally {
      loading.value = false;
    }
  }

  watch(
    form,
    (value) => {
      if (applying) return;
      appearance.preview({ ...value });
    },
    { deep: true },
  );

  async function save() {
    const chrome = form.chrome;
    saving.value = true;
    try {
      applyForm(
        await appearanceApi.update({
          siteName: form.siteName.trim() || DEFAULT_APPEARANCE.siteName,
          primary: form.primary,
          primaryMid: form.primaryMid,
          accent: form.accent,
          success: form.success,
          warning: form.warning,
          danger: form.danger,
          header: form.header,
          text: form.text,
          textSecondary: form.textSecondary,
          textMuted: form.textMuted,
          sidebarOpacity: form.sidebarOpacity,
          surfaceOpacity: form.surfaceOpacity,
          blurPx: form.blurPx,
          backgroundBlurPx: form.backgroundBlurPx,
          chrome,
        }),
        chrome,
      );
      ElMessage.success(t('外观设置已保存'));
    } finally {
      saving.value = false;
    }
  }

  async function resetAll() {
    try {
      await ElMessageBox.confirm(
        t('将恢复站点名称、Logo、配色、背景和玻璃参数为出厂默认，已上传的图片会被移除。'),
        t('恢复默认主题'),
        {
          type: 'warning',
          get confirmButtonText() {
            return t('恢复默认');
          },
          get cancelButtonText() {
            return t('取消');
          },
        },
      );
    } catch {
      return;
    }
    resetting.value = true;
    try {
      applyForm(await appearanceApi.reset());
      ElMessage.success(t('已恢复为默认主题'));
    } finally {
      resetting.value = false;
    }
  }

  async function uploadAsset(
    kind: 'logo' | 'favicon' | 'background',
    options: UploadRequestOptions,
  ) {
    uploading.value = kind;
    try {
      const file = await prepareBrandingImage(options.file as File, kind);
      applyForm(await appearanceApi.upload(kind, file));
      ElMessage.success(kind === 'background' ? t('背景图已更新') : t('图片已更新'));
    } catch (error) {
      if (error instanceof Error && error.message && !('status' in error)) {
        ElMessage.error(error.message);
      }
      throw error;
    } finally {
      uploading.value = null;
    }
  }

  async function removeAsset(kind: 'logo' | 'favicon' | 'background') {
    uploading.value = kind;
    try {
      applyForm(await appearanceApi.remove(kind));
    } finally {
      uploading.value = null;
    }
  }

  onMounted(load);
  onBeforeUnmount(() => appearance.restorePreview());
</script>

<template>
  <PageSection :title="t('UI 自定义')" :subtitle="t('内容与界面')" :icon="Brush">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('刷新') }}</el-button>
      <el-button :icon="RefreshLeft" :loading="resetting" @click="resetAll">
        {{
          t('恢复默认')
        }}
      </el-button>
      <el-button type="primary" :icon="Check" :loading="saving" @click="save">
        {{
          t('保存')
        }}
      </el-button>
    </template>

    <el-skeleton v-if="loading" :rows="8" animated />
    <el-form v-else :model="form" label-position="top" class="smtp-form">
      <section class="smtp-section">
        <h3>{{ t('站点信息') }}</h3>
        <div class="smtp-grid">
          <el-form-item :label="t('站点名称')">
            <el-input
              v-model="form.siteName"
              maxlength="40"
              show-word-limit
              placeholder="Starry API"
            />
          </el-form-item>
          <el-form-item :label="t('站点 Logo')">
            <div class="asset-field">
              <SiteBrandLogo class="asset-field__thumb" />
              <el-upload
                :show-file-list="false"
                accept="image/png,image/jpeg,image/webp"
                :http-request="(options: UploadRequestOptions) => uploadAsset('logo', options)"
              >
                <el-button :loading="uploading === 'logo'">{{ t('上传') }}</el-button>
              </el-upload>
              <el-button
                v-if="form.logoUrl"
                :disabled="uploading === 'logo'"
                @click="removeAsset('logo')"
              >
                {{ t('清除') }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item :label="t('浏览器图标')">
            <div class="asset-field">
              <img
                v-if="form.faviconUrl"
                class="asset-field__thumb"
                :src="form.faviconUrl"
                alt=""
              >
              <el-upload
                :show-file-list="false"
                accept="image/png,image/jpeg,image/webp"
                :http-request="(options: UploadRequestOptions) => uploadAsset('favicon', options)"
              >
                <el-button :loading="uploading === 'favicon'">{{ t('上传') }}</el-button>
              </el-upload>
              <el-button
                v-if="form.faviconUrl"
                :disabled="uploading === 'favicon'"
                @click="removeAsset('favicon')"
              >
                {{ t('清除') }}
              </el-button>
            </div>
          </el-form-item>
        </div>
      </section>

      <section class="smtp-section">
        <h3>{{ t('界面风格') }}</h3>
        <p class="chrome-hint">{{ t('控制台列表页的页眉与卡片语言。登录页不受此项影响。') }}</p>
        <div class="chrome-picker" role="radiogroup" :aria-label="t('界面风格')">
          <button
            type="button"
            class="chrome-option"
            :class="{ 'is-active': form.chrome === 'classic' }"
            role="radio"
            :aria-checked="form.chrome === 'classic'"
            @click="form.chrome = 'classic'"
          >
            <span class="chrome-option__preview is-classic" aria-hidden="true">
              <i /><i /><i />
            </span>
            <strong>{{ t('磨砂玻璃') }}</strong>
            <span>{{ t('原来的卡片页眉、玻璃面板与指标条') }}</span>
          </button>
          <button
            type="button"
            class="chrome-option"
            :class="{ 'is-active': form.chrome === 'editorial' }"
            role="radio"
            :aria-checked="form.chrome === 'editorial'"
            @click="form.chrome = 'editorial'"
          >
            <span class="chrome-option__preview is-editorial" aria-hidden="true">
              <i /><b /><b />
            </span>
            <strong>{{ t('线框编辑') }}</strong>
            <span>{{ t('API Key 同款图标页眉、分割指标条与通透表格') }}</span>
          </button>
        </div>
      </section>

      <section class="smtp-section">
        <h3>{{ t('三色渐变') }}</h3>
        <div class="smtp-grid smtp-grid--3">
          <el-form-item :label="t('起始色')">
            <el-input v-model="form.primary" maxlength="7">
              <template #prefix>
                <el-color-picker v-model="form.primary" color-format="hex" />
              </template>
            </el-input>
          </el-form-item>
          <el-form-item :label="t('中间色')">
            <el-input v-model="form.primaryMid" maxlength="7">
              <template #prefix>
                <el-color-picker v-model="form.primaryMid" color-format="hex" />
              </template>
            </el-input>
          </el-form-item>
          <el-form-item :label="t('结束色')">
            <el-input v-model="form.accent" maxlength="7">
              <template #prefix>
                <el-color-picker v-model="form.accent" color-format="hex" />
              </template>
            </el-input>
          </el-form-item>
        </div>
        <div class="brand-gradient-preview" aria-hidden="true" />
      </section>

      <section class="smtp-section smtp-section--fold">
        <button
          type="button"
          class="section-toggle"
          :aria-expanded="detailColorsOpen"
          @click="detailColorsOpen = !detailColorsOpen"
        >
          <h3>{{ t('详细配色') }}</h3>
          <span class="section-toggle__hint">{{ detailColorsOpen ? t('收起') : t('展开') }}</span>
          <el-icon class="section-toggle__arrow" :class="{ 'is-open': detailColorsOpen }">
            <ArrowDown />
          </el-icon>
        </button>
        <el-collapse-transition>
          <div v-show="detailColorsOpen" class="smtp-grid smtp-grid--3">
            <el-form-item :label="t('成功')">
              <el-input v-model="form.success" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.success" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('警告')">
              <el-input v-model="form.warning" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.warning" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('危险')">
              <el-input v-model="form.danger" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.danger" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('顶栏')">
              <el-input v-model="form.header" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.header" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('正文')">
              <el-input v-model="form.text" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.text" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('次要文字')">
              <el-input v-model="form.textSecondary" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.textSecondary" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('弱化文字')">
              <el-input v-model="form.textMuted" maxlength="7">
                <template #prefix>
                  <el-color-picker v-model="form.textMuted" color-format="hex" />
                </template>
              </el-input>
            </el-form-item>
          </div>
        </el-collapse-transition>
      </section>

      <section class="smtp-section">
        <h3>{{ t('背景与玻璃') }}</h3>
        <div class="smtp-grid">
          <el-form-item :label="t('控制台背景图')">
            <div class="asset-field">
              <el-upload
                :show-file-list="false"
                accept="image/png,image/jpeg,image/webp"
                :http-request="
                  (options: UploadRequestOptions) => uploadAsset('background', options)
                "
              >
                <el-button :loading="uploading === 'background'">{{ t('上传') }}</el-button>
              </el-upload>
              <el-button
                v-if="form.backgroundImageUrl"
                :disabled="uploading === 'background'"
                @click="removeAsset('background')"
              >
                {{ t('清除') }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item :label="t('背景模糊 {p0}px', { p0: form.backgroundBlurPx })">
            <el-slider
              v-model="form.backgroundBlurPx"
              :min="0"
              :max="40"
              :disabled="!form.backgroundImageUrl"
            />
          </el-form-item>
          <el-form-item
            :label="t('左侧分类栏透明度 {p0}%', { p0: Math.round(form.sidebarOpacity * 100) })"
          >
            <el-slider v-model="form.sidebarOpacity" :min="0.15" :max="1" :step="0.01" />
          </el-form-item>
          <el-form-item
            :label="t('面板透明度 {p0}%', { p0: Math.round(form.surfaceOpacity * 100) })"
          >
            <el-slider v-model="form.surfaceOpacity" :min="0.2" :max="0.95" :step="0.01" />
          </el-form-item>
          <el-form-item :label="t('模糊程度 {p0}px', { p0: form.blurPx })">
            <el-slider v-model="form.blurPx" :min="4" :max="48" />
          </el-form-item>
        </div>
      </section>
    </el-form>
  </PageSection>
</template>

<style scoped lang="scss">
  .smtp-form {
    display: grid;
    gap: 22px;
    min-width: 0;
    max-width: 100%;
  }

  .chrome-hint {
    margin: -6px 0 14px;
    color: var(--kp-text-muted);
    font-size: 12px;
  }

  .chrome-picker {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }

  .chrome-option {
    display: grid;
    gap: 8px;
    padding: 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 10px;
    background: transparent;
    color: inherit;
    cursor: pointer;
    text-align: left;
    font: inherit;

    strong {
      font-size: 14px;
    }

    > span:last-child {
      color: var(--kp-text-muted);
      font-size: 12px;
      line-height: 1.5;
    }

    &.is-active {
      border-color: var(--kp-primary);
      box-shadow: inset 0 0 0 1px var(--kp-primary);
    }

    &__preview {
      display: grid;
      gap: 6px;
      height: 72px;
      padding: 10px;
      border-radius: 8px;
      background: linear-gradient(135deg, #eef3ff, #f8f0ff);

      i,
      b {
        display: block;
        border-radius: 4px;
      }

      &.is-classic {
        i {
          height: 10px;
          background: rgba(255, 255, 255, 0.78);
          box-shadow: 0 4px 10px rgba(31, 45, 80, 0.08);
        }
        i:nth-child(2) {
          height: 18px;
        }
        i:nth-child(3) {
          flex: 1;
          height: 22px;
        }
      }

      &.is-editorial {
        i {
          width: 18px;
          height: 18px;
          border: 1px solid rgba(15, 23, 42, 0.16);
        }
        b {
          height: 6px;
          background: rgba(15, 23, 42, 0.18);
        }
        b:last-child {
          width: 62%;
        }
      }
    }
  }

  @media (max-width: 720px) {
    .chrome-picker {
      grid-template-columns: 1fr;
    }
  }

  .smtp-section {
    & + & {
      padding-top: 22px;
      border-top: 1px solid var(--kp-divider);
    }

    h3 {
      margin: 0 0 14px;
      font-size: 15px;
      font-weight: 650;
      letter-spacing: 0;
    }

    &--fold h3 {
      margin: 0;
    }
  }

  .section-toggle {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    margin: 0;
    padding: 0;
    border: 0;
    background: transparent;
    color: inherit;
    cursor: pointer;
    text-align: left;

    h3 {
      flex: 0 0 auto;
    }
  }

  .section-toggle__hint {
    flex: 1;
    font-size: 12px;
    color: var(--kp-text-muted);
  }

  .section-toggle__arrow {
    flex: 0 0 auto;
    color: var(--kp-text-muted);
    transition: transform 0.2s ease;

    &.is-open {
      transform: rotate(-180deg);
    }
  }

  .smtp-section--fold .smtp-grid {
    padding-top: 14px;
  }

  .smtp-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
    min-width: 0;

    &--3 {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    :deep(.el-form-item) {
      margin-bottom: 0;
    }

    :deep(.el-slider) {
      margin: 0 10px;
    }
  }

  .brand-gradient-preview {
    height: 10px;
    margin-top: 14px;
    border-radius: 8px;
    background: var(--kp-brand-gradient);
  }

  .asset-field {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 32px;
  }

  .asset-field__thumb {
    width: 32px;
    height: 32px;
    flex: 0 0 32px;
    object-fit: contain;
    border-radius: 8px;
  }

  :deep(.el-input__prefix) {
    margin-right: 4px;
  }

  :deep(.el-color-picker) {
    display: flex;
  }

  :deep(.el-color-picker__trigger) {
    width: 22px;
    height: 22px;
    padding: 2px;
    border: 0;
  }

  @media (max-width: 720px) {
    .smtp-grid,
    .smtp-grid--3 {
      grid-template-columns: 1fr;
    }
  }
</style>
