<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 修改密码页。
   *
   * 用途：
   * 1. 首次登录强制改密
   * 2. 普通用户主动改密
   *
   * 改密成功后撤销全部会话并返回登录页。
   */
  import { computed, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { authApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import { useAppearanceStore } from '@/stores/appearance';
  import SiteBrandLogo from '@/components/SiteBrandLogo.vue';

  const router = useRouter();
  const auth = useAuthStore();
  const appearance = useAppearanceStore();

  const formRef = ref<FormInstance>();
  const loading = ref(false);
  const isApplicationRoot = computed(() => auth.user?.username?.trim().toLowerCase() === 'admin');

  const form = reactive({
    oldPassword: '',
    newPassword: '',
    confirm: '',
  });

  const rules: FormRules = {
    oldPassword: [
      {
        required: true,
        get message() {
          return t('请输入当前密码');
        },
        trigger: 'blur',
      },
    ],
    newPassword: [
      {
        required: true,
        get message() {
          return t('请输入新密码');
        },
        trigger: 'blur',
      },
      {
        min: 12,
        max: 128,
        get message() {
          return t('密码长度须为 12–128 位');
        },
        trigger: 'blur',
      },
    ],
    confirm: [
      {
        required: true,
        get message() {
          return t('请再次输入新密码');
        },
        trigger: 'blur',
      },
      {
        validator: (_, value, cb) =>
          value === form.newPassword ? cb() : cb(new Error(t('两次输入不一致'))),
        trigger: 'blur',
      },
    ],
  };

  async function submit() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    loading.value = true;
    try {
      await authApi.changePassword({
        oldPassword: form.oldPassword,
        newPassword: form.newPassword,
      });
      auth.logout();
      ElMessage.success(t('密码修改成功，所有后台会话已撤销，请重新登录'));
      router.replace({ name: 'login' });
    } catch (err) {
      ElMessage.error((err as Error).message || t('修改失败'));
    } finally {
      loading.value = false;
    }
  }

  function handleCancel() {
    if (auth.requirePasswordChange) {
      // 首次登录用户取消则退出
      auth.logout();
      router.replace({ name: 'login' });
      return;
    }
    router.back();
  }
</script>

<template>
  <div class="change">
    <div class="change__panel glass-panel glass-panel--strong">
      <SiteBrandLogo class="change__logo" />
      <h1 class="change__title">{{ appearance.siteName }}</h1>
      <p class="change__subtitle">{{ t('修改密码') }}</p>

      <el-alert
        v-if="isApplicationRoot"
        class="change__root-warning"
        :title="t('系统 Root 凭据')"
        :description="
          t(
            '默认管理员拥有全站最高权限。密码或有效会话一旦泄漏，应按整套服务已失陷处置；请使用唯一强密码并仅保存在受控密码管理器中。',
          )
        "
        type="warning"
        show-icon
        :closable="false"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item :label="t('当前密码')" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" maxlength="128" show-password />
        </el-form-item>
        <el-form-item :label="t('新密码')" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" maxlength="128" show-password />
        </el-form-item>
        <el-form-item :label="t('确认新密码')" prop="confirm">
          <el-input
            v-model="form.confirm"
            type="password"
            maxlength="128"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>

        <div class="change__actions">
          <el-button @click="handleCancel">{{ t('取消') }}</el-button>
          <el-button type="primary" :loading="loading" @click="submit">{{ t('提交') }}</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped lang="scss">
  .change {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;

    &__panel {
      width: 100%;
      max-width: 460px;
      padding: 36px 32px;
    }

    &__logo {
      width: 56px;
      height: 56px;
      display: block;
      margin: 0 auto 12px;
      object-fit: contain;
    }

    &__title {
      margin: 0 0 6px;
      font-size: 22px;
      text-align: center;
    }

    &__subtitle {
      margin: 0 0 18px;
      color: var(--kp-text-secondary);
      font-size: 13px;
      text-align: center;
    }

    &__hint {
      margin: 0 0 20px;
      font-size: 13px;
    }

    &__root-warning {
      margin: 16px 0 20px;
      align-items: flex-start;
    }

    &__actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 12px;
    }
  }
</style>
