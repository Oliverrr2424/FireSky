import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
// Windows batch files run through cmd.exe; reject shell metacharacters.
if (args.some((arg) => !/^[a-zA-Z0-9_.:=/-]+$/.test(arg))) {
  console.error('Unsupported Gradle argument. Use task names and simple -Pkey=value options.');
  process.exit(1);
}
const windows = process.platform === 'win32';
const result = spawnSync(windows ? 'cmd.exe' : './gradlew',
  windows ? ['/d', '/s', '/c', 'gradlew.bat', ...args] : args, {
    cwd: fileURLToPath(new URL('../android/', import.meta.url)),
    stdio: 'inherit',
  });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
