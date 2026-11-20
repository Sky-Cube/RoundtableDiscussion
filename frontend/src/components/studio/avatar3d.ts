import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import type { Participant } from '../../api/discussions'
import { avatarPalette, avatarStyleFor, type AvatarStyle } from './avatar-style'

export interface FigureVisual {
  group: THREE.Group
  head: THREE.Group
  mouth: THREE.Mesh
  eyeGroup: THREE.Group
  rightArm: THREE.Group
  leftArm: THREE.Group
  ringMaterial: THREE.MeshBasicMaterial
  ring: THREE.Mesh
  phase: number
  speaking: boolean
  baseY: number
  seatAngle: number
  update: (time: number, targetYaw: number, speaking: boolean, reduced: boolean) => void
  dispose: () => void
}

function material(color: THREE.ColorRepresentation, roughness = 0.72, metalness = 0.02) {
  return new THREE.MeshStandardMaterial({ color, roughness, metalness })
}

function addMesh(
  parent: THREE.Object3D,
  geometry: THREE.BufferGeometry,
  surface: THREE.Material,
  position: [number, number, number],
  rotation?: [number, number, number],
  scale?: [number, number, number],
) {
  const object = new THREE.Mesh(geometry, surface)
  object.position.set(...position)
  if (rotation) object.rotation.set(...rotation)
  if (scale) object.scale.set(...scale)
  object.castShadow = true
  object.receiveShadow = true
  parent.add(object)
  return object
}

function ellipse(
  parent: THREE.Object3D,
  surface: THREE.Material,
  x: number, y: number, z: number,
  width: number, height: number, depth: number,
  rotation?: [number, number, number],
) {
  return addMesh(parent, new THREE.SphereGeometry(1, 22, 16), surface, [x, y, z], rotation, [width, height, depth])
}

function hairStyle(
  style: AvatarStyle,
  head: THREE.Group,
  hair: THREE.Material,
  accent: THREE.Material,
  index: number,
) {
  addMesh(head, new THREE.SphereGeometry(0.235, 24, 16, 0, Math.PI * 2, 0, 1.3), hair, [0, 2.09, 0.01], [0.02, 0, 0], [1.01, 1.05, 1.01])
  addMesh(head, new THREE.SphereGeometry(0.237, 24, 16, -Math.PI * 0.06, Math.PI * 1.12, 1.02, Math.PI - 1.02), hair, [0, 2.09, 0.008], undefined, [1.015, 1.015, 1.015])

  if (style === 'anime') {
    for (const [x, y, z, scale] of [
      [-0.13, 2.25, -0.19, 0.1], [-0.04, 2.29, -0.2, 0.12], [0.07, 2.27, -0.19, 0.11], [0.16, 2.2, -0.17, 0.09],
    ] as const) {
      addMesh(head, new THREE.ConeGeometry(scale, 0.24, 8), hair, [x, y, z], [0.2, 0, x * 1.6])
    }
    ellipse(head, hair, 0.22, 2.03, 0.12, 0.08, 0.27, 0.1, [0.2, 0, -0.2])
    ellipse(head, hair, -0.22, 2.03, 0.12, 0.08, 0.27, 0.1, [0.2, 0, 0.2])
    ellipse(head, hair, 0.26, 1.83, 0.12, 0.055, 0.18, 0.055, [0.1, 0, -0.22])
    ellipse(head, hair, -0.26, 1.83, 0.12, 0.055, 0.18, 0.055, [0.1, 0, 0.22])
    addMesh(head, new THREE.TorusGeometry(0.07, 0.012, 8, 18, Math.PI), accent, [-0.17, 2.24, -0.18], [0, 0, 0.2])
    addMesh(head, new THREE.SphereGeometry(0.035, 12, 10), accent, [0.19, 2.17, -0.17], undefined, [1, 0.7, 1])
  } else if (style === 'creative') {
    ellipse(head, hair, 0.17, 2.3, 0.14, 0.1, 0.11, 0.1)
    addMesh(head, new THREE.CylinderGeometry(0.045, 0.08, 0.11, 12), hair, [0.17, 2.2, 0.12], [0.2, 0, -0.3])
    ellipse(head, hair, -0.19, 2.11, -0.14, 0.07, 0.18, 0.08, [0.2, 0, 0.35])
    if (index % 2 === 0) addMesh(head, new THREE.ConeGeometry(0.05, 0.18, 8), hair, [-0.17, 2.03, 0.1], [0.4, 0, 0.35])
  } else if (style === 'scholar') {
    ellipse(head, hair, 0, 2.33, 0.12, 0.15, 0.13, 0.12)
    addMesh(head, new THREE.CylinderGeometry(0.055, 0.08, 0.1, 12), hair, [0, 2.23, 0.14], [0.1, 0, 0])
    addMesh(head, new THREE.BoxGeometry(0.19, 0.06, 0.06), hair, [-0.08, 2.25, -0.18], [0.1, 0.2, -0.32])
  } else if (style === 'casual') {
    addMesh(head, new THREE.ConeGeometry(0.09, 0.18, 8), hair, [0.06, 2.29, -0.08], [0.35, 0.2, -0.25])
    ellipse(head, hair, -0.13, 2.2, -0.17, 0.09, 0.1, 0.07, [0.2, 0, 0.3])
  } else {
    addMesh(head, new THREE.BoxGeometry(0.23, 0.075, 0.08), hair, [-0.02, 2.24, -0.17], [0.15, 0.12, -0.22])
    ellipse(head, hair, 0.18, 2.13, 0.02, 0.06, 0.13, 0.08, [0, 0, -0.15])
  }
}

function addGlasses(head: THREE.Group, style: AvatarStyle) {
  const frame = material('#26333b', 0.45, 0.35)
  if (style === 'scholar') {
    for (const x of [-0.082, 0.082]) addMesh(head, new THREE.TorusGeometry(0.07, 0.008, 8, 20), frame, [x, 2.08, -0.205])
  } else {
    for (const x of [-0.082, 0.082]) addMesh(head, new RoundedBoxGeometry(0.14, 0.1, 0.015, 2, 0.02), frame, [x, 2.08, -0.205])
  }
  addMesh(head, new THREE.CylinderGeometry(0.006, 0.006, 0.07, 6), frame, [0, 2.08, -0.205], [0, 0, Math.PI / 2])
  for (const x of [-0.16, 0.16]) addMesh(head, new THREE.CylinderGeometry(0.006, 0.006, 0.09, 6), frame, [x, 2.09, -0.13], [0, 0.65, 0])
}

function addOutfit(
  body: THREE.Group,
  style: AvatarStyle,
  color: THREE.Color,
  jacket: THREE.Material,
  jacketDark: THREE.Material,
  shirt: THREE.Material,
  index: number,
) {
  const collar = new THREE.Shape()
  collar.moveTo(-0.1, 0)
  collar.lineTo(0.1, 0)
  collar.lineTo(0.0, 0.22)
  collar.closePath()
  const collarMesh = new THREE.Mesh(
    new THREE.ShapeGeometry(collar),
    new THREE.MeshStandardMaterial({ color: '#f3f5f4', roughness: 0.8, side: THREE.DoubleSide }),
  )
  collarMesh.position.set(0, 1.5, -0.27)
  collarMesh.castShadow = true
  body.add(collarMesh)

  if (style === 'anchor' || style === 'corporate') {
    const tie = new THREE.Mesh(new RoundedBoxGeometry(0.055, 0.28, 0.02, 2, 0.012), material(new THREE.Color(color).offsetHSL(0, 0, -0.22)))
    tie.position.set(0, 1.41, -0.3)
    tie.rotation.x = -0.05
    body.add(tie)
    for (const x of [-0.16, 0.16]) {
      const lapel = new THREE.Mesh(new RoundedBoxGeometry(0.12, 0.36, 0.035, 2, 0.02), jacketDark)
      lapel.position.set(x, 1.4, -0.26)
      lapel.rotation.z = x > 0 ? -0.32 : 0.32
      body.add(lapel)
    }
  } else if (style === 'anime') {
    const ribbon = new THREE.Mesh(new RoundedBoxGeometry(0.12, 0.08, 0.025, 2, 0.02), material(new THREE.Color(color).offsetHSL(0, 0.1, 0.22)))
    ribbon.position.set(0, 1.43, -0.3)
    ribbon.rotation.z = 0.15
    body.add(ribbon)
    const collarBand = new THREE.Mesh(new THREE.TorusGeometry(0.16, 0.018, 8, 24, Math.PI), shirt)
    collarBand.position.set(0, 1.53, -0.22)
    collarBand.rotation.x = Math.PI / 2.1
    body.add(collarBand)
  } else if (style === 'creative') {
    const cowl = new THREE.Mesh(new THREE.TorusGeometry(0.12, 0.045, 8, 24), jacketDark)
    cowl.position.set(0, 1.68, -0.01)
    cowl.rotation.x = Math.PI / 2
    body.add(cowl)
    addMesh(body, new THREE.CapsuleGeometry(0.028, 0.22, 4, 8), jacketDark, [-0.09, 1.5, -0.28], [-0.1, 0, 0.12])
  } else if (style === 'scholar') {
    const cardigan = new THREE.Mesh(new RoundedBoxGeometry(0.48, 0.45, 0.08, 2, 0.04), jacket)
    cardigan.position.set(0, 1.36, -0.2)
    cardigan.scale.set(1, 1, 0.5)
    body.add(cardigan)
    for (const x of [-0.08, 0.08]) {
      addMesh(body, new THREE.CylinderGeometry(0.012, 0.012, 0.06, 6), material('#d8dfe1'), [x, 1.55, -0.29])
    }
    addMesh(body, new THREE.SphereGeometry(0.012, 8, 8), material('#d8dfe1'), [-0.08, 1.51, -0.3])
    addMesh(body, new THREE.SphereGeometry(0.012, 8, 8), material('#d8dfe1'), [0.08, 1.51, -0.3])
  } else {
    const hood = new THREE.Mesh(new THREE.TorusGeometry(0.18, 0.06, 8, 22, Math.PI), jacketDark)
    hood.position.set(0, 1.58, 0.12)
    hood.rotation.x = -0.25
    body.add(hood)
    addMesh(body, new THREE.CylinderGeometry(0.01, 0.01, 0.22, 6), material('#f1f3f2'), [-0.07, 1.48, -0.29])
    addMesh(body, new THREE.CylinderGeometry(0.01, 0.01, 0.22, 6), material('#f1f3f2'), [0.07, 1.48, -0.29])
  }

  if (index % 5 === 2) {
    addMesh(body, new THREE.SphereGeometry(0.026, 8, 8), material('#e6c882', 0.3, 0.65), [0.31, 1.66, -0.05])
  }
}

export function createFigure(participant: Participant, index: number, seatColor: string): FigureVisual {
  const style = avatarStyleFor(participant, index)
  const palette = avatarPalette(participant, index)
  const color = new THREE.Color(seatColor)
  const group = new THREE.Group()
  const body = new THREE.Group()
  group.add(body)

  const jacket = material(color.clone().offsetHSL(0, -0.02, -0.02), 0.68, 0.03)
  const jacketDark = material(color.clone().offsetHSL(0, -0.05, -0.15), 0.76, 0.02)
  const shirt = material('#eef2f2', 0.84)
  const skin = material(palette.skin, 0.64)
  const skinShadow = material(new THREE.Color(palette.skin).offsetHSL(0, -0.02, -0.08), 0.7)
  const hairColor = style === 'anime'
    ? new THREE.Color(palette.hair).lerp(color, 0.42)
    : new THREE.Color(palette.hair)
  const hair = material(hairColor, 0.82)
  const dark = material('#202a30', 0.5)

  addMesh(body, new THREE.CapsuleGeometry(0.3, 0.48, 5, 18), jacket, [0, 1.35, 0.01], undefined, [1.24, 1, 0.88])
  ellipse(body, jacket, 0, 1.66, 0.01, 0.46, 0.24, 0.3)
  addMesh(body, new THREE.CapsuleGeometry(0.2, 0.42, 4, 14), material('#2d3a43'), [0, 0.72, -0.04], [0.1, 0, 0], [1.1, 1, 0.8])

  const neck = addMesh(body, new THREE.CylinderGeometry(0.085, 0.095, 0.15, 14), skin, [0, 1.83, 0])
  neck.castShadow = true
  addOutfit(body, style, color, jacket, jacketDark, shirt, index)

  const head = new THREE.Group()
  body.add(head)
  addMesh(head, new THREE.SphereGeometry(0.225, 28, 22), skin, [0, 2.08, 0], undefined, [0.93, 1.03, 0.92])
  for (const x of [-0.212, 0.212]) ellipse(head, skin, x, 2.08, 0.005, 0.04, 0.055, 0.04)
  ellipse(head, skinShadow, 0, 2.015, -0.223, 0.026, 0.032, 0.025)

  const eyeGroup = new THREE.Group()
  eyeGroup.position.y = 2.08
  head.add(eyeGroup)
  const anime = style === 'anime'
  if (anime) {
    for (const x of [-0.075, 0.075]) {
      ellipse(eyeGroup, material('#ffffff', 0.3), x, 0.01, -0.198, 0.065, 0.085, 0.025)
      ellipse(eyeGroup, dark, x, 0.01, -0.218, 0.038, 0.055, 0.02)
      ellipse(eyeGroup, material('#ffffff', 0.2), x - 0.012, 0.028, -0.232, 0.012, 0.016, 0.008)
    }
  } else {
    for (const x of [-0.075, 0.075]) ellipse(eyeGroup, dark, x, 0.015, -0.205, 0.019, 0.024, 0.015)
  }
  for (const x of [-0.075, 0.075]) {
    addMesh(head, new THREE.BoxGeometry(0.06, 0.008, 0.01), hair, [x, 2.145, -0.2], [0, 0, x > 0 ? -0.08 : 0.08])
  }
  const mouth = addMesh(head, new THREE.BoxGeometry(0.06, 0.008, 0.01), material('#8b5f51', 0.6), [0, 1.995, -0.212])
  mouth.castShadow = false
  hairStyle(style, head, hair, anime ? material('#e5aabf', 0.55) : hair, index)
  if (style === 'corporate' || style === 'scholar' || (style === 'anchor' && index % 2 === 0)) addGlasses(head, style)

  const arms: THREE.Group[] = []
  for (const sign of [-1, 1]) {
    const arm = new THREE.Group()
    arm.position.set(sign * 0.36, 1.63, -0.01)
    arm.rotation.set(-0.34, 0, sign * -0.12)
    body.add(arm)
    addMesh(arm, new THREE.CapsuleGeometry(0.075, 0.28, 4, 10), jacket, [0, -0.14, 0], undefined, [1, 1, 0.95])
    addMesh(arm, new THREE.CapsuleGeometry(0.065, 0.22, 4, 10), jacketDark, [0, -0.33, -0.1], [-0.55, 0, 0])
    addMesh(arm, new THREE.SphereGeometry(0.062, 12, 10), skin, [0, -0.45, -0.21], undefined, [1.1, 0.66, 1.25])
    arms.push(arm)
  }
  const rightArm = arms[1]!
  const leftArm = arms[0]!

  const ringMaterial = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.26, depthWrite: false, side: THREE.DoubleSide })
  const ring = new THREE.Mesh(new THREE.RingGeometry(0.54, 0.6, 64), ringMaterial)
  ring.rotation.x = -Math.PI / 2
  ring.position.y = 0.055
  group.add(ring)

  const phase = index * 1.37 + 0.4
  const visual: FigureVisual = {
    group,
    head,
    mouth,
    eyeGroup,
    rightArm,
    leftArm,
    ringMaterial,
    ring,
    phase,
    speaking: false,
    baseY: 0,
    seatAngle: 0,
    update: (time, targetYaw, speaking, reduced) => {
      visual.speaking = speaking
      const breathe = reduced ? 0 : Math.sin(time * 0.9 + phase) * 0.006
      group.position.y = visual.baseY + breathe
      const desiredYaw = speaking ? Math.sin(time * 1.1 + phase) * 0.035 : targetYaw
      head.rotation.y += (desiredYaw - head.rotation.y) * 0.045
      head.rotation.x = speaking && !reduced ? Math.sin(time * 2.2 + phase) * 0.022 : 0
      mouth.scale.y = speaking && !reduced ? 0.8 + Math.abs(Math.sin(time * 8.5 + phase)) * 1.8 : 1
      mouth.scale.x = speaking && !reduced ? 0.9 + Math.sin(time * 4.2 + phase) * 0.15 : 1
      const blink = !reduced && (time + phase) % 4.3 < 0.11 ? 0.08 : 1
      eyeGroup.scale.y = blink
      rightArm.rotation.x = -0.34 + (speaking && !reduced ? -0.08 + Math.sin(time * 2.1 + phase) * 0.04 : 0)
      leftArm.rotation.x = -0.34
      const targetRing = speaking ? 0.92 : 0.24
      ringMaterial.opacity += (targetRing - ringMaterial.opacity) * 0.06
      const ringScale = speaking && !reduced ? 1 + Math.sin(time * 3.2 + phase) * 0.05 : 1
      ring.scale.setScalar(ringScale)
    },
    dispose: () => {
      group.traverse(object => {
        const mesh = object as THREE.Mesh
        if (mesh.geometry) mesh.geometry.dispose()
        if (mesh.material) {
          const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
          for (const item of materials) item.dispose()
        }
      })
    },
  }
  group.position.y = visual.baseY
  return visual
}

export { avatarStyleFor }

export function createChair(): THREE.Group {
  const group = new THREE.Group()
  const fabric = material('#4f606e', 0.94)
  const fabricLight = material('#5a6c79', 0.92)
  const wood = material('#5a4030', 0.58, 0.04)
  const metal = material('#9ca5aa', 0.42, 0.62)
  addMesh(group, new RoundedBoxGeometry(0.92, 0.15, 0.9, 3, 0.07), fabric, [0, 0.67, 0])
  addMesh(group, new RoundedBoxGeometry(0.9, 0.82, 0.13, 3, 0.06), fabricLight, [0, 1.12, 0.36], [-0.075, 0, 0])
  for (const x of [-0.46, 0.46]) addMesh(group, new RoundedBoxGeometry(0.62, 0.09, 0.09, 2, 0.035), fabric, [x, 0.88, 0])
  for (const x of [-0.33, 0.33]) {
    for (const z of [-0.3, 0.3]) {
      addMesh(group, new THREE.CylinderGeometry(0.035, 0.045, 0.43, 10), wood, [x, 0.32, z])
      addMesh(group, new THREE.CylinderGeometry(0.028, 0.028, 0.02, 10), metal, [x, 0.09, z])
    }
  }
  return group
}

export function disposeGroup(group: THREE.Object3D) {
  group.traverse(object => {
    const mesh = object as THREE.Mesh
    if (mesh.geometry) mesh.geometry.dispose()
    if (mesh.material) {
      const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
      for (const item of materials) item.dispose()
    }
  })
}
