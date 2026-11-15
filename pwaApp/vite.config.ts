import { defineConfig } from 'vite';
import path from 'node:path';

export default defineConfig({
  resolve: {
    alias: {
      '@i18n': path.resolve(__dirname, '../i18n')
    }
  },
  server: {
    fs: {
      allow: ['..']
    }
  }
});
