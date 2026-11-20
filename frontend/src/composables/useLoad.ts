import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { requestError } from '../api/discussions'

export function useLoad<T>(request: (signal: AbortSignal) => Promise<T>, fallback: string) {
  const data = shallowRef<T>()
  const loading = ref(true)
  const error = ref('')
  let pending: AbortController | undefined

  async function load() {
    if (pending) return
    const controller = new AbortController()
    pending = controller
    loading.value = true
    error.value = ''
    try {
      const result = await request(controller.signal)
      if (!controller.signal.aborted) data.value = result
    } catch (cause) {
      if (!controller.signal.aborted) error.value = requestError(cause, fallback)
    } finally {
      if (!controller.signal.aborted) {
        loading.value = false
        pending = undefined
      }
    }
  }

  onMounted(load)
  onBeforeUnmount(() => pending?.abort())

  return { data, loading, error, load }
}
