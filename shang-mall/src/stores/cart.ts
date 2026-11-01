import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchCart, type CartItemVO, type CartVO } from '@/api/cart'

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItemVO[]>([])
  const selectedAmount = ref(0)

  const count = computed(() =>
    items.value.reduce((sum, it) => sum + (it.available ? it.quantity : 0), 0),
  )

  async function refresh() {
    try {
      const res = await fetchCart()
      const data = res.data as CartVO
      items.value = (data.items || []).map((it) => ({
        ...it,
        cartItemId: String(it.cartItemId ?? it.id ?? ''),
        id: String(it.id ?? it.cartItemId ?? ''),
        available: it.available ?? it.valid ?? false,
      }))
      selectedAmount.value = data.selectedAmount || 0
    } catch {
      items.value = []
      selectedAmount.value = 0
    }
  }

  function reset() {
    items.value = []
    selectedAmount.value = 0
  }

  return { items, selectedAmount, count, refresh, reset }
})
