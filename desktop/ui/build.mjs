/* Build the desktop UI into a single offline bundle (no CDN, no in-browser Babel).
   Concatenates the prototype scripts (which rely on global load order, not ES
   modules) behind a prelude that pins React, then esbuild transpiles the JSX. */
import esbuild from "esbuild";
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from "fs";

const APP = "src/app";
const FILES = [
  "theme.js",
  "data.js",
  "icons.jsx",
  "chart.jsx",
  "ui.jsx",
  "screens-market.jsx",
  "screens-more.jsx",
  "appearance.jsx",
  "desktop-shell.jsx",
];

const prelude =
  "import React from 'react';\n" +
  "import { createRoot } from 'react-dom/client';\n" +
  "const ReactDOM = { createRoot };\n" +
  "globalThis.React = React; globalThis.ReactDOM = ReactDOM;\n";

const contents =
  prelude +
  FILES.map((f) => `\n/* ==================== ${f} ==================== */\n` + readFileSync(`${APP}/${f}`, "utf8")).join("\n");

mkdirSync("dist", { recursive: true });

await esbuild.build({
  stdin: { contents, loader: "jsx", resolveDir: process.cwd() },
  bundle: true,
  format: "iife",
  minify: true,
  sourcemap: false,
  target: ["es2020"],
  jsx: "transform",
  jsxFactory: "React.createElement",
  jsxFragment: "React.Fragment",
  outfile: "dist/bundle.js",
  logLevel: "info",
});

copyFileSync("src/styles.css", "dist/styles.css");
copyFileSync("src/index.html", "dist/index.html");
console.log("✓ built dist/{bundle.js, styles.css, index.html}");
