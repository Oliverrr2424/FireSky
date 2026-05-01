import React, { useEffect, useRef } from 'react';

// Single-canvas weather backdrop inspired by Apple Weather.
// Picks a particle system based on `theme` and adapts to day/night.
// Themes: clear | cloudy | rain | storm | snow | fog

export default function WeatherBackdrop({ theme = 'clear', isNight = false }) {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return undefined;
    const ctx = canvas.getContext('2d');
    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    let width = 0;
    let height = 0;
    let particles = [];
    let splashes = [];
    let droplets = [];
    let stars = [];
    let clouds = [];
    let lightning = { alpha: 0, nextAt: 0, flickers: 0 };
    let sunHalo = { phase: 0 };
    let rafId = 0;
    let last = performance.now();
    let visible = !document.hidden;

    function rand(min, max) {
      return min + Math.random() * (max - min);
    }

    function resize() {
      width = canvas.clientWidth;
      height = canvas.clientHeight;
      canvas.width = Math.max(1, Math.round(width * dpr));
      canvas.height = Math.max(1, Math.round(height * dpr));
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      init();
    }

    function init() {
      particles = [];
      splashes = [];
      droplets = [];
      stars = [];
      clouds = [];
      const area = width * height;
      const cloudCount = theme === 'clear' && !isNight ? 6 : theme === 'rain' || theme === 'storm' ? 18 : theme === 'fog' ? 20 : 14;
      for (let i = 0; i < cloudCount; i += 1) clouds.push(makeCloud(theme));

      if (theme === 'rain' || theme === 'storm') {
        const isStorm = theme === 'storm';
        const count = Math.min(isStorm ? 520 : 380, Math.max(80, Math.floor(area / 1600)));
        for (let i = 0; i < count; i += 1) particles.push(makeRain(isStorm));
        const dropletCount = Math.min(140, Math.max(40, Math.floor(area / 10500)));
        for (let i = 0; i < dropletCount; i += 1) droplets.push(makeDroplet());
        lightning.nextAt = performance.now() + rand(2500, 6000);
      } else if (theme === 'snow') {
        const count = Math.min(260, Math.max(60, Math.floor(area / 4500)));
        for (let i = 0; i < count; i += 1) particles.push(makeSnow());
      } else if (theme === 'cloudy' || theme === 'fog') {
        /* Clouds already initialized above so every theme has real atmosphere. */
      } else if (isNight) {
        const count = Math.min(240, Math.max(70, Math.floor(area / 6600)));
        for (let i = 0; i < count; i += 1) stars.push(makeStar());
      } else {
        const count = Math.min(60, Math.max(20, Math.floor(area / 22000)));
        for (let i = 0; i < count; i += 1) particles.push(makeDust());
      }
    }

    function makeRain(isStorm) {
      const wind = isStorm ? rand(140, 240) : rand(40, 110);
      return {
        x: rand(-40, width + 40),
        y: rand(-height, height),
        len: rand(10, isStorm ? 26 : 20),
        speed: rand(isStorm ? 620 : 460, isStorm ? 980 : 760),
        wind,
        thickness: rand(0.7, isStorm ? 1.6 : 1.2),
        alpha: rand(0.18, isStorm ? 0.55 : 0.45)
      };
    }

    function makeDroplet() {
      return {
        x: rand(0, width),
        y: rand(0, height),
        r: rand(1.2, 4.8),
        tail: rand(4, 20),
        alpha: rand(0.08, 0.22),
        speed: rand(2, 14)
      };
    }

    function makeSplash(x) {
      return { x, y: height - rand(2, 14), r: 0, life: 1, max: rand(6, 12) };
    }

    function makeSnow() {
      return {
        x: rand(0, width),
        y: rand(-height, height),
        r: rand(0.9, 2.8),
        speed: rand(22, 70),
        drift: rand(8, 38),
        phase: rand(0, Math.PI * 2),
        spin: rand(0.4, 1.4),
        alpha: rand(0.55, 1)
      };
    }

    function makeCloud(t) {
      const isFog = t === 'fog';
      return {
        x: rand(-220, width),
        y: isFog ? rand(height * 0.24, height * 0.95) : rand(-height * 0.08, height * 0.62),
        r: rand(isFog ? 180 : 140, isFog ? 420 : 340),
        speed: isFog ? rand(2, 6) : rand(3, 10),
        alpha: isFog ? rand(0.1, 0.22) : rand(0.08, 0.2),
        stretch: rand(1.35, 2.7)
      };
    }

    function makeStar() {
      return {
        x: rand(0, width),
        y: rand(0, height * 0.85),
        r: rand(0.3, 1.5),
        baseAlpha: rand(0.35, 0.95),
        twinkle: rand(0, Math.PI * 2),
        speed: rand(0.6, 1.6)
      };
    }

    function makeDust() {
      return {
        x: rand(0, width),
        y: rand(0, height),
        r: rand(0.6, 1.8),
        vx: rand(-6, 14),
        vy: rand(-12, -2),
        alpha: rand(0.06, 0.22)
      };
    }

    function drawBaseSky(now) {
      const cloudy = theme === 'cloudy' || theme === 'rain' || theme === 'storm' || theme === 'fog';
      const top = isNight
        ? (cloudy ? '#121a3a' : '#090f27')
        : (cloudy ? '#77899d' : '#74bdf7');
      const mid = isNight
        ? (cloudy ? '#283452' : '#17234a')
        : (cloudy ? '#8fa3b6' : '#87c9ff');
      const bottom = isNight
        ? (cloudy ? '#334461' : '#243c68')
        : (cloudy ? '#718297' : '#4c91d7');
      const sky = ctx.createLinearGradient(0, 0, 0, height);
      sky.addColorStop(0, top);
      sky.addColorStop(0.48, mid);
      sky.addColorStop(1, bottom);
      ctx.fillStyle = sky;
      ctx.fillRect(0, 0, width, height);

      const glowX = isNight ? width * 0.78 : width * 0.72;
      const glowY = isNight ? height * 0.18 : height * 0.16;
      const glow = ctx.createRadialGradient(glowX, glowY, 0, glowX, glowY, Math.max(width, height) * 0.72);
      if (isNight) {
        glow.addColorStop(0, 'rgba(205, 215, 255, 0.18)');
        glow.addColorStop(0.44, 'rgba(100, 128, 200, 0.08)');
        glow.addColorStop(1, 'rgba(20, 30, 70, 0)');
      } else {
        glow.addColorStop(0, 'rgba(255, 238, 190, 0.35)');
        glow.addColorStop(0.38, 'rgba(255, 218, 150, 0.12)');
        glow.addColorStop(1, 'rgba(255, 218, 150, 0)');
      }
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, width, height);

      if (theme === 'storm' || theme === 'rain' || theme === 'fog') {
        ctx.fillStyle = theme === 'storm' ? 'rgba(8, 14, 32, 0.32)' : 'rgba(95, 110, 128, 0.22)';
        ctx.fillRect(0, 0, width, height);
      }

      if (isNight && theme !== 'rain' && theme !== 'storm') {
        for (const s of stars) {
          s.twinkle += 0.012 * s.speed;
          const a = s.baseAlpha * (0.52 + 0.48 * Math.abs(Math.sin(s.twinkle + now * 0.00035)));
          ctx.fillStyle = `rgba(255,255,255,${a})`;
          ctx.beginPath();
          ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }

    function drawRain(dt, now) {
      const isStorm = theme === 'storm';
      drawClouds(dt, true);
      ctx.lineCap = 'round';
      for (const p of particles) {
        p.y += p.speed * dt;
        p.x += p.wind * dt;
        if (p.y > height + 4) {
          if (Math.random() < 0.35 && splashes.length < 80) splashes.push(makeSplash(p.x));
          p.y = rand(-40, -4);
          p.x = rand(-40, width + 40);
        }
        if (p.x > width + 20) p.x = -20;
        if (p.x < -40) p.x = width + 20;
        ctx.strokeStyle = `rgba(220,228,240,${p.alpha})`;
        ctx.lineWidth = p.thickness;
        ctx.beginPath();
        ctx.moveTo(p.x, p.y);
        ctx.lineTo(p.x - p.wind * 0.04, p.y - p.len);
        ctx.stroke();
      }

      drawDroplets(dt);

      // ripples
      for (let i = splashes.length - 1; i >= 0; i -= 1) {
        const s = splashes[i];
        s.r += dt * 18;
        s.life -= dt * 1.4;
        if (s.life <= 0 || s.r > s.max) {
          splashes.splice(i, 1);
          continue;
        }
        ctx.strokeStyle = `rgba(220,230,245,${0.22 * s.life})`;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.ellipse(s.x, s.y, s.r, s.r * 0.32, 0, 0, Math.PI * 2);
        ctx.stroke();
      }

      if (isStorm) {
        if (now >= lightning.nextAt) {
          lightning.alpha = 1;
          lightning.flickers = Math.floor(rand(1, 4));
          lightning.nextAt = now + rand(3500, 9000);
        }
        if (lightning.alpha > 0) {
          ctx.fillStyle = `rgba(225,235,255,${lightning.alpha * 0.42})`;
          ctx.fillRect(0, 0, width, height);
          lightning.alpha -= dt * 2.6;
          if (lightning.alpha <= 0 && lightning.flickers > 0) {
            lightning.flickers -= 1;
            lightning.alpha = rand(0.5, 1);
          }
        }
      }
    }

    function drawDroplets(dt) {
      ctx.save();
      ctx.globalCompositeOperation = 'screen';
      for (const d of droplets) {
        d.y += d.speed * dt;
        if (d.y - d.tail > height) {
          d.y = rand(-40, 0);
          d.x = rand(0, width);
        }
        const highlight = ctx.createRadialGradient(d.x - d.r * 0.35, d.y - d.r * 0.45, 0, d.x, d.y, d.r * 2.2);
        highlight.addColorStop(0, `rgba(255,255,255,${d.alpha * 1.2})`);
        highlight.addColorStop(0.45, `rgba(220,232,245,${d.alpha * 0.42})`);
        highlight.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.fillStyle = highlight;
        ctx.beginPath();
        ctx.ellipse(d.x, d.y, d.r * 0.75, d.r * 1.12, 0, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = `rgba(255,255,255,${d.alpha * 0.5})`;
        ctx.lineWidth = 0.8;
        ctx.beginPath();
        ctx.moveTo(d.x + d.r * 0.2, d.y + d.r);
        ctx.lineTo(d.x + d.r * 0.6, d.y + d.tail);
        ctx.stroke();
      }
      ctx.restore();
    }

    function drawSnow(dt) {
      drawClouds(dt, true);
      for (const p of particles) {
        p.phase += dt * p.spin;
        p.y += p.speed * dt;
        p.x += Math.sin(p.phase) * p.drift * dt;
        if (p.y > height + 4) {
          p.y = -4;
          p.x = rand(0, width);
        }
        if (p.x > width + 6) p.x = -6;
        if (p.x < -6) p.x = width + 6;
        ctx.fillStyle = `rgba(255,255,255,${p.alpha})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fill();
      }
    }

    function drawClouds(dt, heavy = false) {
      const isFog = theme === 'fog';
      const tint = isFog ? '225,234,242' : (isNight ? '150,160,200' : '232,240,248');
      for (const c of clouds) {
        c.x += c.speed * dt;
        if (c.x - c.r > width) {
          c.x = -c.r;
          c.y = isFog ? rand(height * 0.35, height * 0.95) : rand(-40, height * 0.65);
        }
        ctx.save();
        ctx.translate(c.x, c.y);
        ctx.scale(c.stretch, 0.72);
        const g = ctx.createRadialGradient(0, 0, 0, 0, 0, c.r);
        g.addColorStop(0, `rgba(${tint},${c.alpha * (heavy ? 1.7 : 1)})`);
        g.addColorStop(0.44, `rgba(${tint},${c.alpha * (heavy ? 1 : 0.62)})`);
        g.addColorStop(0.78, `rgba(${tint},${c.alpha * 0.24})`);
        g.addColorStop(1, `rgba(${tint},0)`);
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(0, 0, c.r, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
      }
    }

    function drawClearNight(dt) {
      // Soft moon glow
      const mx = width * 0.82;
      const my = height * 0.18;
      const halo = ctx.createRadialGradient(mx, my, 0, mx, my, Math.max(width, height) * 0.55);
      halo.addColorStop(0, 'rgba(220,230,255,0.18)');
      halo.addColorStop(0.5, 'rgba(180,200,240,0.05)');
      halo.addColorStop(1, 'rgba(180,200,240,0)');
      ctx.fillStyle = halo;
      ctx.fillRect(0, 0, width, height);
      ctx.beginPath();
      ctx.fillStyle = 'rgba(245,245,235,0.85)';
      ctx.arc(mx, my, 22, 0, Math.PI * 2);
      ctx.fill();

      drawClouds(dt);
    }

    function drawClearDay(dt) {
      sunHalo.phase += dt * 0.25;
      const cx = width * 0.82;
      const cy = height * 0.18;
      const pulse = 1 + Math.sin(sunHalo.phase) * 0.04;
      const r = Math.max(width, height) * 0.6 * pulse;

      const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
      g.addColorStop(0, 'rgba(255,236,196,0.55)');
      g.addColorStop(0.25, 'rgba(255,210,140,0.22)');
      g.addColorStop(0.6, 'rgba(255,200,120,0.06)');
      g.addColorStop(1, 'rgba(255,200,120,0)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, width, height);

      ctx.beginPath();
      ctx.fillStyle = 'rgba(255,248,230,0.9)';
      ctx.arc(cx, cy, 26, 0, Math.PI * 2);
      ctx.fill();

      for (const p of particles) {
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        if (p.y < -6) {
          p.y = height + 6;
          p.x = rand(0, width);
        }
        if (p.x > width + 10) p.x = -10;
        if (p.x < -10) p.x = width + 10;
        ctx.fillStyle = `rgba(255,240,210,${p.alpha})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fill();
      }
      drawClouds(dt);
    }

    function step(now) {
      if (!visible) {
        last = now;
        rafId = requestAnimationFrame(step);
        return;
      }
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;
      drawBaseSky(now);

      if (theme === 'rain' || theme === 'storm') drawRain(dt, now);
      else if (theme === 'snow') drawSnow(dt);
      else if (theme === 'cloudy' || theme === 'fog') drawClouds(dt);
      else if (isNight) drawClearNight(dt);
      else drawClearDay(dt);

      rafId = requestAnimationFrame(step);
    }

    function onVisibility() {
      visible = !document.hidden;
      last = performance.now();
    }

    const ro = new ResizeObserver(resize);
    ro.observe(canvas);
    document.addEventListener('visibilitychange', onVisibility);
    resize();
    rafId = requestAnimationFrame(step);

    return () => {
      cancelAnimationFrame(rafId);
      ro.disconnect();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [theme, isNight]);

  return <canvas ref={canvasRef} className={`weather-backdrop theme-${theme} ${isNight ? 'night' : 'day'}`} aria-hidden="true" />;
}
