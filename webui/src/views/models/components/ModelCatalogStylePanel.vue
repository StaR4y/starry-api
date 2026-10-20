<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed } from 'vue';
  import type { ModelCatalogDisplaySettings } from '@/types/api';

  const settings = defineModel<ModelCatalogDisplaySettings>({ required: true });

  const layoutOptions = [
    {
      get label() {
        return t('卡片');
      },
      value: 'cards',
    },
    {
      get label() {
        return t('列表');
      },
      value: 'list',
    },
    {
      get label() {
        return t('紧凑');
      },
      value: 'compact',
    },
  ];
  const densityOptions = [
    {
      get label() {
        return t('舒适');
      },
      value: 'comfortable',
    },
    {
      get label() {
        return t('紧凑');
      },
      value: 'compact',
    },
  ];
  const groupOptions = [
    {
      get label() {
        return t('按厂商');
      },
      value: 'provider',
    },
    {
      get label() {
        return t('平铺全部');
      },
      value: 'none',
    },
  ];
  const columnOptions = [
    {
      get label() {
        return t('自动');
      },
      value: 0,
    },
    {
      get label() {
        return t('2 列');
      },
      value: 2,
    },
    {
      get label() {
        return t('3 列');
      },
      value: 3,
    },
    {
      get label() {
        return t('4 列');
      },
      value: 4,
    },
  ];
  const styleOptions = [
    {
      get label() {
        return t('默认');
      },
      value: 'default',
    },
    {
      get label() {
        return t('描边');
      },
      value: 'outline',
    },
    {
      get label() {
        return t('扁平');
      },
      value: 'flat',
    },
  ];

  const showGridControls = computed(() => settings.value.layout !== 'list');

  function patch(partial: Partial<ModelCatalogDisplaySettings>) {
    settings.value = { ...settings.value, ...partial };
  }
</script>

<template>
  <div class="style-panel">
    <div class="style-grid">
      <section class="style-block">
        <h4>{{ t('布局') }}</h4>
        <el-segmented
          :model-value="settings.layout"
          :options="layoutOptions"
          block
          @change="patch({ layout: String($event) as ModelCatalogDisplaySettings['layout'] })"
        />
      </section>
      <section class="style-block">
        <h4>{{ t('密度') }}</h4>
        <el-segmented
          :model-value="settings.density"
          :options="densityOptions"
          block
          @change="patch({ density: String($event) as ModelCatalogDisplaySettings['density'] })"
        />
      </section>
      <section class="style-block">
        <h4>{{ t('分组') }}</h4>
        <el-segmented
          :model-value="settings.groupBy"
          :options="groupOptions"
          block
          @change="patch({ groupBy: String($event) as ModelCatalogDisplaySettings['groupBy'] })"
        />
      </section>
      <section v-if="showGridControls" class="style-block">
        <h4>{{ t('每行列数') }}</h4>
        <el-segmented
          :model-value="settings.columns"
          :options="columnOptions"
          block
          @change="patch({ columns: Number($event) })"
        />
      </section>
      <section v-if="showGridControls" class="style-block">
        <h4>{{ t('卡片样式') }}</h4>
        <el-segmented
          :model-value="settings.cardStyle"
          :options="styleOptions"
          block
          @change="patch({ cardStyle: String($event) as ModelCatalogDisplaySettings['cardStyle'] })"
        />
      </section>
    </div>

    <section class="style-block">
      <h4>{{ t('显示内容') }}</h4>
      <div class="toggle-list">
        <label>
          <span>{{ t('模型图标') }}</span>
          <el-switch
            :model-value="settings.showIcon"
            @change="patch({ showIcon: Boolean($event) })"
          />
        </label>
        <label>
          <span>{{ t('模型系列') }}</span>
          <el-switch
            :model-value="settings.showFamily"
            @change="patch({ showFamily: Boolean($event) })"
          />
        </label>
        <label>
          <span>{{ t('上下文窗口') }}</span>
          <el-switch
            :model-value="settings.showContext"
            @change="patch({ showContext: Boolean($event) })"
          />
        </label>
        <label>
          <span>{{ t('内置 / 自定义') }}</span>
          <el-switch
            :model-value="settings.showOrigin"
            @change="patch({ showOrigin: Boolean($event) })"
          />
        </label>
        <label>
          <span>{{ t('模型描述') }}</span>
          <el-switch
            :model-value="settings.showDescription"
            @change="patch({ showDescription: Boolean($event) })"
          />
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
  .style-panel {
    display: grid;
    gap: 20px;
  }

  .style-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px 20px;
  }

  .style-block {
    min-width: 0;

    h4 {
      margin: 0 0 10px;
      color: var(--kp-text-secondary);
      font-size: 12px;
      font-weight: 650;
    }

    :deep(.el-segmented) {
      width: 100%;
    }

    :deep(.el-segmented__item) {
      flex: 1;
    }
  }

  .toggle-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 2px 24px;
    padding: 4px 14px;
    border: 1px solid var(--kp-divider);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.42);

    label {
      display: flex;
      align-items: center;
      justify-content: space-between;
      min-height: 42px;
      gap: 12px;
      color: var(--kp-text);
      font-size: 13px;
    }
  }

  @media (max-width: 720px) {
    .style-grid,
    .toggle-list {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
