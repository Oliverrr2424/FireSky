import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { LiquidGlassWebGL } from 'apple-liquid-glass-webgl';

const TARGET_SELECTOR = '[data-liquid-glass]';

function getSurfaceElements(root, canvas) {
  const canvasRect = canvas.getBoundingClientRect();
  return Array.from(root.querySelectorAll(TARGET_SELECTOR))
    .map((element, index) => {
      const rect = element.getBoundingClientRect();
      return {
        id: element.dataset.liquidGlassId || `surface-${index}`,
        shape: element.dataset.liquidGlass,
        x: rect.left - canvasRect.left,
        y: rect.top - canvasRect.top,
        width: rect.width,
        height: rect.height,
      };
    })
    .filter((element) => element.width > 0 && element.height > 0);
}

export default function LiquidGlassSurface() {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const root = document.querySelector('.account-page-shell');
    if (!canvas || !root) return undefined;

    const reducedTransparency = window.matchMedia?.('(prefers-reduced-transparency: reduce)');
    if (reducedTransparency?.matches) return undefined;

    let renderer;
    let animationFrame = 0;
    let disposed = false;
    let resizeObserver;

    const render = () => {
      animationFrame = 0;
      if (disposed || !renderer) return;
      const elements = getSurfaceElements(root, canvas);
      if (!elements.length) return;
      renderer.setElements(elements, false).render();
      root.classList.add('is-liquid-glass');
    };

    const scheduleRender = () => {
      if (animationFrame) return;
      animationFrame = window.requestAnimationFrame(render);
    };

    try {
      renderer = new LiquidGlassWebGL(canvas, {
        compositeMode: 'overlay',
      });

      const backdrop = document.querySelector('.weather-backdrop');
      if (backdrop?.tagName === 'CANVAS') {
        renderer.setBackdrop(backdrop, { update: 'live' });
      }

      resizeObserver = new ResizeObserver(scheduleRender);
      resizeObserver.observe(root);
      root.querySelectorAll(TARGET_SELECTOR).forEach((element) => resizeObserver.observe(element));
      window.addEventListener('resize', scheduleRender, { passive: true });
      scheduleRender();
    } catch (error) {
      // Keep the CSS glass fallback when WebGL2 is unavailable or blocked.
      console.warn('Liquid glass WebGL is unavailable; using CSS glass fallback.', error);
    }

    return () => {
      disposed = true;
      if (animationFrame) window.cancelAnimationFrame(animationFrame);
      resizeObserver?.disconnect();
      window.removeEventListener('resize', scheduleRender);
      root.classList.remove('is-liquid-glass');
      renderer?.destroy();
    };
  }, []);

  return typeof document === 'undefined'
    ? null
    : createPortal(<canvas ref={canvasRef} className="liquid-glass-canvas" aria-hidden="true" />, document.body);
}
