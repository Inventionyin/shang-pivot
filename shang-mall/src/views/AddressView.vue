<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { showToast } from 'vant'
import {
  createAddress,
  fetchAddresses,
  removeAddress,
  setDefaultAddress,
  type AddressVO,
} from '@/api/customer'

const list = ref<AddressVO[]>([])
const showForm = ref(false)
const form = reactive({
  name: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: true,
})

async function load() {
  try {
    const res = await fetchAddresses()
    list.value = (res.data || []).map((a) => ({
      ...a,
      id: String(a.id),
    }))
  } catch {
    list.value = []
  }
}

onMounted(load)

async function onCreate() {
  if (!form.name || !form.phone || !form.province || !form.city || !form.district || !form.detail) {
    showToast('请完善地址信息')
    return
  }
  await createAddress({ ...form })
  showForm.value = false
  showToast('已添加')
  await load()
}

async function onDefault(id: string) {
  await setDefaultAddress(id)
  await load()
}

async function onRemove(id: string) {
  await removeAddress(id)
  await load()
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1 class="page-title">收货地址</h1>
      <van-button size="small" type="primary" @click="showForm = true">新增</van-button>
    </div>

    <div v-if="!list.length" class="empty-hint">
      <strong>暂无地址</strong>
      下单前请先添加收货地址
    </div>

    <div v-for="a in list" :key="a.id" class="addr">
      <div class="title">{{ a.name }} {{ a.phone }}</div>
      <div class="detail">{{ a.province }}{{ a.city }}{{ a.district }}{{ a.detail }}</div>
      <div class="ops">
        <van-tag v-if="a.isDefault" type="primary">默认</van-tag>
        <button v-else type="button" class="link" @click="onDefault(a.id)">设为默认</button>
        <button type="button" class="link danger" @click="onRemove(a.id)">删除</button>
      </div>
    </div>

    <van-popup v-model:show="showForm" position="bottom" round :style="{ padding: '16px' }">
      <van-field v-model="form.name" label="收货人" />
      <van-field v-model="form.phone" label="手机号" />
      <van-field v-model="form.province" label="省" />
      <van-field v-model="form.city" label="市" />
      <van-field v-model="form.district" label="区" />
      <van-field v-model="form.detail" label="详细地址" />
      <van-button type="primary" block style="margin-top: 12px" @click="onCreate">保存</van-button>
    </van-popup>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.addr {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 14px;
  margin-bottom: 10px;
}

.title {
  font-weight: 600;
  margin-bottom: 6px;
}

.detail {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 8px;
}

.ops {
  display: flex;
  gap: 12px;
  align-items: center;
}

.link {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  padding: 0;
}

.link.danger {
  color: var(--brick);
}
</style>
