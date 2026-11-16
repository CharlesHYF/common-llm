/**
 * 配置 tsup 输出 ESM 与 CJS 产物，并用 tsc 生成两套模块格式的类型声明
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { execFileSync } from "node:child_process";
import { cpSync, rmSync, writeFileSync } from "node:fs";
import { defineConfig } from "tsup";

// TypeScript 7 不再提供 tsup 生成 .d.ts 所需的 JS API，因此改由 tsc 生成声明
function emitDeclarations(): void {
	rmSync("dist/types", {
		recursive: true,
		force: true,
	});
	rmSync("dist/types-cjs", {
		recursive: true,
		force: true,
	});
	execFileSync(process.execPath, [
		"node_modules/typescript/bin/tsc",

		"-p",

		"tsconfig.json",

		"--emitDeclarationOnly",
	], {
		stdio: "inherit",
	});
	cpSync("dist/types", "dist/types-cjs", {
		recursive: true,
	});
	writeFileSync("dist/types-cjs/package.json", `${JSON.stringify({
		type: "commonjs",
	})}\n`);
}

export default defineConfig({
	entry: [
		"src/index.ts",
	],
	format: [
		"esm",

		"cjs",
	],
	platform: "node",
	target: "node22",
	dts: false,
	sourcemap: true,
	clean: true,
	splitting: false,
	onSuccess: async () => {
		emitDeclarations();
	},
});
