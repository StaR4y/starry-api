<script setup lang="ts">
  import { computed } from 'vue';
  import type { ModelCatalogDisplaySettings } from '@/types/api';

  const settings = defineModel<ModelCatalogDisplaySettings>({ required: true });

  const layoutOptions = [
    { label: '卡片', value: 'cards' },
    { label: '列表', value: 'list' },
    { label: '紧凑', value: 'compact' },
  ];
  const densityOptions = [
    { label: '舒适', value: 'comfortable' },
    { label: '紧凑', value: 'compact' },
  ];
  const groupOptions = [
    { label: '按厂商', value: 'provider' },
    { label: '平铺全部', value: 'none' },
  ];
  const columnOptions = [
    { label: '自动', value: 0 },
    { label: '2 列', value: 2 },
    { label: '3 列', value: 3 },
    { label: '4 列', value: 4 },
  ];
  const styleOptions = [
    { label: '默认', value: 'default' },
    { label: '描边', value: 'outline' },
    { label: '扁平', value: 'flat' },
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
        <h4>布局</h4>
        <el-segmented
          :model-value="settings.layout"
          :options="layoutOptions"
          block
          @change="patch({ layout: String($event) as ModelCatalogDisplaySettings['layout'] })"
        />
      </section>
      <section class="style-block">
        <h4>密度</h4>
        <el-segmented
          :model-value="settings.density"
          :options="densityOptions"
          block
          @change="patch({ density: String($event) as ModelCatalogDisplaySettings['density'] })"
        />
      </section>
      <section class="style-block">
        <h4>分组</h4>
        <el-segmented
          :model-value="settings.groupBy"
          :options="groupOptions"
          block
          @change="patch({ groupBy: String($event) as ModelCatalogDisplaySettings['groupBy'] })"
        />
      </section>
      <section v-if="showGridControls" class="style-block">
        <h4>每行列数</h4>
        <el-segmented
          :model-value="settings.columns"
          :options="columnOptions"
          block
          @change="patch({ columns: Number($event) })"
        />
      </section>
      <section v-if="showGridControls" class="style-block">
        <h4>卡片样式</h4>
        <el-segmented
          :model-value="settings.cardStyle"
          :options="styleOptions"
          block
          @change="patch({ cardStyle: String($event) as ModelCatalogDisplaySettings['cardStyle'] })"
        />
      </section>
    </div>

    <section class="style-block">
      <h4>显示内容</h4>
      <div class="toggle-list">
        <label>
          <span>模型图标</span>
          <el-switch :model-value="settings.showIcon" @change="patch({ showIcon: Boolean($event) })" />
        </label>
        <label>
          <span>模型系列</span>
          <el-switch :model-value="settings.showFamily" @change="patch({ showFamily: Boolean($event) })" />
        </label>
        <label>
          <span>上下文窗口</span>
          <el-switch :model-value="settings.showContext" @change="patch({ showContext: Boolean($event) })" />
        </label>
        <label>
          <span>内置 / 自定义</span>
          <el-switch :model-value="settings.showOrigin" @change="patch({ showOrigin: Boolean($event) })" />
        </label>
        <label>
          <span>模型描述</span>
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
