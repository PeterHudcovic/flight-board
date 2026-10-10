import { useLayoutEffect, useRef } from 'react';

/** Keep a fixed-height, single-line cell; reduce only this name by at most 20%. */
export function Destination({ name = '' }: { name?: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    const text = ref.current;
    const cell = text?.parentElement;
    if (!text || !cell) return;
    let active = true;
    const fit = () => {
      if (!active) return;
      const base = Number.parseFloat(getComputedStyle(cell).fontSize);
      text.style.fontSize = base + 'px';
      const available = text.clientWidth;
      const needed = text.scrollWidth;
      if (available > 0 && needed > available) {
        const size = Math.max(base * .8, Math.floor(base * (available - 1) / needed * 20) / 20);
        text.style.fontSize = size + 'px';
      }
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(cell);
    void document.fonts.ready.then(fit);
    document.fonts.addEventListener('loadingdone', fit);
    return () => { active = false; observer.disconnect(); document.fonts.removeEventListener('loadingdone', fit); };
  }, [name]);
  return <td className="destination" title={name}><span ref={ref} className="destination-text">{name}</span></td>;
}
