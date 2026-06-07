<script setup lang="ts">
  /**
   * 修改密码页。
   *
   * 用途：
   * 1. 首次登录强制改密
   * 2. 普通用户主动改密
   *
   * 改密成功后清空 firstLogin 标记并跳回当前角色默认首页。
   */
  import { reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { authApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';

  const router = useRouter();
  const auth = useAuthStore();

  const formRef = ref<FormInstance>();
  const loading = ref(false);

  const form = reactive({
    oldPassword: '',
    newPassword: '',
    confirm: '',
  });

  const rules: FormRules = {
    oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
    newPassword: [
      { required: true, message: '请输入新密码', trigger: 'blur' },
      { min: 8, message: '至少 8 位', trigger: 'blur' },
    ],
    confirm: [
      { required: true, message: '请再次输入新密码', trigger: 'blur' },
      {
        validator: (_, value, cb) =>
          value === form.newPassword ? cb() : cb(new Error('两次输入不一致')),
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
      auth.markPasswordChanged();
      ElMessage.success('密码修改成功');
      router.replace({ name: auth.defaultRouteName });
    } catch (err) {
      ElMessage.error((err as Error).message || '修改失败');
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
      <h1 class="change__title">修改密码</h1>
      <p v-if="auth.requirePasswordChange" class="change__hint text-secondary">
        首次登录请先设置新密码，再继续使用控制台
      </p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password @keyup.enter="submit" />
        </el-form-item>

        <div class="change__actions">
          <el-button @click="handleCancel">取消</el-button>
          <el-button type="primary" :loading="loading" @click="submit">提交</el-button>
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

    &__title {
      margin: 0 0 6px;
      font-size: 22px;
    }

    &__hint {
      margin: 0 0 20px;
      font-size: 13px;
    }

    &__actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 12px;
    }
  }
</style>
