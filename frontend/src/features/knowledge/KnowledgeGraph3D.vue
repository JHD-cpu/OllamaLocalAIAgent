<script setup>
// Three.js 三维知识点树：文档为父节点，合并后的知识点为子节点；点击弹出详情。
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'

const props = defineProps({
  /** [{ id, label, children: knowledgePoints[] }] 子节点已按知识点合并分片 */
  tree: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 独立窗口已有标题时可隐藏组件内标题栏 */
  showHead: { type: Boolean, default: true }
})

const emit = defineEmits(['select-chunk'])

const host = ref(null)
const hint = ref('拖拽旋转 · 滚轮缩放 · 点击节点查看')

let renderer
let scene
let camera
let controls
let raycaster
let pointer
let frameId = 0
let rootGroup
let nodeMeshes = []
let resizeObserver
let disposed = false

onMounted(async () => {
  await nextTick()
  initScene()
  rebuild()
  animate()
  window.addEventListener('resize', onResize)
  if (host.value) {
    resizeObserver = new ResizeObserver(() => onResize())
    resizeObserver.observe(host.value)
  }
})

onUnmounted(() => {
  disposed = true
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
  cancelAnimationFrame(frameId)
  disposeScene()
})

watch(
  () => props.tree,
  () => rebuild(),
  { deep: true }
)

function initScene() {
  const el = host.value
  if (!el) {
    return
  }
  const width = el.clientWidth || 640
  const height = el.clientHeight || 360

  scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(0x060a12, 0.018)

  camera = new THREE.PerspectiveCamera(48, width / height, 0.1, 200)
  camera.position.set(0, 8, 22)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setSize(width, height)
  renderer.setClearColor(0x000000, 0)
  el.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.06
  controls.minDistance = 6
  controls.maxDistance = 60
  controls.target.set(0, 2, 0)

  const ambient = new THREE.AmbientLight(0x8ec8ff, 0.55)
  const key = new THREE.DirectionalLight(0x2fd6c9, 1.1)
  key.position.set(8, 16, 10)
  const fill = new THREE.DirectionalLight(0x4f8cff, 0.45)
  fill.position.set(-10, 6, -8)
  scene.add(ambient, key, fill)

  const grid = new THREE.GridHelper(40, 20, 0x1a3a44, 0x102030)
  grid.position.y = -0.02
  scene.add(grid)

  raycaster = new THREE.Raycaster()
  pointer = new THREE.Vector2()
  renderer.domElement.addEventListener('pointerdown', onPointerDown)
}

function disposeScene() {
  if (renderer?.domElement) {
    renderer.domElement.removeEventListener('pointerdown', onPointerDown)
  }
  if (rootGroup) {
    scene?.remove(rootGroup)
    disposeObject(rootGroup)
    rootGroup = null
  }
  nodeMeshes = []
  controls?.dispose()
  renderer?.dispose()
  if (renderer?.domElement?.parentNode) {
    renderer.domElement.parentNode.removeChild(renderer.domElement)
  }
  renderer = null
  scene = null
  camera = null
  controls = null
}

function disposeObject(obj) {
  obj.traverse((child) => {
    if (child.geometry) {
      child.geometry.dispose()
    }
    if (child.material) {
      if (Array.isArray(child.material)) {
        child.material.forEach((m) => m.dispose())
      }
      else {
        child.material.dispose()
      }
    }
  })
}

function rebuild() {
  if (!scene) {
    return
  }
  if (rootGroup) {
    scene.remove(rootGroup)
    disposeObject(rootGroup)
  }
  rootGroup = new THREE.Group()
  nodeMeshes = []

  const docs = Array.isArray(props.tree) ? props.tree : []
  const layout = layoutTree(docs)

  const linkMat = new THREE.LineBasicMaterial({
    color: 0x2fd6c9,
    transparent: true,
    opacity: 0.35
  })

  for (const link of layout.links) {
    const geo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(link.a.x, link.a.y, link.a.z),
      new THREE.Vector3(link.b.x, link.b.y, link.b.z)
    ])
    rootGroup.add(new THREE.Line(geo, linkMat))
  }

  for (const node of layout.nodes) {
    const mesh = createNodeMesh(node)
    mesh.position.set(node.x, node.y, node.z)
    rootGroup.add(mesh)
    nodeMeshes.push(mesh)

    const label = createSpriteLabel(node.label, node.kind === 'doc' ? '#2fd6c9' : '#d7e6f5')
    label.position.set(node.x, node.y + (node.kind === 'root' ? 1.1 : 0.75), node.z)
    rootGroup.add(label)
  }

  scene.add(rootGroup)
  hint.value = docs.length
    ? '拖拽旋转 · 滚轮缩放 · 点击青色文档或知识点球体'
    : '上传并处理完成后，这里会生成三维知识点树'
}

function layoutTree(docs) {
  const nodes = []
  const links = []
  const root = { id: 'root', kind: 'root', label: '知识库', x: 0, y: 6.5, z: 0 }
  nodes.push(root)

  const n = Math.max(docs.length, 1)
  const radius = Math.max(5, n * 2.2)
  docs.forEach((doc, i) => {
    const angle = (i / n) * Math.PI * 2 - Math.PI / 2
    const docNode = {
      id: doc.id,
      kind: 'doc',
      label: shorten(doc.label, 14),
      payload: doc,
      x: Math.cos(angle) * radius,
      y: 3.2,
      z: Math.sin(angle) * radius
    }
    nodes.push(docNode)
    links.push({ a: root, b: docNode })

    const children = Array.isArray(doc.children) ? doc.children : []
    const m = Math.max(children.length, 1)
    const childRadius = Math.max(2.2, Math.min(5.5, m * 0.55))
    children.forEach((point, j) => {
      const ca = angle + ((j - (m - 1) / 2) * 0.28)
      const pointNode = {
        id: point.id,
        kind: 'point',
        label: shorten(point.label, 12),
        payload: point,
        weight: Math.max(1, point.chunkCount || 1),
        x: docNode.x + Math.cos(ca) * childRadius,
        y: 0.4 + (j % 3) * 0.15,
        z: docNode.z + Math.sin(ca) * childRadius
      }
      nodes.push(pointNode)
      links.push({ a: docNode, b: pointNode })
    })
  })
  return { nodes, links }
}

function createNodeMesh(node) {
  let radius = 0.28
  let color = 0x4f8cff
  if (node.kind === 'root') {
    radius = 0.55
    color = 0x2fd6c9
  }
  else if (node.kind === 'doc') {
    radius = 0.38
    color = 0x1aa8a0
  }
  else if (node.kind === 'point') {
    radius = 0.26 + Math.min(0.22, (node.weight || 1) * 0.04)
    color = 0x4f8cff
  }
  const geo = new THREE.SphereGeometry(radius, 24, 16)
  const mat = new THREE.MeshStandardMaterial({
    color,
    emissive: color,
    emissiveIntensity: 0.22,
    metalness: 0.25,
    roughness: 0.35
  })
  const mesh = new THREE.Mesh(geo, mat)
  mesh.userData = { kind: node.kind, payload: node.payload, id: node.id }
  return mesh
}

function createSpriteLabel(text, color) {
  const canvas = document.createElement('canvas')
  canvas.width = 256
  canvas.height = 64
  const ctx = canvas.getContext('2d')
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = 'rgba(6, 10, 18, 0.55)'
  ctx.fillRect(8, 14, 240, 36)
  ctx.font = '600 22px Sora, Segoe UI, sans-serif'
  ctx.fillStyle = color
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, 128, 32)
  const texture = new THREE.CanvasTexture(canvas)
  texture.needsUpdate = true
  const mat = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false })
  const sprite = new THREE.Sprite(mat)
  sprite.scale.set(3.2, 0.8, 1)
  return sprite
}

function shorten(text, max) {
  const t = String(text || '').trim()
  if (t.length <= max) {
    return t || '节点'
  }
  return `${t.slice(0, max)}…`
}

function onPointerDown(event) {
  if (!renderer || !camera || !host.value) {
    return
  }
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hits = raycaster.intersectObjects(nodeMeshes, false)
  if (!hits.length) {
    return
  }
  const data = hits[0].object.userData
  if ((data.kind === 'point' || data.kind === 'chunk') && data.payload) {
    emit('select-chunk', data.payload)
  }
  else if (data.kind === 'doc' && data.payload?.children?.length) {
    const points = data.payload.children
    const names = points.map((p) => p.label).filter(Boolean).slice(0, 12)
    const more = points.length > names.length ? `\n…共 ${points.length} 个知识点` : ''
    emit('select-chunk', {
      id: data.payload.id,
      label: data.payload.label,
      text: `文档「${data.payload.label}」共 ${points.length} 个知识点（分片已按知识点合并）。\n\n${names.map((n) => `· ${n}`).join('\n')}${more}`,
      charCount: 0,
      index: -1,
      documentId: data.payload.id,
      filename: data.payload.label,
      isDocHint: true
    })
  }
}

function onResize() {
  if (!host.value || !renderer || !camera) {
    return
  }
  const width = host.value.clientWidth || 640
  const height = host.value.clientHeight || 360
  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height)
}

function animate() {
  if (disposed) {
    return
  }
  frameId = requestAnimationFrame(animate)
  if (rootGroup) {
    rootGroup.rotation.y += 0.0015
  }
  controls?.update()
  if (renderer && scene && camera) {
    renderer.render(scene, camera)
  }
}
</script>

<template>
  <div class="knowledge-3d">
    <div v-if="showHead" class="knowledge-3d-head">
      <h3>三维知识点树</h3>
      <span class="field-hint">{{ hint }}</span>
    </div>
    <div v-else class="knowledge-3d-head knowledge-3d-head-slim">
      <span class="field-hint">{{ hint }}</span>
    </div>
    <div ref="host" v-loading="loading" class="knowledge-3d-canvas" />
  </div>
</template>
