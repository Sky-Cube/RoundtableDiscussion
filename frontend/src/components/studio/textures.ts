import * as THREE from "three";

function mulberry32(seed: number): () => number {
  let value = seed;
  return () => {
    value |= 0;
    value = (value + 0x6d2b79f5) | 0;
    let t = Math.imul(value ^ (value >>> 15), 1 | value);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function canvasTexture(
  canvas: HTMLCanvasElement,
  { repeat = 1, anisotropy = 8 }: { repeat?: number; anisotropy?: number } = {},
): THREE.CanvasTexture {
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.wrapS = THREE.RepeatWrapping;
  texture.wrapT = THREE.RepeatWrapping;
  texture.repeat.set(repeat, repeat);
  texture.anisotropy = anisotropy;
  return texture;
}

export function createWoodTexture(): THREE.CanvasTexture {
  const size = 1024;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  const random = mulberry32(2411);

  const base = context.createLinearGradient(0, 0, size, size);
  base.addColorStop(0, "#684832");
  base.addColorStop(0.38, "#5d3f2b");
  base.addColorStop(0.7, "#6d4c34");
  base.addColorStop(1, "#513523");
  context.fillStyle = base;
  context.fillRect(0, 0, size, size);

  const center = size / 2;
  context.save();
  context.translate(center, center);
  for (let ring = 0; ring < 92; ring += 1) {
    const radius = 12 + ring * 5.7 + random() * 6;
    const wobble = 3 + random() * 10;
    context.beginPath();
    for (let point = 0; point <= 96; point += 1) {
      const angle = (point / 96) * Math.PI * 2;
      const noise =
        Math.sin(angle * (2 + (ring % 5)) + ring) * wobble +
        Math.sin(angle * 9 + ring * 0.7) * 2.4;
      const r = radius + noise;
      const x = Math.cos(angle) * r;
      const y = Math.sin(angle) * r;
      if (point === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    }
    context.closePath();
    const alpha = 0.075 + random() * 0.16;
    context.strokeStyle = `rgba(34, 18, 9, ${alpha})`;
    context.lineWidth = 0.8 + random() * 2;
    context.stroke();
  }
  context.restore();

  for (let line = 0; line < 360; line += 1) {
    const y = random() * size;
    const x = random() * size;
    const length = 40 + random() * 280;
    context.beginPath();
    context.moveTo(x, y);
    context.bezierCurveTo(
      x + length * 0.35,
      y + (random() - 0.5) * 6,
      x + length * 0.7,
      y + (random() - 0.5) * 6,
      x + length,
      y + (random() - 0.5) * 3,
    );
    context.strokeStyle = `rgba(34, 19, 10, ${0.03 + random() * 0.09})`;
    context.lineWidth = 0.6 + random();
    context.stroke();
  }

  const vignette = context.createRadialGradient(
    center,
    center,
    size * 0.28,
    center,
    center,
    size * 0.72,
  );
  vignette.addColorStop(0, "rgba(20, 10, 4, 0)");
  vignette.addColorStop(1, "rgba(20, 10, 4, 0.36)");
  context.fillStyle = vignette;
  context.fillRect(0, 0, size, size);

  return canvasTexture(canvas, { anisotropy: 12 });
}

export function createFloorTexture(): THREE.CanvasTexture {
  const size = 512;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  const random = mulberry32(773);
  context.fillStyle = "#8d979d";
  context.fillRect(0, 0, size, size);

  for (let i = 0; i < 9000; i += 1) {
    const alpha = 0.015 + random() * 0.045;
    const shade = random() > 0.5 ? 255 : 12;
    context.fillStyle = `rgba(${shade}, ${shade}, ${shade}, ${alpha})`;
    const radius = random() * 1.7;
    context.fillRect(random() * size, random() * size, radius, radius);
  }

  for (let i = 0; i < 22; i += 1) {
    const gradient = context.createRadialGradient(
      random() * size,
      random() * size,
      0,
      random() * size,
      random() * size,
      size * (0.08 + random() * 0.2),
    );
    gradient.addColorStop(0, "rgba(255,255,255,0.035)");
    gradient.addColorStop(1, "rgba(255,255,255,0)");
    context.fillStyle = gradient;
    context.fillRect(0, 0, size, size);
  }

  return canvasTexture(canvas, { repeat: 5, anisotropy: 8 });
}

export function createWallTexture(): THREE.CanvasTexture {
  const width = 1024;
  const height = 512;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  const random = mulberry32(991);

  const base = context.createLinearGradient(0, 0, 0, height);
  base.addColorStop(0, "#647684");
  base.addColorStop(0.48, "#7d909e");
  base.addColorStop(1, "#91a1ad");
  context.fillStyle = base;
  context.fillRect(0, 0, width, height);

  const light = context.createRadialGradient(
    width * 0.5,
    height * 0.54,
    20,
    width * 0.5,
    height * 0.54,
    width * 0.58,
  );
  light.addColorStop(0, "rgba(214, 228, 238, 0.34)");
  light.addColorStop(0.5, "rgba(197, 214, 226, 0.12)");
  light.addColorStop(1, "rgba(120, 138, 151, 0)");
  context.fillStyle = light;
  context.fillRect(0, 0, width, height);

  for (let index = 0; index < 14; index += 1) {
    const x = (index + 0.5) * (width / 14);
    context.fillStyle = `rgba(42, 61, 74, ${0.025 + random() * 0.02})`;
    context.fillRect(x, 0, 1, height);
  }

  for (let index = 0; index < 1800; index += 1) {
    const alpha = 0.012 + random() * 0.02;
    context.fillStyle = `rgba(255,255,255,${alpha})`;
    context.fillRect(
      random() * width,
      random() * height,
      1 + random() * 35,
      0.6,
    );
  }

  const vignette = context.createRadialGradient(
    width / 2,
    height / 2,
    width * 0.22,
    width / 2,
    height / 2,
    width * 0.7,
  );
  vignette.addColorStop(0, "rgba(19, 34, 44, 0)");
  vignette.addColorStop(1, "rgba(19, 34, 44, 0.18)");
  context.fillStyle = vignette;
  context.fillRect(0, 0, width, height);

  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 8;
  return texture;
}

export function createWallBrandTexture(): THREE.CanvasTexture {
  const width = 2048;
  const height = 512;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  context.clearRect(0, 0, width, height);

  context.textAlign = "center";
  context.textBaseline = "middle";
  context.fillStyle = "rgba(229, 239, 245, 0.12)";
  context.font = "300 104px 'Manrope', 'Segoe UI', sans-serif";
  context.fillText("AI PANEL STUDIO", width / 2, height * 0.42);

  context.strokeStyle = "rgba(229, 239, 245, 0.11)";
  context.lineWidth = 2;
  context.beginPath();
  context.moveTo(width * 0.3, height * 0.56);
  context.lineTo(width * 0.7, height * 0.56);
  context.stroke();

  context.fillStyle = "rgba(229, 239, 245, 0.09)";
  context.font = "500 27px 'Manrope', 'Segoe UI', sans-serif";
  context.letterSpacing = "12px";
  context.fillText("ROUNDTABLE  ·  STUDIO B  ·  LIVE COMPOSITE", width / 2, height * 0.67);

  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 8;
  return texture;
}

export function createGlowTexture(): THREE.CanvasTexture {
  const size = 512;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  const gradient = context.createRadialGradient(
    size / 2,
    size / 2,
    0,
    size / 2,
    size / 2,
    size / 2,
  );
  gradient.addColorStop(0, "rgba(255,255,255,0.85)");
  gradient.addColorStop(0.22, "rgba(255,255,255,0.45)");
  gradient.addColorStop(0.55, "rgba(255,255,255,0.13)");
  gradient.addColorStop(1, "rgba(255,255,255,0)");
  context.fillStyle = gradient;
  context.fillRect(0, 0, size, size);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

export function createShadowTexture(): THREE.CanvasTexture {
  const size = 256;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  const gradient = context.createRadialGradient(
    size / 2,
    size / 2,
    size * 0.06,
    size / 2,
    size / 2,
    size * 0.5,
  );
  gradient.addColorStop(0, "rgba(0,0,0,0.5)");
  gradient.addColorStop(0.45, "rgba(0,0,0,0.22)");
  gradient.addColorStop(0.78, "rgba(0,0,0,0.06)");
  gradient.addColorStop(1, "rgba(0,0,0,0)");
  context.fillStyle = gradient;
  context.fillRect(0, 0, size, size);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

export function createDiscTexture(): THREE.CanvasTexture {
  const size = 1024;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas 2D context unavailable");
  context.clearRect(0, 0, size, size);

  context.strokeStyle = "rgba(224, 231, 234, 0.32)";
  context.lineWidth = 2;
  context.beginPath();
  context.arc(size / 2, size / 2, 460, 0, Math.PI * 2);
  context.stroke();

  context.strokeStyle = "rgba(224, 231, 234, 0.14)";
  context.beginPath();
  context.arc(size / 2, size / 2, 410, 0, Math.PI * 2);
  context.stroke();

  context.save();
  context.translate(size / 2, size / 2);
  context.rotate(-Math.PI / 2);
  context.fillStyle = "rgba(228, 235, 238, 0.7)";
  context.font = "500 28px 'Manrope', sans-serif";
  context.textAlign = "center";
  context.textBaseline = "middle";

  const label = "AI PANEL STUDIO   ·   ROUNDTABLE 04   ·   LIVE COMPOSITE";
  const characters = Array.from(label);
  const radius = 434;
  const step = (Math.PI * 1.55) / characters.length;
  const start = -Math.PI * 0.775;
  characters.forEach((character, index) => {
    context.save();
    context.rotate(start + step * index);
    context.translate(0, -radius);
    context.fillText(character, 0, 0);
    context.restore();
  });
  context.restore();

  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 8;
  return texture;
}
