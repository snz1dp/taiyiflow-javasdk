import { onMounted, onUnmounted } from 'vue'

/**
 * 通过事件委托监听 .mermaid 容器的点击事件，
 * 提取内部 SVG 并触发预览回调。
 */
export function useMermaidClickPreview(onPreview) {
  function handleClick(e) {
    const target = e.target
    // 向上查找 .mermaid 容器
    const mermaidContainer = target.closest('.mermaid')
    if (!mermaidContainer) return

    // 查找内部的 SVG 元素
    const svg = mermaidContainer.querySelector('svg')
    if (!svg) return

    e.preventDefault()
    e.stopPropagation()
    onPreview(svg)
  }

  onMounted(() => {
    document.addEventListener('click', handleClick)
  })

  onUnmounted(() => {
    document.removeEventListener('click', handleClick)
  })
}