import * as THREE from 'three'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'
import type { Participant } from '../../api/discussions'
import { avatarStyleFor, type AvatarStyle } from './avatar-style'
import { createChair, createFigure, disposeGroup, type FigureVisual } from './avatar3d'
import {
  createFloorTexture,
  createGlowTexture,
  createShadowTexture,
  createWallTexture,
  createWoodTexture,
} from './textures'
import { seatColor } from './avatar-style'

export { seatColor }

export interface SeatPosition {
  id: string
  color: string
  angle: number
  x: number
  z: number
  participant: Participant
  style: AvatarStyle
}

export interface RoundtableController {
  render: (width: number, height: number) => { x: number; y: number }[]
  setActive: (id: string | undefined) => void
  dispose: () => void
}

const TABLE_RADIUS = 3.05
const SEAT_RADIUS = 3.72

export function seatPositions(members: Participant[]): SeatPosition[] {
  const ordered = [...members.filter(member => member.role === 'HOST'), ...members.filter(member => member.role === 'EXPERT')]
  return ordered.map((member, index) => {
    const angle = Math.PI + (index / ordered.length) * Math.PI * 2
    return {
      id: member.id,
      color: seatColor(member.colorKey),
      angle,
      x: Math.sin(angle) * SEAT_RADIUS,
      z: Math.cos(angle) * SEAT_RADIUS,
      participant: member,
      style: avatarStyleFor(member, index),
    }
  })
}

interface SeatVisual {
  seat: SeatPosition
  wrapper: THREE.Group
  figure: FigureVisual
}

export function createRoundtable(canvas: HTMLCanvasElement, seats: SeatPosition[]): RoundtableController | undefined {
  let renderer: THREE.WebGLRenderer
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false, powerPreference: 'high-performance' })
  } catch {
    return undefined
  }

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.04
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFShadowMap
  renderer.shadowMap.autoUpdate = true

  const scene = new THREE.Scene()
  scene.background = new THREE.Color('#a8b6c1')
  scene.fog = new THREE.Fog('#a8b6c1', 18, 36)
  const pmrem = new THREE.PMREMGenerator(renderer)
  const roomEnvironment = new RoomEnvironment()
  const environment = pmrem.fromScene(roomEnvironment, 0.04)
  scene.environment = environment.texture
  scene.environmentIntensity = 0.34
  roomEnvironment.dispose()
  pmrem.dispose()

  const camera = new THREE.PerspectiveCamera(35, 1, 0.1, 80)
  const woodTexture = createWoodTexture()
  const floorTexture = createFloorTexture()
  const wallTexture = createWallTexture()
  const glowTexture = createGlowTexture()
  const shadowTexture = createShadowTexture()
  const ownedTextures = [woodTexture, floorTexture, wallTexture, glowTexture, shadowTexture]

  const material = (color: string, roughness = 0.8, metalness = 0.02) =>
    new THREE.MeshStandardMaterial({ color, roughness, metalness })
  const add = (parent: THREE.Object3D, geometry: THREE.BufferGeometry, surface: THREE.Material, x: number, y: number, z: number) => {
    const object = new THREE.Mesh(geometry, surface)
    object.position.set(x, y, z)
    object.castShadow = true
    object.receiveShadow = true
    parent.add(object)
    return object
  }

  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(46, 34),
    new THREE.MeshStandardMaterial({ map: floorTexture, color: '#c2cbd0', roughness: 0.64, metalness: 0.1, envMapIntensity: 0.45 }),
  )
  floor.rotation.x = -Math.PI / 2
  floor.receiveShadow = true
  scene.add(floor)

  const dais = new THREE.Mesh(
    new THREE.CylinderGeometry(5.15, 5.3, 0.055, 96),
    material('#929ca3', 0.7, 0.14),
  )
  dais.position.y = 0.028
  dais.receiveShadow = true
  scene.add(dais)

  const daisRing = new THREE.Mesh(
    new THREE.RingGeometry(4.87, 4.9, 96),
    new THREE.MeshBasicMaterial({ color: '#e8edf0', transparent: true, opacity: 0.2, side: THREE.DoubleSide }),
  )
  daisRing.rotation.x = -Math.PI / 2
  daisRing.position.y = 0.058
  scene.add(daisRing)

  const wall = new THREE.Mesh(
    new THREE.PlaneGeometry(44, 18),
    new THREE.MeshStandardMaterial({ map: wallTexture, color: '#f4f7f9', roughness: 0.97, metalness: 0.01 }),
  )
  wall.position.set(0, 7.2, -10.5)
  wall.receiveShadow = true
  scene.add(wall)

  const wallGlow = new THREE.Mesh(
    new THREE.PlaneGeometry(16, 7),
    new THREE.MeshBasicMaterial({
      map: glowTexture, color: '#eaf3fb', transparent: true, opacity: 0.22, depthWrite: false, blending: THREE.AdditiveBlending,
    }),
  )
  wallGlow.position.set(0, 3.9, -10.45)
  scene.add(wallGlow)

  for (let index = 0; index < 9; index += 1) {
    const seam = new THREE.Mesh(
      new THREE.BoxGeometry(0.012, 12, 0.02),
      new THREE.MeshBasicMaterial({ color: '#6f7e8b', transparent: true, opacity: 0.15 }),
    )
    seam.position.set(-14 + index * 3.5, 6, -10.47)
    scene.add(seam)
  }

  const cove = new THREE.Mesh(
    new THREE.BoxGeometry(30, 0.085, 0.06),
    new THREE.MeshBasicMaterial({ color: '#eaf3fb', transparent: true, opacity: 0.4 }),
  )
  cove.position.set(0, 8.2, -10.44)
  scene.add(cove)

  const tableShadow = new THREE.Mesh(
    new THREE.PlaneGeometry(8, 8),
    new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, opacity: 0.4, depthWrite: false, color: '#20272c' }),
  )
  tableShadow.rotation.x = -Math.PI / 2
  tableShadow.position.y = 0.062
  scene.add(tableShadow)

  const wood = new THREE.MeshPhysicalMaterial({
    map: woodTexture, color: '#b58a65', roughness: 0.48, metalness: 0.02, clearcoat: 0.28, clearcoatRoughness: 0.34, envMapIntensity: 0.56,
  })
  const darkWood = material('#4b3628', 0.62, 0.01)
  const top = add(scene, new THREE.CylinderGeometry(TABLE_RADIUS, TABLE_RADIUS, 0.15, 96), wood, 0, 1.51, 0)
  top.receiveShadow = true
  const edge = add(scene, new THREE.TorusGeometry(TABLE_RADIUS + 0.005, 0.024, 12, 96), material('#3e2d22', 0.4, 0.15), 0, 1.582, 0)
  edge.rotation.x = Math.PI / 2
  add(scene, new THREE.CylinderGeometry(0.5, 0.7, 0.84, 48), darkWood, 0, 0.46, 0)
  add(scene, new THREE.CylinderGeometry(1.36, 1.5, 0.08, 64), darkWood, 0, 0.04, 0)

  const inlay = new THREE.Mesh(
    new THREE.CircleGeometry(0.82, 96),
    new THREE.MeshStandardMaterial({ color: '#313a40', roughness: 0.46, metalness: 0.18, envMapIntensity: 0.58 }),
  )
  inlay.rotation.x = -Math.PI / 2
  inlay.position.y = 1.588
  inlay.receiveShadow = true
  scene.add(inlay)
  const inlayRing = new THREE.Mesh(
    new THREE.RingGeometry(0.81, 0.842, 96),
    new THREE.MeshStandardMaterial({ color: '#9c875f', roughness: 0.28, metalness: 0.78 }),
  )
  inlayRing.rotation.x = -Math.PI / 2
  inlayRing.position.y = 1.589
  scene.add(inlayRing)
  const center = new THREE.Mesh(
    new THREE.CircleGeometry(0.12, 48),
    new THREE.MeshStandardMaterial({ color: '#c8b58c', roughness: 0.25, metalness: 0.8 }),
  )
  center.rotation.x = -Math.PI / 2
  center.position.y = 1.59
  scene.add(center)

  const seatVisuals: SeatVisual[] = []
  for (const [index, seat] of seats.entries()) {
    const wrapper = new THREE.Group()
    wrapper.position.set(seat.x, 0, seat.z)
    wrapper.rotation.y = seat.angle
    scene.add(wrapper)

    const chair = createChair()
    wrapper.add(chair)

    const contact = new THREE.Mesh(
      new THREE.PlaneGeometry(2.2, 2.2),
      new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, opacity: 0.32, depthWrite: false, color: '#1c2226' }),
    )
    contact.rotation.x = -Math.PI / 2
    contact.position.set(0, 0.064, 0.24)
    wrapper.add(contact)

    const figure = createFigure(seat.participant, index, seat.color)
    figure.group.position.z = -0.18
    figure.group.scale.setScalar(0.98 + (index % 4) * 0.016)
    figure.seatAngle = seat.angle
    wrapper.add(figure.group)
    seatVisuals.push({ seat, wrapper, figure })
  }

  scene.add(new THREE.HemisphereLight('#e5eff7', '#6d6a63', 1.08))
  const key = new THREE.DirectionalLight('#f7f9fb', 3.1)
  key.position.set(-5, 9, 6.5)
  key.castShadow = true
  key.shadow.mapSize.set(2048, 2048)
  key.shadow.camera.left = -8
  key.shadow.camera.right = 8
  key.shadow.camera.top = 8
  key.shadow.camera.bottom = -8
  key.shadow.camera.near = 1
  key.shadow.camera.far = 24
  key.shadow.bias = -0.0004
  key.shadow.normalBias = 0.025
  scene.add(key)
  const fill = new THREE.DirectionalLight('#d7e7f7', 0.72)
  fill.position.set(7, 6, 5)
  scene.add(fill)
  const rim = new THREE.DirectionalLight('#dfeaf3', 1.75)
  rim.position.set(0, 7, -8)
  scene.add(rim)
  const tableWash = new THREE.PointLight('#eaf4ff', 14, 10, 2)
  tableWash.position.set(0, 4.2, 1.4)
  scene.add(tableWash)

  let activeId: string | undefined
  let frame = 0
  let lastFrame = 0
  let disposed = false
  let fitted = false
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  const clock = new THREE.Clock()

  function fit(width: number, height: number) {
    const aspect = width / height
    camera.aspect = aspect
    camera.fov = 35
    const aspectFit = aspect < 1.18 ? 1 + (1.18 - aspect) * 0.72 : 1
    const widthFit = Math.min(1.16, Math.max(1, 1180 / Math.max(width, 1)))
    const fitScale = aspectFit * widthFit
    camera.position.set(0, 6.1 * fitScale, 12.25 * fitScale)
    camera.lookAt(0, 1.36, 0.14)
    camera.updateProjectionMatrix()
    fitted = true
  }

  function project(seat: SeatPosition) {
    const point = new THREE.Vector3(seat.x, 2.52, seat.z).project(camera)
    return { x: (point.x + 1) * 50, y: (1 - point.y) * 50 }
  }

  function targetYaw(seat: SeatPosition) {
    if (!activeId || activeId === seat.id) return 0
    const activeSeat = seats.find(item => item.id === activeId)
    if (!activeSeat) return 0
    const dx = activeSeat.x - seat.x
    const dz = activeSeat.z - seat.z
    return Math.max(-0.6, Math.min(0.6, Math.atan2(-dx, -dz) - seat.angle))
  }

  function tick(time: number) {
    if (disposed) return
    frame = requestAnimationFrame(tick)
    if (!fitted || time - lastFrame < 32) return
    lastFrame = time
    const elapsed = clock.getElapsedTime()
    for (const visual of seatVisuals) {
      visual.figure.update(elapsed, targetYaw(visual.seat), activeId === visual.seat.id, reduced)
    }
    renderer.render(scene, camera)
  }

  return {
    render(width: number, height: number) {
      if (!width || !height) return []
      fit(width, height)
      renderer.setSize(width, height, false)
      renderer.render(scene, camera)
      if (!reduced && !frame) frame = requestAnimationFrame(tick)
      return seats.map(project)
    },
    setActive(id: string | undefined) {
      activeId = id
      if (reduced) renderer.render(scene, camera)
    },
    dispose() {
      disposed = true
      cancelAnimationFrame(frame)
      scene.traverse(object => {
        const mesh = object as THREE.Mesh
        if (mesh.geometry) mesh.geometry.dispose()
        if (mesh.material) {
          const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
          for (const item of materials) item.dispose()
        }
      })
      for (const texture of ownedTextures) texture.dispose()
      environment.dispose()
      key.shadow.dispose()
      renderer.dispose()
    },
  }
}
