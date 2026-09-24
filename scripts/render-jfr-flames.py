"""Render JFR-exported folded stacks with Python's standard library. No network/CDN."""
import hashlib
import html
import json
import pathlib
import sys


def render(source, title, unit):
    root = {"name": "all request samples", "value": 0, "children": {}}
    for line in source.read_text(encoding="utf-8").splitlines():
        stack, weight = line.rsplit(" ", 1)
        value = int(weight)
        node = root
        node["value"] += value
        for frame in stack.split(";"):
            node = node["children"].setdefault(frame, {"name": frame, "value": 0, "children": {}})
            node["value"] += value
    if not root["value"]:
        raise ValueError(f"No samples in {source}")
    boxes = []

    def walk(node, x, depth):
        width = node["value"] / root["value"] * 1600
        boxes.append((node, x, depth, width))
        for child in sorted(node["children"].values(), key=lambda n: n["name"]):
            walk(child, x, depth + 1)
            x += child["value"] / root["value"] * 1600

    walk(root, 0, 0)
    height = (max(b[2] for b in boxes) + 1) * 20 + 45
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 {height}" '
             f'width="1600" height="{height}" role="img" aria-label="{html.escape(title)}">',
             '<style>text{font:11px monospace;pointer-events:none}g:hover rect{stroke:#111;stroke-width:1.5}</style>',
             f'<text x="10" y="20">{html.escape(title)} | {root["value"]:,} {unit}</text>']
    for node, x, depth, width in boxes:
        y = height - (depth + 1) * 20
        shade = hashlib.sha256(node["name"].encode()).digest()
        color = f'rgb({210 + shade[0] % 40},{100 + shade[1] % 100},{65 + shade[2] % 70})'
        label = f'{node["name"]} | {node["value"]:,} {unit} | {node["value"] / root["value"]:.2%}'
        parts.append(f'<g data-name="{html.escape(node["name"], quote=True)}">'
                     f'<title>{html.escape(label)}</title><rect x="{x:.5f}" y="{y}" '
                     f'width="{width:.5f}" height="19" fill="{color}"/>')
        if width > 25:
            short = node["name"].split(".")[-2:]
            text = ".".join(short)[:max(0, int(width / 7) - 1)]
            parts.append(f'<text x="{x + 3:.5f}" y="{y + 13}">{html.escape(text)}</text>')
        parts.append('</g>')
    parts.append('</svg>')
    return "".join(parts), height


def main():
    directory = pathlib.Path(sys.argv[1])
    cpu, cpu_height = render(directory / 'cpu.folded', 'Java execution sample flame graph', 'samples')
    allocation, allocation_height = render(directory / 'allocation.folded', 'Allocation flame graph (weighted estimate)', 'bytes')
    (directory / 'cpu-flame.svg').write_text(cpu, encoding='utf-8')
    (directory / 'allocation-flame.svg').write_text(allocation, encoding='utf-8')
    summary = json.loads((directory / 'summary.json').read_text(encoding='utf-8'))
    metrics = ' | '.join(f'{k}: {summary[k]}' for k in ['candidates', 'sequence_length', 'observe', 'p50_ms', 'p99_ms'])
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>ZGC / JFR 火焰图</title>
<style>body{font:15px system-ui;margin:24px;background:#fafafa;color:#17212b}section{overflow:auto;background:white;margin:20px 0;padding:12px;border:1px solid #ddd}svg{width:100%;height:auto}input,button{font:inherit;padding:6px}h1{font-size:26px}</style>
<h1>单人单场景 · CANDIDATES 货 · ZGC / JFR</h1><p>METRICS</p>
<p>横向宽度表示采样占比，纵向表示调用栈；不是时间轴。点击矩形放大；悬停查看方法、权重和占比。分配图是估算分配字节，不是存活堆。</p>
<input id="search" placeholder="搜索方法 / 类名" oninput="searchFrames()"><button onclick="reset()">重置缩放</button>
<section>CPU_SVG</section><section>ALLOC_SVG</section>
<script>
const svgs=[...document.querySelectorAll('svg')];
const original=svgs.map(s=>s.getAttribute('viewBox'));
document.querySelectorAll('g[data-name]').forEach(g=>{g.style.cursor='pointer';g.addEventListener('click',zoom);});
function zoom(e){const r=e.currentTarget.querySelector('rect'),s=e.currentTarget.ownerSVGElement;
s.setAttribute('viewBox',[r.x.baseVal.value,0,Math.max(0.001,r.width.baseVal.value),s.height.baseVal.value].join(' '));}
function reset(){svgs.forEach((s,i)=>s.setAttribute('viewBox',original[i]));}
function searchFrames(){const q=document.getElementById('search').value.toLowerCase();
document.querySelectorAll('g[data-name]').forEach(g=>g.style.opacity=!q||g.dataset.name.toLowerCase().includes(q)?1:0.2);}
</script></html>'''
    page = page.replace('CANDIDATES', str(summary['candidates'])).replace('METRICS', html.escape(metrics)).replace('CPU_SVG', cpu).replace('ALLOC_SVG', allocation)
    (directory / 'flamegraphs.html').write_text(page, encoding='utf-8')
    print(directory / 'flamegraphs.html')


if __name__ == '__main__':
    main()
