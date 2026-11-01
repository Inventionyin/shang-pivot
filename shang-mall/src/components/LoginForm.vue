<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { showToast } from 'vant'
import {
  CUSTOMER_FROZEN_CODE,
  LOGIN_FAILED_CODE,
  sendEmailCode,
  sendSms,
  type LoginResult,
} from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

/**
 * 登录表单（PRD §8.1 增量：手机/邮箱双模式）：
 * - 手机号 11 位 / 1 开头，验证码 6 位（演示环境固定 123456）；
 * - 邮箱模式：真发验证码（随机 6 位，5 分钟有效），未注册自动注册；
 * 「获取验证码」发送后进入 60 秒倒计时不可重发；
 * 验证码错误内联红字在输入框下方，弹层不关闭。
 * 登录页与登录层共用，提交按钮由各自的容器渲染。
 */
withDefaults(defineProps<{ intro?: boolean }>(), { intro: false })

const emit = defineEmits<{ success: [LoginResult] }>()

const auth = useAuthStore()

type Mode = 'phone' | 'email'
const mode = ref<Mode>('phone')

const phone = ref('')
const email = ref('')
const code = ref('')
const phoneError = ref('')
const emailError = ref('')
const codeError = ref('')
const cooldown = ref(0)
const submitting = ref(false)
let timer: number | undefined

const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

function stopTimer() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

onUnmounted(stopTimer)

function switchMode(m: Mode) {
  if (mode.value === m) return
  mode.value = m
  phoneError.value = ''
  emailError.value = ''
  codeError.value = ''
}

interface FailPayload {
  code?: number
  msg?: string
}

async function onSend() {
  if (cooldown.value > 0) return
  if (mode.value === 'phone') {
    phoneError.value = ''
    if (!/^1\d{10}$/.test(phone.value)) {
      phoneError.value = '请输入正确的手机号'
      return
    }
    try {
      const res = await sendSms(phone.value)
      startCooldown(res.data?.cooldownSec || 60)
      showToast('验证码已发送（演示环境固定 123456）')
    } catch (err) {
      showToast((err as FailPayload)?.msg || '验证码发送失败，请稍后重试')
    }
    return
  }
  emailError.value = ''
  if (!EMAIL_RE.test(email.value.trim())) {
    emailError.value = '请输入正确的邮箱地址'
    return
  }
  try {
    const res = await sendEmailCode(email.value.trim())
    startCooldown(res.data?.cooldownSec || 60)
    showToast('验证码已发送至邮箱，请查收（5 分钟内有效）')
  } catch (err) {
    showToast((err as FailPayload)?.msg || '验证码发送失败，请稍后重试')
  }
}

function startCooldown(seconds: number) {
  cooldown.value = seconds
  stopTimer()
  timer = window.setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) stopTimer()
  }, 1000)
}

async function submit() {
  if (submitting.value) return
  phoneError.value = ''
  emailError.value = ''
  codeError.value = ''
  if (!/^\d{6}$/.test(code.value)) {
    codeError.value = '请输入 6 位验证码'
    return
  }
  submitting.value = true
  try {
    if (mode.value === 'phone') {
      if (!/^1\d{10}$/.test(phone.value)) {
        phoneError.value = '请输入正确的手机号'
        return
      }
      const res = await auth.login(phone.value, code.value)
      showToast('登录成功')
      emit('success', res)
    } else {
      if (!EMAIL_RE.test(email.value.trim())) {
        emailError.value = '请输入正确的邮箱地址'
        return
      }
      const res = await auth.loginByEmail(email.value.trim(), code.value)
      showToast('登录成功')
      emit('success', res)
    }
  } catch (err) {
    const payload = err as FailPayload
    if (payload?.code === LOGIN_FAILED_CODE) {
      codeError.value = '验证码错误，请重新获取'
    } else if (payload?.code === CUSTOMER_FROZEN_CODE) {
      showToast('该账号不可用，请联系客服')
    } else {
      showToast(payload?.msg || '登录失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}

defineExpose({ submit, submitting })
</script>

<template>
  <div class="login-form">
    <p v-if="intro" class="muted intro">
      登录后才能加入购物车、下单与查看个人中心，登录成功后会回到刚才的页面。
    </p>

    <div class="mode-tabs" role="tablist">
      <button
        type="button"
        class="mode-tab"
        :class="{ active: mode === 'phone' }"
        role="tab"
        :aria-selected="mode === 'phone'"
        @click="switchMode('phone')"
      >
        手机登录
      </button>
      <button
        type="button"
        class="mode-tab"
        :class="{ active: mode === 'email' }"
        role="tab"
        :aria-selected="mode === 'email'"
        @click="switchMode('email')"
      >
        邮箱登录
      </button>
    </div>

    <div v-if="mode === 'phone'" class="field">
      <span class="field-label">手机号<span class="req">*</span></span>
      <input
        v-model="phone"
        class="input"
        :class="{ 'is-err': !!phoneError }"
        type="tel"
        inputmode="numeric"
        maxlength="11"
        placeholder="请输入 11 位手机号"
        @input="phoneError = ''"
      />
      <span v-if="phoneError" class="error-text">{{ phoneError }}</span>
      <span v-else class="hint">11 位数字，1 开头</span>
    </div>

    <div v-else class="field">
      <span class="field-label">邮箱<span class="req">*</span></span>
      <input
        v-model="email"
        class="input"
        :class="{ 'is-err': !!emailError }"
        type="email"
        inputmode="email"
        maxlength="128"
        placeholder="请输入邮箱地址"
        @input="emailError = ''"
      />
      <span v-if="emailError" class="error-text">{{ emailError }}</span>
      <span v-else class="hint">未注册的邮箱将在验证通过后自动注册</span>
    </div>

    <div class="field">
      <span class="field-label">验证码<span class="req">*</span></span>
      <div class="code-row">
        <input
          v-model="code"
          class="input"
          :class="{ 'is-err': !!codeError }"
          type="tel"
          inputmode="numeric"
          maxlength="6"
          placeholder="请输入 6 位验证码"
          @input="codeError = ''"
        />
        <button
          type="button"
          class="btn btn-secondary code-btn"
          :disabled="cooldown > 0"
          @click="onSend"
        >
          {{ cooldown > 0 ? `${cooldown}s 后重发` : '获取验证码' }}
        </button>
      </div>
      <span v-if="codeError" class="error-text">{{ codeError }}</span>
      <span v-if="mode === 'phone'" class="hint">演示环境验证码固定为 123456；发送后 60 秒内不可重发</span>
      <span v-else class="hint">验证码发送至邮箱，5 分钟内有效；发送后 60 秒内不可重发</span>
    </div>
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 16px;
  line-height: 1.6;
}

.mode-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.mode-tab {
  flex: 1;
  padding: 8px 0;
  font-size: 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--sand, #f4eee0);
  color: var(--muted, #6f6354);
  cursor: pointer;
  transition: all 0.15s ease;
}

.mode-tab.active {
  background: linear-gradient(135deg, #ff8a5b, #c8453c);
  border-color: transparent;
  color: #fff;
  font-weight: 600;
}

.code-row {
  display: flex;
  gap: 8px;
}

.code-row .input {
  flex: 1;
  min-width: 0;
}

.code-btn {
  white-space: nowrap;
  flex-shrink: 0;
}
</style>
