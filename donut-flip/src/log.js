const LEVELS = { silent: 0, error: 1, warn: 2, info: 3, debug: 4 };
let current = LEVELS.info;

export function setLevel(name) {
  if (name in LEVELS) current = LEVELS[name];
}

const stamp = () => new Date().toISOString().slice(11, 19);

function emit(level, colour, ...args) {
  if (LEVELS[level] > current) return;
  const stream = level === 'error' || level === 'warn' ? process.stderr : process.stdout;
  stream.write(`${colour}${stamp()} ${level.padEnd(5)}\x1b[0m ${args.join(' ')}\n`);
}

export const log = {
  error: (...a) => emit('error', '\x1b[31m', ...a),
  warn: (...a) => emit('warn', '\x1b[33m', ...a),
  info: (...a) => emit('info', '\x1b[36m', ...a),
  debug: (...a) => emit('debug', '\x1b[90m', ...a),
};
