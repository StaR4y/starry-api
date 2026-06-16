<script setup lang="ts">
  /**
   * 登录页。
   *
   * 流程：
   * 1. 表单提交后调用 authApi.login，结果写入 auth store。
   * 2. 若返回 firstLogin=true，跳转 /change-password。
   * 3. 否则跳回 redirect query 或 dashboard。
   */
  import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
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
  const turnstileEnabled = ref(false);
  const turnstileSiteKey = ref('');
  const turnstileToken = ref('');
  const turnstileContainerRef = ref<HTMLElement | null>(null);
  const turnstileWidgetId = ref<string | null>(null);

  declare global {
    interface Window {
      turnstile?: {
        render: (container: HTMLElement, options: Record<string, unknown>) => string;
        reset: (widgetId?: string) => void;
        remove: (widgetId: string) => void;
      };
    }
  }

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
    emailVerificationToken: '',
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

  const showRegisterDetails = computed(() => {
    if (!emailVerifyEnabled.value) return true;
    return Boolean(registerForm.emailVerificationToken) || (inviteCodeEnabled.value && Boolean(registerForm.inviteCode));
  });

  async function loadRegistrationSettings() {
    try {
      const settings = await authApi.registrationSettings();
      registrationEnabled.value = settings.registrationEnabled;
      emailVerifyEnabled.value = settings.emailVerifyEnabled;
      inviteCodeEnabled.value = settings.inviteCodeEnabled;
      allowedEmailSuffixes.value = settings.registrationEmailSuffixWhitelist ?? [];
      turnstileEnabled.value = Boolean(settings.turnstileEnabled);
      turnstileSiteKey.value = settings.turnstileSiteKey ?? '';
    } catch {
      registrationEnabled.value = false;
      emailVerifyEnabled.value = false;
      inviteCodeEnabled.value = false;
      allowedEmailSuffixes.value = [];
      turnstileEnabled.value = false;
      turnstileSiteKey.value = '';
    }
  }

  function openRegister() {
    registerVisible.value = true;
    renderTurnstileWhenReady();
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
    if (turnstileEnabled.value && !turnstileToken.value) {
      ElMessage.warning('请先完成人机验证');
      return;
    }
    verifyCodeSending.value = true;
    try {
      const result = await authApi.sendVerifyCode({
        email: registerForm.email,
        turnstileToken: turnstileToken.value || undefined,
      });
      verifyCodeCountdown.value = result.countdown || 60;
      tickVerifyCodeCountdown();
      resetTurnstile();
      ElMessage.success('注册链接已发送，请查看邮箱');
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
    if (!registerForm.emailVerificationToken && turnstileEnabled.value && !turnstileToken.value) {
      ElMessage.warning('请先完成人机验证');
      return;
    }

    registerLoading.value = true;
    try {
      const result = await auth.register({
        username: registerForm.username,
        email: registerForm.email,
        displayName: registerForm.displayName || undefined,
        password: registerForm.password,
        emailVerificationToken: registerForm.emailVerificationToken || undefined,
        verifyCode: registerForm.verifyCode || undefined,
        inviteCode: registerForm.inviteCode || undefined,
        turnstileToken: registerForm.emailVerificationToken ? undefined : turnstileToken.value || undefined,
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
      resetTurnstile();
    } finally {
      registerLoading.value = false;
    }
  }

  function applyRegistrationTokenFromRoute() {
    const token = typeof route.query.registrationToken === 'string' ? route.query.registrationToken : '';
    if (!token) return;
    registerForm.emailVerificationToken = token;
    if (typeof route.query.email === 'string') {
      registerForm.email = route.query.email;
    }
    registerVisible.value = true;
  }

  function shouldRenderTurnstile() {
    return turnstileEnabled.value
      && Boolean(turnstileSiteKey.value)
      && registerVisible.value
      && !registerForm.emailVerificationToken;
  }

  async function loadTurnstileScript() {
    if (window.turnstile) return;
    await new Promise<void>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>('script[data-turnstile="true"]');
      if (existing) {
        existing.addEventListener('load', () => resolve(), { once: true });
        existing.addEventListener('error', () => reject(new Error('Cloudflare Turnstile 加载失败')), { once: true });
        return;
      }
      const script = document.createElement('script');
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      script.async = true;
      script.defer = true;
      script.dataset.turnstile = 'true';
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Cloudflare Turnstile 加载失败'));
      document.head.appendChild(script);
    });
  }

  async function renderTurnstileWhenReady() {
    if (!shouldRenderTurnstile()) return;
    await nextTick();
    if (!turnstileContainerRef.value || turnstileWidgetId.value) return;
    try {
      await loadTurnstileScript();
      if (!window.turnstile || !turnstileContainerRef.value || !shouldRenderTurnstile()) return;
      turnstileWidgetId.value = window.turnstile.render(turnstileContainerRef.value, {
        sitekey: turnstileSiteKey.value,
        callback: (token: string) => {
          turnstileToken.value = token;
        },
        'expired-callback': () => {
          turnstileToken.value = '';
        },
        'error-callback': () => {
          turnstileToken.value = '';
        },
      });
    } catch (err) {
      ElMessage.error((err as Error).message || 'Cloudflare Turnstile 加载失败');
    }
  }

  function resetTurnstile() {
    turnstileToken.value = '';
    if (window.turnstile && turnstileWidgetId.value) {
      window.turnstile.reset(turnstileWidgetId.value);
    }
  }

  function removeTurnstile() {
    turnstileToken.value = '';
    if (window.turnstile && turnstileWidgetId.value) {
      window.turnstile.remove(turnstileWidgetId.value);
    }
    turnstileWidgetId.value = null;
  }

  watch(
    () => [registerVisible.value, turnstileEnabled.value, turnstileSiteKey.value, registerForm.emailVerificationToken],
    () => {
      if (shouldRenderTurnstile()) {
        renderTurnstileWhenReady();
      } else {
        removeTurnstile();
      }
    },
  );

  onMounted(async () => {
    await loadRegistrationSettings();
    applyRegistrationTokenFromRoute();
  });

  onBeforeUnmount(removeTurnstile);
</script>

<template>
  <div class="login">
    <div class="login__panel glass-panel glass-panel--strong">
      <div class="login__header">
        <span class="login__brand">⌁ Starry API</span>
        <h1 class="login__title">控制台登录</h1>
        <p class="login__hint text-secondary">
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
        @submit.prevent="showRegisterDetails ? submitRegister : sendVerifyCode"
      >
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="registerForm.email" :disabled="Boolean(registerForm.emailVerificationToken)" autocomplete="email" />
          <div class="login__register-hint">{{ registrationHint }}</div>
        </el-form-item>
        <el-form-item v-if="inviteCodeEnabled" label="邀请码">
          <el-input v-model="registerForm.inviteCode" placeholder="INV-XXXX-XXXX-XXXX" />
          <div class="login__register-hint">填写有效邀请码可直接注册。</div>
        </el-form-item>
        <el-alert
          v-if="registerForm.emailVerificationToken"
          class="login__register-alert"
          type="success"
          :closable="false"
          show-icon
          title="邮箱链接已验证，请补全账号信息完成注册。"
        />
        <el-form-item
          v-if="turnstileEnabled && !registerForm.emailVerificationToken"
          label="人机验证"
        >
          <div ref="turnstileContainerRef" class="login__turnstile"></div>
          <div v-if="!turnstileSiteKey" class="login__register-hint">管理员尚未配置 Cloudflare Turnstile Site Key。</div>
        </el-form-item>
        <template v-if="showRegisterDetails">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="registerForm.username" autocomplete="username" />
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
        </template>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button 
          v-if="!showRegisterDetails"
          type="primary" 
          :disabled="verifyCodeCountdown > 0"
          :loading="verifyCodeSending" 
          @click="sendVerifyCode"
        >
          {{ verifyCodeCountdown > 0 ? `${verifyCodeCountdown}s` : '发送注册链接' }}
        </el-button>
        <el-button 
          v-else
          type="primary" 
          :loading="registerLoading" 
          @click="submitRegister"
        >
          注册
        </el-button>
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

    &__register-alert {
      margin-bottom: 14px;
    }

    &__verify-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 8px;
      width: 100%;
    }

    &__turnstile {
      min-height: 65px;
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
