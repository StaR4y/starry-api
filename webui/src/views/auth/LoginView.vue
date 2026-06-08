<script setup lang="ts">
  /**
   * 登录页。
   *
   * 流程：
   * 1. 表单提交后调用 authApi.login，结果写入 auth store。
   * 2. 若返回 firstLogin=true，跳转 /change-password。
   * 3. 否则跳回 redirect query 或 dashboard。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { authApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';

  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();

  const formRef = ref<FormInstance>();
  const registerFormRef = ref<FormInstance>();
  const loading = ref(false);
  const registerVisible = ref(false);
  const registerLoading = ref(false);
  const verifyCodeSending = ref(false);
  const verifyCodeCountdown = ref(0);
  const registrationEnabled = ref(false);
  const emailVerifyEnabled = ref(false);
  const inviteCodeEnabled = ref(false);
  const allowedEmailSuffixes = ref<string[]>([]);

  const form = reactive({
    username: 'admin',
    password: '',
    remember: true,
  });

  const registerForm = reactive({
    username: '',
    email: '',
    displayName: '',
    password: '',
    confirm: '',
    verifyCode: '',
    inviteCode: '',
  });

  const rules: FormRules = {
    username: [{ required: true, message: '请输入用户名或邮箱', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  };

  const registerRules: FormRules = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    email: [
      { required: true, message: '请输入邮箱', trigger: 'blur' },
      { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    ],
    password: [
      { required: true, message: '请输入密码', trigger: 'blur' },
      { min: 6, message: '密码至少 6 位', trigger: 'blur' },
    ],
    confirm: [{ required: true, message: '请确认密码', trigger: 'blur' }],
  };

  const registrationHint = computed(() => {
    if (inviteCodeEnabled.value && !registrationEnabled.value) {
      return '当前仅允许凭邀请码注册';
    }
    if (allowedEmailSuffixes.value.length === 0) return '当前允许任意邮箱注册';
    return `允许邮箱后缀：${allowedEmailSuffixes.value.join('、')}`;
  });

  async function loadRegistrationSettings() {
    try {
      const settings = await authApi.registrationSettings();
      registrationEnabled.value = settings.registrationEnabled;
      emailVerifyEnabled.value = settings.emailVerifyEnabled;
      inviteCodeEnabled.value = settings.inviteCodeEnabled;
      allowedEmailSuffixes.value = settings.registrationEmailSuffixWhitelist ?? [];
    } catch {
      registrationEnabled.value = false;
      emailVerifyEnabled.value = false;
      inviteCodeEnabled.value = false;
      allowedEmailSuffixes.value = [];
    }
  }

  function openRegister() {
    registerVisible.value = true;
  }

  function tickVerifyCodeCountdown() {
    if (verifyCodeCountdown.value <= 0) return;
    window.setTimeout(() => {
      verifyCodeCountdown.value = Math.max(0, verifyCodeCountdown.value - 1);
      tickVerifyCodeCountdown();
    }, 1000);
  }

  async function sendVerifyCode() {
    if (!registerForm.email) {
      ElMessage.warning('请先填写邮箱');
      return;
    }
    verifyCodeSending.value = true;
    try {
      const result = await authApi.sendVerifyCode({ email: registerForm.email });
      verifyCodeCountdown.value = result.countdown || 60;
      tickVerifyCodeCountdown();
      ElMessage.success('验证码已发送');
    } catch (err) {
      ElMessage.error((err as Error).message || '验证码发送失败');
    } finally {
      verifyCodeSending.value = false;
    }
  }

  async function submit() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    loading.value = true;
    try {
      const result = await auth.login(form);
      ElMessage.success('登录成功');

      if (result.user.firstLogin) {
        router.replace({ name: 'change-password' });
        return;
      }

      const redirect = (route.query.redirect as string) || '/';
      router.replace(redirect);
    } catch (err) {
      // 全局拦截器已对非静默接口提示，这里登录页 silent，所以补一次
      ElMessage.error((err as Error).message || '登录失败');
    } finally {
      loading.value = false;
    }
  }

  async function submitRegister() {
    if (!registerFormRef.value) return;
    const valid = await registerFormRef.value.validate().catch(() => false);
    if (!valid) return;
    if (registerForm.password !== registerForm.confirm) {
      ElMessage.error('两次输入的密码不一致');
      return;
    }
    if (inviteCodeEnabled.value && !registrationEnabled.value && !registerForm.inviteCode) {
      ElMessage.warning('请输入邀请码');
      return;
    }

    registerLoading.value = true;
    try {
      const result = await auth.register({
        username: registerForm.username,
        email: registerForm.email,
        displayName: registerForm.displayName || undefined,
        password: registerForm.password,
        verifyCode: registerForm.verifyCode || undefined,
        inviteCode: registerForm.inviteCode || undefined,
      });
      ElMessage.success('注册成功');
      registerVisible.value = false;

      if (result.user.firstLogin) {
        router.replace({ name: 'change-password' });
        return;
      }

      const redirect = (route.query.redirect as string) || '/';
      router.replace(redirect);
    } catch (err) {
      ElMessage.error((err as Error).message || '注册失败');
    } finally {
      registerLoading.value = false;
    }
  }

  onMounted(loadRegistrationSettings);
</script>

<template>
  <div class="login">
    <div class="login__panel glass-panel glass-panel--strong">
      <div class="login__header">
        <span class="login__brand">⌁ Starry API</span>
        <h1 class="login__title">控制台登录</h1>
        <p class="login__hint text-secondary">
          首次部署后请使用 <span class="mono">admin</span> 和启动日志中的随机密码登录
        </p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名或邮箱" prop="username">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <div class="login__options">
          <el-checkbox v-model="form.remember">记住登录状态</el-checkbox>
        </div>

        <el-button
          type="primary"
          class="login__submit"
          :loading="loading"
          size="large"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>

      <div v-if="registrationEnabled || inviteCodeEnabled" class="login__register">
        <span class="text-secondary">没有账号？</span>
        <el-button link type="primary" @click="openRegister">注册</el-button>
      </div>
    </div>

    <el-dialog v-model="registerVisible" title="注册控制台账号" width="460px">
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-position="top"
        @submit.prevent="submitRegister"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="registerForm.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="registerForm.email" autocomplete="email" />
          <div class="login__register-hint">{{ registrationHint }}</div>
        </el-form-item>
        <el-form-item v-if="inviteCodeEnabled" label="邀请码">
          <el-input v-model="registerForm.inviteCode" placeholder="INV-XXXX-XXXX-XXXX" />
          <div class="login__register-hint">填写有效邀请码可直接注册。</div>
        </el-form-item>
        <el-form-item v-if="emailVerifyEnabled && !registerForm.inviteCode" label="邮箱验证码">
          <div class="login__verify-row">
            <el-input v-model="registerForm.verifyCode" maxlength="6" />
            <el-button
              :disabled="verifyCodeCountdown > 0"
              :loading="verifyCodeSending"
              @click="sendVerifyCode"
            >
              {{ verifyCodeCountdown > 0 ? `${verifyCodeCountdown}s` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="registerForm.displayName" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="registerForm.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input
            v-model="registerForm.confirm"
            type="password"
            show-password
            autocomplete="new-password"
            @keyup.enter="submitRegister"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="registerLoading" @click="submitRegister">注册</el-button>
      </template>
    </el-dialog>

    <div class="login__bg-orb login__bg-orb--a" />
    <div class="login__bg-orb login__bg-orb--b" />
  </div>
</template>

<style scoped lang="scss">
  .login {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    position: relative;
    overflow: hidden;

    &__panel {
      position: relative;
      width: 100%;
      max-width: 420px;
      padding: 40px 36px;
      z-index: 1;
    }

    &__header {
      margin-bottom: 24px;
    }

    &__brand {
      display: inline-block;
      padding: 4px 12px;
      border-radius: 999px;
      font-weight: 600;
      background: rgba(91, 141, 239, 0.12);
      color: var(--kp-primary);
      font-size: 13px;
    }

    &__title {
      margin: 14px 0 6px;
      font-size: 26px;
      letter-spacing: 0.5px;
    }

    &__hint {
      margin: 0;
      font-size: 13px;
    }

    &__options {
      margin-bottom: 18px;
      display: flex;
      align-items: center;
    }

    &__submit {
      width: 100%;
      margin-top: 6px;
    }

    &__register {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 4px;
      margin-top: 14px;
      font-size: 13px;
    }

    &__register-hint {
      margin-top: 6px;
      color: var(--kp-text-secondary);
      font-size: 12px;
      line-height: 1.5;
    }

    &__verify-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 8px;
      width: 100%;
    }

    /* 背景装饰圆，强化磨砂玻璃透光效果 */
    &__bg-orb {
      position: absolute;
      border-radius: 50%;
      filter: blur(60px);
      opacity: 0.7;
      pointer-events: none;

      &--a {
        width: 360px;
        height: 360px;
        background: radial-gradient(circle, #b9d0ff 0%, transparent 70%);
        top: 8%;
        left: 12%;
      }
      &--b {
        width: 420px;
        height: 420px;
        background: radial-gradient(circle, #e1c4ff 0%, transparent 70%);
        bottom: 6%;
        right: 8%;
      }
    }
  }
</style>
