import { Capacitor } from '@capacitor/core';
import { VideoProcessor } from 'capacitor-video-processor';

function log(msg) {
  const el = document.getElementById('log');
  if (el) {
    el.textContent += `${msg}\n`;
  }
}

window.runCompress = async () => {
  const inputEl = document.getElementById('inputPath');
  const outputEl = document.getElementById('outputPath');
  const input = inputEl?.value?.trim() ?? '';
  const output = outputEl?.value?.trim() ?? '';

  if (!input || !output) {
    log('Renseignez input et output.');
    return;
  }

  try {
    log(`Plateforme: ${Capacitor.getPlatform()}`);
    const { output: outPath } = await VideoProcessor.compressVideo({ input, output });
    log(`OK → ${outPath}`);
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    log(`Erreur: ${message}`);
  }
};
