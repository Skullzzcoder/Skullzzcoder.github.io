// A DOM small enough to run the dashboard's drawing against, and no smaller.
// The point is not to be a browser: it is that a mistyped element id, a missing null
// guard or a field the client never sends should fail here rather than in the browser,
// where a dashboard that renders nothing looks exactly like a client that is not running.
class Node {
  constructor(tag) {
    this.tag = tag; this.children = []; this._id = ''; this.className = '';
    this._text = ''; this.onclick = null; this.oninput = null;
    this.style = { setProperty() {} };
  }
  get id() { return this._id; }
  set id(value) { this._id = value; REGISTRY.set(value, this); }
  get textContent() { return this._text; }
  set textContent(value) { this._text = String(value); this.children = []; }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren(...kids) { this.children = kids; }
  get text() {
    return (this._text || '') + this.children.map(c => c.text).join(' ');
  }
  find(predicate, out = []) {
    if (predicate(this)) out.push(this);
    for (const child of this.children) child.find(predicate, out);
    return out;
  }
}

const REGISTRY = new Map();
for (const id of ['nav', 'page', 'power', 'rigs', 'search', 'themename', 'brand', 'mark']) {
  const node = new Node('div');
  node.id = id;
}

globalThis.document = {
  documentElement: { style: { setProperty() {} } },
  getElementById: id => REGISTRY.get(id) || null,
  createElement: tag => new Node(tag),
  createRange: () => ({ selectNodeContents() {} }),
};
globalThis.window = { getSelection: () => ({ removeAllRanges() {}, addRange() {} }) };
// node already defines navigator as a getter, so it has to be redefined rather than set.
Object.defineProperty(globalThis, 'navigator', {
  value: { clipboard: { writeText: async () => {} } }, configurable: true, writable: true,
});
globalThis.setTimeout = () => 0;
globalThis.setInterval = () => 0;
globalThis.fetch = async () => ({ text: async () => '{}' });

export { Node, REGISTRY };
