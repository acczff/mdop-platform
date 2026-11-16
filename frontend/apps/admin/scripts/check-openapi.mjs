import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const adminDir = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
)
const specPath = path.join(adminDir, 'src/shared/api/openapi.json')
const schemaPath = path.join(adminDir, 'src/shared/api/schema.d.ts')

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mdop-openapi-'))
const tmpOut = path.join(tmpDir, 'schema.d.ts')

try {
  const cli = path.join(adminDir, 'node_modules/openapi-typescript/bin/cli.js')
  execFileSync(process.execPath, [cli, specPath, '-o', tmpOut], {
    cwd: adminDir,
    stdio: 'pipe',
  })

  const expected = fs.readFileSync(tmpOut, 'utf8').replace(/\r\n/g, '\n')
  const committed = fs.readFileSync(schemaPath, 'utf8').replace(/\r\n/g, '\n')

  if (expected !== committed) {
    console.error(
      'OpenAPI types in src/shared/api/schema.d.ts are out of date with src/shared/api/openapi.json.\n' +
        'Run `pnpm --filter @mdop/admin run api:types` to regenerate.',
    )
    process.exit(1)
  }
  console.log('OpenAPI schema.d.ts is in sync with openapi.json.')
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true })
}
